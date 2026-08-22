package com.karaokedj.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StftTest {

    private static final int N_FFT = 2048;
    private static final int HOP = 441;
    private static final int CHUNK = 352800; // T=801 frames, igual que BS-RoFormer

    @Test
    void testRoundTripIsIdentity() {
        float[] x = new float[CHUNK];
        for (int i = 0; i < x.length; i++) {
            x[i] = (float) (0.4 * Math.sin(2 * Math.PI * 220.0 * i / 44100.0)
                    + 0.2 * Math.sin(2 * Math.PI * 880.0 * i / 44100.0));
        }

        Stft.Spectrum spec = Stft.forward(x, N_FFT, HOP);
        assertEquals(801, spec.nFrames(), "T debe ser 801 frames para el chunk de BS-RoFormer");
        assertEquals(N_FFT / 2 + 1, spec.nBins());

        float[] recovered = Stft.inverse(spec, N_FFT, HOP, x.length);

        double maxErr = 0;
        for (int i = 0; i < x.length; i++) {
            maxErr = Math.max(maxErr, Math.abs(x[i] - recovered[i]));
        }
        assertTrue(maxErr < 1e-4, "Round-trip STFT->iSTFT debe ser identidad, err=" + maxErr);
    }

    @Test
    void testPureTonePeaksAtExpectedBin() {
        // 10 ciclos exactos de una sinusoide en el bin k=50 => energía concentrada ahí.
        // Con center=True los frames de borde ven padding reflectante; usamos un frame
        // interior (t=2) que cae íntegramente dentro de la señal original.
        int nFft = 2048;
        int hop = nFft;
        int k = 50;
        float[] x = new float[4 * nFft];
        for (int i = 0; i < x.length; i++) {
            x[i] = (float) Math.cos(2 * Math.PI * k * i / nFft);
        }

        Stft.Spectrum spec = Stft.forward(x, nFft, hop);
        assertEquals(5, spec.nFrames(), "1 + L/hop con L=4*N");

        int frame = 2;
        int peakBin = 0;
        double peakMag = -1;
        for (int b = 0; b < spec.nBins(); b++) {
            double mag = Math.hypot(spec.real()[b][frame], spec.imag()[b][frame]);
            if (mag > peakMag) {
                peakMag = mag;
                peakBin = b;
            }
        }
        assertEquals(k, peakBin, "El pico espectral debe caer en el bin de la frecuencia pura");
    }

    @Test
    void testFrameCountMatchesContract() {
        // Contrato del modelo: L=352800 con hop=441 y center=True -> 801 frames
        Stft.Spectrum spec = Stft.forward(new float[CHUNK], N_FFT, HOP);
        assertEquals(801, spec.nFrames());
    }

    @Test
    void testInverseNormalizesWindowOverlap() {
        // Señal DC constante: tras round-trip debe mantenerse constante
        float[] x = new float[4096];
        java.util.Arrays.fill(x, 0.5f);

        Stft.Spectrum spec = Stft.forward(x, N_FFT, HOP);
        float[] recovered = Stft.inverse(spec, N_FFT, HOP, x.length);

        for (int i = 100; i < x.length - 100; i++) {
            assertEquals(0.5f, recovered[i], 1e-4f,
                    "La normalización por ventana² debe reconstruir la amplitud original");
        }
    }
}
