package com.karaokedj.model;

import java.nio.file.Path;

public class SongMetadata {

    private String title;
    private String artist;
    private String album;
    private double durationSeconds;
    private Path filePath;

    public SongMetadata() {
    }

    public SongMetadata(String title, String artist, String album, double durationSeconds, Path filePath) {
        this.title = title;
        this.artist = artist;
        this.album = album;
        this.durationSeconds = durationSeconds;
        this.filePath = filePath;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public String getAlbum() {
        return album;
    }

    public void setAlbum(String album) {
        this.album = album;
    }

    public double getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(double durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Path getFilePath() {
        return filePath;
    }

    public void setFilePath(Path filePath) {
        this.filePath = filePath;
    }

    public String getDurationFormatted() {
        int minutes = (int) (durationSeconds / 60);
        int seconds = (int) (durationSeconds % 60);
        return String.format("%02d:%02d", minutes, seconds);
    }

    @Override
    public String toString() {
        return artist + " - " + title + " [" + album + "] (" + getDurationFormatted() + ")";
    }
}
