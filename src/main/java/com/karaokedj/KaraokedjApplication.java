package com.karaokedj;

import com.karaokedj.controller.MainController;
import com.karaokedj.lyrics.AiLyricsPipeline;
import com.karaokedj.lyrics.KaralyrProvider;
import com.karaokedj.lyrics.LrclibProvider;
import com.karaokedj.lyrics.LrcmuxProvider;
import com.karaokedj.lyrics.LyricsProvider;
import com.karaokedj.lyrics.LyricsSearchService;
import com.karaokedj.ml.WhisperModel;
import com.karaokedj.service.AudioPlayerService;
import com.karaokedj.service.AudioProcessorService;
import com.karaokedj.service.LrcFileService;
import com.karaokedj.service.LrcGeneratorService;
import com.karaokedj.service.LrcVerificationService;
import com.karaokedj.service.MetadataService;
import com.karaokedj.service.ModelDownloadService;
import com.karaokedj.service.SongRecognitionService;
import com.karaokedj.service.StemSeparatorService;
import com.karaokedj.service.TranscriptionService;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.List;

public class KaraokedjApplication extends Application {

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws Exception {
        // Inicialización e inyección manual de dependencias
        MetadataService metadataService = new MetadataService();
        SongRecognitionService songRecognitionService = new SongRecognitionService();
        LrcFileService lrcFileService = new LrcFileService();
        AudioPlayerService audioPlayerService = new AudioPlayerService();
        AudioProcessorService audioProcessorService = new AudioProcessorService();
        ModelDownloadService modelDownloadService = new ModelDownloadService();
        LrcGeneratorService lrcGeneratorService = new LrcGeneratorService();
        LrcVerificationService lrcVerificationService = new LrcVerificationService(lrcGeneratorService);
        WhisperModel whisperModel = new WhisperModel();

        StemSeparatorService stemSeparatorService = new StemSeparatorService(
                modelDownloadService,
                audioProcessorService
        );

        TranscriptionService transcriptionService = new TranscriptionService(
                modelDownloadService,
                audioProcessorService,
                whisperModel
        );

        AiLyricsPipeline aiLyricsPipeline = new AiLyricsPipeline(
                stemSeparatorService,
                transcriptionService,
                lrcVerificationService,
                audioProcessorService,
                modelDownloadService
        );

        LrclibProvider lrclibProvider = new LrclibProvider();
        List<LyricsProvider> providers = List.of(
                new KaralyrProvider(),
                new LrcmuxProvider(),
                lrclibProvider
        );

        LyricsSearchService lyricsSearchService = new LyricsSearchService(
                providers,
                lrclibProvider,
                aiLyricsPipeline
        );

        MainController controller = new MainController(
                metadataService,
                songRecognitionService,
                lyricsSearchService,
                aiLyricsPipeline,
                lrcFileService,
                audioPlayerService
        );

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main_view.fxml"));
        loader.setController(controller);

        Parent root = loader.load();

        Scene scene = new Scene(root);
        stage.setTitle("Karaokedj - Karaoke Lyrics Manager");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.show();
    }
}
