package com.karaokedj.audio;

import com.karaokedj.model.WordTiming;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CtcForcedAlignerTest {

    private static final int FRAMES_MS = CtcForcedAligner.MS_PER_FRAME;

    /** Vocabulario mínimo realista: blank + letras usadas en los tests. */
    private static CtcForcedAligner.Vocab vocab(String letters) {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        m.put("<blank>", 0);
        int next = 1;
        for (char c : letters.toCharArray()) {
            String s = String.valueOf(c);
            if (Character.isLetter(c) && !m.containsKey(s)) m.put(s, next++);
        }
        return new CtcForcedAligner.Vocab(m);
    }

    /**
     * Construye emisiones sintéticas: cada char de {@code text} tiene un pico
     * alto en el frame indicado por {@code framesPerChar}; el resto del tiempo
     * favorece al blank.
     */
    private static float[][] emissionsFor(int totalFrames, String text, int[] framesPerChar,
                                          Map<String, Integer> tokenToId) {
        float[][] em = new float[totalFrames][tokenToId.size()];
        for (float[] row : em) row[0] = 5f;   // blank domina por defecto
        for (int c = 0; c < text.length(); c++) {
            Integer id = tokenToId.get(String.valueOf(text.charAt(c)));
            if (id != null && framesPerChar[c] < totalFrames) {
                em[framesPerChar[c]][id] = 8f;   // pico del char
            }
        }
        return em;
    }

    @Test
    void testAlignsWordsNearTheirPeaks() {
        var vocab = vocab("holamund");
        String text = "hola mundo";
        // "hola" en frames 10,14,18,22 | "mundo" en frames 40,45,50,55,60
        int[] peaks = {10, 14, 18, 22, 40, 45, 50, 55, 60};
        float[][] em = emissionsFor(100, text.replace(" ", ""), peaks, vocab.tokenToId());

        List<WordTiming> coarse = List.of(
                new WordTiming("hola", 0, 0),
                new WordTiming("mundo", 0, 0));

        List<WordTiming> out = CtcForcedAligner.refineEmissions(
                em, vocab, coarse, 5000L);

        assertEquals(2, out.size());
        long holaStart = out.get(0).getStartMs() - 5000L;
        long mundoStart = out.get(1).getStartMs() - 5000L;

        assertTrue(Math.abs(holaStart - 10 * FRAMES_MS) <= 3 * FRAMES_MS,
                "'hola' debe arrancar cerca del frame 10, fue " + holaStart + "ms");
        assertTrue(Math.abs(mundoStart - 40 * FRAMES_MS) <= 3 * FRAMES_MS,
                "'mundo' debe arrancar cerca del frame 40, fue " + mundoStart + "ms");
    }

    @Test
    void testMonotonicAndPositiveDurations() {
        var vocab = vocab("abcdef");
        String text = "abc def";
        int[] peaks = {5, 12, 20, 35, 42, 55};
        float[][] em = emissionsFor(80, text.replace(" ", ""), peaks, vocab.tokenToId());

        List<WordTiming> out = CtcForcedAligner.refineEmissions(
                em, vocab,
                List.of(new WordTiming("abc", 0, 0), new WordTiming("def", 0, 0)),
                0L);

        for (int i = 0; i < out.size(); i++) {
            WordTiming w = out.get(i);
            assertTrue(w.getEndMs() > w.getStartMs(), "duración positiva palabra " + i);
            if (i > 0) {
                assertTrue(w.getStartMs() >= out.get(i - 1).getEndMs(),
                        "monotonía entre palabras");
            }
        }
    }

    @Test
    void testRomanizesAccents() {
        assertEquals("manana", CtcForcedAligner.romanize("Mañana"));
        assertEquals("corazon", CtcForcedAligner.romanize("CORAZÓN"));
        assertEquals("guitarra", CtcForcedAligner.romanize("guitárra"));
        // Números y símbolos desaparecen
        assertEquals("", CtcForcedAligner.romanize("2024!"));
    }

    @Test
    void testInterpolatesNonRomanizableWords() {
        var vocab = vocab("sol");
        String text = "sol";
        int[] peaks = {30, 34, 38};
        float[][] em = emissionsFor(60, text, peaks, vocab.tokenToId());

        List<WordTiming> out = CtcForcedAligner.refineEmissions(
                em, vocab,
                List.of(new WordTiming("2024", 0, 0),
                        new WordTiming("sol", 0, 0),
                        new WordTiming("!!!", 0, 0)),
                0L);

        assertEquals(3, out.size(), "las palabras sin chars se interpolan, no se pierden");
        assertTrue(out.get(1).getEndMs() > out.get(1).getStartMs());
        assertTrue(out.get(2).getStartMs() >= out.get(1).getEndMs() - 1);
    }

    @Test
    void testReturnsNullWhenAudioTooShort() {
        var vocab = vocab("hola");
        // 5 frames pero la palabra necesita 4 chars... con 3 frames no alcanza
        float[][] em = new float[3][vocab.size()];
        for (float[] row : em) row[0] = 5f;

        List<WordTiming> out = CtcForcedAligner.refineEmissions(
                em, vocab, List.of(new WordTiming("hola", 0, 0)), 0L);
        assertNull(out, "audio más corto que el texto => null (fallback)");
    }

    @Test
    void testNormalizationZeroMeanUnitVar() {
        float[] audio = {2f, 2f, 2f, 6f};   // media=3, std≈2
        float[] norm = CtcForcedAligner.normalize(audio);
        double mean = 0;
        for (float v : norm) mean += v;
        mean /= norm.length;
        double var = 0;
        for (float v : norm) var += (v - mean) * (v - mean);
        var /= norm.length;

        assertEquals(0, mean, 1e-5);
        assertEquals(1, Math.sqrt(var), 1e-3);
    }
}
