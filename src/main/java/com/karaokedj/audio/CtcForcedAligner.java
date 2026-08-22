package com.karaokedj.audio;

import com.karaokedj.ml.CtcAlignerModel;
import com.karaokedj.model.WordTiming;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Forced alignment CTC: ancla las palabras ya transcritas contra el audio real.
 *
 * Pipeline: normalizar audio → logits del modelo MMS (frames de 20 ms) →
 * secuencia de caracteres romanizados de las palabras → Viterbi monótono
 * chars×frames → fronteras de palabra con precisión de frame.
 *
 * Si el audio es demasiado corto para la secuencia, devuelve null y el caller
 * usa su fallback aproximado.
 */
public final class CtcForcedAligner {

    /** Frames de 20 ms (stride 320 muestras a 16 kHz). */
    public static final int MS_PER_FRAME = 20;

    private CtcForcedAligner() {
    }

    /** Vocabulario CTC cargado de vocab.json (nombre de token → id). */
    public record Vocab(Map<String, Integer> tokenToId) {
        public int id(String token) {
            Integer id = tokenToId.get(token);
            if (id == null) throw new IllegalArgumentException("Token ausente en vocabulario: " + token);
            return id;
        }

        public int size() {
            return tokenToId.size();
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Carga vocab.json: {"<blank>": 0, "a": 4, ...} */
    public static Vocab loadVocab(java.nio.file.Path vocabJson) throws java.io.IOException {
        Map<String, Integer> map = JSON.readValue(vocabJson.toFile(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Integer>>() {});
        return new Vocab(map);
    }

    // ============================================================
    // Preprocesamiento
    // ============================================================

    /** Normalización wav2vec2: media 0, varianza unitaria. */
    public static float[] normalize(float[] audio) {
        double mean = 0;
        for (float v : audio) mean += v;
        mean /= Math.max(1, audio.length);

        double variance = 0;
        for (float v : audio) {
            double d = v - mean;
            variance += d * d;
        }
        variance /= Math.max(1, audio.length);
        double std = Math.sqrt(variance) + 1e-7;

        float[] out = new float[audio.length];
        for (int i = 0; i < audio.length; i++) {
            out[i] = (float) ((audio[i] - mean) / std);
        }
        return out;
    }

    /**
     * Romanización para el vocabulario latino: minúsculas, sin acentos
     * (á→a, ñ→n...), conservando solo [a-z'].
     */
    public static String romanize(String word) {
        String decomposed = Normalizer.normalize(word.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        StringBuilder sb = new StringBuilder(decomposed.length());
        for (char c : decomposed.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || c == '\'') sb.append(c);
        }
        return sb.toString();
    }

    // ============================================================
    // Alineación principal
    // ============================================================

    /**
     * Refina los timestamps por palabra usando forced alignment CTC.
     *
     * @return alineación precisa; null si no es posible (caller aplica fallback)
     */
    public static List<WordTiming> refine(float[] chunkAudio16k,
                                          List<WordTiming> coarseWords,
                                          long chunkOffsetMs,
                                          CtcAlignerModel model,
                                          Vocab vocab) throws Exception {
        if (coarseWords.isEmpty()) return coarseWords;
        float[][] emissions = model.logits(normalize(chunkAudio16k));
        return refineEmissions(emissions, vocab, coarseWords, chunkOffsetMs);
    }

    /**
     * Núcleo testeable: alinea sin tocar el modelo.
     *
     * @param emissions [T][31] logits CTC (frame de 20 ms por fila)
     * @return alineación precisa; null si no es posible
     */
    public static List<WordTiming> refineEmissions(float[][] emissions, Vocab vocab,
                                                   List<WordTiming> coarseWords,
                                                   long chunkOffsetMs) {
        if (coarseWords.isEmpty()) return coarseWords;

        // Secuencia de caracteres: ids + índice de palabra dueña de cada char
        List<Integer> seqTokens = new ArrayList<>();
        List<Integer> seqWordIdx = new ArrayList<>();
        List<Integer> keptWordIdx = new ArrayList<>();

        for (int w = 0; w < coarseWords.size(); w++) {
            String romanized = romanize(coarseWords.get(w).getWord());
            if (romanized.isEmpty()) continue;   // p.ej. números: se interpolan luego
            keptWordIdx.add(w);
            for (int c = 0; c < romanized.length(); c++) {
                Integer id = vocab.tokenToId().get(String.valueOf(romanized.charAt(c)));
                if (id == null) continue;
                seqTokens.add(id);
                seqWordIdx.add(keptWordIdx.size() - 1);
            }
        }

        int blankId = vocab.id("<blank>");
        int frames = emissions.length;
        int k = seqTokens.size();
        if (k == 0 || frames < k) return null;   // audio más corto que el texto

        // ---- Viterbi monótono ----
        // advance: 0 = blank/stay, 1 = se emitió S[c] viniendo de c-1,
        //          2 = arranque del camino emitiendo S[0] en este frame
        double negInf = -1e18;
        double[][] dp = new double[frames][k];
        byte[][] advance = new byte[frames][k];

        for (int c = 1; c < k; c++) dp[0][c] = negInf;
        dp[0][0] = emissions[0][seqTokens.get(0)];
        advance[0][0] = 2;

        for (int t = 1; t < frames; t++) {
            double blankScore = emissions[t][blankId];
            for (int c = 0; c < k; c++) {
                int tokenCol = seqTokens.get(c);
                double stay = dp[t - 1][c] + blankScore;

                if (c > 0) {
                    double emit = dp[t - 1][c - 1] + emissions[t][tokenCol];
                    if (emit >= stay) {
                        dp[t][c] = emit;
                        advance[t][c] = 1;
                    } else {
                        dp[t][c] = stay;
                    }
                } else {
                    // El primer char puede iniciar el camino en cualquier frame
                    // (el audio previo es silencio/blank sin costo contable)
                    double startHere = emissions[t][tokenCol];
                    if (startHere >= stay) {
                        dp[t][0] = startHere;
                        advance[t][0] = 2;
                    } else {
                        dp[t][0] = stay;
                    }
                }
            }
        }

        // Backtrack: frame de emisión de cada char
        int[] emitFrame = new int[k];
        int c = k - 1;
        int t = frames - 1;
        while (c >= 0 && t >= 0) {
            byte flag = advance[t][c];
            if (flag >= 1) {
                emitFrame[c] = t;
                c--;
            }
            t--;
        }
        if (c >= 0) return null;   // no se alcanzó a emitir todo el texto

        // El marco de S[0] lo marca el inicio del camino (t arbitrario); el
        // instante acústico correcto es el pico de ese char antes de S[1].
        if (k > 1) {
            int limit = emitFrame[1];
            int bestT = 0;
            float bestV = Float.NEGATIVE_INFINITY;
            int firstCol = seqTokens.get(0);
            for (int f = 0; f < limit; f++) {
                if (emissions[f][firstCol] > bestV) {
                    bestV = emissions[f][firstCol];
                    bestT = f;
                }
            }
            emitFrame[0] = bestT;
        }

        // Fronteras por palabra a partir de sus chars
        long durationMs = frames * (long) MS_PER_FRAME;
        List<WordTiming> refined = new ArrayList<>(coarseWords.size());
        int charCursor = 0;
        for (int wIdx = 0; wIdx < keptWordIdx.size(); wIdx++) {
            WordTiming source = coarseWords.get(keptWordIdx.get(wIdx));

            int first = charCursor;
            while (charCursor < seqWordIdx.size() && seqWordIdx.get(charCursor) == wIdx) charCursor++;
            if (first == charCursor) continue;   // palabra vacía tras filtrar chars

            long firstFrameMs = Math.min(emitFrame[first] * (long) MS_PER_FRAME, durationMs);
            long lastFrameMs = Math.min((emitFrame[charCursor - 1] + 1L) * MS_PER_FRAME, durationMs);
            long startMs = chunkOffsetMs + firstFrameMs;
            long endMs = chunkOffsetMs + Math.max(lastFrameMs, firstFrameMs);
            refined.add(new WordTiming(source.getWord(), startMs, endMs));
        }

        // Palabras descartadas por romanización vacía (números): interpolar entre vecinos
        return interpolateSkipped(coarseWords, refined);
    }

    /** Reinserta las palabras omitidas (sin chars romanizables) interpolando tiempos. */
    private static List<WordTiming> interpolateSkipped(List<WordTiming> coarse,
                                                       List<WordTiming> aligned) {
        List<WordTiming> result = new ArrayList<>();
        int alignedIdx = 0;
        for (WordTiming original : coarse) {
            if (alignedIdx < aligned.size()
                    && original.getWord().equals(aligned.get(alignedIdx).getWord())) {
                result.add(aligned.get(alignedIdx++));
            } else {
                long prevEnd = result.isEmpty()
                        ? (aligned.isEmpty() ? 0 : aligned.get(0).getStartMs())
                        : result.get(result.size() - 1).getEndMs();
                long nextStart = alignedIdx < aligned.size()
                        ? aligned.get(alignedIdx).getStartMs()
                        : prevEnd + 300;
                long mid = Math.max(prevEnd, (prevEnd + nextStart) / 2);
                result.add(new WordTiming(original.getWord(), mid, mid + 200));
            }
        }
        return result;
    }
}
