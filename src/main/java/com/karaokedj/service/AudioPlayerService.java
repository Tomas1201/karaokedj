package com.karaokedj.service;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;

@Service
public class AudioPlayerService {

    private static final Logger log = LoggerFactory.getLogger(AudioPlayerService.class);

    private MediaPlayer mediaPlayer;
    private Media media;
    private Path currentFile;

    /** Posición de reproducción en segundos; observable por la UI sin hilos de polling. */
    private final ObjectProperty<Double> currentTime = new SimpleObjectProperty<>(0.0);

    public ObjectProperty<Double> currentTimeProperty() {
        return currentTime;
    }

    public void load(Path audioFilePath) {
        stop();

        log.info("Loading audio: {}", audioFilePath.getFileName());
        currentFile = audioFilePath;

        try {
            media = new Media(audioFilePath.toUri().toString());
            mediaPlayer = new MediaPlayer(media);

            mediaPlayer.currentTimeProperty().addListener((obs, old, val) -> {
                if (val != null) currentTime.set(val.toSeconds());
            });

            mediaPlayer.setOnReady(() -> log.info("Audio ready: {}", audioFilePath.getFileName()));
            mediaPlayer.setOnError(() -> log.error("Media error: {}", mediaPlayer.getError().getMessage()));
            mediaPlayer.setOnEndOfMedia(() -> log.info("Playback finished"));

        } catch (Exception e) {
            log.error("Failed to load audio: {}", e.getMessage());
            mediaPlayer = null;
        }
    }

    public void play() {
        if (mediaPlayer != null) {
            mediaPlayer.play();
            log.info("Playing: {}", currentFile != null ? currentFile.getFileName() : "unknown");
        }
    }

    public void pause() {
        if (mediaPlayer != null) {
            mediaPlayer.pause();
            log.info("Paused");
        }
    }

    public void stop() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.dispose();
            mediaPlayer = null;
            media = null;
            log.info("Stopped");
        }
        currentTime.set(0.0);
    }

    public void seek(double seconds) {
        if (mediaPlayer != null) {
            mediaPlayer.seek(javafx.util.Duration.seconds(seconds));
        }
    }

    public void seekPercent(double percent) {
        if (mediaPlayer != null && mediaPlayer.getTotalDuration() != null) {
            double totalSeconds = mediaPlayer.getTotalDuration().toSeconds();
            mediaPlayer.seek(javafx.util.Duration.seconds(totalSeconds * percent));
        }
    }

    public void setVolume(double volume) {
        if (mediaPlayer != null) {
            mediaPlayer.setVolume(Math.max(0, Math.min(1, volume)));
        }
    }

    public double getCurrentTime() {
        if (mediaPlayer != null && mediaPlayer.getCurrentTime() != null) {
            return mediaPlayer.getCurrentTime().toSeconds();
        }
        return 0;
    }

    public double getTotalDuration() {
        if (mediaPlayer != null && mediaPlayer.getTotalDuration() != null) {
            return mediaPlayer.getTotalDuration().toSeconds();
        }
        return 0;
    }

    public double getVolume() {
        return (mediaPlayer != null) ? mediaPlayer.getVolume() : 1.0;
    }

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING;
    }

    public boolean isPaused() {
        return mediaPlayer != null && mediaPlayer.getStatus() == MediaPlayer.Status.PAUSED;
    }

    public boolean isReady() {
        return mediaPlayer != null && mediaPlayer.getStatus() == MediaPlayer.Status.READY;
    }

    public Path getCurrentFile() {
        return currentFile;
    }

    @PreDestroy
    public void cleanup() {
        stop();
    }
}
