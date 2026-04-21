import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Track {

    public enum TrackType {
        VIDEO,
        OVERLAY,
        TEXT,
        AUDIO
    }

    private final TrackType type;
    private final List<Clip> clips;

    public Track(TrackType type) {
        this(type, new ArrayList<>());
    }

    public Track(TrackType type, List<Clip> clips) {
        this.type = type == null ? TrackType.VIDEO : type;
        this.clips = clips == null ? new ArrayList<>() : clips;
    }

    public TrackType getType() {
        return type;
    }

    public List<Clip> getClips() {
        return Collections.unmodifiableList(clips);
    }

    List<Clip> getMutableClips() {
        return clips;
    }
}
