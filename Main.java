import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Main extends Application {

    private static final int VIDEO_WIDTH = 900;
    private static final int VIDEO_HEIGHT = 360;
    private static final double CLIP_PIXELS_PER_SECOND = 26;
    private static final double MIN_CLIP_WIDTH = 150;
    private static final double SNAP_THRESHOLD_PX = 8;
    private static final int MIN_WAVEFORM_POINTS = 500;
    private static final int MAX_WAVEFORM_POINTS = 900;

    private final VideoEngine videoEngine;
    private TimelineModel timelineModel = new TimelineModel();
    private Clip selectedClip;

    private MediaView mediaView;
    private StackPane previewPane;
    private Pane previewOverlayLayer;
    private Rectangle dipOverlay;
    private TimelinePlaybackController playbackController;

    private double startTimeSec = 0;
    private double endTimeSec = 0;
    private double totalDurationSec = 0;

    private boolean updatingSliderFromPlayback = false;
    private boolean userDraggingTimeline = false;
    private boolean internalVolumeUiUpdate = false;
    private boolean internalTransitionUiUpdate = false;

    private Slider timelineSlider;
    private Slider volumeSlider;
    private Label volumeValueLabel;
    private VBox volumePanel;

    private ComboBox<Clip.TransitionType> transitionInCombo;
    private ComboBox<Clip.TransitionType> transitionOutCombo;
    private Slider transitionDurationSlider;
    private Label transitionDurationLabel;
    private VBox transitionPanel;

    private Button playPauseBtn;
    private Label timeLabel;
    private Label statusLabel;
    private TextField inputField;
    private TextField outputField;

    private ScrollPane clipTimelineScroll;
    private HBox clipTimelineBox;
    private VBox timelineTracksContainer;
    private Pane timelineOverlayPane;
    private Line snapGuideLine;
    private Line playheadGuideLine;

    private Pane selectedClipTrimPane;
    private Region startHandle;
    private Region endHandle;

    private final Map<Clip, double[]> clipTrimRanges = new HashMap<>();
    private final Map<Clip, StackPane> clipNodes = new HashMap<>();
    private final Map<Clip, ClipUi> clipUiMap = new HashMap<>();
    private final Map<Integer, HBox> trackTimelineBoxes = new HashMap<>();
    private final Map<Clip, Integer> clipTrackIndexMap = new HashMap<>();
    private final Map<Clip, TimelinePlaybackController.PlaybackClip> playbackClipByClip = new HashMap<>();
    private List<TimelinePlaybackController.PlaybackClip> latestPlaybackClips = new ArrayList<>();
    private List<List<TimelinePlaybackController.PlaybackClip>> latestPlaybackTracks = new ArrayList<>();
    private List<TimelinePlaybackController.PlaybackClip> latestActiveNonPrimaryTrackClips = new ArrayList<>();

    private final Map<String, double[]> waveformCache = new ConcurrentHashMap<>();
    private final Set<String> waveformLoadingKeys = ConcurrentHashMap.newKeySet();

    private Clip draggingClip;
    private double dragStartSceneX;
    private int dragStartIndex = -1;
    private int dragTrackIndex = 0;
    private boolean snapLockActive = false;
    private double lockedSnapX = 0;
    private boolean lockedToRightEdge = false;
    private Clip hoveredTimelineClip;

    private static final int TRACK_VIDEO = 0;

    private static class ClipUi {
        final StackPane clipBlock;
        final Canvas waveformCanvas;
        final Label clipLabel;
        final Pane trimOverlay;

        ClipUi(StackPane clipBlock, Canvas waveformCanvas, Label clipLabel, Pane trimOverlay) {
            this.clipBlock = clipBlock;
            this.waveformCanvas = waveformCanvas;
            this.clipLabel = clipLabel;
            this.trimOverlay = trimOverlay;
        }
    }

    public Main() {
        this(new JavaFFmpegEngine("D:/Tools/FFmpeg/bin/ffmpeg.exe"));
    }

    public Main(VideoEngine videoEngine) {
        this.videoEngine = videoEngine;
    }

    @Override
    public void start(Stage stage) {
        mediaView = new MediaView();
        mediaView.setFitWidth(VIDEO_WIDTH);
        mediaView.setFitHeight(VIDEO_HEIGHT);
        mediaView.setPreserveRatio(true);

        previewOverlayLayer = new Pane();
        previewOverlayLayer.setPickOnBounds(false);
        previewOverlayLayer.setMouseTransparent(false);
        previewOverlayLayer.setPrefSize(VIDEO_WIDTH, VIDEO_HEIGHT);

        dipOverlay = new Rectangle(VIDEO_WIDTH, VIDEO_HEIGHT, Color.BLACK);
        dipOverlay.setOpacity(0);
        dipOverlay.setMouseTransparent(true);

        previewPane = new StackPane(mediaView, previewOverlayLayer, dipOverlay);
        previewPane.setStyle("-fx-background-color: #0f172a; -fx-padding: 8; -fx-background-radius: 8;");

        playbackController = new TimelinePlaybackController(mediaView, new TimelinePlaybackController.Listener() {
            @Override
            public void onTimeUpdate(double globalSeconds, double totalSeconds) {
                Platform.runLater(() -> {
                    totalDurationSec = totalSeconds;
                    updatingSliderFromPlayback = true;
                    timelineSlider.setMax(Math.max(0, totalDurationSec));
                    timelineSlider.setValue(clamp(globalSeconds, 0, Math.max(0, totalDurationSec)));
                    updatingSliderFromPlayback = false;
                    updateTimeLabel(globalSeconds);
                    updatePlayheadGuide();
                });
            }

            @Override
            public void onClipChanged(TimelinePlaybackController.PlaybackClip previous, TimelinePlaybackController.PlaybackClip current, boolean autoTransition) {
                Platform.runLater(() -> {
                    if (current != null && current.getClip() != null && current.getClip() != selectedClip) {
                        setSelectedClip(current.getClip(), false);
                    }
                    if (autoTransition && previous != null && current != null) {
                        simulateTransition(previous.getClip(), current.getClip());
                    }
                });
            }

            @Override
            public void onPlaybackStateChanged(boolean playing) {
                Platform.runLater(() -> playPauseBtn.setText(playing ? "⏸ Pause" : "▶ Play"));
            }

            @Override
            public void onError(String message) {
                Platform.runLater(() -> statusLabel.setText("❌ " + message));
            }

            @Override
            public void onActiveTrackClipsChanged(double globalSeconds, List<TimelinePlaybackController.PlaybackClip> activeNonPrimaryTrackClips) {
                Platform.runLater(() -> {
                    latestActiveNonPrimaryTrackClips = activeNonPrimaryTrackClips == null
                            ? new ArrayList<>()
                            : new ArrayList<>(activeNonPrimaryTrackClips);
                    renderPreviewOverlays(latestActiveNonPrimaryTrackClips);
                });
            }
        });

        timelineModel.ensureTrack(Track.TrackType.OVERLAY);
        timelineModel.ensureTrack(Track.TrackType.TEXT);
        timelineModel.ensureTrack(Track.TrackType.AUDIO);

        inputField = new TextField();
        inputField.setPromptText("Input video path...");
        inputField.setEditable(false);

        outputField = new TextField();
        outputField.setPromptText("Output path...");

        Button addClipBtn = createPrimaryButton("Add Clip");
        Button splitBtn = createPrimaryButton("Split");
        Button trimBtn = createPrimaryButton("Trim");
        Button exportBtn = createPrimaryButton("Export");
        Button addOverlayBtn = createSecondaryButton("Add Overlay");
        Button addTextBtn = createSecondaryButton("Add Text");
        Button addMusicBtn = createSecondaryButton("Add Music");
        Button openFolderBtn = createSecondaryButton("Open Output Folder");
        Button clearBtn = createSecondaryButton("Clear");

        Button prevBtn = createTransportButton("⏮ Prev");
        playPauseBtn = createTransportButton("▶ Play");
        Button nextBtn = createTransportButton("⏭ Next");
        Button stopBtn = createTransportButton("⏹ Stop");

        timelineSlider = new Slider(0, 0, 0);
        timelineSlider.setDisable(true);
        timelineSlider.setMaxWidth(Double.MAX_VALUE);

        timeLabel = new Label("00:00 / 00:00");
        timeLabel.setStyle("-fx-text-fill: #e2e8f0; -fx-font-size: 13px; -fx-font-weight: 600;");
        statusLabel = new Label("Status: Ready");

        HBox transportButtons = new HBox(12, prevBtn, playPauseBtn, nextBtn, stopBtn);
        transportButtons.setAlignment(Pos.CENTER);

        HBox playbackBar = new HBox(16, transportButtons, timelineSlider, timeLabel);
        playbackBar.setAlignment(Pos.CENTER);
        playbackBar.setPadding(new Insets(12));
        HBox.setHgrow(timelineSlider, Priority.ALWAYS);
        playbackBar.setStyle("-fx-background-color: #111827; -fx-background-radius: 10;");

        volumeSlider = new Slider(0, 2.0, 1.0);
        volumeSlider.setShowTickMarks(false);
        volumeSlider.setShowTickLabels(false);
        volumeSlider.setMajorTickUnit(0.25);
        volumeSlider.setBlockIncrement(0.05);
        volumeSlider.setPrefWidth(220);
        volumeValueLabel = new Label("1.00x");
        HBox volumeRow = new HBox(10, new Label("Volume"), volumeSlider, volumeValueLabel);
        volumeRow.setAlignment(Pos.CENTER_LEFT);
        volumePanel = new VBox(6, volumeRow);

        transitionInCombo = new ComboBox<>();
        transitionOutCombo = new ComboBox<>();
        transitionInCombo.getItems().setAll(Clip.TransitionType.values());
        transitionOutCombo.getItems().setAll(Clip.TransitionType.values());
        transitionInCombo.setPrefWidth(160);
        transitionOutCombo.setPrefWidth(160);

        transitionDurationSlider = new Slider(0.2, 2.0, 0.6);
        transitionDurationSlider.setPrefWidth(220);
        transitionDurationLabel = new Label("0.60s");

        HBox transitionRow1 = new HBox(10, new Label("In"), transitionInCombo, new Label("Out"), transitionOutCombo);
        transitionRow1.setAlignment(Pos.CENTER_LEFT);
        HBox transitionRow2 = new HBox(10, new Label("Duration"), transitionDurationSlider, transitionDurationLabel);
        transitionRow2.setAlignment(Pos.CENTER_LEFT);
        transitionPanel = new VBox(8, new Label("Transitions"), transitionRow1, transitionRow2);
        transitionPanel.setStyle("-fx-background-color: #111827; -fx-background-radius: 10; -fx-padding: 10;");

        VBox clipPropertiesPanel = new VBox(10, volumePanel, transitionPanel);
        clipPropertiesPanel.setPadding(new Insets(12));
        clipPropertiesPanel.setPrefWidth(280);
        clipPropertiesPanel.setMinWidth(260);
        clipPropertiesPanel.setMaxWidth(300);
        clipPropertiesPanel.setStyle("-fx-background-color: #111827; -fx-background-radius: 10;");

        clipTimelineBox = new HBox(8);
        timelineTracksContainer = new VBox(8);
        timelineTracksContainer.setPadding(new Insets(8));
        timelineTracksContainer.setFillWidth(true);

        clipTimelineScroll = new ScrollPane(timelineTracksContainer);
        clipTimelineScroll.setFitToHeight(false);
        clipTimelineScroll.setFitToWidth(true);
        clipTimelineScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        clipTimelineScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        clipTimelineScroll.setStyle("-fx-background: #0f172a; -fx-background-color: #0f172a;");
        clipTimelineScroll.setPrefViewportHeight(210);

        timelineOverlayPane = new Pane();
        timelineOverlayPane.setMouseTransparent(true);
        timelineOverlayPane.setPickOnBounds(false);

        playheadGuideLine = new Line(0, 0, 0, 90);
        playheadGuideLine.setStroke(Color.rgb(250, 204, 21, 0.9));
        playheadGuideLine.setStrokeWidth(1.5);
        playheadGuideLine.setVisible(false);

        snapGuideLine = new Line(0, 0, 0, 90);
        snapGuideLine.setStroke(Color.rgb(34, 211, 238, 0.95));
        snapGuideLine.setStrokeWidth(2.0);
        snapGuideLine.setVisible(false);

        timelineOverlayPane.getChildren().addAll(playheadGuideLine, snapGuideLine);
        StackPane timelineStack = new StackPane(clipTimelineScroll, timelineOverlayPane);
        timelineStack.setStyle("-fx-background-color: #0f172a; -fx-background-radius: 8;");
        timelineStack.setPadding(new Insets(2));

        startHandle = createTrimHandle("#22c55e");
        endHandle = createTrimHandle("#ef4444");

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(14));
        root.setTop(previewPane);

        Label helperLabel = new Label("Tracks: VIDEO / OVERLAY / TEXT / AUDIO. Drag clips within a track to reorder.");
        helperLabel.setStyle("-fx-text-fill: #94a3b8;");
        VBox timelineSection = new VBox(10, timelineStack, helperLabel);
        timelineSection.setPadding(new Insets(8, 4, 6, 4));

        HBox inputRow = new HBox(8, new Label("Input:"), inputField);
        inputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(inputField, Priority.ALWAYS);

        HBox outputRow = new HBox(8, new Label("Output:"), outputField);
        outputRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(outputField, Priority.ALWAYS);

        HBox actionRow = new HBox(10, addClipBtn, addOverlayBtn, addTextBtn, addMusicBtn, splitBtn, trimBtn, exportBtn, openFolderBtn, clearBtn);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        ScrollPane timelineSectionScroll = new ScrollPane(timelineSection);
        timelineSectionScroll.setFitToWidth(true);
        timelineSectionScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        timelineSectionScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(timelineSectionScroll, Priority.ALWAYS);

        BorderPane editorCenter = new BorderPane();
        editorCenter.setCenter(timelineSectionScroll);
        BorderPane.setMargin(timelineSectionScroll, new Insets(0, 12, 0, 0));
        editorCenter.setRight(clipPropertiesPanel);
        root.setCenter(editorCenter);

        VBox controlsBox = new VBox(10, playbackBar, inputRow, outputRow, actionRow, statusLabel);
controlsBox.setPadding(new Insets(10, 4, 0, 4));

// 🔥 ADD SCROLL HERE

VBox mainContent = new VBox(10,
        editorCenter,
        playbackBar,
        inputRow,
        outputRow,
        actionRow,
        statusLabel
);

ScrollPane mainScroll = new ScrollPane(mainContent);
mainScroll.setFitToWidth(true);
mainScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
mainScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

root.setCenter(mainScroll); // adjust if needed



        setupTimelineBehavior();
        setupTrimHandleDragging();
        setupVolumeBehavior();
        setupTransitionBehavior();

        addClipBtn.setOnAction(e -> onSelectVideo(stage));
        addOverlayBtn.setOnAction(e -> onAddOverlay(stage));
        addTextBtn.setOnAction(e -> onAddText());
        addMusicBtn.setOnAction(e -> onAddMusic(stage));
        splitBtn.setOnAction(e -> splitSelectedClipAtPlayhead());
        trimBtn.setOnAction(e -> trimVideo());
        exportBtn.setOnAction(e -> exportMergedTimeline());
        openFolderBtn.setOnAction(e -> openOutputFolder());
        clearBtn.setOnAction(e -> clearState());

        playPauseBtn.setOnAction(e -> togglePlayPause());
        prevBtn.setOnAction(e -> playbackController.previousClip());
        nextBtn.setOnAction(e -> playbackController.nextClip());
        stopBtn.setOnAction(e -> playbackController.stop());

        Scene scene = new Scene(root, 1100, 700);
        setupKeyboardShortcuts(scene);
        stage.setTitle("Video Editor (JavaFX + FFmpeg)");
        stage.setScene(scene);
        stage.show();
        stage.setMaximized(true);

        stage.setOnCloseRequest(e -> disposePlayer());

        clipTimelineScroll.hvalueProperty().addListener((obs, oldV, newV) -> {
            updatePlayheadGuide();
            updateSnapGuideVisibility(false, 0);
        });
        clipTimelineScroll.viewportBoundsProperty().addListener((obs, oldV, newV) -> updatePlayheadGuide());
        timelineOverlayPane.heightProperty().addListener((obs, oldV, newV) -> {
            playheadGuideLine.setEndY(newV.doubleValue());
            snapGuideLine.setEndY(newV.doubleValue());
        });

        refreshClipTimelineView();
        rebuildPlaybackTimeline(false);
    }

    private Button createPrimaryButton(String text) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: #1d4ed8; -fx-text-fill: white; -fx-background-radius: 8; -fx-padding: 8 14;");
        button.setOnMouseEntered(e -> button.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-background-radius: 8; -fx-padding: 8 14;"));
        button.setOnMouseExited(e -> button.setStyle("-fx-background-color: #1d4ed8; -fx-text-fill: white; -fx-background-radius: 8; -fx-padding: 8 14;"));
        return button;
    }

    private Button createSecondaryButton(String text) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: #374151; -fx-text-fill: white; -fx-background-radius: 8; -fx-padding: 8 14;");
        button.setOnMouseEntered(e -> button.setStyle("-fx-background-color: #4b5563; -fx-text-fill: white; -fx-background-radius: 8; -fx-padding: 8 14;"));
        button.setOnMouseExited(e -> button.setStyle("-fx-background-color: #374151; -fx-text-fill: white; -fx-background-radius: 8; -fx-padding: 8 14;"));
        return button;
    }

    private Button createTransportButton(String text) {
        Button button = new Button(text);
        button.setStyle("-fx-background-color: #0f172a; -fx-border-color: #334155; -fx-text-fill: #e2e8f0; -fx-background-radius: 8; -fx-border-radius: 8; -fx-padding: 7 12;");
        button.setOnMouseEntered(e -> button.setStyle("-fx-background-color: #1e293b; -fx-border-color: #475569; -fx-text-fill: #f8fafc; -fx-background-radius: 8; -fx-border-radius: 8; -fx-padding: 7 12;"));
        button.setOnMouseExited(e -> button.setStyle("-fx-background-color: #0f172a; -fx-border-color: #334155; -fx-text-fill: #e2e8f0; -fx-background-radius: 8; -fx-border-radius: 8; -fx-padding: 7 12;"));
        return button;
    }

    private Region createTrimHandle(String color) {
        Region handle = new Region();
        handle.setPrefWidth(10);
        handle.setPrefHeight(22);
        handle.setStyle("-fx-background-color: " + color + "; -fx-background-radius: 3;");
        return handle;
    }

    private void setupTimelineBehavior() {
        timelineSlider.valueChangingProperty().addListener((obs, oldVal, newVal) -> {
            userDraggingTimeline = newVal;
            if (!newVal) {
                seekToSeconds(timelineSlider.getValue());
                updatePlayheadGuide();
            }
        });

        timelineSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateTimeLabel(newVal.doubleValue());
            if (!timelineSlider.isValueChanging()) {
                updatePlayheadGuide();
            }

            if (updatingSliderFromPlayback) {
                return;
            }
            if (!timelineSlider.isValueChanging()) {
                seekToSeconds(newVal.doubleValue());
            }
        });
    }

    private void setupVolumeBehavior() {
        volumeSlider.valueProperty().addListener((obs, oldV, newV) -> {
            volumeValueLabel.setText(String.format("%.2fx", newV.doubleValue()));
            if (internalVolumeUiUpdate || selectedClip == null) {
                return;
            }

            Clip old = selectedClip;
            Clip updated = old.withVolume(clamp(newV.doubleValue(), 0, 2.0));
            if (mediaView.getMediaPlayer() != null) {
    mediaView.getMediaPlayer().setVolume(
        clamp(updated.getVolume(), 0, 1)
    );
}
            int trackIndex = clipTrackIndexMap.getOrDefault(old, TRACK_VIDEO);
            int index = timelineModel.indexOfInTrack(trackIndex, old);
            if (index < 0) {
                return;
            }

            timelineModel.replaceClipInTrack(trackIndex, index, updated);
            migrateClipMetadata(old, updated);
            selectedClip = updated;

            StackPane oldNode = clipNodes.remove(old);
            if (oldNode != null) {
                clipNodes.put(updated, oldNode);
            }

            ClipUi oldUi = clipUiMap.remove(old);
            if (oldUi != null) {
                clipUiMap.put(updated, oldUi);
                updateClipLabel(updated, oldUi.clipLabel);
                drawWaveform(updated, oldUi.waveformCanvas, true);
                applyClipSelectionState(updated, oldUi, true);
            } else {
                refreshClipTimelineView();
            }

            rebuildPlaybackTimeline(false);
        });
    }

    private void setupTransitionBehavior() {
        transitionInCombo.valueProperty().addListener((obs, oldV, newV) -> applyTransitionFromPanel());
        transitionOutCombo.valueProperty().addListener((obs, oldV, newV) -> applyTransitionFromPanel());
        transitionDurationSlider.valueProperty().addListener((obs, oldV, newV) -> {
            transitionDurationLabel.setText(String.format("%.2fs", newV.doubleValue()));
            applyTransitionFromPanel();
        });
    }

    private void applyTransitionFromPanel() {
        if (internalTransitionUiUpdate || selectedClip == null) {
            return;
        }

        Clip.TransitionType in = transitionInCombo.getValue() == null ? Clip.TransitionType.NONE : transitionInCombo.getValue();
        Clip.TransitionType out = transitionOutCombo.getValue() == null ? Clip.TransitionType.NONE : transitionOutCombo.getValue();
        double duration = clamp(transitionDurationSlider.getValue(), 0.2, 2.0);

        Clip old = selectedClip;
        Clip updated = old.withTransitions(in, out, duration, duration);
        int trackIndex = clipTrackIndexMap.getOrDefault(old, TRACK_VIDEO);
        int index = timelineModel.indexOfInTrack(trackIndex, old);
        if (index < 0) {
            return;
        }

        timelineModel.replaceClipInTrack(trackIndex, index, updated);
        migrateClipMetadata(old, updated);
        selectedClip = updated;

        StackPane oldNode = clipNodes.remove(old);
        if (oldNode != null) {
            clipNodes.put(updated, oldNode);
        }

        ClipUi oldUi = clipUiMap.remove(old);
        if (oldUi != null) {
            clipUiMap.put(updated, oldUi);
            updateClipLabel(updated, oldUi.clipLabel);
            drawWaveform(updated, oldUi.waveformCanvas, true);
        }

        refreshClipTimelineView();
        rebuildPlaybackTimeline(false);
    }

    private void setupTrimHandleDragging() {
        startHandle.setOnMousePressed(MouseEvent::consume);
        startHandle.setOnMouseDragged(e -> {
            if (getSelectedClipDuration() <= 0 || selectedClipTrimPane == null) {
                e.consume();
                return;
            }
            double x = clamp(e.getX() + startHandle.getLayoutX(), 0, selectedClipTrimPane.getWidth());
            double sec = xToSeconds(x);
            startTimeSec = Math.min(sec, endTimeSec);
            persistSelectedClipTrim();
            refreshTrimHandles();
            rebuildPlaybackTimeline(false);
            e.consume();
        });
        startHandle.setOnMouseReleased(MouseEvent::consume);

        endHandle.setOnMousePressed(MouseEvent::consume);
        endHandle.setOnMouseDragged(e -> {
            if (getSelectedClipDuration() <= 0 || selectedClipTrimPane == null) {
                e.consume();
                return;
            }
            double x = clamp(e.getX() + endHandle.getLayoutX(), 0, selectedClipTrimPane.getWidth());
            double sec = xToSeconds(x);
            endTimeSec = Math.max(sec, startTimeSec);
            persistSelectedClipTrim();
            refreshTrimHandles();
            rebuildPlaybackTimeline(false);
            e.consume();
        });
        endHandle.setOnMouseReleased(MouseEvent::consume);
    }

    private void onSelectVideo(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Video");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Video Files", "*.mp4", "*.mov", "*.mkv", "*.avi"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );

        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }

        inputField.setText(file.getAbsolutePath());
        if (outputField.getText().trim().isEmpty()) {
            outputField.setText("D:/Dev/Project/Video_Editor/exports/output_" + System.currentTimeMillis() + ".mp4");
        }

        statusLabel.setText("Status: Adding clip...");
        addClipToTimeline(file.getAbsolutePath(), true);
    }

    private void addClipToTimeline(String videoPath, boolean autoSelect) {
        addClipToTrack(videoPath, TRACK_VIDEO, autoSelect, Track.TrackType.VIDEO);
    }

    private void onAddOverlay(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Overlay (Image/Video)");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Media Files", "*.mp4", "*.mov", "*.mkv", "*.avi", "*.png", "*.jpg", "*.jpeg", "*.webp"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        int trackIndex = timelineModel.ensureTrack(Track.TrackType.OVERLAY);
        addClipToTrack(file.getAbsolutePath(), trackIndex, true, Track.TrackType.OVERLAY);
    }

    private void onAddMusic(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select Music / Audio");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Audio Files", "*.mp3", "*.wav", "*.aac", "*.m4a", "*.ogg"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        int trackIndex = timelineModel.ensureTrack(Track.TrackType.AUDIO);
        addClipToTrack(file.getAbsolutePath(), trackIndex, true, Track.TrackType.AUDIO);
    }

    private void onAddText() {
        TextInputDialog dialog = new TextInputDialog("Sample Text");
        dialog.setTitle("Add Text Clip");
        dialog.setHeaderText(null);
        dialog.setContentText("Text:");
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty() || result.get().trim().isEmpty()) {
            return;
        }
        int trackIndex = timelineModel.ensureTrack(Track.TrackType.TEXT);
        Clip textClip = new Clip("__TEXT__", 0, 4.0, 1.0)
                .withText(result.get().trim())
                .withPosition(VIDEO_WIDTH * 0.1, VIDEO_HEIGHT * 0.8)
                .withOpacity(1.0)
                .withTextFontSize(42);
        timelineModel.addClipToTrack(trackIndex, textClip);
        clipTrimRanges.put(textClip, new double[]{0, textClip.getDurationSeconds()});
        refreshClipTimelineView();
        rebuildPlaybackTimeline(false);
        setSelectedClip(textClip, false);
    }

    private void addClipToTrack(String path, int trackIndex, boolean autoSelect, Track.TrackType trackType) {
        if (trackType == Track.TrackType.TEXT) {
            return;
        }

        if (isStillImage(path)) {
            Clip clip = new Clip(path, 0, 4.0, 1.0)
                    .withPosition(VIDEO_WIDTH / 2.0, VIDEO_HEIGHT / 2.0)
                    .withScale(1.0)
                    .withOpacity(1.0);
            timelineModel.addClipToTrack(trackIndex, clip);
            clipTrimRanges.put(clip, new double[]{0, clip.getDurationSeconds()});
            refreshClipTimelineView();
            rebuildPlaybackTimeline(false);
            if (autoSelect) {
                setSelectedClip(clip, false);
            }
            return;
        }

        Media media = new Media(new File(path).toURI().toString());
        MediaPlayer probe = new MediaPlayer(media);

        probe.setOnReady(() -> {
            double duration = probe.getTotalDuration().toSeconds();
            probe.dispose();

            Platform.runLater(() -> {
                Clip clip = new Clip(path, 0, duration, 1.0);
                timelineModel.addClipToTrack(trackIndex, clip);
                clipTrimRanges.put(clip, new double[]{0, clip.getDurationSeconds()});
                refreshClipTimelineView();
                rebuildPlaybackTimeline(false);

                if (autoSelect) {
                    setSelectedClip(clip, true);
                }

                statusLabel.setText("✅ Clip added to " + trackType + " track");
            });
        });

        probe.setOnError(() -> {
            probe.dispose();
            Platform.runLater(() -> showError("Failed to load clip"));
        });
    }

    private boolean isStillImage(String path) {
        String lower = path == null ? "" : path.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp");
    }

    private void splitSelectedClipAtPlayhead() {
        if (selectedClip == null) {
            showInfo("Select a clip first.");
            return;
        }

        TimelinePlaybackController.PlaybackClip selectedSegment = playbackClipByClip.get(selectedClip);
        if (selectedSegment == null) {
            showInfo("Selected clip has no playable range.");
            return;
        }

        double splitGlobal = timelineSlider.getValue();
        double splitLocalInSegment = splitGlobal - selectedSegment.getTimelineStart();
        splitLocalInSegment = clamp(splitLocalInSegment, 0, selectedSegment.getDurationSeconds());

        double splitSourceSeconds = selectedSegment.getSourceStart() + splitLocalInSegment;
        double clipRelative = splitSourceSeconds - selectedClip.getStartSeconds();
        if (clipRelative <= 0.01 || clipRelative >= selectedClip.getDurationSeconds() - 0.01) {
            showInfo("Move playhead inside the clip to split.");
            return;
        }

        persistSelectedClipTrim();

        int trackIndex = clipTrackIndexMap.getOrDefault(selectedClip, TRACK_VIDEO);
        int index = timelineModel.indexOfInTrack(trackIndex, selectedClip);
        if (index < 0) {
            return;
        }

        Clip original = selectedClip;
        Clip[] splitResult;
        try {
            splitResult = timelineModel.splitClipAtSourceTimeInTrack(trackIndex, index, splitSourceSeconds);
        } catch (Exception ex) {
            showError("Split failed: " + ex.getMessage());
            return;
        }

        double[] originalTrim = clipTrimRanges.getOrDefault(original, new double[]{0, original.getDurationSeconds()});
        clipTrimRanges.remove(original);

        double leftDuration = splitResult[0].getDurationSeconds();
        double rightDuration = splitResult[1].getDurationSeconds();

        double leftStart = clamp(originalTrim[0], 0, leftDuration);
        double leftEnd = clamp(originalTrim[1], leftStart, leftDuration);

        double rightStart = clamp(originalTrim[0] - clipRelative, 0, rightDuration);
        double rightEnd = clamp(originalTrim[1] - clipRelative, rightStart, rightDuration);

        clipTrimRanges.put(splitResult[0], new double[]{leftStart, leftEnd});
        clipTrimRanges.put(splitResult[1], new double[]{rightStart, rightEnd});

        if (splitResult[0].getWaveformPeaks() != null) {
            waveformCache.put(splitResult[0].getWaveformCacheKey(), splitResult[0].getWaveformPeaks());
        }
        if (splitResult[1].getWaveformPeaks() != null) {
            waveformCache.put(splitResult[1].getWaveformCacheKey(), splitResult[1].getWaveformPeaks());
        }

        refreshClipTimelineView();
rebuildPlaybackTimeline(false);
setSelectedClip(splitResult[0], true); // ONLY ONCE, AT END
        statusLabel.setText("✅ Clip split at " + formatTime((int) Math.floor(splitGlobal)));
    }

    private void trimVideo() {
        if (selectedClip == null) {
            showInfo("Select a clip first!");
            return;
        }

        String requestedOutput = outputField.getText().trim();
        String output = buildUniqueOutputPath(requestedOutput, "trim");
        outputField.setText(output);

        double[] range = clipTrimRanges.get(selectedClip);
        if (range == null) {
            showInfo("No trim range set!");
            return;
        }

        double relStart = clamp(range[0], 0, selectedClip.getDurationSeconds());
        double relEnd = clamp(range[1], relStart, selectedClip.getDurationSeconds());

        Clip trimmedClip = selectedClip.withRange(
                selectedClip.getStartSeconds() + relStart,
                selectedClip.getStartSeconds() + relEnd
        );

        statusLabel.setText("Status: Trimming clip...");

        new Thread(() -> {
            try {
                ((JavaFFmpegEngine) videoEngine).trimSingleClip(trimmedClip, output);
                Platform.runLater(() -> {
    statusLabel.setText("✅ Trim complete!");
    showInfo("Clip trimmed successfully!");
    updateVolumePanel();
    updateTransitionPanel();
});
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("❌ Trim failed");
                    showError("Error: " + ex.getMessage());
                });
            }
        }, "trim-thread").start();
    }

    private void exportMergedTimeline() {
        if (timelineModel.isEmpty()) {
            showInfo("Add clips first!");
            return;
        }

        String requestedOutput = outputField.getText().trim();
        String output = buildUniqueOutputPath(requestedOutput, "merge");
        outputField.setText(output);

        TimelineModel exportTimeline = new TimelineModel();
        List<Track> tracks = timelineModel.getTracks();
        for (int t = 0; t < tracks.size(); t++) {
            Track sourceTrack = tracks.get(t);
            int exportTrackIndex = t == 0 ? TRACK_VIDEO : exportTimeline.ensureTrack(sourceTrack.getType());

            for (Clip clip : sourceTrack.getClips()) {
                double[] range = clipTrimRanges.getOrDefault(clip, new double[]{0, clip.getDurationSeconds()});

                double relStart = clamp(range[0], 0, clip.getDurationSeconds());
                double relEnd = clamp(range[1], relStart, clip.getDurationSeconds());

                double start = clip.getStartSeconds() + relStart;
                double end = clip.getStartSeconds() + relEnd;

                if (end > start) {
                    exportTimeline.addClipToTrack(exportTrackIndex, new Clip(
                            clip.getSourcePath(),
                            start,
                            end,
                            clip.getVolume(),
                            clip.getTransitionIn(),
                            clip.getTransitionOut(),
                            clip.getTransitionInDurationSeconds(),
                            clip.getTransitionOutDurationSeconds(),
                            clip.getPositionX(),
                            clip.getPositionY(),
                            clip.getScale(),
                            clip.getOpacity(),
                            clip.getTextContent(),
                            clip.getZIndex(),
                            clip.getTextFontSize()
                    ));
                }
            }
        }

        if (exportTimeline.isEmpty()) {
            showInfo("Selected trim ranges are empty. Adjust trim handles and try again.");
            return;
        }

        statusLabel.setText("Status: Exporting merged timeline...");

        new Thread(() -> {
            try {
                videoEngine.renderTimeline(exportTimeline, output);
                Platform.runLater(() -> {
    statusLabel.setText("✅ Export/Merge complete!");
    showInfo("Merged video exported successfully!");
    updateVolumePanel();
    updateTransitionPanel();
});
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("❌ Export/Merge failed");
                    showError("Merge error: " + ex.getMessage());
                });
            }
        }, "export-thread").start();
    }

    private String buildUniqueOutputPath(String requestedPath, String operation) {
        File defaultDir = Paths.get("D:/Dev/Project/Video_Editor/exports/").toFile();
        if (!defaultDir.exists()) {
            defaultDir.mkdirs();
        }

        String normalized = requestedPath == null ? "" : requestedPath.trim();
        File target = normalized.isEmpty() ? new File(defaultDir, "output.mp4") : new File(normalized);

        File parent = target.getParentFile();
        if (parent == null) {
            parent = defaultDir;
        }

        String fileName = target.getName().isEmpty() ? "output.mp4" : target.getName();
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        String extension = dotIndex > 0 ? fileName.substring(dotIndex) : ".mp4";

        String uniqueName = baseName + "_" + operation + "_" + System.currentTimeMillis() + extension;
        return new File(parent, uniqueName).getAbsolutePath();
    }

    private void openOutputFolder() {
        try {
            File folder = Paths.get("D:/Dev/Project/Video_Editor/exports/").toFile();
            if (!folder.exists()) {
                folder.mkdirs();
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(folder);
            }
        } catch (Exception ex) {
            showError("Unable to open folder: " + ex.getMessage());
        }
    }

    private void clearState() {
        inputField.clear();
        outputField.clear();

        timelineModel = new TimelineModel();
        timelineModel.ensureTrack(Track.TrackType.OVERLAY);
        timelineModel.ensureTrack(Track.TrackType.TEXT);
        timelineModel.ensureTrack(Track.TrackType.AUDIO);
        selectedClip = null;
        updateVolumePanel();
updateTransitionPanel();
        

        clipTrimRanges.clear();
        clipNodes.clear();
        clipUiMap.clear();
        playbackClipByClip.clear();
        latestPlaybackClips.clear();
        latestActiveNonPrimaryTrackClips.clear();
        waveformCache.clear();
        waveformLoadingKeys.clear();
        trackTimelineBoxes.clear();
        clipTrackIndexMap.clear();
        previewOverlayLayer.getChildren().clear();

        selectedClipTrimPane = null;
        startTimeSec = 0;
        endTimeSec = 0;
        totalDurationSec = 0;

        timelineSlider.setMax(0);
        timelineSlider.setValue(0);
        timelineSlider.setDisable(true);

        internalVolumeUiUpdate = true;
        volumeSlider.setValue(1.0);
        volumeValueLabel.setText("1.00x");
        internalVolumeUiUpdate = false;

        internalTransitionUiUpdate = true;
        transitionInCombo.setValue(Clip.TransitionType.NONE);
        transitionOutCombo.setValue(Clip.TransitionType.NONE);
        transitionDurationSlider.setValue(0.6);
        transitionDurationLabel.setText("0.60s");
        internalTransitionUiUpdate = false;

        updateTimeLabel(0);
        statusLabel.setText("Status: Cleared");

        disposePlayer();
        refreshClipTimelineView();
        refreshTrimHandles();
        updatePlayheadGuide();
    }

    private void togglePlayPause() {
        if (latestPlaybackClips.isEmpty()) {
            showInfo("Add clips first!");
            return;
        }

        if (playbackController.isPlaying()) {
            playbackController.pause();
            if (videoEngine != null) {
                videoEngine.pause();
            }
        } else {
            playbackController.play();
            if (videoEngine != null) {
                videoEngine.play();
            }
        }
    }

    private void disposePlayer() {
        if (playbackController != null) {
            playbackController.dispose();
        }
    }

    private void seekToSeconds(double seconds) {
        if (latestPlaybackClips.isEmpty()) {
            return;
        }
        playbackController.seek(seconds);
        if (videoEngine != null) {
            videoEngine.seek(seconds);
        }
    }

    private void rebuildPlaybackTimeline(boolean seekToSelectedClipStart) {
        double currentTime = timelineSlider == null ? 0 : timelineSlider.getValue();

        latestPlaybackTracks = buildPlaybackTracks();
        latestPlaybackClips = latestPlaybackTracks.isEmpty() ? new ArrayList<>() : new ArrayList<>(latestPlaybackTracks.get(0));
        playbackClipByClip.clear();
        for (List<TimelinePlaybackController.PlaybackClip> track : latestPlaybackTracks) {
            for (TimelinePlaybackController.PlaybackClip playbackClip : track) {
                playbackClipByClip.put(playbackClip.getClip(), playbackClip);
            }
        }

        playbackController.setTimelineTracks(latestPlaybackTracks);
        totalDurationSec = playbackController.getTotalDurationSeconds();

        if (timelineSlider != null) {
            timelineSlider.setDisable(latestPlaybackClips.isEmpty());
            timelineSlider.setMax(Math.max(0, totalDurationSec));
        }

        double seekTarget = currentTime;
        if (seekToSelectedClipStart && selectedClip != null) {
            TimelinePlaybackController.PlaybackClip segment = playbackClipByClip.get(selectedClip);
            if (segment != null) {
                seekTarget = segment.getTimelineStart();
            }
        }

        playbackController.seek(clamp(seekTarget, 0, Math.max(0, totalDurationSec)));
        updateTimeLabel(playbackController.getCurrentGlobalSeconds());
        updatePlayheadGuide();
    }

    private List<List<TimelinePlaybackController.PlaybackClip>> buildPlaybackTracks() {
        List<List<TimelinePlaybackController.PlaybackClip>> tracks = new ArrayList<>();
        List<Track> modelTracks = timelineModel.getTracks();
        for (int t = 0; t < modelTracks.size(); t++) {
            List<TimelinePlaybackController.PlaybackClip> row = new ArrayList<>();
            double timelineCursor = 0;
            for (Clip clip : modelTracks.get(t).getClips()) {
                double[] range = clipTrimRanges.getOrDefault(clip, new double[]{0, clip.getDurationSeconds()});
                double relStart = clamp(range[0], 0, clip.getDurationSeconds());
                double relEnd = clamp(range[1], relStart, clip.getDurationSeconds());
                double sourceStart = clip.getStartSeconds() + relStart;
                double sourceEnd = clip.getStartSeconds() + relEnd;
                if (sourceEnd <= sourceStart) {
                    continue;
                }
                TimelinePlaybackController.PlaybackClip playbackClip =
                        new TimelinePlaybackController.PlaybackClip(clip, timelineCursor, sourceStart, sourceEnd);
                row.add(playbackClip);
                timelineCursor += playbackClip.getDurationSeconds();
            }
            tracks.add(row);
        }
        return tracks;
    }

    private void refreshTrimHandles() {
        double width = selectedClipTrimPane != null ? selectedClipTrimPane.getWidth() : 0;
        if (selectedClipTrimPane == null || width <= 0 || getSelectedClipDuration() <= 0) {
            return;
        }

        clampTrimTimes();
        double startX = secondsToX(startTimeSec);
        double endX = secondsToX(endTimeSec);

        startHandle.setLayoutX(clamp(startX - startHandle.getPrefWidth() / 2.0, 0, width - startHandle.getPrefWidth()));
        endHandle.setLayoutX(clamp(endX - endHandle.getPrefWidth() / 2.0, 0, width - endHandle.getPrefWidth()));
    }

    private void clampTrimTimes() {
        double selectedDuration = Math.max(0, getSelectedClipDuration());
        startTimeSec = clamp(startTimeSec, 0, selectedDuration);
        endTimeSec = clamp(endTimeSec, 0, selectedDuration);
        if (endTimeSec < startTimeSec) {
            endTimeSec = startTimeSec;
        }
    }

    private double xToSeconds(double x) {
        double selectedDuration = getSelectedClipDuration();
        if (selectedClipTrimPane == null || selectedClipTrimPane.getWidth() <= 0 || selectedDuration <= 0) {
            return 0;
        }
        return (x / selectedClipTrimPane.getWidth()) * selectedDuration;
    }

    private double secondsToX(double sec) {
        double selectedDuration = getSelectedClipDuration();
        if (selectedClipTrimPane == null || selectedDuration <= 0) {
            return 0;
        }
        return (sec / selectedDuration) * selectedClipTrimPane.getWidth();
    }

    private void refreshClipTimelineView() {
        timelineTracksContainer.getChildren().clear();
        clipNodes.clear();
        clipUiMap.clear();
        trackTimelineBoxes.clear();
        clipTrackIndexMap.clear();
        selectedClipTrimPane = null;

        List<Track> tracks = timelineModel.getTracks();
        for (int t = 0; t < tracks.size(); t++) {
            Track track = tracks.get(t);
            HBox trackBox = new HBox(8);
            trackBox.setAlignment(Pos.CENTER_LEFT);
            trackBox.setPadding(new Insets(8));
            trackBox.setMinHeight(88);
            trackBox.setStyle("-fx-background-color: rgba(15,23,42,0.6); -fx-background-radius: 6;");
            trackTimelineBoxes.put(t, trackBox);

            Label trackLabel = new Label("Track " + t + " [" + track.getType() + "]");
            trackLabel.setStyle("-fx-text-fill: #93c5fd; -fx-font-weight: 700; -fx-min-width: 120;");

            for (int i = 0; i < track.getClips().size(); i++) {
                Clip clip = track.getClips().get(i);
                clipTrackIndexMap.put(clip, t);
                boolean selected = clip == selectedClip;
                double width = Math.max(MIN_CLIP_WIDTH, clip.getDurationSeconds() * CLIP_PIXELS_PER_SECOND);

                StackPane clipBlock = new StackPane();
                clipBlock.setPrefWidth(width);
                clipBlock.setMinWidth(width);
                clipBlock.setMaxWidth(width);
                clipBlock.setPrefHeight(80); // Increased height
                clipBlock.setPadding(new Insets(8, 10, 8, 10)); // Increased padding for larger clickable area
                clipBlock.setCursor(javafx.scene.Cursor.HAND);
                applyClipBlockStyle(clip, clipBlock, selected);

                Canvas waveformCanvas = new Canvas(width - 8, 34);
                StackPane.setAlignment(waveformCanvas, Pos.CENTER);
                drawWaveform(clip, waveformCanvas, selected);

                String filename = clip.getSourcePath();
                if (!"__TEXT__".equals(filename)) {
                    filename = new File(clip.getSourcePath()).getName();
                } else {
                    filename = "Text: " + clip.getTextContent();
                }
                Label clipLabel = new Label((i + 1) + ": " + filename + "  [vol " + String.format("%.2f", clip.getVolume()) + "x]");
                clipLabel.setStyle("-fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: 700;");
                StackPane.setAlignment(clipLabel, Pos.TOP_LEFT);
                StackPane.setMargin(clipLabel, new Insets(4, 6, 0, 6));

                Pane trimOverlay = new Pane();
                trimOverlay.setPrefHeight(20);
                trimOverlay.setMaxHeight(20);
                trimOverlay.setMinHeight(20);
                trimOverlay.setStyle("-fx-background-color: rgba(15,23,42,0.38); -fx-background-radius: 0 0 6 6;");
                trimOverlay.setVisible(selected);
                StackPane.setAlignment(trimOverlay, Pos.BOTTOM_LEFT);
                trimOverlay.widthProperty().addListener((obs, oldV, newV) -> {
                    if (clip == selectedClip) {
                        refreshTrimHandles();
                    }
                });

                clipBlock.getChildren().addAll(waveformCanvas, clipLabel, trimOverlay);
                attachClipDragHandlers(clip, clipBlock, t);
                attachClipHoverHandlers(clip, clipBlock);

                clipNodes.put(clip, clipBlock);
                clipUiMap.put(clip, new ClipUi(clipBlock, waveformCanvas, clipLabel, trimOverlay));
                trackBox.getChildren().add(clipBlock);

                if (i < track.getClips().size() - 1) {
                    Region indicator = createTransitionIndicator(clip, track.getClips().get(i + 1));
                    trackBox.getChildren().add(indicator);
                }
            }

            final int currentTrackIndex = t;
            HBox row = new HBox(8, trackLabel, trackBox);
            row.setAlignment(Pos.CENTER_LEFT);
            trackBox.setOnMouseClicked(e -> {
                if (e.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                selectNearestClipInTrackOrClear(currentTrackIndex, trackBox, e.getX());
            });
            timelineTracksContainer.getChildren().add(row);
        }

       
if (selectedClip != null && !timelineModel.contains(selectedClip)) {
    selectedClip = timelineModel.getClips().isEmpty() ? null : timelineModel.getClips().get(0);
}
applySelectionVisualState(null, selectedClip);
        refreshTrimHandles();
        updateVolumePanel();
        updateTransitionPanel();
        Platform.runLater(this::updatePlayheadGuide);
    }

    private Region createTransitionIndicator(Clip left, Clip right) {
        String text = "";
        Clip.TransitionType out = left.getTransitionOut();
        Clip.TransitionType in = right.getTransitionIn();
        Clip.TransitionType type = out != Clip.TransitionType.NONE ? out : in;
        if (type != Clip.TransitionType.NONE) {
            text = "↔";
        }

        Label indicator = new Label(text);
        indicator.setMinWidth(16);
        indicator.setPrefWidth(16);
        indicator.setMaxWidth(16);
        indicator.setAlignment(Pos.CENTER);
        indicator.setStyle("-fx-text-fill: #facc15; -fx-background-color: rgba(250,204,21,0.15); -fx-background-radius: 3;");
        return indicator;
    }

    private void attachClipDragHandlers(Clip clip, StackPane clipBlock, int trackIndex) {
        final double dragThreshold = 5.0;

        final double[] pressSceneX = new double[1];
        final double[] pressSceneY = new double[1];
        final boolean[] dragging = new boolean[1];

        clipBlock.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;

            pressSceneX[0] = e.getSceneX();
            pressSceneY[0] = e.getSceneY();
            dragging[0] = false;
        });

        clipBlock.setOnMouseDragged(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;

            double dx = e.getSceneX() - pressSceneX[0];
            double dy = e.getSceneY() - pressSceneY[0];

            if (!dragging[0]) {
                if (Math.hypot(dx, dy) < dragThreshold) return;

                dragging[0] = true;
                draggingClip = clip;
                dragStartSceneX = pressSceneX[0];
                dragTrackIndex = trackIndex;
                dragStartIndex = timelineModel.indexOfInTrack(trackIndex, clip);
                clipBlock.toFront();
                clipBlock.setOpacity(0.9);
            }

            if (draggingClip != clip) return;

            clipBlock.setTranslateX(dx);
            applySnapping(clipBlock, trackIndex);
            e.consume();
        });

        clipBlock.setOnMouseReleased(e -> {
            if (!dragging[0]) {
                setSelectedClip(clip, true);
                return;
            }

            if (draggingClip != clip) return;

            int targetIndex = computeDropIndex(clip, clipBlock, trackIndex);

            clipBlock.setTranslateX(0);
            clipBlock.setOpacity(1.0);

            draggingClip = null;
            dragging[0] = false;

            updateSnapGuideVisibility(false, 0);

            if (dragStartIndex >= 0 && targetIndex != dragStartIndex) {
                timelineModel.removeClipFromTrack(trackIndex, dragStartIndex);
                if (targetIndex > dragStartIndex) targetIndex--;
                timelineModel.insertClipInTrack(trackIndex, targetIndex, clip);
            }

            refreshClipTimelineView();
            rebuildPlaybackTimeline(false);
            e.consume();
        });
    }

    private int computeDropIndex(Clip draggedClip, StackPane draggedBlock, int trackIndex) {
        double draggedCenter = draggedBlock.getBoundsInParent().getMinX() + draggedBlock.getBoundsInParent().getWidth() / 2.0;

        List<Map.Entry<Clip, StackPane>> others = new ArrayList<>();
        for (Map.Entry<Clip, StackPane> entry : clipNodes.entrySet()) {
            if (entry.getKey() != draggedClip && clipTrackIndexMap.getOrDefault(entry.getKey(), TRACK_VIDEO) == trackIndex) {
                others.add(entry);
            }
        }

        others.sort(Comparator.comparingDouble(e -> e.getValue().getBoundsInParent().getMinX()));

        int index = 0;
        for (Map.Entry<Clip, StackPane> entry : others) {
            StackPane block = entry.getValue();
            double center = block.getBoundsInParent().getMinX() + block.getBoundsInParent().getWidth() / 2.0;
            if (draggedCenter > center) {
                index++;
            }
        }

        return index;
    }

    private void applySnapping(StackPane draggedBlock, int trackIndex) {
        double left = draggedBlock.getBoundsInParent().getMinX();
        double right = draggedBlock.getBoundsInParent().getMaxX();

        double threshold = SNAP_THRESHOLD_PX;
        double hysteresis = SNAP_THRESHOLD_PX + 3;

        if (snapLockActive) {
            double distance = Math.min(Math.abs((lockedToRightEdge ? right : left) - lockedSnapX), Math.abs(right - lockedSnapX));
            if (distance <= hysteresis) {
                double delta = lockedToRightEdge ? (lockedSnapX - right) : (lockedSnapX - left);
                draggedBlock.setTranslateX(draggedBlock.getTranslateX() + delta);
                updateSnapGuideVisibility(true, lockedSnapX);
                return;
            }
            snapLockActive = false;
        }

        double bestDist = Double.MAX_VALUE;
        SnapDecision bestDecision = new SnapDecision(0, false);

        for (StackPane other : clipNodes.values()) {
            Clip otherClip = null;
            for (Map.Entry<Clip, StackPane> entry : clipNodes.entrySet()) {
                if (entry.getValue() == other) {
                    otherClip = entry.getKey();
                    break;
                }
            }
            if (other == draggedBlock || otherClip == null || clipTrackIndexMap.getOrDefault(otherClip, TRACK_VIDEO) != trackIndex) {
                continue;
            }

            double oLeft = other.getBoundsInParent().getMinX();
            double oRight = other.getBoundsInParent().getMaxX();
            bestDist = evaluateSnapCandidate(left, right, oLeft, bestDist, holder -> {
                bestDecision.snapX = holder.snapX;
                bestDecision.alignRight = holder.alignRight;
            });
            bestDist = evaluateSnapCandidate(left, right, oRight, bestDist, holder -> {
                bestDecision.snapX = holder.snapX;
                bestDecision.alignRight = holder.alignRight;
            });
        }

        Double playheadContentX = getPlayheadContentX();
        if (playheadContentX != null) {
            bestDist = evaluateSnapCandidate(left, right, playheadContentX, bestDist, holder -> {
                bestDecision.snapX = holder.snapX;
                bestDecision.alignRight = holder.alignRight;
            });
        }

        if (bestDist <= threshold) {
            double delta = bestDecision.alignRight ? (bestDecision.snapX - right) : (bestDecision.snapX - left);
            draggedBlock.setTranslateX(draggedBlock.getTranslateX() + delta);
            snapLockActive = true;
            lockedSnapX = bestDecision.snapX;
            lockedToRightEdge = bestDecision.alignRight;
            updateSnapGuideVisibility(true, bestDecision.snapX);
        } else {
            updateSnapGuideVisibility(false, 0);
        }
    }

    private interface SnapConsumer {
        void accept(SnapDecision decision);
    }

    private static class SnapDecision {
        double snapX;
        boolean alignRight;

        SnapDecision(double snapX, boolean alignRight) {
            this.snapX = snapX;
            this.alignRight = alignRight;
        }
    }

    private double evaluateSnapCandidate(double left, double right, double candidateX, double currentBest, SnapConsumer consumer) {
        double distLeft = Math.abs(left - candidateX);
        if (distLeft < currentBest) {
            consumer.accept(new SnapDecision(candidateX, false));
            currentBest = distLeft;
        }

        double distRight = Math.abs(right - candidateX);
        if (distRight < currentBest) {
            consumer.accept(new SnapDecision(candidateX, true));
            currentBest = distRight;
        }

        return currentBest;
    }

    private void updateSnapGuideVisibility(boolean visible, double contentX) {
        snapGuideLine.setVisible(visible);
        if (visible) {
            snapGuideLine.setStartX(contentXToOverlayX(contentX));
            snapGuideLine.setEndX(contentXToOverlayX(contentX));
        }
    }

    private void attachTrimHandlesToSelectedClip() {
        if (selectedClipTrimPane == null) {
            return;
        }

        if (startHandle.getParent() instanceof Pane) {
            ((Pane) startHandle.getParent()).getChildren().remove(startHandle);
        }
        if (endHandle.getParent() instanceof Pane) {
            ((Pane) endHandle.getParent()).getChildren().remove(endHandle);
        }

        selectedClipTrimPane.getChildren().addAll(startHandle, endHandle);
        startHandle.toFront();
        endHandle.toFront();
    }

    private void attachClipHoverHandlers(Clip clip, StackPane clipBlock) {
        clipBlock.setOnMouseEntered(e -> {
            hoveredTimelineClip = clip;
            applyClipBlockStyle(clip, clipBlock, clip == selectedClip);
        });
        clipBlock.setOnMouseExited(e -> {
            if (hoveredTimelineClip == clip) {
                hoveredTimelineClip = null;
            }
            applyClipBlockStyle(clip, clipBlock, clip == selectedClip);
        });
    }

    private void selectNearestClipInTrackOrClear(int trackIndex, HBox trackBox, double clickX) {
        List<Clip> clips = timelineModel.getClipsInTrack(trackIndex);
        if (clips.isEmpty()) {
            clearSelectedClip();
            return;
        }

        Clip nearest = null;
        double bestDistance = Double.MAX_VALUE;
        for (Clip c : clips) {
            StackPane node = clipNodes.get(c);
            if (node == null) {
                continue;
            }
            double center = node.getBoundsInParent().getMinX() + node.getBoundsInParent().getWidth() / 2.0;
            double distance = Math.abs(clickX - center);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = c;
            }
        }

        if (nearest != null) {
            setSelectedClip(nearest, false);
        } else {
            clearSelectedClip();
        }
    }

    private void clearSelectedClip() {
        Clip previous = selectedClip;
        persistSelectedClipTrim();
        selectedClip = null;
        inputField.clear();
        applySelectionVisualState(previous, null);
        updateVolumePanel();
        updateTransitionPanel();
        renderPreviewOverlays(latestActiveNonPrimaryTrackClips);
    }

    private void setupKeyboardShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getTarget() instanceof TextInputControl) {
                return;
            }

            if (event.getCode() == KeyCode.LEFT) {
                selectRelativeClip(-1, false);
                event.consume();
            } else if (event.getCode() == KeyCode.RIGHT) {
                selectRelativeClip(1, false);
                event.consume();
            } else if (event.getCode() == KeyCode.TAB) {
                selectRelativeClip(event.isShiftDown() ? -1 : 1, true);
                event.consume();
            } else if (event.getCode() == KeyCode.SPACE) {
                togglePlayPause();
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                deleteSelectedClip();
                event.consume();
            }
        });
    }

    private void deleteSelectedClip() {
        if (selectedClip == null) return;
        int trackIndex = clipTrackIndexMap.getOrDefault(selectedClip, TRACK_VIDEO);
        int clipIndex = timelineModel.indexOfInTrack(trackIndex, selectedClip);
        if (clipIndex >= 0) {
            timelineModel.removeClipFromTrack(trackIndex, clipIndex);
            clearSelectedClip();
            refreshClipTimelineView();
            rebuildPlaybackTimeline(false);
        }
    }

    private void selectRelativeClip(int delta, boolean wrap) {
        List<Clip> all = new ArrayList<>();
        for (Track track : timelineModel.getTracks()) {
            all.addAll(track.getClips());
        }
        if (all.isEmpty()) {
            return;
        }

        int currentIndex = selectedClip == null ? -1 : all.indexOf(selectedClip);
        int nextIndex;
        if (currentIndex < 0) {
            nextIndex = delta >= 0 ? 0 : all.size() - 1;
        } else {
            nextIndex = currentIndex + delta;
            if (wrap) {
                nextIndex = (nextIndex % all.size() + all.size()) % all.size();
            } else {
                nextIndex = Math.max(0, Math.min(all.size() - 1, nextIndex));
            }
        }

        setSelectedClip(all.get(nextIndex), false);
    }

    private void setSelectedClip(Clip clip, boolean seekInPlayer) {
        if (clip == null || !timelineModel.contains(clip)) {
            return;
        }

        if (clip == selectedClip) {
            updateVolumePanel();
            updateTransitionPanel();
            if (!seekInPlayer) {
                return;
            }
        }

        Clip previous = selectedClip;
        persistSelectedClipTrim();
        selectedClip = clip;
        inputField.setText(selectedClip.getSourcePath());

        double[] range = clipTrimRanges.get(selectedClip);
        if (range != null) {
            startTimeSec = range[0];
            endTimeSec = range[1];
        } else {
            startTimeSec = 0;
            endTimeSec = selectedClip.getDurationSeconds();
            clipTrimRanges.put(selectedClip, new double[]{startTimeSec, endTimeSec});
        }

        updateVolumePanel();
        updateTransitionPanel();
        applySelectionVisualState(previous, selectedClip);

        if (seekInPlayer) {
            rebuildPlaybackTimeline(true);
            statusLabel.setText("Status: Selected clip");
        }

        renderPreviewOverlays(latestActiveNonPrimaryTrackClips);
        updatePlayheadGuide();
    }

    private void updateVolumePanel() {
        boolean show = selectedClip != null;
        volumePanel.setManaged(show);
        volumePanel.setVisible(show);
        if (!show) {
            return;
            
        }

        if (mediaView.getMediaPlayer() != null) {
            mediaView.getMediaPlayer().setVolume(
                    clamp(selectedClip.getVolume(), 0, 1)
            );
        }

        internalVolumeUiUpdate = true;
        volumeSlider.setValue(clamp(selectedClip.getVolume(), 0, 2.0));
        volumeValueLabel.setText(String.format("%.2fx", selectedClip.getVolume()));
        internalVolumeUiUpdate = false;
    }

    private void updateTransitionPanel() {
    transitionPanel.setManaged(true);
    transitionPanel.setVisible(true);

    if (selectedClip == null) {
        return;
    }

    internalTransitionUiUpdate = true;

    transitionInCombo.setValue(selectedClip.getTransitionIn());
    transitionOutCombo.setValue(selectedClip.getTransitionOut());
    transitionDurationSlider.setValue(selectedClip.getTransitionOutDurationSeconds());
    transitionDurationLabel.setText(
        String.format("%.2fs", selectedClip.getTransitionOutDurationSeconds())
    );

    internalTransitionUiUpdate = false;
}

        

       
    

    private void persistSelectedClipTrim() {
        if (selectedClip == null) {
            return;
        }
        clipTrimRanges.put(selectedClip, new double[]{startTimeSec, endTimeSec});
    }

    private double getSelectedClipDuration() {
        return selectedClip == null ? 0 : selectedClip.getDurationSeconds();
    }

    private void migrateClipMetadata(Clip oldClip, Clip newClip) {
        double[] range = clipTrimRanges.remove(oldClip);
        if (range == null) {
            range = new double[]{0, newClip.getDurationSeconds()};
        }
        clipTrimRanges.put(newClip, range);

        double[] peaks = oldClip.getWaveformPeaks();
        if (peaks == null) {
            peaks = waveformCache.get(oldClip.getWaveformCacheKey());
        }
        if (peaks != null) {
            newClip.cacheWaveform(peaks);
            waveformCache.put(newClip.getWaveformCacheKey(), peaks);
        }
    }

    private void drawWaveform(Clip clip, Canvas canvas, boolean selected) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();

        gc.setFill(Color.rgb(15, 23, 42, 0.30));
        gc.fillRoundRect(0, 0, w, h, 6, 6);

        double[] peaks = clip.getWaveformPeaks();
        if (peaks == null) {
            peaks = waveformCache.get(clip.getWaveformCacheKey());
            if (peaks != null) {
                clip.cacheWaveform(peaks);
            }
        }

        if (peaks == null || peaks.length == 0) {
            gc.setStroke(Color.rgb(203, 213, 225, 0.35));
            gc.strokeLine(0, h / 2.0, w, h / 2.0);
            ensureWaveformGeneratedAsync(clip, idealWaveformPointsForWidth(w));
            return;
        }

        if (peaks.length > MAX_WAVEFORM_POINTS) {
            peaks = downsamplePeaks(peaks, MAX_WAVEFORM_POINTS);
        }

        gc.setStroke(selected ? Color.rgb(251, 191, 36, 0.95) : Color.rgb(226, 232, 240, 0.78));
        gc.setLineWidth(1.0);

        double mid = h / 2.0;
        int len = peaks.length;
        double stepX = len <= 1 ? w : (w / (len - 1));

        for (int i = 0; i < len; i++) {
            double x = i * stepX;
            double amp = clamp(peaks[i], 0, 1) * (h * 0.44);
            gc.strokeLine(x, mid - amp, x, mid + amp);
        }
    }

    private void ensureWaveformGeneratedAsync(Clip clip, int targetSamples) {
        if (!(videoEngine instanceof JavaFFmpegEngine)) {
            return;
        }

        int cappedSamples = Math.max(MIN_WAVEFORM_POINTS, Math.min(MAX_WAVEFORM_POINTS, targetSamples));

        String key = clip.getWaveformCacheKey();
        if (waveformCache.containsKey(key) || !waveformLoadingKeys.add(key)) {
            return;
        }

        new Thread(() -> {
            try {
                double[] peaks = ((JavaFFmpegEngine) videoEngine).generateWaveformPeaks(clip, cappedSamples);
                if (peaks.length > MAX_WAVEFORM_POINTS) {
                    peaks = downsamplePeaks(peaks, MAX_WAVEFORM_POINTS);
                }
                clip.cacheWaveform(peaks);
                waveformCache.put(key, peaks);
                Platform.runLater(() -> redrawClipWaveform(clip));
            } catch (Exception ex) {
                System.err.println("Waveform generation failed: " + ex.getMessage());
            } finally {
                waveformLoadingKeys.remove(key);
            }
        }, "waveform-gen-" + Math.abs(key.hashCode())).start();
    }

    private int idealWaveformPointsForWidth(double width) {
        int desired = (int) Math.round(width * 2.0);
        return Math.max(MIN_WAVEFORM_POINTS, Math.min(MAX_WAVEFORM_POINTS, desired));
    }

    private double[] downsamplePeaks(double[] peaks, int maxPoints) {
        if (peaks == null || peaks.length <= maxPoints) {
            return peaks;
        }

        double[] downsampled = new double[maxPoints];
        double bucket = (double) peaks.length / maxPoints;

        for (int i = 0; i < maxPoints; i++) {
            int start = (int) Math.floor(i * bucket);
            int end = Math.min(peaks.length, (int) Math.floor((i + 1) * bucket));
            if (end <= start) {
                end = Math.min(peaks.length, start + 1);
            }

            double max = 0;
            for (int j = start; j < end; j++) {
                if (peaks[j] > max) {
                    max = peaks[j];
                }
            }
            downsampled[i] = max;
        }

        return downsampled;
    }

    private void redrawClipWaveform(Clip clip) {
        ClipUi ui = clipUiMap.get(clip);
        if (ui == null) {
            return;
        }
        drawWaveform(clip, ui.waveformCanvas, clip == selectedClip);
    }

    private void applySelectionVisualState(Clip oldClip, Clip newClip) {
        if (oldClip != null && oldClip != newClip) {
            ClipUi oldUi = clipUiMap.get(oldClip);
            if (oldUi != null) {
                applyClipSelectionState(oldClip, oldUi, false);
            }
        }

        if (newClip != null) {
            ClipUi newUi = clipUiMap.get(newClip);
            if (newUi != null) {
                applyClipSelectionState(newClip, newUi, true);
                selectedClipTrimPane = newUi.trimOverlay;
                attachTrimHandlesToSelectedClip();
                refreshTrimHandles();
            }
        } else {
            selectedClipTrimPane = null;
        }
    }

    private void applyClipSelectionState(Clip clip, ClipUi ui, boolean selected) {
        applyClipBlockStyle(clip, ui.clipBlock, selected);
        ui.trimOverlay.setVisible(selected);
        drawWaveform(clip, ui.waveformCanvas, selected);
    }

    private void applyClipBlockStyle(Clip clip, StackPane clipBlock, boolean selected) {
        int trackIndex = clipTrackIndexMap.getOrDefault(clip, TRACK_VIDEO);
        int index = Math.max(0, timelineModel.indexOfInTrack(trackIndex, clip));
        String color = colorForIndex(index);
        boolean isHovered = clip == hoveredTimelineClip;
        String borderColor = selected ? "#facc15" : (isHovered ? "#93c5fd" : "#334155");
        double opacity = isHovered || selected ? 1.0 : 0.85;
        
        clipBlock.setStyle(
                "-fx-background-color: " + color + ";" +
                        "-fx-background-radius: 8;" +
                        "-fx-border-color: " + borderColor + ";" +
                        "-fx-border-width: " + (selected ? "3" : "2") + ";" +
                        "-fx-border-radius: 8;" +
                        "-fx-opacity: " + opacity + ";" +
                        "-fx-effect: " + (selected ? "dropshadow(three-pass-box, rgba(250,204,21,0.4), 10, 0, 0, 0)" : "none") + ";"
        );
    }

    private void updateClipLabel(Clip clip, Label label) {
        int trackIndex = clipTrackIndexMap.getOrDefault(clip, TRACK_VIDEO);
        int index = timelineModel.indexOfInTrack(trackIndex, clip);
        if (index < 0) {
            return;
        }
        String filename = new File(clip.getSourcePath()).getName();
        label.setText((index + 1) + ": " + filename + "  [vol " + String.format("%.2f", clip.getVolume()) + "x]");
    }

    private String colorForIndex(int index) {
        String[] palette = {
                "#1d4ed8", "#0f766e", "#7c3aed", "#be123c", "#b45309", "#166534"
        };
        return palette[index % palette.length];
    }

    private void updateTimeLabel(double seconds) {
        timeLabel.setText(formatTime((int) Math.floor(seconds)) + " / " + formatTime((int) Math.floor(totalDurationSec)));
    }

    private void updatePlayheadGuide() {
        Double playheadX = getPlayheadContentX();
        if (playheadX == null) {
            playheadGuideLine.setVisible(false);
            return;
        }

        double overlayX = contentXToOverlayX(playheadX);
        playheadGuideLine.setStartX(overlayX);
        playheadGuideLine.setEndX(overlayX);
        playheadGuideLine.setVisible(true);
    }

    private Double getPlayheadContentX() {
        if (latestPlaybackClips.isEmpty()) {
            return null;
        }

        double global = clamp(timelineSlider.getValue(), 0, Math.max(0, totalDurationSec));
        TimelinePlaybackController.PlaybackClip target = null;
        for (TimelinePlaybackController.PlaybackClip clip : latestPlaybackClips) {
            if (global >= clip.getTimelineStart() && global <= clip.getTimelineEnd()) {
                target = clip;
                break;
            }
        }

        if (target == null) {
            target = latestPlaybackClips.get(latestPlaybackClips.size() - 1);
        }

        StackPane selectedBlock = clipNodes.get(target.getClip());
        if (selectedBlock == null) {
            return null;
        }

        double duration = target.getDurationSeconds();
        if (duration <= 0) {
            return null;
        }

        double relative = clamp(global - target.getTimelineStart(), 0, duration);
        double blockMinX = selectedBlock.getBoundsInParent().getMinX();
        double blockWidth = selectedBlock.getBoundsInParent().getWidth();
        return blockMinX + (relative / duration) * blockWidth;
    }

    private double contentXToOverlayX(double contentX) {
        double viewportWidth = clipTimelineScroll.getViewportBounds().getWidth();
        HBox baseTrack = trackTimelineBoxes.getOrDefault(TRACK_VIDEO, clipTimelineBox);
        double contentWidth = baseTrack.getBoundsInLocal().getWidth();
        double maxOffset = Math.max(0, contentWidth - viewportWidth);
        double offset = clipTimelineScroll.getHvalue() * maxOffset;
        return contentX - offset;
    }

    private void renderPreviewOverlays(List<TimelinePlaybackController.PlaybackClip> activeNonPrimaryTrackClips) {
        previewOverlayLayer.getChildren().clear();
        if (activeNonPrimaryTrackClips == null || activeNonPrimaryTrackClips.isEmpty()) {
            return;
        }

        activeNonPrimaryTrackClips.sort(Comparator.comparingInt(a -> a.getClip().getZIndex()));
        for (TimelinePlaybackController.PlaybackClip playbackClip : activeNonPrimaryTrackClips) {
            Clip clip = playbackClip.getClip();
            int trackIndex = clipTrackIndexMap.getOrDefault(clip, TRACK_VIDEO);
            Track.TrackType type = timelineModel.getTracks().get(trackIndex).getType();

            if (type == Track.TrackType.TEXT) {
                final int clipIndex = timelineModel.indexOfInTrack(trackIndex, clip);
                if (clipIndex < 0) {
                    continue;
                }

                Pane textContainer = new Pane();
                textContainer.setPickOnBounds(false);
                textContainer.setLayoutX(clip.getPositionX());
                textContainer.setLayoutY(clip.getPositionY());

                Label label = new Label(clip.getTextContent());
                applyPreviewTextLabelStyle(label, clip, clip == selectedClip);
                label.setOpacity(clip.getOpacity());

                Region resizeHandle = new Region();
                resizeHandle.setPrefSize(10, 10);
                resizeHandle.setStyle("-fx-background-color: #facc15; -fx-border-color: #0f172a; -fx-border-width: 1; -fx-cursor: se-resize;");

                Runnable updateHandlePosition = () -> {
                    double w = Math.max(14, label.getLayoutBounds().getWidth());
                    double h = Math.max(14, label.getLayoutBounds().getHeight());
                    resizeHandle.setLayoutX(w - resizeHandle.getPrefWidth() * 0.5);
                    resizeHandle.setLayoutY(h - resizeHandle.getPrefHeight() * 0.5);
                };
                label.layoutBoundsProperty().addListener((obs, oldV, newV) -> updateHandlePosition.run());
                Platform.runLater(updateHandlePosition);

                textContainer.getChildren().addAll(label, resizeHandle);

                final double[] pressSceneX = new double[1];
                final double[] pressSceneY = new double[1];
                final double[] pressClipX = new double[1];
                final double[] pressClipY = new double[1];
                final int[] pressFont = new int[1];
                final boolean[] resizing = new boolean[1];
                final Clip[] workingClip = new Clip[]{clip};

                label.setOnMouseClicked(e -> {
                    if (e.getButton() != MouseButton.PRIMARY) {
                        return;
                    }
                    setSelectedClip(workingClip[0], false);
                    e.consume();
                });

                label.setOnMousePressed(e -> {
                    if (e.getButton() != MouseButton.PRIMARY) {
                        return;
                    }
                    resizing[0] = false;
                    pressSceneX[0] = e.getSceneX();
                    pressSceneY[0] = e.getSceneY();
                    pressClipX[0] = workingClip[0].getPositionX();
                    pressClipY[0] = workingClip[0].getPositionY();
                    setSelectedClip(workingClip[0], false);
                    e.consume();
                });

                label.setOnMouseDragged(e -> {
                    if (e.getButton() != MouseButton.PRIMARY || resizing[0]) {
                        return;
                    }
                    double dx = e.getSceneX() - pressSceneX[0];
                    double dy = e.getSceneY() - pressSceneY[0];

                    double maxX = Math.max(0, VIDEO_WIDTH - label.getLayoutBounds().getWidth());
                    double maxY = Math.max(0, VIDEO_HEIGHT - label.getLayoutBounds().getHeight());
                    double newX = clamp(pressClipX[0] + dx, 0, maxX);
                    double newY = clamp(pressClipY[0] + dy, 0, maxY);

                    textContainer.setLayoutX(newX);
                    textContainer.setLayoutY(newY);
                    
                    // Only update working object and selectedClip reference for UI sync
                    workingClip[0] = workingClip[0].withPosition(newX, newY);
                    selectedClip = workingClip[0];
                    
                    // Highlight in timeline if visible
                    ClipUi ui = clipUiMap.get(clip);
                    if (ui != null) {
                        applyClipSelectionState(workingClip[0], ui, true);
                    }
                    
                    e.consume();
                });

                label.setOnMouseReleased(e -> {
                    if (workingClip[0] != clip) {
                        commitPreviewTextClipUpdate(clip, workingClip[0], trackIndex, clipIndex);
                    }
                    e.consume();
                });

                resizeHandle.setOnMousePressed(e -> {
                    resizing[0] = true;
                    pressSceneY[0] = e.getSceneY();
                    pressFont[0] = workingClip[0].getTextFontSize();
                    setSelectedClip(workingClip[0], false);
                    e.consume();
                });

                resizeHandle.setOnMouseDragged(e -> {
                    if (!resizing[0]) {
                        return;
                    }
                    int newFont = Math.max(8, Math.min(160, (int) Math.round(pressFont[0] - (e.getSceneY() - pressSceneY[0]) * 0.25)));
                    workingClip[0] = workingClip[0].withTextFontSize(newFont);
                    selectedClip = workingClip[0];
                    applyPreviewTextLabelStyle(label, workingClip[0], true);
                    updateHandlePosition.run();
                    e.consume();
                });

                resizeHandle.setOnMouseReleased(e -> {
                    resizing[0] = false;
                    if (workingClip[0] != clip) {
                        commitPreviewTextClipUpdate(clip, workingClip[0], trackIndex, clipIndex);
                    }
                    e.consume();
                });

                previewOverlayLayer.getChildren().add(textContainer);
            } else if (type == Track.TrackType.OVERLAY && isStillImage(clip.getSourcePath())) {
                try {
                    Image image = new Image(new File(clip.getSourcePath()).toURI().toString(), true);
                    ImageView imageView = new ImageView(image);
                    imageView.setPreserveRatio(true);
                    imageView.setFitWidth(Math.max(20, VIDEO_WIDTH * 0.35 * clip.getScale()));
                    imageView.setOpacity(clip.getOpacity());
                    imageView.setLayoutX(clip.getPositionX());
                    imageView.setLayoutY(clip.getPositionY());
                    previewOverlayLayer.getChildren().add(imageView);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void applyPreviewTextLabelStyle(Label label, Clip clip, boolean selected) {
        String border = selected
                ? "-fx-border-color: #facc15; -fx-border-width: 2; -fx-border-radius: 3; -fx-padding: 4 8; -fx-background-color: rgba(0,0,0,0.3);"
                : "-fx-border-color: transparent; -fx-border-width: 2; -fx-padding: 4 8;";
        label.setStyle("-fx-text-fill: white; -fx-font-size: " + clip.getTextFontSize() + "px; -fx-font-weight: 800; " + border);
    }

    private void commitPreviewTextClipUpdate(Clip originalClip, Clip updatedClip, int trackIndex, int clipIndex) {
        timelineModel.replaceClipInTrack(trackIndex, clipIndex, updatedClip);
        migrateClipMetadata(originalClip, updatedClip);

        clipTrackIndexMap.remove(originalClip);
        clipTrackIndexMap.put(updatedClip, trackIndex);

        StackPane clipNode = clipNodes.remove(originalClip);
        if (clipNode != null) {
            clipNodes.put(updatedClip, clipNode);
        }

        ClipUi clipUi = clipUiMap.remove(originalClip);
        if (clipUi != null) {
            clipUiMap.put(updatedClip, clipUi);
            updateClipLabel(updatedClip, clipUi.clipLabel);
            applyClipSelectionState(updatedClip, clipUi, true);
        }

        for (int i = 0; i < latestActiveNonPrimaryTrackClips.size(); i++) {
            TimelinePlaybackController.PlaybackClip playbackClip = latestActiveNonPrimaryTrackClips.get(i);
            if (playbackClip.getClip() == originalClip) {
                latestActiveNonPrimaryTrackClips.set(i,
                        new TimelinePlaybackController.PlaybackClip(
                                updatedClip,
                                playbackClip.getTimelineStart(),
                                playbackClip.getSourceStart(),
                                playbackClip.getSourceEnd()
                        )
                );
            }
        }

        selectedClip = updatedClip;
        applySelectionVisualState(originalClip, updatedClip);
        renderPreviewOverlays(latestActiveNonPrimaryTrackClips);
    }

    private void simulateTransition(Clip previous, Clip current) {
        Clip.TransitionType type = previous.getTransitionOut() != Clip.TransitionType.NONE
                ? previous.getTransitionOut()
                : current.getTransitionIn();

        double durationSec = previous.getTransitionOut() != Clip.TransitionType.NONE
                ? previous.getTransitionOutDurationSeconds()
                : current.getTransitionInDurationSeconds();

        durationSec = clamp(durationSec, 0.2, 2.0);

        mediaView.setOpacity(1);
        mediaView.setTranslateX(0);
        mediaView.setScaleX(1);
        mediaView.setScaleY(1);

        Duration d = Duration.seconds(durationSec);

        switch (type) {
            case FADE -> {
                FadeTransition ft = new FadeTransition(d, mediaView);
                ft.setFromValue(0.2);
                ft.setToValue(1.0);
                ft.play();
            }
            case SLIDE_LEFT -> {
                TranslateTransition tt = new TranslateTransition(d, mediaView);
                tt.setFromX(60);
                tt.setToX(0);
                tt.play();
            }
            case SLIDE_RIGHT -> {
                TranslateTransition tt = new TranslateTransition(d, mediaView);
                tt.setFromX(-60);
                tt.setToX(0);
                tt.play();
            }
            case ZOOM_IN -> {
                ScaleTransition st = new ScaleTransition(d, mediaView);
                st.setFromX(1.12);
                st.setFromY(1.12);
                st.setToX(1.0);
                st.setToY(1.0);
                st.play();
            }
            case ZOOM_OUT -> {
                ScaleTransition st = new ScaleTransition(d, mediaView);
                st.setFromX(0.88);
                st.setFromY(0.88);
                st.setToX(1.0);
                st.setToY(1.0);
                st.play();
            }
            case DIP_TO_BLACK -> {
                FadeTransition dipIn = new FadeTransition(Duration.seconds(durationSec / 2.0), dipOverlay);
                dipIn.setFromValue(0);
                dipIn.setToValue(1);
                FadeTransition dipOut = new FadeTransition(Duration.seconds(durationSec / 2.0), dipOverlay);
                dipOut.setFromValue(1);
                dipOut.setToValue(0);
                dipIn.setOnFinished(e -> dipOut.play());
                dipIn.play();
            }
            case NONE -> {
                FadeTransition subtle = new FadeTransition(Duration.seconds(0.12), mediaView);
                subtle.setFromValue(0.85);
                subtle.setToValue(1.0);
                subtle.play();
            }
        }
    }

    private void showInfo(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static String formatTime(int totalSeconds) {
        int safeSeconds = Math.max(0, totalSeconds);
        int minutes = safeSeconds / 60;
        int seconds = safeSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static void main(String[] args) {
        launch(args);
    }
}
