package com.karaokedj.audio;

import com.karaokedj.model.WordTiming;

import java.util.ArrayList;
import java.util.List;

/**
 * Refinamiento de timestamps palabra a palabra.
 *
 * Whisper greedy solo emite tokens de timestamp en los BORDES de cada segmento,
 * no entre palabras: todas las palabras interiores heredan el instante de inicio
 * del segmento (start == end). Este alineador reparte el tramo conocido del
 * segmento proporcionalmente al largo de cada palabra y ajusta cada frontera
 * interna al valle de energía RMS más cercano (pausas reales del audio).
 */
public final class WordTimingAligner {

    /** Frames RMS para detección de valles: 20 ms. */
    private static final int RMS_FRAME_MS = 20;
    /** Búsqueda máxima de valle alrededor de la frontera proporcional. */
    private static final long SNAP_WINDOW_MS = 250;
    /** Separación mínima entre fronteras tras el ajuste. */
    private static final long MIN_WORD_GAP_MS = 40;
    /** Segmentos más cortos que esto se quedan con la distribución proporcional pura. */
    private static final long MIN_SPAN_FOR_SNAP_MS = 400;

    private WordTimingAligner() {
    }

    /**
     * @param chunkWords  palabras decodificadas del chunk (pueden venir colapsadas start==end)
     * @param chunkAudio  muestras mono 16 kHz del chunk completo
     * @param sampleRate  sample rate de {@code chunkAudio}
     * @param authStartMs frontera autoritativa del chunk (zona solapada ya descartada)
     */
    public static List<WordTiming> refine(List<WordTiming> chunkWords, float[] chunkAudio,
                                          int sampleRate, long authStartMs) {
        if (chunkWords == null || chunkWords.size() < 2) return chunkWords;

        double[] frameRms = frameRms(chunkAudio, sampleRate);
        long audioDurationMs = chunkAudio.length * 1000L / sampleRate;

        List<WordTiming> result = new ArrayList<>(chunkWords.size());
        int i = 0;
        while (i < chunkWords.size()) {
            WordTiming w = chunkWords.get(i);

            // Palabra con duración real (borde de segmento): se conserva tal cual
            if (w.getEndMs() > w.getStartMs()) {
                result.add(w);
                i++;
                continue;
            }

            // Tramo colapsado: reunir palabras que comparten el mismo instante
            int j = i;
            while (j + 1 < chunkWords.size()
                    && chunkWords.get(j + 1).getStartMs() == w.getStartMs()
                    && chunkWords.get(j + 1).getEndMs() == w.getEndMs()) {
                j++;
            }

            long spanEnd = findSpanEnd(chunkWords, j, w.getStartMs(), audioDurationMs);
            refineRun(chunkWords, i, j, w.getStartMs(), spanEnd,
                    frameRms, sampleRate, audioDurationMs, result);

            i = j + 1;
        }
        return result;
    }

    /**
     * Fin del tramo disponible para las palabras colapsadas que terminan en índice j:
     * el inicio de la siguiente palabra distinta, o el final del audio.
     */
    private static long findSpanEnd(List<WordTiming> words, int collapsedLastIdx,
                                    long spanStartMs, long audioDurationMs) {
        for (int k = collapsedLastIdx + 1; k < words.size(); k++) {
            WordTiming next = words.get(k);
            if (next.getStartMs() > spanStartMs) {
                return Math.max(next.getStartMs(), next.getEndMs());
            }
        }
        return audioDurationMs;
    }

    /** Reparte [runStart, runEnd] entre words[from..to] por caracteres y ajusta valles. */
    private static void refineRun(List<WordTiming> words, int from, int to,
                                  long runStartMs, long runEndMs,
                                  double[] frameRms, int sampleRate, long audioDurationMs,
                                  List<WordTiming> out) {
        runEndMs = Math.max(runEndMs, runStartMs + MIN_WORD_GAP_MS * (to - from + 1));
        runEndMs = Math.min(runEndMs, Math.max(audioDurationMs, runStartMs));

        int count = to - from + 1;
        long totalWeight = 0;
        long[] weights = new long[count];
        for (int k = 0; k < count; k++) {
            weights[k] = Math.max(1, words.get(from + k).getWord().length());
            totalWeight += weights[k];
        }

        long[] boundaries = new long[count + 1];
        boundaries[0] = runStartMs;
        boundaries[count] = runEndMs;

        boolean canSnap = (runEndMs - runStartMs) >= MIN_SPAN_FOR_SNAP_MS;
        long prevBoundary = runStartMs;
        for (int k = 1; k < count; k++) {
            long proportional = runStartMs + (runEndMs - runStartMs) * prefixWeight(weights, k) / totalWeight;
            long boundary = canSnap
                    ? snapToValley(proportional, frameRms, sampleRate, prevBoundary, runEndMs)
                    : proportional;
            boundary = Math.max(boundary, prevBoundary + MIN_WORD_GAP_MS);
            boundary = Math.min(boundary, runEndMs - MIN_WORD_GAP_MS * (count - k));
            boundaries[k] = boundary;
            prevBoundary = boundary;
        }
        // Reimpose monotonía estricta hacia atrás si el snap comprimió demasiado
        for (int k = count - 1; k >= 1; k--) {
            boundaries[k] = Math.min(boundaries[k], boundaries[k + 1] - MIN_WORD_GAP_MS);
            boundaries[k] = Math.max(boundaries[k], runStartMs);
        }

        for (int k = 0; k < count; k++) {
            WordTiming original = words.get(from + k);
            out.add(new WordTiming(original.getWord(), boundaries[k], boundaries[k + 1]));
        }
    }

    private static long prefixWeight(long[] weights, int upToExclusive) {
        long sum = 0;
        for (int i = 0; i < upToExclusive; i++) sum += weights[i];
        return sum;
    }

    /**
     * Busca el mínimo RMS dentro de ±SNAP_WINDOW_MS alrededor de {@code targetMs}
     * y devuelve el centro de ese frame.
     */
    private static long snapToValley(long targetMs, double[] frameRms, int sampleRate,
                                     long lowerBoundMs, long upperBoundMs) {
        int frameCount = frameRms.length;
        if (frameCount == 0) return targetMs;

        int centerFrame = (int) (targetMs / RMS_FRAME_MS);
        int windowFrames = (int) (SNAP_WINDOW_MS / RMS_FRAME_MS);
        int lo = Math.max(0, centerFrame - windowFrames);
        int hi = Math.min(frameCount - 1, centerFrame + windowFrames);

        long loMs = msOfFrame(lo, sampleRate);
        long hiMs = msOfFrame(hi, sampleRate);
        if (hiMs < lowerBoundMs || loMs > upperBoundMs) return targetMs;

        int best = centerFrame;
        double bestRms = Double.MAX_VALUE;
        for (int f = lo; f <= hi; f++) {
            if (frameRms[f] < bestRms) {
                bestRms = frameRms[f];
                best = f;
            }
        }
        return msOfFrame(best, sampleRate);
    }

    private static long msOfFrame(int frame, int sampleRate) {
        return (long) frame * RMS_FRAME_MS + RMS_FRAME_MS / 2;
    }

    /** RMS por frames de RMS_FRAME_MS sobre todo el chunk. */
    private static double[] frameRms(float[] audio, int sampleRate) {
        int frameSamples = sampleRate * RMS_FRAME_MS / 1000;
        if (frameSamples <= 0) return new double[0];

        int frames = (audio.length + frameSamples - 1) / frameSamples;
        double[] rms = new double[frames];
        for (int f = 0; f < frames; f++) {
            int from = f * frameSamples;
            int to = Math.min(from + frameSamples, audio.length);
            double sum = 0;
            for (int i = from; i < to; i++) {
                sum += audio[i] * audio[i];
            }
            rms[f] = Math.sqrt(sum / Math.max(1, to - from));
        }
        return rms;
    }
}
