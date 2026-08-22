package com.karaokedj.service;

import com.karaokedj.model.WordTiming;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LrcGeneratorServiceTest {

    private final LrcGeneratorService service = new LrcGeneratorService();

    @Test
    void testGenerateFromWords() {
        List<WordTiming> words = List.of(
                new WordTiming("Hello", 1000, 1500),
                new WordTiming("world", 1600, 2000),
                new WordTiming("how", 3000, 3300),
                new WordTiming("are", 3400, 3700),
                new WordTiming("you", 3800, 4100)
        );

        String lrc = service.generateEnhancedLrc(words);

        assertNotNull(lrc);
        assertTrue(lrc.contains("[00:01.00]"));
        assertTrue(lrc.contains("<00:01.00>Hello"));
        assertTrue(lrc.contains("<00:01.60>world"));
        assertTrue(lrc.contains("[00:03.00]"));
        assertTrue(lrc.contains("<00:03.00>how"));
        assertTrue(lrc.contains("<00:03.80>you"));
    }

    @Test
    void testGenerateNullEmpty() {
        assertNull(service.generateEnhancedLrc(null));
        assertNull(service.generateEnhancedLrc(List.of()));
    }

    @Test
    void testLineBreakOnPause() {
        List<WordTiming> words = List.of(
                new WordTiming("First", 0, 500),
                new WordTiming("line", 600, 1000),
                new WordTiming("Second", 2000, 2500),
                new WordTiming("line", 2600, 3000)
        );

        String lrc = service.generateEnhancedLrc(words);

        assertNotNull(lrc);
        String[] lines = lrc.split("\n");
        assertEquals(2, lines.length);
    }

    @Test
    void testLineBreakOnPunctuation() {
        List<WordTiming> words = List.of(
                new WordTiming("Hello", 0, 300),
                new WordTiming("world.", 400, 800),
                new WordTiming("How", 1500, 1800),
                new WordTiming("are", 1900, 2200),
                new WordTiming("you?", 2300, 2700)
        );

        String lrc = service.generateEnhancedLrc(words);

        assertNotNull(lrc);
        String[] lines = lrc.split("\n");
        assertTrue(lines.length >= 2, "Should break line after 'world.' due to punctuation + 700ms gap");
    }

    @Test
    void testTimestampFormat() {
        List<WordTiming> words = List.of(
                new WordTiming("Test", 65000, 66000)
        );

        String lrc = service.generateEnhancedLrc(words);

        assertNotNull(lrc);
        assertTrue(lrc.contains("[01:05.00]"));
        assertTrue(lrc.contains("<01:05.00>Test"));
    }

    @Test
    void testSingleWord() {
        List<WordTiming> words = List.of(
                new WordTiming("Alone", 5000, 5500)
        );

        String lrc = service.generateEnhancedLrc(words);

        assertNotNull(lrc);
        assertTrue(lrc.contains("[00:05.00]"));
        assertTrue(lrc.contains("Alone"));
    }

    // ==== Conversión synced/plain -> Enhanced LRC (antes en LyricsService) ====

    @Test
    void testFromSyncedLyrics() {
        String synced = "[00:10.00]Hello world\n[00:15.00]Second line\n";
        String enhanced = service.generateFromSyncedLyrics(synced);

        assertNotNull(enhanced);
        assertTrue(enhanced.contains("[00:10.00]"));
        assertTrue(enhanced.contains("<00:10.00>"));
        assertTrue(enhanced.contains("Hello"));
        assertTrue(enhanced.contains("world"));
        assertTrue(enhanced.contains("[00:15.00]"));
        assertTrue(enhanced.contains("Second"));
    }

    @Test
    void testFromSyncedLyricsNull() {
        assertNull(service.generateFromSyncedLyrics(null));
        assertNull(service.generateFromSyncedLyrics(""));
        assertNull(service.generateFromSyncedLyrics("   "));
    }

    @Test
    void testFromPlainLyrics() {
        String plain = "First line\nSecond line\nThird line";
        String enhanced = service.generateFromPlainLyrics(plain, 30.0);

        assertNotNull(enhanced);
        assertTrue(enhanced.contains("[00:00.00]"));
        assertTrue(enhanced.contains("First"));
        assertTrue(enhanced.contains("Second"));
        assertTrue(enhanced.contains("Third"));
        assertTrue(enhanced.contains("<"));
    }

    @Test
    void testFromPlainLyricsNull() {
        assertNull(service.generateFromPlainLyrics(null, 120.0));
        assertNull(service.generateFromPlainLyrics("", 120.0));
    }

    @Test
    void testDistributesWords() {
        String synced = "[00:00.00]One two three\n";
        String enhanced = service.generateFromSyncedLyrics(synced);

        assertNotNull(enhanced);
        assertTrue(enhanced.contains("<00:00.00>One"));
        assertTrue(enhanced.contains("two"));
        assertTrue(enhanced.contains("three"));
    }

    @Test
    void testSingleWordEnhanced() {
        String synced = "[00:05.00]Hello\n";
        String enhanced = service.generateFromSyncedLyrics(synced);

        assertNotNull(enhanced);
        assertTrue(enhanced.contains("Hello"));
        assertTrue(enhanced.contains("<00:05.00>"));
    }
}
