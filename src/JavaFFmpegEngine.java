import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class JavaFFmpegEngine implements VideoEngine {

    private final String ffmpegPath;

    public JavaFFmpegEngine(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    @Override public void loadVideo(String path) {}
    @Override public void play() {}
    @Override public void pause() {}
    @Override public void seek(double seconds) {}

    // 🔥 Prevent FFmpeg freeze
    private void consumeStream(InputStream stream) {
        consumeStream(stream, true);
    }

    private void consumeStream(InputStream stream, boolean printLogs) {
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (printLogs) {
                        System.out.println(line); // show FFmpeg logs
                    }
                }
            } catch (IOException ignored) {}
        }, "ffmpeg-log-consumer").start();
    }

    private static String volumeExpr(double volume) {
        return String.format(Locale.US, "volume=%.4f", volume);
    }

    private static String formatSeconds(double seconds) {
        return String.format(Locale.US, "%.6f", seconds);
    }

    private static String quoteForConsole(String value) {
        if (value == null || value.isEmpty()) {
            return "\"\"";
        }

        boolean needsQuoting = value.contains(" ")
                || value.contains("\t")
                || value.contains("\"")
                || value.contains("(")
                || value.contains(")");

        if (!needsQuoting) {
            return value;
        }

        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String toConsoleCommand(List<String> command) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < command.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(quoteForConsole(command.get(i)));
        }
        return sb.toString();
    }

    private static final class FfmpegResult {
        final int exitCode;
        final String stdout;
        final String stderr;

        FfmpegResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    private static Thread pipeStream(InputStream stream, StringBuilder sink, boolean printToConsole) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sink.append(line).append(System.lineSeparator());
                    if (printToConsole) {
                        System.out.println(line);
                    }
                }
            } catch (IOException ignored) {
            }
        }, "ffmpeg-stream-reader");
        thread.start();
        return thread;
    }

    private FfmpegResult runFfmpeg(List<String> command) throws Exception {
        System.out.println("🚀 Running FFmpeg command:");
        System.out.println(toConsoleCommand(command));

        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        Thread outThread = pipeStream(process.getInputStream(), stdout, false);
        Thread errThread = pipeStream(process.getErrorStream(), stderr, true);

        int exitCode = process.waitFor();
        outThread.join();
        errThread.join();

        return new FfmpegResult(exitCode, stdout.toString(), stderr.toString());
    }

    // ✅ TRIM SINGLE CLIP (WORKING)
    public void trimSingleClip(Clip clip, String outputPath) throws Exception {

        double duration = clip.getEndSeconds() - clip.getStartSeconds();
        new File(outputPath).getParentFile().mkdirs();

        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-y",
                "-ss", String.valueOf(clip.getStartSeconds()),
                "-i", clip.getSourcePath(),
                "-t", String.valueOf(duration),

                "-af", volumeExpr(clip.getVolume()),

                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-crf", "28",

                "-c:a", "aac",
                "-pix_fmt", "yuv420p",

                outputPath
        );

        pb.redirectErrorStream(true);
        Process p = pb.start();
        consumeStream(p.getInputStream());

        int exit = p.waitFor();
        if (exit != 0) {
            throw new RuntimeException("❌ Trim failed");
        }

        System.out.println("✅ Trim done");
    }

    // ✅ FINAL MERGE (concat filter + consistent re-encode)
    @Override
    public void renderTimeline(TimelineModel timeline, String outputPath) throws Exception {
        if (timeline.getTracks().size() > 1) {
            renderMultiTrackTimeline(timeline, outputPath);
            return;
        }

        List<Clip> clips = timeline.getClips();
        if (clips.isEmpty()) {
            throw new IllegalArgumentException("No clips");
        }

        List<RenderClipSpec> specs = new ArrayList<>();
        for (Clip clip : clips) {
            double trimmedStart = Math.max(0, clip.getStartSeconds());
            double trimmedEnd = Math.max(trimmedStart, clip.getEndSeconds());
            double trimmedDuration = Math.max(0, trimmedEnd - trimmedStart);
            if (trimmedDuration <= 0) {
                continue;
            }

            specs.add(new RenderClipSpec(
                    clip,
                    trimmedStart,
                    trimmedEnd,
                    trimmedDuration,
                    hasAudioStream(clip.getSourcePath())
            ));
        }

        if (specs.isEmpty()) {
            throw new IllegalArgumentException("No valid clips to render");
        }

        File parent = new File(outputPath).getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");

        for (RenderClipSpec spec : specs) {
            command.add("-i");
            command.add(spec.clip().getSourcePath());
        }

        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < specs.size(); i++) {
            RenderClipSpec spec = specs.get(i);

            filter.append("[").append(i).append(":v]")
                    .append("trim=start=").append(formatSeconds(spec.startSeconds()))
                    .append(":end=").append(formatSeconds(spec.endSeconds()))
                    .append(",setpts=PTS-STARTPTS,fps=30,format=yuv420p")
                    .append("[v").append(i).append("];");

            if (spec.hasAudio()) {
                filter.append("[").append(i).append(":a]")
                        .append("atrim=start=").append(formatSeconds(spec.startSeconds()))
                        .append(":end=").append(formatSeconds(spec.endSeconds()))
                        .append(",asetpts=PTS-STARTPTS,")
                        .append("aformat=sample_rates=48000:channel_layouts=stereo,")
                        .append(volumeExpr(spec.clip().getVolume()))
                        .append("[a").append(i).append("];");
            } else {
                filter.append("anullsrc=r=48000:cl=stereo,d=")
                        .append(formatSeconds(spec.durationSeconds()))
                        .append(",aformat=sample_rates=48000:channel_layouts=stereo,")
                        .append(volumeExpr(spec.clip().getVolume()))
                        .append("[a").append(i).append("];");
            }
        }

        String currentVideo = "v0";
        String currentAudio = "a0";
        double timelineCursor = specs.get(0).durationSeconds();

        for (int i = 1; i < specs.size(); i++) {
            RenderClipSpec previousSpec = specs.get(i - 1);
            RenderClipSpec currentSpec = specs.get(i);

            Clip.TransitionType transitionType = resolveTransitionType(previousSpec.clip(), currentSpec.clip());
            String transitionName = toXfadeTransition(transitionType);
            String nextVideo = "vx" + i;
            String nextAudio = "ax" + i;

            if (transitionName == null) {
                filter.append("[")
                        .append(currentVideo)
                        .append("][v").append(i).append("]")
                        .append("concat=n=2:v=1:a=0[")
                        .append(nextVideo)
                        .append("];");

                filter.append("[")
                        .append(currentAudio)
                        .append("][a").append(i).append("]")
                        .append("concat=n=2:v=0:a=1[")
                        .append(nextAudio)
                        .append("];");

                timelineCursor += currentSpec.durationSeconds();
                currentVideo = nextVideo;
                currentAudio = nextAudio;
                continue;
            }

            double requestedTransitionDuration = resolveTransitionDuration(previousSpec.clip(), currentSpec.clip(), transitionType);
            double transitionDuration = resolveSafeTransitionDuration(
                    requestedTransitionDuration,
                    timelineCursor,
                    currentSpec.durationSeconds()
            );

            if (transitionDuration <= 0) {
                filter.append("[")
                        .append(currentVideo)
                        .append("][v").append(i).append("]")
                        .append("concat=n=2:v=1:a=0[")
                        .append(nextVideo)
                        .append("];");

                filter.append("[")
                        .append(currentAudio)
                        .append("][a").append(i).append("]")
                        .append("concat=n=2:v=0:a=1[")
                        .append(nextAudio)
                        .append("];");

                timelineCursor += currentSpec.durationSeconds();
                currentVideo = nextVideo;
                currentAudio = nextAudio;
                continue;
            }

            double offset = Math.max(0.01, timelineCursor - transitionDuration);
            if (offset >= timelineCursor) {
                offset = Math.max(0.01, timelineCursor - 0.01);
            }

            filter.append("[")
                    .append(currentVideo)
                    .append("][v").append(i).append("]")
                    .append("xfade=transition=").append(transitionName)
                    .append(":duration=").append(formatSeconds(transitionDuration))
                    .append(":offset=").append(formatSeconds(offset))
                    .append("[")
                    .append(nextVideo)
                    .append("];");

            filter.append("[")
                    .append(currentAudio)
                    .append("][a").append(i).append("]")
                    .append("acrossfade=d=").append(formatSeconds(transitionDuration))
                    .append("[")
                    .append(nextAudio)
                    .append("];");

            timelineCursor += currentSpec.durationSeconds() - transitionDuration;
            currentVideo = nextVideo;
            currentAudio = nextAudio;
        }

        String finalVideoLabel = currentVideo;
        String finalAudioLabel = currentAudio;

        command.add("-filter_complex");
        command.add(filter.toString());
        command.add("-map");
        command.add("[" + finalVideoLabel + "]");
        command.add("-map");
        command.add("[" + finalAudioLabel + "]");
        command.add("-c:v");
        command.add("libx264");
        command.add("-c:a");
        command.add("aac");
        command.add("-preset");
        command.add("fast");
        command.add("-crf");
        command.add("23");
        command.add("-r");
        command.add("30");
        command.add("-fps_mode");
        command.add("cfr");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-ar");
        command.add("48000");
        command.add("-ac");
        command.add("2");
        command.add("-movflags");
        command.add("+faststart");
        command.add("-shortest");
        command.add(outputPath);

        System.out.println("🎛️ filter_complex:");
        System.out.println(filter);

        FfmpegResult result = runFfmpeg(command);

        if (result.exitCode != 0) {
            String errorOutput = result.stderr == null ? "" : result.stderr.trim();
            if (errorOutput.isEmpty()) {
                errorOutput = result.stdout == null ? "" : result.stdout.trim();
            }
            throw new RuntimeException("❌ Merge failed: " + errorOutput);
        }

        System.out.println("🎉 MERGE SUCCESS!");
    }

    private record TimelineClipSpec(Clip clip, double sourceStart, double sourceEnd, double duration, double timelineStart, boolean hasAudio, int inputIndex) {}

    private void renderMultiTrackTimeline(TimelineModel timeline, String outputPath) throws Exception {
        List<Track> tracks = timeline.getTracks();
        if (tracks.isEmpty() || tracks.get(0).getClips().isEmpty()) {
            throw new IllegalArgumentException("Track 0 (VIDEO) must contain clips");
        }

        File parent = new File(outputPath).getParentFile();
        if (parent != null) parent.mkdirs();

        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-y");

        List<TimelineClipSpec> base = new ArrayList<>();
        List<TimelineClipSpec> overlays = new ArrayList<>();
        List<TimelineClipSpec> audios = new ArrayList<>();
        List<TimelineClipSpec> texts = new ArrayList<>();

        int inputIndex = 0;
        for (int t = 0; t < tracks.size(); t++) {
            Track track = tracks.get(t);
            double cursor = 0;
            for (Clip clip : track.getClips()) {
                double s = Math.max(0, clip.getStartSeconds());
                double e = Math.max(s, clip.getEndSeconds());
                double d = e - s;
                if (d <= 0) continue;

                boolean image = isImagePath(clip.getSourcePath());
                if (image) {
                    command.add("-loop");
                    command.add("1");
                    command.add("-t");
                    command.add(formatSeconds(d));
                }
                command.add("-i");
                command.add(clip.getSourcePath());

                TimelineClipSpec spec = new TimelineClipSpec(clip, s, e, d, cursor, hasAudioStream(clip.getSourcePath()), inputIndex++);
                switch (track.getType()) {
                    case VIDEO -> base.add(spec);
                    case OVERLAY -> overlays.add(spec);
                    case AUDIO -> audios.add(spec);
                    case TEXT -> texts.add(spec);
                }
                cursor += d;
            }
        }

        if (base.isEmpty()) {
            throw new IllegalArgumentException("No base video clips in track 0");
        }

        StringBuilder filter = new StringBuilder();

        List<String> baseVideoLabels = new ArrayList<>();
        List<String> baseAudioLabels = new ArrayList<>();
        for (int i = 0; i < base.size(); i++) {
            TimelineClipSpec s = base.get(i);
            String v = "bv" + i;
            String a = "ba" + i;
            filter.append("[").append(s.inputIndex()).append(":v]")
                    .append("trim=start=").append(formatSeconds(s.sourceStart())).append(":end=").append(formatSeconds(s.sourceEnd()))
                    .append(",setpts=PTS-STARTPTS,fps=30,format=yuv420p[").append(v).append("];\n");
            if (s.hasAudio()) {
                filter.append("[").append(s.inputIndex()).append(":a]")
                        .append("atrim=start=").append(formatSeconds(s.sourceStart())).append(":end=").append(formatSeconds(s.sourceEnd()))
                        .append(",asetpts=PTS-STARTPTS,").append(volumeExpr(s.clip().getVolume())).append("[").append(a).append("];\n");
            } else {
                filter.append("anullsrc=r=48000:cl=stereo:d=").append(formatSeconds(s.duration()))
                        .append(",aformat=sample_rates=48000:channel_layouts=stereo[").append(a).append("];\n");
            }
            baseVideoLabels.add("[" + v + "]");
            baseAudioLabels.add("[" + a + "]");
        }

        String baseVideoOut = "basev";
        String baseAudioOut = "basea";
        filter.append(String.join("", baseVideoLabels)).append("concat=n=").append(base.size()).append(":v=1:a=0[").append(baseVideoOut).append("];\n");
        filter.append(String.join("", baseAudioLabels)).append("concat=n=").append(base.size()).append(":v=0:a=1[").append(baseAudioOut).append("];\n");

        String currentVideo = baseVideoOut;
        int ovCount = 0;
        for (TimelineClipSpec s : overlays) {
            String ov = "ov" + ovCount;
            String out = "ovv" + ovCount;
            if (isImagePath(s.clip().getSourcePath())) {
                filter.append("[").append(s.inputIndex()).append(":v]")
                        .append("setpts=PTS-STARTPTS+").append(formatSeconds(s.timelineStart())).append("/TB")
                        .append(",scale=iw*").append(formatSeconds(s.clip().getScale())).append(":ih*").append(formatSeconds(s.clip().getScale()))
                        .append(",format=rgba,colorchannelmixer=aa=").append(formatSeconds(s.clip().getOpacity()))
                        .append("[").append(ov).append("];\n");
            } else {
                filter.append("[").append(s.inputIndex()).append(":v]")
                        .append("trim=start=").append(formatSeconds(s.sourceStart())).append(":end=").append(formatSeconds(s.sourceEnd()))
                        .append(",setpts=PTS-STARTPTS+").append(formatSeconds(s.timelineStart())).append("/TB")
                        .append(",scale=iw*").append(formatSeconds(s.clip().getScale())).append(":ih*").append(formatSeconds(s.clip().getScale()))
                        .append(",format=rgba,colorchannelmixer=aa=").append(formatSeconds(s.clip().getOpacity()))
                        .append("[").append(ov).append("];\n");
            }
            filter.append("[").append(currentVideo).append("][").append(ov).append("]")
                    .append("overlay=x=").append(formatSeconds(s.clip().getPositionX()))
                    .append(":y=").append(formatSeconds(s.clip().getPositionY()))
                    .append(":enable='between(t,").append(formatSeconds(s.timelineStart())).append(",")
                    .append(formatSeconds(s.timelineStart() + s.duration())).append(")'[").append(out).append("];\n");
            currentVideo = out;
            ovCount++;
        }

        int txCount = 0;
        for (TimelineClipSpec s : texts) {
            String out = "txtv" + txCount;
            String text = escapeDrawtext(s.clip().getTextContent());
            filter.append("[").append(currentVideo).append("]")
                    .append("drawtext=text='").append(text).append("'")
                    .append(":x=").append(formatSeconds(s.clip().getPositionX()))
                    .append(":y=").append(formatSeconds(s.clip().getPositionY()))
                    .append(":fontsize=").append(s.clip().getTextFontSize())
                    .append(":fontcolor=white@1.0")
                    .append(":enable='between(t,").append(formatSeconds(s.timelineStart())).append(",")
                    .append(formatSeconds(s.timelineStart() + s.duration())).append(")'[").append(out).append("];\n");
            currentVideo = out;
            txCount++;
        }

        String currentAudio = baseAudioOut;
        if (!audios.isEmpty()) {
            List<String> mixInputs = new ArrayList<>();
            mixInputs.add("[" + baseAudioOut + "]");
            int ax = 0;
            for (TimelineClipSpec s : audios) {
                if (!s.hasAudio()) continue;
                String a = "mx" + ax++;
                filter.append("[").append(s.inputIndex()).append(":a]")
                        .append("atrim=start=").append(formatSeconds(s.sourceStart())).append(":end=").append(formatSeconds(s.sourceEnd()))
                        .append(",asetpts=PTS-STARTPTS+").append(formatSeconds(s.timelineStart())).append("/TB,")
                        .append(volumeExpr(s.clip().getVolume())).append("[").append(a).append("];\n");
                mixInputs.add("[" + a + "]");
            }
            if (mixInputs.size() > 1) {
                filter.append(String.join("", mixInputs)).append("amix=inputs=").append(mixInputs.size()).append(":normalize=0[finala];\n");
                currentAudio = "finala";
            }
        }

        command.add("-filter_complex");
        command.add(filter.toString());
        command.add("-map");
        command.add("[" + currentVideo + "]");
        command.add("-map");
        command.add("[" + currentAudio + "]");
        command.add("-c:v");
        command.add("libx264");
        command.add("-c:a");
        command.add("aac");
        command.add("-pix_fmt");
        command.add("yuv420p");
        command.add("-movflags");
        command.add("+faststart");
        command.add("-shortest");
        command.add(outputPath);

        FfmpegResult result = runFfmpeg(command);
        if (result.exitCode != 0) {
            throw new RuntimeException("❌ Multi-track render failed: " + (result.stderr == null ? result.stdout : result.stderr));
        }
    }

    private static String toXfadeTransition(Clip.TransitionType type) {
        if (type == null || type == Clip.TransitionType.NONE) {
            return null;
        }
        return switch (type) {
            case FADE -> "fade";
            case SLIDE_LEFT -> "slideleft";
            case SLIDE_RIGHT -> "slideright";
            case ZOOM_IN -> "zoomin";
            case ZOOM_OUT -> "zoomout";
            case DIP_TO_BLACK -> "fadeblack";
            case NONE -> null;
        };
    }

    private Clip.TransitionType resolveTransitionType(Clip previous, Clip current) {
        if (previous.getTransitionOut() != null && previous.getTransitionOut() != Clip.TransitionType.NONE) {
            return previous.getTransitionOut();
        }
        if (current.getTransitionIn() != null && current.getTransitionIn() != Clip.TransitionType.NONE) {
            return current.getTransitionIn();
        }
        return Clip.TransitionType.NONE;
    }

    private double resolveTransitionDuration(Clip previous, Clip current, Clip.TransitionType transitionType) {
        if (transitionType == null || transitionType == Clip.TransitionType.NONE) {
            return 0;
        }
        if (previous.getTransitionOut() == transitionType && previous.getTransitionOut() != Clip.TransitionType.NONE) {
            return previous.getTransitionOutDurationSeconds();
        }
        if (current.getTransitionIn() == transitionType && current.getTransitionIn() != Clip.TransitionType.NONE) {
            return current.getTransitionInDurationSeconds();
        }
        return Math.min(previous.getTransitionOutDurationSeconds(), current.getTransitionInDurationSeconds());
    }

    private double resolveSafeTransitionDuration(double requested, double previousDuration, double currentDuration) {
        if (requested <= 0 || previousDuration <= 0 || currentDuration <= 0) {
            return 0;
        }

        double maxAllowed = Math.min(previousDuration, currentDuration) - 0.01;
        if (maxAllowed <= 0.01) {
            return 0;
        }

        double safeRequested = Math.max(0.01, requested);
        return Math.min(safeRequested, maxAllowed);
    }

    private boolean hasAudioStream(String sourcePath) {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath);
        command.add("-v");
        command.add("error");
        command.add("-i");
        command.add(sourcePath);
        command.add("-map");
        command.add("0:a:0");
        command.add("-f");
        command.add("null");
        command.add("-");

        try {
            FfmpegResult result = runFfmpeg(command);
            return result.exitCode == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private record RenderClipSpec(
            Clip clip,
            double startSeconds,
            double endSeconds,
            double durationSeconds,
            boolean hasAudio
    ) {}

    private static boolean isImagePath(String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        return p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".webp") || p.endsWith(".bmp");
    }

    private static String escapeDrawtext(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\").replace("'", "\\'").replace(":", "\\:");
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public double[] generateWaveformPeaks(Clip clip, int targetSamples) throws Exception {
        int sampleCount = Math.max(32, Math.min(targetSamples, 1200));
        double duration = clip.getDurationSeconds();
        if (duration <= 0) {
            return new double[sampleCount];
        }

        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-v", "error",
                "-ss", String.valueOf(clip.getStartSeconds()),
                "-t", String.valueOf(duration),
                "-i", clip.getSourcePath(),
                "-vn",
                "-ac", "1",
                "-ar", "8000",
                "-f", "s16le",
                "-"
        );

        Process process = pb.start();
        consumeStream(process.getErrorStream(), false);

        ByteArrayOutputStream pcmBytes = new ByteArrayOutputStream(64 * 1024);
        try (InputStream stdout = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stdout.read(buffer)) != -1) {
                pcmBytes.write(buffer, 0, read);
            }
        }

        int exit = process.waitFor();
        if (exit != 0) {
            throw new RuntimeException("Waveform extraction failed for: " + clip.getSourcePath());
        }

        byte[] raw = pcmBytes.toByteArray();
        if (raw.length < 2) {
            return new double[sampleCount];
        }

        short[] samples = new short[raw.length / 2];
        ByteBuffer.wrap(raw)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(samples);

        double[] peaks = new double[sampleCount];
        int samplesPerBucket = Math.max(1, samples.length / sampleCount);

        for (int i = 0; i < sampleCount; i++) {
            int start = i * samplesPerBucket;
            int end = (i == sampleCount - 1) ? samples.length : Math.min(samples.length, start + samplesPerBucket);
            if (start >= samples.length) {
                peaks[i] = 0;
                continue;
            }

            int max = 0;
            for (int j = start; j < end; j++) {
                int amp = Math.abs(samples[j]);
                if (amp > max) {
                    max = amp;
                }
            }

            peaks[i] = Math.min(1.0, max / 32768.0);
        }

        return peaks;
    }
}