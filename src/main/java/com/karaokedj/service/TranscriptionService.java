package com.karaokedj.service;

import com.karaokedj.audio.CtcForcedAligner;
import com.karaokedj.audio.VadChunker;
import com.karaokedj.audio.VadChunker.AudioChunk;
import com.karaokedj.audio.WavIO;
import com.karaokedj.audio.WordTimingAligner;
import com.karaokedj.ml.CtcAlignerModel;
import com.karaokedj.ml.MelSpectrogram;
import com.karaokedj.ml.WhisperLanguage;
import com.karaokedj.ml.WhisperModel;
import com.karaokedj.ml.WhisperModel.EncoderOutput;
import com.karaokedj.model.WordTiming;
import com.karaokedj.util.LrcTime;
import com.karaokedj.util.MemoryMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Transcripción voz→palabras con timestamps usando Whisper.
 * Orquesta: conversión de audio, descarga/validación del modelo, decodificación por chunks.
 * La inferencia ONNX vive en {@link WhisperModel}.
 */
@Service
public class TranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionService.class);

    private static final int SAMPLE_RATE = MelSpectrogram.SAMPLE_RATE;

    @Autowired
    private ModelDownloadService modelDownloadService;

    @Autowired
    private AudioProcessorService audioProcessorService;

    @Autowired
    private WhisperModel whisperModel;

    public List<WordTiming> transcribe(Path vocalWav, Path outputDir,
                                        ModelDownloadService.ProgressCallback progress,
                                        ProgressListener listener,
                                        WhisperLanguage language) throws Exception {
        MemoryMonitor.log("Before Whisper transcription");

        if (listener != null) listener.onStep("Convirtiendo vocal a 16kHz mono...");
        Path audio16k = audioProcessorService.extractVocalForWhisper(vocalWav, outputDir);

        float[] audio = WavIO.readMonoFloat(audio16k, SAMPLE_RATE);
        log.info("Audio loaded for transcription: {} samples ({}s)", audio.length,
                (double) audio.length / SAMPLE_RATE);
        if (listener != null) {
            listener.onDetail(String.format("Vocal cargado: %.1f segundos", audio.length / (double) SAMPLE_RATE));
        }

        if (progress != null) progress.onProgress(0);

        if (listener != null) listener.onStep("Verificando modelo Whisper...");
        Path whisperDir = modelDownloadService.ensureWhisperModels(progress);

        Path encoderPath = findFile(whisperDir, "*-encoder.onnx");
        Path decoderPath = findFile(whisperDir, "*-decoder.onnx");
        Path tokensPath = findFile(whisperDir, "*-tokens.txt");

        log.info("Using encoder: {}", encoderPath.getFileName());
        log.info("Using decoder: {}", decoderPath.getFileName());

        // Alineador CTC opcional (precisión ms); fallback aproximado si no está
        CtcAlignerModel ctcAligner = null;
        CtcForcedAligner.Vocab ctcVocab = null;
        Path ctcPath = modelDownloadService.alignerModelIfPresent();
        Path vocabPath = modelDownloadService.alignerVocabIfPresent();
        if (ctcPath != null && vocabPath != null) {
            try {
                ctcAligner = new CtcAlignerModel();
                ctcAligner.load(ctcPath);
                ctcVocab = CtcForcedAligner.loadVocab(vocabPath);
                log.info("Alineador CTC activo (precisión por palabra)");
            } catch (Exception e) {
                log.warn("Alineador CTC no disponible ({}): se usará alineación aproximada", e.getMessage());
                if (ctcAligner != null) { ctcAligner.close(); ctcAligner = null; }
            }
        }

        try {
            if (listener != null) listener.onStep("Cargando modelo Whisper (~488MB)...");
            whisperModel.loadEncoder(encoderPath);
            whisperModel.loadDecoder(decoderPath);
            MemoryMonitor.log("After loading Whisper models");

            String[] tokens = loadTokens(tokensPath);
            log.info("Loaded {} tokens", tokens.length);

            if (listener != null) listener.onStep("Transcribiendo con Whisper (" + language.displayName() + ")...");
            log.info("Running Whisper transcription (language={})...", language.code());
            List<WordTiming> words = transcribeAudio(audio, tokens, language, listener, ctcAligner, ctcVocab);

            audio = null;

            if (listener != null) {
                listener.onStep(String.format("Transcripción completa: %d palabras", words.size()));
            }
            log.info("Transcription complete: {} words", words.size());
            Files.deleteIfExists(audio16k);
            return words;
        } finally {
            whisperModel.close();
            if (ctcAligner != null) ctcAligner.close();
            System.gc();
            MemoryMonitor.log("After closing Whisper session");
        }
    }

    /**
     * Fracciona con VAD (corta en silencios, overlap si habla continua) y transcribe
     * cada chunk con su offset absoluto real. Los chunks cortos usan mel exacta,
     * sin padding a 30 s.
     */
    private List<WordTiming> transcribeAudio(float[] audio, String[] tokens,
                                              WhisperLanguage language,
                                              ProgressListener listener,
                                              CtcAlignerModel ctcAligner,
                                              CtcForcedAligner.Vocab ctcVocab) throws Exception {
        List<WordTiming> allWords = new ArrayList<>();
        List<AudioChunk> chunks = VadChunker.split(audio);

        int[] prompt = buildPrompt(language);

        for (int i = 0; i < chunks.size(); i++) {
            AudioChunk chunk = chunks.get(i);
            long chunkStartMs = chunk.startSample() * 1000L / SAMPLE_RATE;
            long chunkEndMs = (chunk.startSample() + chunk.data().length) * 1000L / SAMPLE_RATE;
            long authoritativeMs = chunk.authoritativeStartSample() * 1000L / SAMPLE_RATE;

            if (listener != null) {
                listener.onStep(String.format("Transcribiendo segmento %d de %d (%s–%s)%s...",
                        i + 1, chunks.size(),
                        LrcTime.minutesSeconds(chunkStartMs),
                        LrcTime.minutesSeconds(chunkEndMs),
                        chunk.overlapped() ? " [solapado]" : ""));
                listener.onFraction((double) i / chunks.size());
            }

            float[][] melSpec = MelSpectrogram.computeExact(chunk.data());
            EncoderOutput encOut = whisperModel.encode(melSpec);

            List<WordTiming> chunkWords = decodeWithTimestamps(
                    tokens, encOut, prompt, chunkStartMs, authoritativeMs);

            // Timestamps por palabra: CTC preciso si hay modelo, aproximado si no
            chunkWords = refineTiming(chunk.data(), chunkWords, authoritativeMs,
                    ctcAligner, ctcVocab, chunkStartMs);

            allWords.addAll(chunkWords);

            if (listener != null) {
                listener.onDetail(String.format("Segmento %d/%d listo — %d palabras nuevas",
                        i + 1, chunks.size(), chunkWords.size()));
            }
        }

        if (listener != null) listener.onFraction(1.0);
        return allWords;
    }

    /**
     * Timestamps por palabra: CTC preciso (MMS) si el modelo está descargado;
     * si falla o falta, alineador aproximado ponderado + valles RMS.
     */
    private List<WordTiming> refineTiming(float[] chunkAudio, List<WordTiming> words,
                                          long authoritativeMs,
                                          CtcAlignerModel ctcAligner,
                                          CtcForcedAligner.Vocab ctcVocab,
                                          long chunkStartMs) {
        if (ctcAligner != null && ctcVocab != null) {
            try {
                List<WordTiming> refined = CtcForcedAligner.refine(
                        chunkAudio, words, chunkStartMs, ctcAligner, ctcVocab);
                if (refined != null) return refined;
            } catch (Exception e) {
                log.warn("CTC alignment falló en chunk: {} — usando aproximado", e.getMessage());
            }
        }
        return WordTimingAligner.refine(words, chunkAudio, SAMPLE_RATE, authoritativeMs);
    }

    /** Prompt según idioma: AUTO deja que el modelo lo prediga desde [SOT]. */
    private int[] buildPrompt(WhisperLanguage language) {
        if (language == WhisperLanguage.AUTO) {
            return new int[]{WhisperModel.SOT_TOKEN};
        }
        return new int[]{WhisperModel.SOT_TOKEN, language.token(), WhisperModel.TRANSCRIBE_TOKEN};
    }

    /**
     * Decodificación greedy incremental con KV cache:
     * el primer paso consume el prompt completo; los siguientes solo el nuevo token.
     * Los tokens de timestamp (cada uno = 20 ms) marcan límites de palabra.
     *
     * @param authoritativeOffsetMs las palabras que empiezan antes de este instante
     *                              absoluto ya fueron transcritas por el chunk previo
     *                              (zona solapada) y se descartan
     */
    private List<WordTiming> decodeWithTimestamps(String[] tokens, EncoderOutput encOut,
                                                  int[] prompt,
                                                  long chunkOffsetMs,
                                                  long authoritativeOffsetMs) throws Exception {
        List<WordTiming> words = new ArrayList<>();
        List<Integer> generated = new ArrayList<>();

        int nLayers = encOut.crossKey().length;
        int nBatch = encOut.crossKey()[0].length;
        int kvDim = encOut.crossKey()[0][0][0].length;

        float[][][][] selfKeyCache = new float[nLayers][nBatch][WhisperModel.KV_CACHE_POSITIONS][kvDim];
        float[][][][] selfValueCache = new float[nLayers][nBatch][WhisperModel.KV_CACHE_POSITIONS][kvDim];

        long currentTimestampMs = 0;
        StringBuilder currentWord = new StringBuilder();
        long offset = 0;

        float[] lastLogits = whisperModel.decodeStep(encOut, prompt, selfKeyCache, selfValueCache, offset);
        offset += prompt.length;

        for (int step = 0; step < 2048 && offset < WhisperModel.KV_CACHE_POSITIONS - 1; step++) {
            int nextToken = argmax(lastLogits);

            if (nextToken == WhisperModel.EOS_TOKEN) break;

            if (nextToken >= WhisperModel.TIMESTAMP_TOKEN_BASE) {
                long timestampMs = (long) (nextToken - WhisperModel.TIMESTAMP_TOKEN_BASE) * 20L;

                flushWord(words, currentWord, chunkOffsetMs + currentTimestampMs,
                        chunkOffsetMs + Math.max(timestampMs, currentTimestampMs + 100),
                        authoritativeOffsetMs);
                currentTimestampMs = timestampMs;
            } else if (nextToken > WhisperModel.EOS_TOKEN && nextToken < WhisperModel.TIMESTAMP_TOKEN_BASE) {
                // tokens especiales (idioma/tarea): ignorar
            } else {
                if (nextToken < tokens.length) {
                    String tokenText = tokens[nextToken];
                    if (tokenText.startsWith(" ") || tokenText.startsWith(",") ||
                            tokenText.startsWith(".") || tokenText.startsWith("!") ||
                            tokenText.startsWith("?")) {
                        flushWord(words, currentWord, chunkOffsetMs + currentTimestampMs,
                                chunkOffsetMs + currentTimestampMs, authoritativeOffsetMs);
                    }
                    currentWord.append(tokenText);
                }
                generated.add(nextToken);
            }

            lastLogits = whisperModel.decodeStep(
                    encOut, new int[]{nextToken}, selfKeyCache, selfValueCache, offset);
            offset++;
        }

        flushWord(words, currentWord, chunkOffsetMs + currentTimestampMs,
                chunkOffsetMs + currentTimestampMs + 500, authoritativeOffsetMs);

        return words;
    }

    /** Cierra la palabra en curso y la agrega si no cae en zona solapada ya transcrita. */
    private void flushWord(List<WordTiming> words, StringBuilder currentWord,
                           long startMs, long endMs, long authoritativeOffsetMs) {
        if (currentWord.length() == 0) return;
        String text = currentWord.toString().trim();
        currentWord.setLength(0);
        if (!text.isEmpty() && startMs >= authoritativeOffsetMs) {
            words.add(new WordTiming(text, startMs, endMs));
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

    private Path findFile(Path dir, String glob) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        String pattern = glob.replace("*", ".*");
                        return name.matches(pattern);
                    })
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("File not found: " + glob + " in " + dir));
        }
    }

    /**
     * Formato sherpa-onnx: "&lt;base64-del-token&gt; &lt;id&gt;" por línea.
     * El texto del token está codificado en Base64 — decodificarlo es obligatorio,
     * si no cada palabra se transcribe como su propio ID numérico.
     */
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
