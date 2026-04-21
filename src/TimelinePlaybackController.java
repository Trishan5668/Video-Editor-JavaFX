import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TimelinePlaybackController {

    public static class PlaybackClip {
        private final Clip clip;
        private final double timelineStart;
        private final double timelineEnd;
        private final double sourceStart;
        private final double sourceEnd;

        public PlaybackClip(Clip clip, double timelineStart, double sourceStart, double sourceEnd) {
            this.clip = clip;
            this.timelineStart = timelineStart;
            this.sourceStart = sourceStart;
            this.sourceEnd = sourceEnd;
            this.timelineEnd = timelineStart + Math.max(0, sourceEnd - sourceStart);
        }

        public Clip getClip() {
            return clip;
        }

        public double getTimelineStart() {
            return timelineStart;
        }

        public double getTimelineEnd() {
            return timelineEnd;
        }

        public double getSourceStart() {
            return sourceStart;
        }

        public double getSourceEnd() {
            return sourceEnd;
        }

        public double getDurationSeconds() {
            return Math.max(0, sourceEnd - sourceStart);
        }
    }

    public interface Listener {
        void onTimeUpdate(double globalSeconds, double totalSeconds);

        void onClipChanged(PlaybackClip previous, PlaybackClip current, boolean autoTransition);

        void onPlaybackStateChanged(boolean playing);

        void onError(String message);

        default void onActiveTrackClipsChanged(double globalSeconds, List<PlaybackClip> activeNonPrimaryTrackClips) {
        }
    }

    private final MediaView mediaView;
    private final Listener listener;

    private final ChangeListener<Duration> currentTimeListener = (obs, oldV, newV) -> onCurrentTimeChanged(newV);

    private List<PlaybackClip> timeline = Collections.emptyList();
    private List<List<PlaybackClip>> multiTrackTimeline = Collections.emptyList();
    private double totalDurationSeconds = 0;

    private MediaPlayer currentPlayer;
    private MediaPlayer preloadedNextPlayer;
    private PlaybackClip currentClip;
    private PlaybackClip preloadedClip;
    private double currentGlobalSeconds = 0;
    private boolean playing = false;

    public TimelinePlaybackController(MediaView mediaView, Listener listener) {
        this.mediaView = mediaView;
        this.listener = listener;
    }

    public void setTimeline(List<PlaybackClip> clips) {
        List<List<PlaybackClip>> wrapped = new ArrayList<>();
        wrapped.add(clips == null ? Collections.emptyList() : new ArrayList<>(clips));
        setTimelineTracks(wrapped);
    }

    public void setTimelineTracks(List<List<PlaybackClip>> tracks) {
        pause();
        this.multiTrackTimeline = tracks == null ? Collections.emptyList() : new ArrayList<>(tracks);
        this.timeline = this.multiTrackTimeline.isEmpty() ? Collections.emptyList() : new ArrayList<>(this.multiTrackTimeline.get(0));
        this.totalDurationSeconds = 0;
        for (PlaybackClip clip : this.timeline) {
            this.totalDurationSeconds = Math.max(this.totalDurationSeconds, clip.getTimelineEnd());
        }

        if (this.timeline.isEmpty()) {
            stop();
            listener.onTimeUpdate(0, 0);
            return;
        }

        seek(currentGlobalSeconds);
    }

    public void play() {
        if (timeline.isEmpty()) {
            return;
        }

        if (currentClip == null) {
            seek(currentGlobalSeconds);
        }

        if (currentPlayer != null) {
            playing = true;
            currentPlayer.play();
            listener.onPlaybackStateChanged(true);
        }
    }

    public void pause() {
        playing = false;
        if (currentPlayer != null) {
            currentPlayer.pause();
        }
        listener.onPlaybackStateChanged(false);
    }

    public void stop() {
        playing = false;
        currentGlobalSeconds = 0;
        if (currentPlayer != null) {
            currentPlayer.pause();
        }
        seek(0);
        listener.onPlaybackStateChanged(false);
    }

    public void nextClip() {
        if (timeline.isEmpty()) {
            return;
        }
        int idx = currentClip == null ? -1 : timeline.indexOf(currentClip);
        int target = Math.min(timeline.size() - 1, idx + 1);
        seek(timeline.get(target).getTimelineStart());
    }

    public void previousClip() {
        if (timeline.isEmpty()) {
            return;
        }
        int idx = currentClip == null ? 0 : timeline.indexOf(currentClip);
        int target = Math.max(0, idx - 1);
        seek(timeline.get(target).getTimelineStart());
    }

    public void seek(double globalSeconds) {
        if (timeline.isEmpty()) {
            currentGlobalSeconds = 0;
            listener.onTimeUpdate(0, 0);
            return;
        }

        double target = clamp(globalSeconds, 0, totalDurationSeconds);
        currentGlobalSeconds = target;

        PlaybackClip targetClip = findClipAtGlobalTime(target);
        if (targetClip == null) {
            targetClip = timeline.get(timeline.size() - 1);
            target = targetClip.getTimelineEnd();
            currentGlobalSeconds = target;
        }

        double sourceTarget = targetClip.getSourceStart() + (target - targetClip.getTimelineStart());
        sourceTarget = clamp(sourceTarget, targetClip.getSourceStart(), targetClip.getSourceEnd());
        boolean autoPlay = playing;
        switchToClip(targetClip, sourceTarget, autoPlay, false);
        listener.onTimeUpdate(currentGlobalSeconds, totalDurationSeconds);
        listener.onActiveTrackClipsChanged(currentGlobalSeconds, findActiveNonPrimaryTrackClips(currentGlobalSeconds));
    }

    public double getTotalDurationSeconds() {
        return totalDurationSeconds;
    }

    public double getCurrentGlobalSeconds() {
        return currentGlobalSeconds;
    }

    public PlaybackClip getCurrentClip() {
        return currentClip;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void dispose() {
        playing = false;
        disposePlayer(preloadedNextPlayer);
        preloadedNextPlayer = null;
        preloadedClip = null;
        if (currentPlayer != null) {
            currentPlayer.currentTimeProperty().removeListener(currentTimeListener);
            disposePlayer(currentPlayer);
            currentPlayer = null;
        }
        mediaView.setMediaPlayer(null);
    }

    private PlaybackClip findClipAtGlobalTime(double globalSeconds) {
        for (PlaybackClip clip : timeline) {
            if (globalSeconds >= clip.getTimelineStart() && globalSeconds < clip.getTimelineEnd()) {
                return clip;
            }
        }
        if (!timeline.isEmpty() && Math.abs(globalSeconds - totalDurationSeconds) < 0.001) {
            return timeline.get(timeline.size() - 1);
        }
        return null;
    }

    private void switchToClip(PlaybackClip targetClip, double sourceSeek, boolean autoPlay, boolean autoTransition) {
        if (targetClip == null) {
            return;
        }

        PlaybackClip previous = currentClip;

        if (currentPlayer != null && currentClip != null && sameSource(currentClip, targetClip)) {
            currentClip = targetClip;
            currentPlayer.seek(Duration.seconds(sourceSeek));
            if (autoPlay) {
                currentPlayer.play();
            }
            listener.onClipChanged(previous, currentClip, autoTransition);
            preloadNextClip();
            return;
        }

        MediaPlayer nextPlayer;
        if (preloadedNextPlayer != null && preloadedClip == targetClip) {
            nextPlayer = preloadedNextPlayer;
            preloadedNextPlayer = null;
            preloadedClip = null;
        } else {
            nextPlayer = createPlayer(targetClip);
        }

        if (currentPlayer != null) {
            currentPlayer.currentTimeProperty().removeListener(currentTimeListener);
            disposePlayer(currentPlayer);
        }

        currentPlayer = nextPlayer;
        currentClip = targetClip;
        currentPlayer.currentTimeProperty().addListener(currentTimeListener);
        mediaView.setMediaPlayer(currentPlayer);

        final double seekTarget = sourceSeek;
        currentPlayer.setOnReady(() -> {
            currentPlayer.seek(Duration.seconds(seekTarget));
            if (autoPlay) {
                currentPlayer.play();
            }
        });

        listener.onClipChanged(previous, currentClip, autoTransition);
        preloadNextClip();
    }

    private MediaPlayer createPlayer(PlaybackClip clip) {
        Media media = new Media(new File(clip.getClip().getSourcePath()).toURI().toString());
        MediaPlayer player = new MediaPlayer(media);
        player.setOnError(() -> listener.onError("Playback error: " + player.getError()));
        player.setOnEndOfMedia(() -> advanceToNextClip(true));
        return player;
    }

    private void onCurrentTimeChanged(Duration newTime) {
        if (currentClip == null || newTime == null) {
            return;
        }

        double sourceSeconds = newTime.toSeconds();
        double local = sourceSeconds - currentClip.getSourceStart();
        double global = currentClip.getTimelineStart() + local;
        currentGlobalSeconds = clamp(global, currentClip.getTimelineStart(), currentClip.getTimelineEnd());
        listener.onTimeUpdate(currentGlobalSeconds, totalDurationSeconds);
        listener.onActiveTrackClipsChanged(currentGlobalSeconds, findActiveNonPrimaryTrackClips(currentGlobalSeconds));

        if (sourceSeconds >= currentClip.getSourceEnd() - 0.01) {
            advanceToNextClip(true);
        }
    }

    private void advanceToNextClip(boolean autoTransition) {
        if (currentClip == null) {
            return;
        }

        int idx = timeline.indexOf(currentClip);
        if (idx < 0 || idx >= timeline.size() - 1) {
            playing = false;
            if (currentPlayer != null) {
                currentPlayer.pause();
                currentPlayer.seek(Duration.seconds(currentClip.getSourceEnd()));
            }
            currentGlobalSeconds = totalDurationSeconds;
            listener.onTimeUpdate(currentGlobalSeconds, totalDurationSeconds);
            listener.onActiveTrackClipsChanged(currentGlobalSeconds, findActiveNonPrimaryTrackClips(currentGlobalSeconds));
            listener.onPlaybackStateChanged(false);
            return;
        }

        PlaybackClip next = timeline.get(idx + 1);
        currentGlobalSeconds = next.getTimelineStart();
        switchToClip(next, next.getSourceStart(), playing, autoTransition);
    }

    private List<PlaybackClip> findActiveNonPrimaryTrackClips(double globalSeconds) {
        if (multiTrackTimeline == null || multiTrackTimeline.size() <= 1) {
            return Collections.emptyList();
        }
        List<PlaybackClip> active = new ArrayList<>();
        for (int t = 1; t < multiTrackTimeline.size(); t++) {
            List<PlaybackClip> clips = multiTrackTimeline.get(t);
            if (clips == null || clips.isEmpty()) {
                continue;
            }
            for (PlaybackClip clip : clips) {
                if (globalSeconds >= clip.getTimelineStart() && globalSeconds <= clip.getTimelineEnd()) {
                    active.add(clip);
                    break;
                }
            }
        }
        return active;
    }

    private void preloadNextClip() {
        if (currentClip == null) {
            return;
        }
        int idx = timeline.indexOf(currentClip);
        if (idx < 0 || idx >= timeline.size() - 1) {
            disposePlayer(preloadedNextPlayer);
            preloadedNextPlayer = null;
            preloadedClip = null;
            return;
        }

        PlaybackClip next = timeline.get(idx + 1);
        if (preloadedClip == next && preloadedNextPlayer != null) {
            return;
        }

        disposePlayer(preloadedNextPlayer);
        preloadedNextPlayer = null;
        preloadedClip = null;

        if (sameSource(currentClip, next)) {
            return;
        }

        try {
            preloadedNextPlayer = createPlayer(next);
            preloadedClip = next;
        } catch (Exception ignored) {
            preloadedNextPlayer = null;
            preloadedClip = null;
        }
    }

    private boolean sameSource(PlaybackClip a, PlaybackClip b) {
        return a != null && b != null && a.getClip().getSourcePath().equals(b.getClip().getSourcePath());
    }

    private void disposePlayer(MediaPlayer player) {
        if (player == null) {
            return;
        }
        try {
            Platform.runLater(() -> {
                try {
                    player.stop();
                    player.dispose();
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
