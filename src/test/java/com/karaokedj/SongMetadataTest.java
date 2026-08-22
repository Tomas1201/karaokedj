package com.karaokedj;

import com.karaokedj.model.SongMetadata;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SongMetadataTest {

    @Test
    void testGetDurationFormatted() {
        SongMetadata metadata = new SongMetadata();
        metadata.setDurationSeconds(215);
        assertEquals("03:35", metadata.getDurationFormatted());
    }

    @Test
    void testGetDurationFormattedZero() {
        SongMetadata metadata = new SongMetadata();
        metadata.setDurationSeconds(0);
        assertEquals("00:00", metadata.getDurationFormatted());
    }

    @Test
    void testGetDurationFormattedLarge() {
        SongMetadata metadata = new SongMetadata();
        metadata.setDurationSeconds(3661);
        assertEquals("61:01", metadata.getDurationFormatted());
    }

    @Test
    void testToString() {
        SongMetadata metadata = new SongMetadata("Bohemian Rhapsody", "Queen", "A Night at the Opera", 354.0, Path.of("/test.mp3"));
        String str = metadata.toString();
        assertTrue(str.contains("Queen"));
        assertTrue(str.contains("Bohemian Rhapsody"));
        assertTrue(str.contains("A Night at the Opera"));
        assertTrue(str.contains("05:54"));
    }

    @Test
    void testConstructor() {
        SongMetadata metadata = new SongMetadata("Title", "Artist", "Album", 120.0, Path.of("/test.mp3"));
        assertEquals("Title", metadata.getTitle());
        assertEquals("Artist", metadata.getArtist());
        assertEquals("Album", metadata.getAlbum());
        assertEquals(120.0, metadata.getDurationSeconds());
        assertEquals(Path.of("/test.mp3"), metadata.getFilePath());
    }
}
