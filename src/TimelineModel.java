import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TimelineModel {

    private final List<Track> tracks = new ArrayList<>();

    public TimelineModel() {
        // Track 0 is always VIDEO
        tracks.add(new Track(Track.TrackType.VIDEO));
    }

    public void addTrack(Track.TrackType type) {
        if (type == null) {
            return;
        }
        if (tracks.isEmpty()) {
            tracks.add(new Track(Track.TrackType.VIDEO));
        }
        if (tracks.get(0).getType() != Track.TrackType.VIDEO) {
            tracks.add(0, new Track(Track.TrackType.VIDEO));
        }
        tracks.add(new Track(type));
    }

    public void addClipToTrack(int trackIndex, Clip clip) {
        if (clip == null) {
            return;
        }
        int safeIndex = safeTrackIndex(trackIndex);
        tracks.get(safeIndex).getMutableClips().add(clip);
    }

    public List<Track> getTracks() {
        return Collections.unmodifiableList(tracks);
    }

    public List<Clip> getClipsInTrack(int trackIndex) {
        int safeIndex = safeTrackIndex(trackIndex);
        return Collections.unmodifiableList(tracks.get(safeIndex).getMutableClips());
    }

    public int findFirstTrackIndexByType(Track.TrackType type) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).getType() == type) {
                return i;
            }
        }
        return -1;
    }

    public int ensureTrack(Track.TrackType type) {
        int existing = findFirstTrackIndexByType(type);
        if (existing >= 0) {
            return existing;
        }
        addTrack(type);
        return tracks.size() - 1;
    }

    // ------------------------
    // Backward-compatibility helpers (operate on VIDEO track 0)
    // ------------------------
    public void addClip(Clip clip) {
        addClipToTrack(0, clip);
    }

    public void insertClip(int index, Clip clip) {
        List<Clip> clips = tracks.get(0).getMutableClips();
        int safeIndex = Math.max(0, Math.min(index, clips.size()));
        clips.add(safeIndex, clip);
    }

    public void replaceClip(int index, Clip clip) {
        List<Clip> clips = tracks.get(0).getMutableClips();
        if (index >= 0 && index < clips.size()) {
            clips.set(index, clip);
        }
    }

    public void replaceClipInTrack(int trackIndex, int clipIndex, Clip clip) {
        List<Clip> clips = tracks.get(safeTrackIndex(trackIndex)).getMutableClips();
        if (clipIndex >= 0 && clipIndex < clips.size()) {
            clips.set(clipIndex, clip);
        }
    }

    public void insertClipInTrack(int trackIndex, int clipIndex, Clip clip) {
        List<Clip> clips = tracks.get(safeTrackIndex(trackIndex)).getMutableClips();
        int safeIndex = Math.max(0, Math.min(clipIndex, clips.size()));
        clips.add(safeIndex, clip);
    }

    public void removeClip(int index) {
        removeClipFromTrack(0, index);
    }

    public void removeClipFromTrack(int trackIndex, int clipIndex) {
        List<Clip> clips = tracks.get(safeTrackIndex(trackIndex)).getMutableClips();
        if (clipIndex >= 0 && clipIndex < clips.size()) {
            clips.remove(clipIndex);
        }
    }

    public List<Clip> getClips() {
        return getClipsInTrack(0);
    }

    public boolean isEmpty() {
        for (Track track : tracks) {
            if (!track.getClips().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public int indexOf(Clip clip) {
        return tracks.get(0).getMutableClips().indexOf(clip);
    }

    public int indexOfInTrack(int trackIndex, Clip clip) {
        return tracks.get(safeTrackIndex(trackIndex)).getMutableClips().indexOf(clip);
    }

    public boolean contains(Clip clip) {
        return findTrackIndexOfClip(clip) >= 0;
    }

    public int findTrackIndexOfClip(Clip clip) {
        if (clip == null) {
            return -1;
        }
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).getMutableClips().contains(clip)) {
                return i;
            }
        }
        return -1;
    }

    public Clip[] splitClipAtSourceTime(int index, double sourceTimeSeconds) {
        return splitClipAtSourceTimeInTrack(0, index, sourceTimeSeconds);
    }

    public Clip[] splitClipAtSourceTimeInTrack(int trackIndex, int clipIndex, double sourceTimeSeconds) {
        List<Clip> clips = tracks.get(safeTrackIndex(trackIndex)).getMutableClips();
        if (clipIndex < 0 || clipIndex >= clips.size()) {
            throw new IllegalArgumentException("Invalid clip index");
        }

        Clip target = clips.get(clipIndex);
        Clip[] split = target.splitAt(sourceTimeSeconds);
        clips.remove(clipIndex);
        clips.add(clipIndex, split[1]);
        clips.add(clipIndex, split[0]);
        return split;
    }

    private int safeTrackIndex(int trackIndex) {
        if (tracks.isEmpty()) {
            tracks.add(new Track(Track.TrackType.VIDEO));
        }
        return Math.max(0, Math.min(trackIndex, tracks.size() - 1));
    }
}
