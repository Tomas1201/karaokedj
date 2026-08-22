package com.karaokedj.audio;

import com.karaokedj.model.WordTiming;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WordTimingAlignerTest {

    private static final int SR = 16000;
    private static final long AUTH = 0L;

    @Test
    void testDistributesCollapsedWordsAcrossSpan() {
        // 10 s de audio; segmento con palabras colapsadas en t=0
        float[] audio = new float[SR * 10];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (float) Math.sin(2 * Math.PI * 220 * i / (double) SR) * 0.2f; // voz constante
        }

        List<WordTiming> collapsed = List.of(
                new WordTiming("hola", 0, 0),
                new WordTiming("mundo", 0, 0),
                new WordTiming("cruel", 0, 0),
                new WordTiming("adios", 0, 0)
        );

        List<WordTiming> refined = WordTimingAligner.refine(collapsed, audio, SR, AUTH);

        assertEquals(4, refined.size());
        // Primera empieza al inicio y última alcanza ~el fin del tramo disponible
        assertEquals(0, refined.get(0).getStartMs());
        assertTrue(refined.get(3).getEndMs() > 8000, "La última palabra debe llegar cerca del fin: "
                + refined.get(3).getEndMs());

        // Monotonía estricta: cada palabra empieza donde termina la anterior (+gap)
        for (int i = 1; i < refined.size(); i++) {
            assertTrue(refined.get(i).getStartMs() >= refined.get(i - 1).getEndMs(),
                    "Solapamiento entre palabra " + (i - 1) + " y " + i);
            assertTrue(refined.get(i).getEndMs() > refined.get(i).getStartMs(),
                    "Duración positiva requerida en palabra " + i);
        }
    }

    @Test
    void testLongerWordsGetMoreTime() {
        float[] audio = new float[SR * 8];
        java.util.Arrays.fill(audio, 0.1f);

        List<WordTiming> collapsed = List.of(
                new WordTiming("a", 0, 0),                              // corta
                new WordTiming("superpalabrisima", 0, 0)                // larga
        );

        List<WordTiming> refined = WordTimingAligner.refine(collapsed, audio, SR, AUTH);

        long shortDur = refined.get(0).getEndMs() - refined.get(0).getStartMs();
        long longDur = refined.get(1).getEndMs() - refined.get(1).getStartMs();
        assertTrue(longDur > shortDur * 3,
                "La palabra larga debe ocupar bastante más: corta=" + shortDur + "ms larga=" + longDur + "ms");
    }

    @Test
    void testSnapsInteriorBoundaryToSilenceValley() {
        // 6 s: voz 0-2s, silencio 2-2.5s, voz 2.5-6s.
        // Dos palabras colapsadas → frontera proporcional ≈3s debe ajustarse al valle (~2.25s)
        float[] audio = new float[SR * 6];
        for (int i = 0; i < audio.length; i++) {
            boolean silence = i >= SR * 2 && i < SR * 5 / 2;
            audio[i] = silence ? 0f : (float) Math.sin(2 * Math.PI * 300 * i / (double) SR) * 0.3f;
        }

        List<WordTiming> collapsed = List.of(
                new WordTiming("antes", 0, 0),
                new WordTiming("despues", 0, 0)
        );

        List<WordTiming> refined = WordTimingAligner.refine(collapsed, audio, SR, AUTH);

        long boundary = refined.get(0).getEndMs();
        double boundarySec = boundary / 1000.0;
        assertTrue(boundarySec > 1.9 && boundarySec < 2.7,
                "La frontera debe caer cerca del silencio (~2.25s), fue " + boundarySec);
    }

    @Test
    void testPreservesWordsWithRealDuration() {
        float[] audio = new float[SR];
        java.util.Arrays.fill(audio, 0.1f);

        List<WordTiming> mixed = List.of(
                new WordTiming("colapsada", 0, 0),
                new WordTiming("real", 3000, 4500)
        );

        List<WordTiming> refined = WordTimingAligner.refine(mixed, audio, SR, AUTH);

        assertEquals(2, refined.size());
        assertEquals(3000, refined.get(1).getStartMs(), "La palabra con duración real no debe tocarse");
        assertEquals(4500, refined.get(1).getEndMs());
    }

    @Test
    void testSingleWordPassthrough() {
        List<WordTiming> single = List.of(new WordTiming("sola", 500, 900));
        assertSame(single, WordTimingAligner.refine(single, new float[SR], SR, AUTH));
    }
}
