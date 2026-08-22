package com.karaokedj.ml;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Diagnóstico: compara MelSpectrogram.computeExact Java contra un volcado Python.
 *  Deshabilitado: depende de /tmp/opencode/{chunk0_samples,mel_java_expected}.bin
 *  (ver dump_mel.py). Habilitar manualmente tras regenerar los volcados. */
@org.junit.jupiter.api.Disabled("diagnóstico manual: requiere volcados previos de Python")
class DiagnosticMelTest {

    @Test
    void compareJavaMelAgainstPythonDump() throws Exception {
        Path samples = Path.of("/tmp/opencode/chunk0_samples.bin");
        Path expected = Path.of("/tmp/opencode/mel_java_expected.bin");
        if (!Files.isReadable(samples) || !Files.isReadable(expected)) {
            System.out.println("[MELDIAG] archivos no disponibles, saltando");
            return;
        }

        float[] audio = float32Le(Files.readAllBytes(samples));
        int nFrames = audio.length / MelSpectrogram.HOP_LENGTH;
        float[][] javaMel = MelSpectrogram.computeExact(audio);

        FloatBuffer pyBuf = ByteBuffer.wrap(Files.readAllBytes(expected))
                .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();

        double maxDiff = 0, sumDiff = 0;
        int diffsOver01 = 0;
        for (int m = 0; m < MelSpectrogram.N_MELS; m++) {
            for (int t = 0; t < nFrames; t++) {
                double d = Math.abs(javaMel[m][t] - pyBuf.get(m * nFrames + t));
                maxDiff = Math.max(maxDiff, d);
                sumDiff += d;
                if (d > 0.1) diffsOver01++;
            }
        }
        System.out.printf("[MELDIAG] frames=%d max|diff|=%.6f media=%.8f celdas>0.1=%d%n",
                nFrames, maxDiff, sumDiff / (80.0 * nFrames), diffsOver01);
        assertTrue(maxDiff < 0.05,
                "Mel Java diverge de Python: max=" + maxDiff + " celdas>0.1=" + diffsOver01);
    }

    private static float[] float32Le(byte[] raw) {
        FloatBuffer fb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] f = new float[fb.remaining()];
        fb.get(f);
        return f;
    }
}
