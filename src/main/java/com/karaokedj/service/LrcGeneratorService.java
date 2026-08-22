package com.karaokedj.service;

import com.karaokedj.model.WordTiming;
import com.karaokedj.util.LrcTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Generación de archivos LRC / Enhanced LRC desde distintas fuentes de letras. */
@Service
public class LrcGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(LrcGeneratorService.class);

    private static final long LINE_BREAK_THRESHOLD_MS = 500;
    private static final long PAUSE_LINE_BREAK_MS = 800;
    /** Duración por defecto para la última línea o líneas sin sucesor. */
    private static final long DEFAULT_LINE_DURATION_MS = 3000;

    /** Genera Enhanced LRC a partir de palabras con timestamps de Whisper. */
    public String generateEnhancedLrc(List<WordTiming> words) {
        if (words == null || words.isEmpty()) {
            log.warn("No words to generate LRC from");
            return null;
        }

        List<List<WordTiming>> lines = groupWordsIntoLines(words);
        StringBuilder sb = new StringBuilder();

        for (List<WordTiming> line : lines) {
            if (line.isEmpty()) continue;

            sb.append(LrcTime.lrc(line.get(0).getStartMs()));
            for (int i = 0; i < line.size(); i++) {
                WordTiming word = line.get(i);
                if (i > 0) sb.append(" ");
                LrcTime.appendEnhanced(sb, word.getStartMs()).append(word.getWord());
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * Convierte una letra sincronizada por línea ([mm:ss.hh] texto) en Enhanced LRC,
     * distribuyendo los timestamps de palabra entre el inicio de cada línea y el de la siguiente.
     */
    public String generateFromSyncedLyrics(String syncedLyrics) {
        if (syncedLyrics == null || syncedLyrics.isBlank()) return null;

        StringBuilder sb = new StringBuilder();
        String[] lines = syncedLyrics.split("\n");

        long prevStartMs = 0;
        boolean hasPrevStart = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            long lineStartMs = LrcTime.parseLrcTimestamp(line);
            if (lineStartMs < 0) {
                sb.append(line).append("\n");
                continue;
            }

            String text = LrcTime.stripTimestamps(line);
            if (text.isEmpty()) continue;

            long lineEndMs;
            if (i + 1 < lines.length) {
                long nextStart = LrcTime.parseLrcTimestamp(lines[i + 1].trim());
                lineEndMs = nextStart >= 0 ? nextStart : lineStartMs + DEFAULT_LINE_DURATION_MS;
            } else if (hasPrevStart) {
                lineEndMs = lineStartMs + (lineStartMs - prevStartMs);
            } else {
                lineEndMs = lineStartMs + DEFAULT_LINE_DURATION_MS;
            }

            sb.append(LrcTime.lrc(lineStartMs));
            distributeWords(sb, text, lineStartMs, lineEndMs);
            sb.append("\n");

            prevStartMs = lineStartMs;
            hasPrevStart = true;
        }
        return sb.toString();
    }

    /**
     * Genera un Enhanced LRC aproximado desde texto plano repartiendo la duración
     * total en partes iguales entre las líneas.
     */
    public String generateFromPlainLyrics(String plainLyrics, double durationSeconds) {
        if (plainLyrics == null || plainLyrics.isBlank()) return null;

        String[] lines = plainLyrics.split("\n");
        if (lines.length == 0) return null;

        StringBuilder sb = new StringBuilder();
        double interval = (durationSeconds * 1000) / lines.length;

        for (int i = 0; i < lines.length; i++) {
            String text = lines[i].trim();
            if (text.isEmpty()) continue;

            long lineStartMs = (long) (i * interval);
            long lineEndMs = (long) ((i + 1) * interval);

            sb.append(LrcTime.lrc(lineStartMs));
            distributeWords(sb, text, lineStartMs, lineEndMs);
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Reparte timestamps <mm:ss.hh> equidistantes entre las palabras de la línea. */
    private void distributeWords(StringBuilder sb, String lineText, long startMs, long endMs) {
        String[] words = lineText.split("\\s+");
        if (words.length == 0) return;

        long wordDuration = (endMs - startMs) / words.length;

        for (int i = 0; i < words.length; i++) {
            long wordStart = startMs + (long) i * wordDuration;
            LrcTime.appendEnhanced(sb, wordStart).append(words[i]);
            if (i < words.length - 1) sb.append(" ");
        }
    }

    private List<List<WordTiming>> groupWordsIntoLines(List<WordTiming> words) {
        List<List<WordTiming>> lines = new ArrayList<>();
        List<WordTiming> currentLine = new ArrayList<>();

        for (int i = 0; i < words.size(); i++) {
            WordTiming word = words.get(i);
            currentLine.add(word);

            boolean shouldBreak = false;

            if (i + 1 < words.size()) {
                long gap = words.get(i + 1).getStartMs() - word.getEndMs();
                if (gap >= PAUSE_LINE_BREAK_MS) {
                    shouldBreak = true;
                } else if (gap >= LINE_BREAK_THRESHOLD_MS) {
                    String text = word.getWord();
                    if (text.endsWith(".") || text.endsWith("!") || text.endsWith("?") ||
                            text.endsWith(",") || text.endsWith(";") || text.endsWith(":")) {
                        shouldBreak = true;
                    }
                }

                if (!shouldBreak && currentLine.size() >= 8) {
                    shouldBreak = true;
                }
            } else {
                shouldBreak = true;
            }

            if (shouldBreak) {
                lines.add(new ArrayList<>(currentLine));
                currentLine.clear();
            }
        }

        return lines;
    }
}
