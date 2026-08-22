package com.karaokedj.service;

import com.karaokedj.audio.VadChunker;
import com.karaokedj.audio.VadChunker.AudioChunk;
import com.karaokedj.audio.WavIO;
import com.karaokedj.ml.MelSpectrogram;
import com.karaokedj.ml.WhisperLanguage;
import com.karaokedj.ml.WhisperModel;
import com.karaokedj.ml.WhisperModel.EncoderOutput;
import com.karaokedj.model.WordTiming;
import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Diagnóstico autónomo del pipeline Java real: usa solo APIs públicas
 * (VadChunker + MelSpectrogram + WhisperModel) replicando la orquestación de
 * TranscriptionService, con logging inmediato a /tmp/opencode/java_diag.log.
 *
 * Deshabilitado: requiere modelos descargados + un WAV de prueba + varios minutos
 * de CPU. Lanzar manualmente con:
 *   mvn test -Dtest=DiagnosticTranscriptionTest -Ddiag.wav=/ruta/vocal.wav [-Ddiag.maxChunks=N]
 */
@org.junit.jupiter.api.Disabled("diagnóstico manual: lento y dependiente de archivos externos")
class DiagnosticTranscriptionTest {

    @Test
    void javaPipelineEndToEndDiagnostic() throws Exception {
        PrintStream log = new PrintStream("/tmp/opencode/java_diag.log", "UTF-8");
        System.setOut(log);
        System.setErr(log);

        String wav = System.getProperty("diag.wav", "/tmp/opencode/vocal_first60.wav");
        Path vocal = Path.of(wav);
        if (!vocal.toFile().canRead()) {
            log.println("[DIAG] archivo no disponible: " + wav);
            return;
        }

        Path whisperDir = Path.of(System.getProperty("user.home"), ".karaokedj", "models", "whisper");
        Path encoder = find(whisperDir, "*-encoder.onnx");
        Path decoder = find(whisperDir, "*-decoder.onnx");
        Path tokensPath = find(whisperDir, "*-tokens.txt");

        WhisperModel model = new WhisperModel();
        long t0 = System.currentTimeMillis();
        log.println("[DIAG] cargando modelos...");
        model.loadEncoder(encoder);
        model.loadDecoder(decoder);
        log.println("[DIAG] modelos cargados en " + (System.currentTimeMillis() - t0) + " ms");

        String[] vocab = loadTokens(tokensPath);
        int[] prompt = {WhisperModel.SOT_TOKEN,
                WhisperLanguage.ES.token(),
                WhisperModel.TRANSCRIBE_TOKEN};

        float[] audio = WavIO.readMonoFloat(vocal, VadChunker.SAMPLE_RATE);
        List<AudioChunk> chunks = VadChunker.split(audio);
        int maxChunks = Integer.getInteger("diag.maxChunks", chunks.size());
        if (maxChunks < chunks.size()) chunks = chunks.subList(0, maxChunks);
        log.println("[DIAG] audio " + audio.length / (double) VadChunker.SAMPLE_RATE
                + "s -> " + chunks.size() + " chunks VAD");

        int totalDigits = 0, totalLetters = 0;

        try (model) {
            for (int i = 0; i < chunks.size(); i++) {
                AudioChunk chunk = chunks.get(i);
                long t1 = System.currentTimeMillis();

                float[][] mel = MelSpectrogram.computeExact(chunk.data());
                EncoderOutput encOut = model.encode(mel);

                long chunkStartMs = chunk.startSample() * 1000L / VadChunker.SAMPLE_RATE;
                long authMs = chunk.authoritativeStartSample() * 1000L / VadChunker.SAMPLE_RATE;

                StringBuilder text = new StringBuilder();
                List<WordTiming> words = decodeChunk(model, vocab, encOut, prompt,
                        chunkStartMs, authMs, text);

                int dg = 0, lt = 0;
                for (WordTiming w : words) {
                    for (char c : w.getWord().toCharArray()) {
                        if (Character.isDigit(c)) dg++;
                        else if (Character.isLetter(c)) lt++;
                    }
                }
                totalDigits += dg;
                totalLetters += lt;

                log.printf("[DIAG] chunk %d/%d (%ds, dur %.1fs, ov=%b) %d ms | palabras=%d dígitos=%d%n",
                        i + 1, chunks.size(), chunk.startSample() / VadChunker.SAMPLE_RATE,
                        chunk.data().length / (double) VadChunker.SAMPLE_RATE,
                        chunk.overlapped(), System.currentTimeMillis() - t1, words.size(), dg);
                log.println("  texto: " + text.substring(0, Math.min(200, text.length())));
            }
        }

        log.println();
        log.println("[DIAG] RESUMEN: letras=" + totalLetters + " dígitos=" + totalDigits);
        log.println("[DIAG] FIN");
        log.close();
    }

    /** Réplica exacta de TranscriptionService.decodeWithTimestamps con salida de texto. */
    private List<WordTiming> decodeChunk(WhisperModel model, String[] tokens, EncoderOutput encOut,
                                         int[] prompt, long chunkOffsetMs, long authoritativeMs,
                                         StringBuilder textOut) throws Exception {
        List<WordTiming> words = new ArrayList<>();

        int nLayers = encOut.crossKey().length;
        int nBatch = encOut.crossKey()[0].length;
        int kvDim = encOut.crossKey()[0][0][0].length;
        float[][][][] selfK = new float[nLayers][nBatch][WhisperModel.KV_CACHE_POSITIONS][kvDim];
        float[][][][] selfV = new float[nLayers][nBatch][WhisperModel.KV_CACHE_POSITIONS][kvDim];

        long tsMs = 0;
        StringBuilder word = new StringBuilder();
        long offset = 0;

        float[] logits = model.decodeStep(encOut, prompt, selfK, selfV, offset);
        offset += prompt.length;

        for (int step = 0; step < 2048 && offset < WhisperModel.KV_CACHE_POSITIONS - 1; step++) {
            int tok = argmax(logits);
            if (tok == WhisperModel.EOS_TOKEN) break;

            if (tok >= WhisperModel.TIMESTAMP_TOKEN_BASE) {
                long newTs = (long) (tok - WhisperModel.TIMESTAMP_TOKEN_BASE) * 20L;
                flush(words, word, chunkOffsetMs + tsMs,
                        chunkOffsetMs + Math.max(newTs, tsMs + 100), authoritativeMs, textOut);
                tsMs = newTs;
            } else if (tok > WhisperModel.EOS_TOKEN && tok < WhisperModel.TIMESTAMP_TOKEN_BASE) {
                // token especial: ignorar
            } else if (tok < tokens.length) {
                String txt = tokens[tok];
                if (txt.startsWith(" ") || txt.startsWith(",") || txt.startsWith(".") ||
                        txt.startsWith("!") || txt.startsWith("?")) {
                    flush(words, word, chunkOffsetMs + tsMs,
                            chunkOffsetMs + tsMs, authoritativeMs, textOut);
                }
                word.append(txt);
            }

            logits = model.decodeStep(encOut, new int[]{tok}, selfK, selfV, offset);
            offset++;
        }
        flush(words, word, chunkOffsetMs + tsMs, chunkOffsetMs + tsMs + 500, authoritativeMs, textOut);
        return words;
    }

    private void flush(List<WordTiming> words, StringBuilder word,
                       long startMs, long endMs, long authMs, StringBuilder textOut) {
        if (word.length() == 0) return;
        String w = word.toString().trim();
        word.setLength(0);
        if (w.isEmpty()) return;
        if (startMs >= authMs) {
            words.add(new WordTiming(w, startMs, endMs));
            textOut.append(w).append(' ');
        }
    }

    private int argmax(float[] logits) {
        int best = 0;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < logits.length; i++) {
            if (logits[i] > bestScore) {
                bestScore = logits[i];
                best = i;
            }
        }
        return best;
    }

    private Path find(Path dir, String glob) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches(glob.replace("*", ".*")))
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No encontrado: " + glob));
        }
    }

    private String[] loadTokens(Path tokensPath) throws Exception {
        List<String> list = new ArrayList<>();
        for (String line : Files.readAllLines(tokensPath)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            String[] parts = trimmed.split(" ", 2);
            if (parts.length < 2) {
                list.add(trimmed);
                continue;
            }
            int id = Integer.parseInt(parts[1].trim());
            String text;
            try {
                text = new String(java.util.Base64.getDecoder().decode(parts[0]),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                text = parts[0];
            }
            while (list.size() <= id) list.add("");
            list.set(id, text);
        }
        return list.toArray(new String[0]);
    }
}
