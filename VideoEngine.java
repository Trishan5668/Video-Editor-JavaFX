public interface VideoEngine {

    void loadVideo(String path);

    void play();

    void pause();

    void seek(double seconds);

    void renderTimeline(TimelineModel timeline, String outputPath) throws Exception;

    // TODO: Replace Java implementation with JNI bridge for a native C++ engine.
    // TODO: Keep this interface stable so UI layer remains unchanged during migration.
}