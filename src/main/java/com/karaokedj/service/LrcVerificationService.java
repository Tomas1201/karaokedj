package com.karaokedj.service;

import com.karaokedj.model.LrcLyrics;
import com.karaokedj.model.WordTiming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LrcVerificationService {

    private static final Logger log = LoggerFactory.getLogger(LrcVerificationService.class);

    private static final double MISMATCH_THRESHOLD = 0.20;

    @Autowired
    private LrcGeneratorService lrcGeneratorService;

    public String verifyAndCorrect(List<WordTiming> whisperWords, LrcLyrics apiLyrics) {
        if (apiLyrics == null || !apiLyrics.hasSyncedLyrics() && !apiLyrics.hasPlainLyrics()) {
            log.info("No API lyrics to verify against, using Whisper transcription as-is");
            return lrcGeneratorService.generateEnhancedLrc(whisperWords);
        }

        String apiText;
        if (apiLyrics.hasSyncedLyrics()) {
            apiText = extractTextFromSyncedLyrics(apiLyrics.getSyncedLyrics());
        } else {
            apiText = apiLyrics.getPlainLyrics();
        }

        if (apiText == null || apiText.isBlank()) {
            return lrcGeneratorService.generateEnhancedLrc(whisperWords);
        }

        List<String> apiWords = normalizeAndSplit(apiText);
        List<String> whisperWordsList = new ArrayList<>();
        for (WordTiming wt : whisperWords) {
            whisperWordsList.add(wt.getWord().toLowerCase().trim());
        }

        double matchRate = calculateMatchRate(apiWords, whisperWordsList);
        log.info("Verification: {}% word match rate ({} API words, {} Whisper words)",
                String.format("%.1f", matchRate * 100), apiWords.size(), whisperWordsList.size());

        if (matchRate >= (1.0 - MISMATCH_THRESHOLD)) {
            log.info("Transcription verified - words match within threshold");
            return lrcGeneratorService.generateEnhancedLrc(whisperWords);
        }

        log.info("Word mismatch detected ({}% match). Attempting correction...",
                String.format("%.1f", matchRate * 100));

        List<WordTiming> corrected = correctWords(whisperWords, apiWords);

        return lrcGeneratorService.generateEnhancedLrc(corrected);
    }

    private double calculateMatchRate(List<String> apiWords, List<String> whisperWords) {
        int matches = 0;
        int total = Math.max(apiWords.size(), whisperWords.size());
        if (total == 0) return 1.0;

        int apiIdx = 0;
        int whisperIdx = 0;

        while (apiIdx < apiWords.size() && whisperIdx < whisperWords.size()) {
            String apiWord = apiWords.get(apiIdx).toLowerCase();
            String whisperWord = whisperWords.get(whisperIdx).toLowerCase();

            if (apiWord.equals(whisperWord) || apiWord.contains(whisperWord) || whisperWord.contains(apiWord)) {
                matches++;
                apiIdx++;
                whisperIdx++;
            } else if (apiIdx + 1 < apiWords.size() &&
                    apiWords.get(apiIdx + 1).toLowerCase().equals(whisperWord)) {
                apiIdx++;
            } else if (whisperIdx + 1 < whisperWords.size() &&
                    whisperWords.get(whisperIdx + 1).toLowerCase().equals(apiWord)) {
                whisperIdx++;
            } else {
                apiIdx++;
                whisperIdx++;
            }
        }

        return (double) matches / total;
    }

    private List<WordTiming> correctWords(List<WordTiming> whisperWords, List<String> apiWords) {
        List<WordTiming> corrected = new ArrayList<>();

        // Reemplaza cada palabra de Whisper por la del texto de referencia,
        // conservando los timestamps detectados por la transcripción.
        for (int i = 0; i < whisperWords.size() && i < apiWords.size(); i++) {
            WordTiming whisperWord = whisperWords.get(i);
            corrected.add(new WordTiming(apiWords.get(i), whisperWord.getStartMs(), whisperWord.getEndMs()));
        }

        log.info("Corrected {} words from API reference", corrected.size());
        return corrected;
    }

    private String extractTextFromSyncedLyrics(String syncedLyrics) {
        Pattern enhancedPattern = Pattern.compile("\\[\\d{2}:\\d{2}\\.\\d{2}\\](?:<\\d{2}:\\d{2}\\.\\d{2}>[^<\\[]+)+");
        Pattern linePattern = Pattern.compile("\\[\\d{2}:\\d{2}\\.\\d{2}\\](.+)");

        StringBuilder sb = new StringBuilder();
        String[] lines = syncedLyrics.split("\n");

        for (String line : lines) {
            String text;
            Matcher enhancedMatcher = enhancedPattern.matcher(line);
            if (enhancedMatcher.matches()) {
                text = line.replaceAll("\\[\\d{2}:\\d{2}\\.\\d{2}\\]", "")
                        .replaceAll("<\\d{2}:\\d{2}\\.\\d{2}>", "");
            } else {
                Matcher lineMatcher = linePattern.matcher(line);
                if (lineMatcher.matches()) {
                    text = lineMatcher.group(1);
                } else {
                    text = line.replaceAll("\\[.*?\\]", "");
                }
            }
            text = text.trim();
            if (!text.isEmpty()) {
                sb.append(text).append("\n");
            }
        }
        return sb.toString();
    }

    private List<String> normalizeAndSplit(String text) {
        String normalized = text.toLowerCase()
                .replaceAll("[^a-zA-Z0-9\\s']", " ")
                .replaceAll("\\s+", " ")
                .trim();

        List<String> words = new ArrayList<>();
        for (String word : normalized.split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }
}
