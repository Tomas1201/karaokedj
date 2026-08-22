package com.karaokedj;

import com.karaokedj.model.LrcLyrics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LrcLyricsTest {

    @Test
    void testHasSyncedLyrics() {
        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setSyncedLyrics("[00:00.00] Line 1");
        assertTrue(lyrics.hasSyncedLyrics());
    }

    @Test
    void testHasSyncedLyricsNull() {
        LrcLyrics lyrics = new LrcLyrics();
        assertFalse(lyrics.hasSyncedLyrics());
    }

    @Test
    void testHasSyncedLyricsBlank() {
        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setSyncedLyrics("   ");
        assertFalse(lyrics.hasSyncedLyrics());
    }

    @Test
    void testHasPlainLyrics() {
        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setPlainLyrics("Some lyrics here");
        assertTrue(lyrics.hasPlainLyrics());
    }

    @Test
    void testHasPlainLyricsNull() {
        LrcLyrics lyrics = new LrcLyrics();
        assertFalse(lyrics.hasPlainLyrics());
    }

    @Test
    void testToString() {
        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setTrackName("My Song");
        lyrics.setArtistName("My Artist");
        lyrics.setSyncedLyrics("[00:00.00] Test");
        String str = lyrics.toString();
        assertTrue(str.contains("My Artist"));
        assertTrue(str.contains("My Song"));
        assertTrue(str.contains("synced"));
    }

    @Test
    void testToStringWithSource() {
        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setTrackName("My Song");
        lyrics.setArtistName("My Artist");
        lyrics.setSyncedLyrics("[00:00.00] Test");
        lyrics.setSource("LRCLIB");
        String str = lyrics.toString();
        assertTrue(str.contains("[LRCLIB]"));
    }

    @Test
    void testSourceAndSyncedFromRepo() {
        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setSource("Lrcmux/NetEase");
        lyrics.setSyncedFromRepo(true);
        assertEquals("Lrcmux/NetEase", lyrics.getSource());
        assertTrue(lyrics.isSyncedFromRepo());
    }

    @Test
    void testSourceDefaultNull() {
        LrcLyrics lyrics = new LrcLyrics();
        assertNull(lyrics.getSource());
        assertFalse(lyrics.isSyncedFromRepo());
    }

    @Test
    void testHasEnhancedLyrics() {
        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setEnhancedLyrics("[00:10.00]<00:10.00>Hello <00:10.50>world");
        assertTrue(lyrics.hasEnhancedLyrics());
    }

    @Test
    void testHasEnhancedLyricsNull() {
        LrcLyrics lyrics = new LrcLyrics();
        assertFalse(lyrics.hasEnhancedLyrics());
    }

    @Test
    void testWordSynced() {
        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setWordSynced(true);
        lyrics.setEnhancedLyrics("[00:10.00]<00:10.00>Test");
        assertTrue(lyrics.isWordSynced());
        assertTrue(lyrics.hasEnhancedLyrics());
    }

    @Test
    void testToStringKaraoke() {
        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setTrackName("Song");
        lyrics.setArtistName("Artist");
        lyrics.setEnhancedLyrics("[00:10.00]<00:10.00>Test");
        lyrics.setSource("Lrcmux/kugou");
        String str = lyrics.toString();
        assertTrue(str.contains("karaoke"));
        assertTrue(str.contains("[Lrcmux/kugou]"));
    }
}
