package com.karaokedj.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fraccionador de audio para Whisper con VAD (Voice Activity Detection).
 *
 * Estrategia:
 * 1. Intenta cortar cada ~{@link #TARGET_CHUNK_SEC} segundos.
 * 2. Busca hacia atrás un silencio (caída de energía RMS) para no partir palabras.
 * 3. Si el habla es continua y no hay silencio, corta forzado pero superpone
 *    los últimos {@link #OVERLAP_SEC} segundos en el chunk siguiente como contexto.
 *
 * Es DSP puro: agnóstico al idioma y al motor de transcripción.
 */
public final class VadChunker {

    public static final int SAMPLE_RATE = 16000;

    public static final int TARGET_CHUNK_SEC = 25;
    public static final int OVERLAP_SEC = 2;
    public static final int SEARCH_BACK_SEC = 3;
    public static final float SILENCE_THRESHOLD_RMS = 0.015f;
    public static final int MIN_CHUNK_SEC = 5;

    private static final int TARGET_SAMPLES = TARGET_CHUNK_SEC * SAMPLE_RATE;
    private static final int OVERLAP_SAMPLES = OVERLAP_SEC * SAMPLE_RATE;
    private static final int SEARCH_BACK_SAMPLES = SEARCH_BACK_SEC * SAMPLE_RATE;
    private static final int MIN_CHUNK_SAMPLES = MIN_CHUNK_SEC * SAMPLE_RATE;
    /** Ventana de análisis RMS: 100 ms. */
    private static final int RMS_WINDOW = SAMPLE_RATE / 10;

    /**
     * @param data                      muestras del fragmento
     * @param startSample               primera muestra real dentro del audio original
     * @param authoritativeStartSample  primera muestra cuyas palabras son NUEVAS;
     *                                  en chunks con overlap las anteriores ya fueron
     *                                  transcritas por el chunk previo y deben descartarse
     * @param overlapped                true si el corte fue forzado (el siguiente chunk
     *                                  superpone el final de este)
     */
    public record AudioChunk(float[] data, long startSample,
                             long authoritativeStartSample, boolean overlapped) {}

    private VadChunker() {
    }

    public static List<AudioChunk> split(float[] mono) {
        List<AudioChunk> chunks = new ArrayList<>();

        int pos = 0;
        long auth = 0;

        while (pos < mono.length) {
            int remaining = mono.length - pos;

            // Último tramo: tomarlo completo sin buscar corte
            if (remaining <= TARGET_SAMPLES) {
                chunks.add(new AudioChunk(
                        Arrays.copyOfRange(mono, pos, mono.length), pos, auth, false));
                break;
            }

            int proposedEnd = pos + TARGET_SAMPLES;

            // Busca silencio hacia atrás; nunca antes de pos+minLen para no crear micro-chunks
            int searchStart = Math.max(pos + MIN_CHUNK_SAMPLES, proposedEnd - SEARCH_BACK_SAMPLES);
            int cut = findSilenceEnd(mono, searchStart, proposedEnd);

            boolean overlapped = false;
            if (cut == -1) {
                cut = proposedEnd;   // habla continua: corte forzado
                overlapped = true;
            }

            chunks.add(new AudioChunk(
                    Arrays.copyOfRange(mono, pos, cut), pos, auth, overlapped));

            if (overlapped) {
                pos = cut - OVERLAP_SAMPLES;   // retrocede para dar contexto al siguiente
                auth = cut;                    // lo anterior al corte ya fue transcrito
            } else {
                pos = cut;
                auth = cut;
            }
        }
        return chunks;
    }

    /**
     * Escanea ventanas de 100 ms desde el final hacia atrás buscando la primera
     * cuyo RMS caiga bajo el umbral.
     *
     * @return índice del fin de esa ventana silenciosa (punto de corte seguro),
     *         o -1 si toda la ventana de búsqueda tiene voz.
     */
    static int findSilenceEnd(float[] audio, int searchStart, int searchEnd) {
        searchStart = Math.max(0, searchStart);
        searchEnd = Math.min(searchEnd, audio.length);

        for (int i = searchEnd - RMS_WINDOW; i >= searchStart; i -= RMS_WINDOW) {
            double sumSquares = 0;
            for (int j = 0; j < RMS_WINDOW; j++) {
                sumSquares += audio[i + j] * audio[i + j];
            }
            float rms = (float) Math.sqrt(sumSquares / RMS_WINDOW);
            if (rms < SILENCE_THRESHOLD_RMS) {
                return i + RMS_WINDOW;
            }
        }
        return -1;
    }
}
