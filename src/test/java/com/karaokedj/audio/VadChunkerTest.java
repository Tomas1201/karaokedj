package com.karaokedj.audio;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class VadChunkerTest {

    private static final int SR = VadChunker.SAMPLE_RATE;

    /** Rellena [from,to) con "voz" (ruido por encima del umbral RMS). */
    private static void voice(float[] audio, int from, int to, Random rng) {
        for (int i = from; i < to; i++) {
            audio[i] = (rng.nextFloat() - 0.5f) * 0.5f;
        }
    }

    @Test
    void testCutsAtSilenceInsteadOfMidWord() {
        // 60 s: voz continua 0-32 s, silencio 32-34 s (dentro de la ventana de búsqueda
        // del límite a los 25 s... no: el corte se busca en [22s..25s]; ponemos silencio ahí)
        float[] audio = new float[SR * 60];
        Random rng = new Random(1);
        voice(audio, 0, SR * 22 + SR / 2, rng);          // voz hasta 22.5 s
        voice(audio, SR * 24, audio.length, rng);         // voz desde 24 s

        List<VadChunker.AudioChunk> chunks = VadChunker.split(audio);

        assertFalse(chunks.isEmpty());
        VadChunker.AudioChunk first = chunks.get(0);
        long cutSample = first.startSample() + first.data().length;
        double cutSec = cutSample / (double) SR;

        // El corte debe caer dentro de la zona de silencio (22.5-24 s), no a los 25 s exactos
        assertTrue(cutSec >= 22.4 && cutSec <= 24.1,
                "El corte debía caer en el silencio (22.5-24s), fue en " + cutSec);
        assertFalse(first.overlapped(), "No debe marcar overlap si encontró silencio");
    }

    @Test
    void testForcesCutWithOverlapOnContinuousSpeech() {
        // 80 s de habla continua sin ningún silencio
        float[] audio = new float[SR * 80];
        Random rng = new Random(2);
        voice(audio, 0, audio.length, rng);

        List<VadChunker.AudioChunk> chunks = VadChunker.split(audio);

        assertTrue(chunks.size() >= 3);
        VadChunker.AudioChunk second = chunks.get(1);
        assertTrue(second.overlapped(), "Sin silencios el segundo chunk debe ser solapado");

        // El siguiente chunk retrocede OVERLAP_SEC respecto al corte forzado
        VadChunker.AudioChunk first = chunks.get(0);
        long forcedCut = first.startSample() + first.data().length;
        assertEquals(forcedCut - VadChunker.OVERLAP_SEC * SR, second.startSample(),
                "El chunk solapado debe empezar OVERLAP_SEC antes del corte");
        assertEquals(forcedCut, second.authoritativeStartSample(),
                "La zona autoritativa del chunk solapado empieza en el corte previo");
    }

    @Test
    void testAuthoritativeDedupWindow() {
        // Habla continua: la zona [cut-overlap, cut) aparece en ambos chunks;
        // authoritativeStart del chunk N+1 marca dónde comienzan las palabras nuevas.
        float[] audio = new float[SR * 60];
        Random rng = new Random(3);
        voice(audio, 0, audio.length, rng);

        List<VadChunker.AudioChunk> chunks = VadChunker.split(audio);

        for (int i = 1; i < chunks.size(); i++) {
            VadChunker.AudioChunk prev = chunks.get(i - 1);
            VadChunker.AudioChunk cur = chunks.get(i);
            if (cur.overlapped()) {
                long prevEnd = prev.startSample() + prev.data().length;
                assertEquals(prevEnd, cur.authoritativeStartSample());
                assertTrue(cur.startSample() < cur.authoritativeStartSample(),
                        "El chunk solapado arranca antes de su frontera autoritativa");
            }
        }
    }

    @Test
    void testLastPartialChunkAndCoverage() {
        float[] audio = new float[SR * 47];   // ~2 chunks completos + resto
        Random rng = new Random(4);
        voice(audio, 0, audio.length, rng);

        List<VadChunker.AudioChunk> chunks = VadChunker.split(audio);

        // Cobertura completa con overlap: cada muestra queda dentro de algún chunk
        long coveredUntil = 0;
        for (int i = 0; i < chunks.size(); i++) {
            VadChunker.AudioChunk c = chunks.get(i);
            assertEquals(i == 0 ? 0 : coveredUntil - VadChunker.OVERLAP_SEC * SR, c.startSample(),
                    "Los chunks deben encadenarse sin huecos");
            coveredUntil = c.startSample() + c.data().length;
        }
        assertTrue(coveredUntil >= audio.length,
                "El último chunk debe alcanzar el final del audio");
    }

    @Test
    void testShortAudioSingleChunk() {
        float[] audio = new float[SR * 8];    // menos que TARGET_CHUNK_SEC
        Random rng = new Random(5);
        voice(audio, 0, audio.length, rng);

        List<VadChunker.AudioChunk> chunks = VadChunker.split(audio);

        assertEquals(1, chunks.size());
        assertArrayEquals(audio, chunks.get(0).data());
        assertEquals(0, chunks.get(0).startSample());
        assertFalse(chunks.get(0).overlapped());
    }

    @Test
    void testSilenceDetectorFindsQuietWindows() {
        float[] audio = new float[SR];
        Random rng = new Random(6);
        voice(audio, 0, SR, rng);
        // Silencio en [600ms, 700ms)
        for (int i = SR * 3 / 5; i < SR * 7 / 10; i++) {
            audio[i] = (float) (Math.sin(i * 0.01) * 0.001);
        }

        int silenceEnd = VadChunker.findSilenceEnd(audio, 0, SR);
        assertTrue(silenceEnd >= SR * 3 / 5 && silenceEnd <= SR * 7 / 10,
                "Debe detectar el fin de la ventana silenciosa, fue " + silenceEnd);
    }
}
