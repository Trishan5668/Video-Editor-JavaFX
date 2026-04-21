import java.util.Arrays;

public class Clip {

    public enum TransitionType {
        NONE,
        FADE,
        SLIDE_LEFT,
        SLIDE_RIGHT,
        ZOOM_IN,
        ZOOM_OUT,
        DIP_TO_BLACK
    }

    private final String sourcePath;
    private final double startSeconds;
    private final double endSeconds;
    private final double volume;
    private final TransitionType transitionIn;
    private final TransitionType transitionOut;
    private final double transitionInDurationSeconds;
    private final double transitionOutDurationSeconds;
    private final double positionX;
    private final double positionY;
    private final double scale;
    private final double opacity;
    private final String textContent;
    private final int zIndex;
    private final int textFontSize;

    // In-memory cache used by UI timeline waveform rendering.
    private volatile double[] waveformPeaks;

    public Clip(String sourcePath, double startSeconds, double endSeconds) {
        this(sourcePath, startSeconds, endSeconds, 1.0);
    }

    public Clip(String sourcePath, double startSeconds, double endSeconds, double volume) {
        this(
                sourcePath,
                startSeconds,
                endSeconds,
                volume,
                TransitionType.NONE,
                TransitionType.NONE,
                0.6,
                0.6,
                0,
                0,
                1.0,
                1.0,
                "",
                0,
                36
        );
    }

    public Clip(
            String sourcePath,
            double startSeconds,
            double endSeconds,
            double volume,
            TransitionType transitionIn,
            TransitionType transitionOut,
            double transitionInDurationSeconds,
            double transitionOutDurationSeconds
    ) {
        this(
                sourcePath,
                startSeconds,
                endSeconds,
                volume,
                transitionIn,
                transitionOut,
                transitionInDurationSeconds,
                transitionOutDurationSeconds,
                0,
                0,
                1.0,
                1.0,
                "",
                0,
                36
        );
    }

    public Clip(
            String sourcePath,
            double startSeconds,
            double endSeconds,
            double volume,
            TransitionType transitionIn,
            TransitionType transitionOut,
            double transitionInDurationSeconds,
            double transitionOutDurationSeconds,
            double positionX,
            double positionY,
            double scale,
            double opacity,
            String textContent,
            int zIndex,
            int textFontSize
    ) {
        this.sourcePath = sourcePath;
        this.startSeconds = startSeconds;
        this.endSeconds = endSeconds;
        this.volume = volume;
        this.transitionIn = transitionIn == null ? TransitionType.NONE : transitionIn;
        this.transitionOut = transitionOut == null ? TransitionType.NONE : transitionOut;
        this.transitionInDurationSeconds = Math.max(0.2, Math.min(2.0, transitionInDurationSeconds));
        this.transitionOutDurationSeconds = Math.max(0.2, Math.min(2.0, transitionOutDurationSeconds));
        this.positionX = positionX;
        this.positionY = positionY;
        this.scale = Math.max(0.05, scale);
        this.opacity = Math.max(0.0, Math.min(1.0, opacity));
        this.textContent = textContent == null ? "" : textContent;
        this.zIndex = zIndex;
        this.textFontSize = Math.max(8, Math.min(160, textFontSize));
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public double getStartSeconds() {
        return startSeconds;
    }

    public double getEndSeconds() {
        return endSeconds;
    }

    public double getDurationSeconds() {
        return Math.max(0, endSeconds - startSeconds);
    }

    public double getVolume() {
        return volume;
    }

    public TransitionType getTransitionIn() {
        return transitionIn;
    }

    public TransitionType getTransitionOut() {
        return transitionOut;
    }

    public double getTransitionInDurationSeconds() {
        return transitionInDurationSeconds;
    }

    public double getTransitionOutDurationSeconds() {
        return transitionOutDurationSeconds;
    }

    public double getPositionX() {
        return positionX;
    }

    public double getPositionY() {
        return positionY;
    }

    public double getScale() {
        return scale;
    }

    public double getOpacity() {
        return opacity;
    }

    public String getTextContent() {
        return textContent;
    }

    public int getZIndex() {
        return zIndex;
    }

    public int getTextFontSize() {
        return textFontSize;
    }

    public Clip withRange(double newStartSeconds, double newEndSeconds) {
        Clip updated = new Clip(
                sourcePath,
                newStartSeconds,
                newEndSeconds,
                volume,
                transitionIn,
                transitionOut,
                transitionInDurationSeconds,
                transitionOutDurationSeconds,
                positionX,
                positionY,
                scale,
                opacity,
                textContent,
                zIndex,
                textFontSize
        );
        updated.cacheWaveform(getWaveformPeaks());
        return updated;
    }

    public Clip withVolume(double newVolume) {
        Clip updated = new Clip(
                sourcePath,
                startSeconds,
                endSeconds,
                newVolume,
                transitionIn,
                transitionOut,
                transitionInDurationSeconds,
                transitionOutDurationSeconds,
                positionX,
                positionY,
                scale,
                opacity,
                textContent,
                zIndex,
                textFontSize
        );
        updated.cacheWaveform(getWaveformPeaks());
        return updated;
    }

    public Clip withTransitions(
            TransitionType newTransitionIn,
            TransitionType newTransitionOut,
            double newTransitionInDurationSeconds,
            double newTransitionOutDurationSeconds
    ) {
        Clip updated = new Clip(
                sourcePath,
                startSeconds,
                endSeconds,
                volume,
                newTransitionIn,
                newTransitionOut,
                newTransitionInDurationSeconds,
                newTransitionOutDurationSeconds,
                positionX,
                positionY,
                scale,
                opacity,
                textContent,
                zIndex,
                textFontSize
        );
        updated.cacheWaveform(getWaveformPeaks());
        return updated;
    }

    public Clip withPosition(double newPositionX, double newPositionY) {
        Clip updated = new Clip(
                sourcePath,
                startSeconds,
                endSeconds,
                volume,
                transitionIn,
                transitionOut,
                transitionInDurationSeconds,
                transitionOutDurationSeconds,
                newPositionX,
                newPositionY,
                scale,
                opacity,
                textContent,
                zIndex,
                textFontSize
        );
        updated.cacheWaveform(getWaveformPeaks());
        return updated;
    }

    public Clip withScale(double newScale) {
        Clip updated = new Clip(
                sourcePath,
                startSeconds,
                endSeconds,
                volume,
                transitionIn,
                transitionOut,
                transitionInDurationSeconds,
                transitionOutDurationSeconds,
                positionX,
                positionY,
                newScale,
                opacity,
                textContent,
                zIndex,
                textFontSize
        );
        updated.cacheWaveform(getWaveformPeaks());
        return updated;
    }

    public Clip withOpacity(double newOpacity) {
        Clip updated = new Clip(
                sourcePath,
                startSeconds,
                endSeconds,
                volume,
                transitionIn,
                transitionOut,
                transitionInDurationSeconds,
                transitionOutDurationSeconds,
                positionX,
                positionY,
                scale,
                newOpacity,
                textContent,
                zIndex,
                textFontSize
        );
        updated.cacheWaveform(getWaveformPeaks());
        return updated;
    }

    public Clip withText(String newTextContent) {
        Clip updated = new Clip(
                sourcePath,
                startSeconds,
                endSeconds,
                volume,
                transitionIn,
                transitionOut,
                transitionInDurationSeconds,
                transitionOutDurationSeconds,
                positionX,
                positionY,
                scale,
                opacity,
                newTextContent,
                zIndex,
                textFontSize
        );
        updated.cacheWaveform(getWaveformPeaks());
        return updated;
    }

    public Clip withTextFontSize(int newTextFontSize) {
        Clip updated = new Clip(
                sourcePath,
                startSeconds,
                endSeconds,
                volume,
                transitionIn,
                transitionOut,
                transitionInDurationSeconds,
                transitionOutDurationSeconds,
                positionX,
                positionY,
                scale,
                opacity,
                textContent,
                zIndex,
                newTextFontSize
        );
        updated.cacheWaveform(getWaveformPeaks());
        return updated;
    }

    public Clip[] splitAt(double sourceSeconds) {
        if (sourceSeconds <= startSeconds || sourceSeconds >= endSeconds) {
            throw new IllegalArgumentException("Split position must be within clip range");
        }

        Clip left = new Clip(
                sourcePath,
                startSeconds,
                sourceSeconds,
                volume,
                transitionIn,
                TransitionType.NONE,
                transitionInDurationSeconds,
                transitionOutDurationSeconds,
                positionX,
                positionY,
                scale,
                opacity,
                textContent,
                zIndex,
                textFontSize
        );
        Clip right = new Clip(
                sourcePath,
                sourceSeconds,
                endSeconds,
                volume,
                TransitionType.NONE,
                transitionOut,
                transitionInDurationSeconds,
                transitionOutDurationSeconds,
                positionX,
                positionY,
                scale,
                opacity,
                textContent,
                zIndex,
                textFontSize
        );

        double[] cached = getWaveformPeaks();
        if (cached != null && cached.length > 2) {
            int splitIndex = (int) Math.round(((sourceSeconds - startSeconds) / getDurationSeconds()) * cached.length);
            splitIndex = Math.max(1, Math.min(cached.length - 1, splitIndex));
            left.cacheWaveform(Arrays.copyOfRange(cached, 0, splitIndex));
            right.cacheWaveform(Arrays.copyOfRange(cached, splitIndex, cached.length));
        }

        return new Clip[]{left, right};
    }

    public String getWaveformCacheKey() {
        return sourcePath + "|" + startSeconds + "|" + endSeconds;
    }

    public double[] getWaveformPeaks() {
        return waveformPeaks;
    }

    public void cacheWaveform(double[] peaks) {
        waveformPeaks = peaks;
    }
}