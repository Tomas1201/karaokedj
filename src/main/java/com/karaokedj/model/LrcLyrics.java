package com.karaokedj.model;

public class LrcLyrics {

    private int id;
    private String trackName;
    private String artistName;
    private String albumName;
    private int duration;
    private boolean instrumental;
    private String plainLyrics;
    private String syncedLyrics;
    private String enhancedLyrics;
    private String source;
    private boolean syncedFromRepo;
    private boolean wordSynced;

    public LrcLyrics() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getTrackName() {
        return trackName;
    }

    public void setTrackName(String trackName) {
        this.trackName = trackName;
    }

    public String getArtistName() {
        return artistName;
    }

    public void setArtistName(String artistName) {
        this.artistName = artistName;
    }

    public String getAlbumName() {
        return albumName;
    }

    public void setAlbumName(String albumName) {
        this.albumName = albumName;
    }

    public int getDuration() {
        return duration;
    }

    public void setDuration(int duration) {
        this.duration = duration;
    }

    public boolean isInstrumental() {
        return instrumental;
    }

    public void setInstrumental(boolean instrumental) {
        this.instrumental = instrumental;
    }

    public String getPlainLyrics() {
        return plainLyrics;
    }

    public void setPlainLyrics(String plainLyrics) {
        this.plainLyrics = plainLyrics;
    }

    public String getSyncedLyrics() {
        return syncedLyrics;
    }

    public void setSyncedLyrics(String syncedLyrics) {
        this.syncedLyrics = syncedLyrics;
    }

    public String getEnhancedLyrics() {
        return enhancedLyrics;
    }

    public void setEnhancedLyrics(String enhancedLyrics) {
        this.enhancedLyrics = enhancedLyrics;
    }

    public boolean hasEnhancedLyrics() {
        return enhancedLyrics != null && !enhancedLyrics.isBlank();
    }

    public boolean hasSyncedLyrics() {
        return syncedLyrics != null && !syncedLyrics.isBlank();
    }

    public boolean hasPlainLyrics() {
        return plainLyrics != null && !plainLyrics.isBlank();
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public boolean isSyncedFromRepo() {
        return syncedFromRepo;
    }

    public void setSyncedFromRepo(boolean syncedFromRepo) {
        this.syncedFromRepo = syncedFromRepo;
    }

    public boolean isWordSynced() {
        return wordSynced;
    }

    public void setWordSynced(boolean wordSynced) {
        this.wordSynced = wordSynced;
    }

    @Override
    public String toString() {
        String type;
        if (hasEnhancedLyrics()) {
            type = "karaoke";
        } else if (hasSyncedLyrics()) {
            type = "synced";
        } else {
            type = "plain";
        }
        String src = (source != null) ? " [" + source + "]" : "";
        return artistName + " - " + trackName + " (" + type + src + ")";
    }
}
