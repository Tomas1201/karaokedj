package com.karaokedj.service;

import com.karaokedj.model.LrcLyrics;
import com.karaokedj.model.WordTiming;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LrcVerificationServiceTest {

    private final LrcGeneratorService lrcGeneratorService = new LrcGeneratorService();
    private final LrcVerificationService service;

    LrcVerificationServiceTest() {
        this.service = new LrcVerificationService();
        try {
            var field = LrcVerificationService.class.getDeclaredField("lrcGeneratorService");
            field.setAccessible(true);
            field.set(service, lrcGeneratorService);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testVerifyWithMatchingWords() {
        List<WordTiming> whisperWords = List.of(
                new WordTiming("Hello", 1000, 1500),
                new WordTiming("world", 1600, 2000),
                new WordTiming("how", 3000, 3300),
                new WordTiming("are", 3400, 3700),
                new WordTiming("you", 3800, 4100)
        );

        LrcLyrics apiLyrics = new LrcLyrics();
        apiLyrics.setPlainLyrics("Hello world\nhow are you");

        String result = service.verifyAndCorrect(whisperWords, apiLyrics);

        assertNotNull(result);
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("world"));
    }

    @Test
    void testVerifyWithNoApiLyrics() {
        List<WordTiming> whisperWords = List.of(
                new WordTiming("Test", 1000, 1500)
        );

        String result = service.verifyAndCorrect(whisperWords, null);

        assertNotNull(result);
        assertTrue(result.contains("Test"));
    }

    @Test
    void testVerifyWithSyncedApiLyrics() {
        List<WordTiming> whisperWords = List.of(
                new WordTiming("Hello", 1000, 1500),
                new WordTiming("world", 1600, 2000)
        );

        LrcLyrics apiLyrics = new LrcLyrics();
        apiLyrics.setSyncedLyrics("[00:01.00]Hello world\n");

        String result = service.verifyAndCorrect(whisperWords, apiLyrics);

        assertNotNull(result);
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("world"));
    }

    @Test
    void testVerifyWithMismatchedWords() {
        List<WordTiming> whisperWords = List.of(
                new WordTiming("wrong", 1000, 1500),
                new WordTiming("words", 1600, 2000)
        );

        LrcLyrics apiLyrics = new LrcLyrics();
        apiLyrics.setPlainLyrics("correct lyrics");

        String result = service.verifyAndCorrect(whisperWords, apiLyrics);

        assertNotNull(result);
        assertTrue(result.contains("correct"));
        assertTrue(result.contains("lyrics"));
    }

    @Test
    void testVerifyWithEmptyWords() {
        LrcLyrics apiLyrics = new LrcLyrics();
        apiLyrics.setPlainLyrics("Some lyrics");

        String result = service.verifyAndCorrect(List.of(), apiLyrics);

        assertNull(result, "Empty whisper words should return null");
    }
}
