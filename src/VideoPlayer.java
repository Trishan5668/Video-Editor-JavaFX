import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.media.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;

public class VideoPlayer extends Application {

    static String videoPath = "D:/Dev/Project/Video_Editor/assets/input.mp4";

    @Override
    public void start(Stage stage) {

        // Safety check
        if (videoPath == null || videoPath.isEmpty()) {
            System.out.println("No video selected!");
            return;
        }

        Media media = new Media(new File(videoPath).toURI().toString());
        MediaPlayer player = new MediaPlayer(media);
        MediaView view = new MediaView(player);

        // Fix video size
        view.setFitWidth(600);
        view.setFitHeight(350);
        view.setPreserveRatio(true);

        // Slider
        Slider slider = new Slider();

        // Sync slider with video
        player.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
            if (!slider.isValueChanging()) {
                slider.setValue(newTime.toSeconds());
            }
        });

        // Set max duration
        player.setOnReady(() -> {
            slider.setMax(player.getTotalDuration().toSeconds());
        });

        // Seek when slider moves
        slider.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (slider.isValueChanging()) {
                player.seek(Duration.seconds(newVal.doubleValue()));
            }
        });

        // Buttons
        Button playBtn = new Button("▶ Play");
        playBtn.setOnAction(e -> player.play());

        Button pauseBtn = new Button("⏸ Pause");
        pauseBtn.setOnAction(e -> player.pause());

        // Layout
        BorderPane root = new BorderPane();

        // Center video
        root.setCenter(view);

        // Controls
        HBox controls = new HBox(10, playBtn, pauseBtn);
        controls.setStyle("-fx-alignment: center;");

        VBox bottom = new VBox(10, slider, controls);
        bottom.setStyle("-fx-padding: 10;");

        root.setBottom(bottom);

        // Scene
        Scene scene = new Scene(root, 600, 450);

        stage.setScene(scene);
        stage.setTitle("Video Preview 🎬");
        stage.show();
        player.setOnReady(() -> {
    player.play();
});

        // 🔥 IMPORTANT: Auto play video
        player.play();
    }

    public static void main(String[] args) {
        launch(args);
    }
}