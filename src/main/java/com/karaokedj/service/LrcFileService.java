package com.karaokedj.service;

import com.karaokedj.model.LrcLyrics;
import com.karaokedj.model.SongMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LrcFileService {

    private static final Logger log = LoggerFactory.getLogger(LrcFileService.class);

    public Path saveLrcFile(SongMetadata metadata, LrcLyrics lyrics) throws IOException {
        Path audioPath = metadata.getFilePath();
        Path lrcPath = getLrcPath(audioPath);

        String lrcContent = generateLrcContent(metadata, lyrics);

        Files.writeString(lrcPath, lrcContent);
        log.info("LRC file saved: {}", lrcPath);
        return lrcPath;
    }

    public Path getLrcPath(Path audioPath) {
        String fileName = audioPath.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = (dotIndex >= 0) ? fileName.substring(0, dotIndex) : fileName;
        return audioPath.getParent().resolve(baseName + ".lrc");
    }

    public boolean lrcFileExists(Path audioPath) {
        return Files.exists(getLrcPath(audioPath));
    }

    public String generateLrcContent(SongMetadata metadata, LrcLyrics lyrics) {
        StringBuilder sb = new StringBuilder();

        // LRC header
        sb.append("[ar:").append(nullSafe(metadata.getArtist())).append("]\n");
        sb.append("[ti:").append(nullSafe(metadata.getTitle())).append("]\n");
        sb.append("[al:").append(nullSafe(metadata.getAlbum())).append("]\n");
        sb.append("[length:").append(metadata.getDurationFormatted()).append("]\n");
        sb.append("\n");

        // Synced lyrics - prefer enhanced (word-level), then line-level, then generate from plain
        if (lyrics.hasEnhancedLyrics()) {
            sb.append(lyrics.getEnhancedLyrics());
        } else if (lyrics.hasSyncedLyrics()) {
            sb.append(lyrics.getSyncedLyrics());
        } else if (lyrics.hasPlainLyrics()) {
            sb.append(generateTimestampedFromPlain(lyrics.getPlainLyrics(), metadata.getDurationSeconds()));
        } else {
            sb.append("[00:00.00] No lyrics available\n");
        }

        return sb.toString();
    }

    private String generateTimestampedFromPlain(String plainLyrics, double durationSeconds) {
        String[] lines = plainLyrics.split("\n");
        if (lines.length == 0) {
            return "[00:00.00] No lyrics available\n";
        }

        StringBuilder sb = new StringBuilder();
        double interval = durationSeconds / lines.length;

        for (int i = 0; i < lines.length; i++) {
            double timestamp = i * interval;
            int minutes = (int) (timestamp / 60);
            double seconds = timestamp % 60;
            sb.append(String.format("[%02d:%05.2f]", minutes, seconds));
            sb.append(lines[i].trim());
            sb.append("\n");
        }

        return sb.toString();
    }

    private String nullSafe(String value) {
        return (value != null) ? value : "";
    }
}
