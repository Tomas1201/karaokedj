package com.karaokedj.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class WavStreamTest {

    @TempDir
    Path tempDir;

    @Test
    void testWriterReaderRoundTrip() throws Exception {
        Path wav = tempDir.resolve("rt.wav");
        int totalFrames = 5000;

        float[] refL = new float[totalFrames];
        float[] refR = new float[totalFrames];
        Random rng = new Random(42);
        for (int i = 0; i < totalFrames; i++) {
            refL[i] = rng.nextFloat() * 2f - 1f;
            refR[i] = rng.nextFloat() * 2f - 1f;
        }

        // Escritura por chunks (tamaños irregulares para probar bordes)
        try (WavChunkWriter writer = new WavChunkWriter(wav, 2, 44100, totalFrames)) {
            int pos = 0;
            int[] chunkSizes = {1000, 4096, 7};
            int c = 0;
            while (pos < totalFrames) {
                int len = Math.min(chunkSizes[c % chunkSizes.length], totalFrames - pos);
                writer.write(new float[][]{
                        java.util.Arrays.copyOfRange(refL, pos, pos + len),
                        java.util.Arrays.copyOfRange(refR, pos, pos + len)
                }, len);
                pos += len;
                c++;
            }
        }

        // Lectura incremental y comparación (tolerancia = cuantización PCM16)
        try (WavChunkReader reader = WavChunkReader.open44kStereo(wav)) {
            assertEquals(totalFrames, reader.totalFrames());

            float[] l = new float[777];
            float[] r = new float[777];
            int pos = 0;
            // Tolerancia de cuantización: truncamiento ×32767 + escala /32768
            // da error máximo teórico < 2 LSB completos.
            float tol = 2.5f / 32768f;
            while (true) {
                int n = reader.read(l, r);
                if (n == 0) break;
                for (int i = 0; i < n; i++) {
                    assertEquals(refL[pos + i], l[i], tol, "canal L en " + (pos + i));
                    assertEquals(refR[pos + i], r[i], tol, "canal R en " + (pos + i));
                }
                pos += n;
            }
            assertEquals(totalFrames, pos);
        }
    }

    @Test
    void testWriterPatchesHeaderWhenSizeUnknown() throws Exception {
        Path wav = tempDir.resolve("unknown.wav");

        try (WavChunkWriter writer = new WavChunkWriter(wav, 2, 44100, -1)) {
            float[] l = {0.5f, -0.5f, 0.25f};
            float[] r = {-0.25f, 0.5f, 0.1f};
            writer.writeStereoPair(l, r, 3);
        }

        try (WavChunkReader reader = WavChunkReader.open44kStereo(wav)) {
            assertEquals(3, reader.totalFrames());
            float[] l = new float[8];
            float[] r = new float[8];
            assertEquals(3, reader.read(l, r));
            assertEquals(0.5f, l[0], 1e-3f);
            assertEquals(-0.5f, l[1], 1e-3f);
        }
    }

    @Test
    void testInverseFlatMatchesSpectrumPath() {
        int nFft = 2048, hop = 441;
        int nBins = nFft / 2 + 1, nFrames = 801;
        Random rng = new Random(7);

        // Espectro aleatorio plano [bin][frame]
        float[][] real = new float[nBins][nFrames];
        float[][] imag = new float[nBins][nFrames];
        for (int b = 0; b < nBins; b++) {
            for (int t = 0; t < nFrames; t++) {
                real[b][t] = rng.nextFloat() * 2 - 1;
                imag[b][t] = rng.nextFloat() * 2 - 1;
            }
        }
        int originalLength = hop * (nFrames - 1);

        // Camino A: Spectrum anidado
        Stft.Spectrum spectrum = new Stft.Spectrum(real, imag, nBins, nFrames);
        float[] viaSpectrum = Stft.inverse(spectrum, nFft, hop, originalLength);

        // Camino B: buffer plano con offset (simula salida [1,4,2,bins,frames] del modelo)
        int stems = 4, channels = 2;
        int plane = nBins * nFrames;
        FloatBuffer flatReal = FloatBuffer.allocate(stems * channels * plane);
        FloatBuffer flatImag = FloatBuffer.allocate(stems * channels * plane);
        int stem = 3, ch = 1; // posición arbitraria dentro del tensor
        int base = (stem * channels + ch) * plane;
        for (int b = 0; b < nBins; b++) {
            int rowBase = base + b * nFrames;
            flatReal.position(rowBase);
            flatReal.put(real[b]);
            flatImag.position(rowBase);
            flatImag.put(imag[b]);
        }
        float[] viaFlat = new float[originalLength];
        Stft.inverseFlat(flatReal, flatImag, base, nBins, nFrames, nFft, hop, originalLength, viaFlat);

        assertArrayEquals(viaSpectrum, viaFlat, 1e-4f,
                "inverseFlat debe producir exactamente lo mismo que inverse(Spectrum)");
    }
}
