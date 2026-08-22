package com.karaokedj;

import com.karaokedj.model.LrcLyrics;
import com.karaokedj.model.SongMetadata;
import com.karaokedj.service.LrcFileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LrcFileServiceTest {

    private final LrcFileService lrcFileService = new LrcFileService();

    @Test
    void testGenerateLrcContentWithSyncedLyrics() {
        SongMetadata metadata = new SongMetadata("Test Song", "Test Artist", "Test Album", 180.0, Path.of("/test/song.mp3"));

        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setSyncedLyrics("[00:00.00] First line\n[00:05.00] Second line\n");

        String content = lrcFileService.generateLrcContent(metadata, lyrics);

        assertTrue(content.contains("[ar:Test Artist]"));
        assertTrue(content.contains("[ti:Test Song]"));
        assertTrue(content.contains("[al:Test Album]"));
        assertTrue(content.contains("[00:00.00] First line"));
        assertTrue(content.contains("[00:05.00] Second line"));
    }

    @Test
    void testGenerateLrcContentWithPlainLyrics() {
        SongMetadata metadata = new SongMetadata("Test", "Artist", "Album", 120.0, Path.of("/test.mp3"));

        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setPlainLyrics("Line 1\nLine 2\nLine 3");

        String content = lrcFileService.generateLrcContent(metadata, lyrics);

        assertTrue(content.contains("[ar:Artist]"));
        assertTrue(content.contains("Line 1"));
        assertTrue(content.contains("Line 2"));
        assertTrue(content.contains("Line 3"));
    }

    @Test
    void testGetLrcPath() {
        Path audioPath = Path.of("/music/song.mp3");
        Path lrcPath = lrcFileService.getLrcPath(audioPath);
        assertEquals("/music/song.lrc", lrcPath.toString());
    }

    @Test
    void testSaveLrcFile(@TempDir Path tempDir) throws IOException {
        Path audioFile = tempDir.resolve("test.mp3");
        Files.writeString(audioFile, "fake audio content");

        SongMetadata metadata = new SongMetadata("Test Song", "Artist", "Album", 60.0, audioFile);

        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setSyncedLyrics("[00:00.00] Hello\n");

        Path lrcPath = lrcFileService.saveLrcFile(metadata, lyrics);

        assertTrue(Files.exists(lrcPath));
        assertEquals("test.lrc", lrcPath.getFileName().toString());

        String content = Files.readString(lrcPath);
        assertTrue(content.contains("[ar:Artist]"));
        assertTrue(content.contains("[00:00.00] Hello"));
    }

    @Test
    void testGenerateLrcContentWithEnhancedLyrics() {
        SongMetadata metadata = new SongMetadata("Karaoke Song", "Artist", "Album", 180.0, Path.of("/test.mp3"));

        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setEnhancedLyrics("[00:10.00]<00:10.00>Hello <00:10.50>world\n[00:15.00]<00:15.00>Second <00:15.50>line\n");

        String content = lrcFileService.generateLrcContent(metadata, lyrics);

        assertTrue(content.contains("[ar:Artist]"));
        assertTrue(content.contains("<00:10.00>Hello"));
        assertTrue(content.contains("<00:10.50>world"));
        assertTrue(content.contains("<00:15.00>Second"));
    }

    @Test
    void testSaveLrcFileWithEnhanced(@TempDir Path tempDir) throws IOException {
        Path audioFile = tempDir.resolve("song.mp3");
        Files.writeString(audioFile, "fake");

        SongMetadata metadata = new SongMetadata("Song", "Artist", "Album", 120.0, audioFile);

        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setEnhancedLyrics("[00:10.00]<00:10.00>Word <00:10.50>test\n");

        Path lrcPath = lrcFileService.saveLrcFile(metadata, lyrics);
        String content = Files.readString(lrcPath);
        assertTrue(content.contains("<00:10.00>Word"));
        assertTrue(content.contains("<00:10.50>test"));
    }
}
