package com.karaokedj.ml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MelSpectrogramTest {

    @Test
    void testComputeReturnsCorrectShape() {
        float[] audio = new float[MelSpectrogram.CHUNK_SAMPLES];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / MelSpectrogram.SAMPLE_RATE);
        }

        float[][] mel = MelSpectrogram.compute(audio);

        assertEquals(MelSpectrogram.N_MELS, mel.length);
        assertEquals(MelSpectrogram.N_FRAMES, mel[0].length);
    }

    @Test
    void testComputeWithSilence() {
        float[] silence = new float[MelSpectrogram.CHUNK_SAMPLES];

        float[][] mel = MelSpectrogram.compute(silence);

        assertNotNull(mel);
        assertEquals(MelSpectrogram.N_MELS, mel.length);
    }

    @Test
    void testComputeWithSine() {
        float[] audio = new float[MelSpectrogram.CHUNK_SAMPLES];
        for (int i = 0; i < audio.length; i++) {
            audio[i] = (float) Math.sin(2.0 * Math.PI * 440.0 * i / MelSpectrogram.SAMPLE_RATE);
        }

        float[][] mel = MelSpectrogram.compute(audio);

        assertNotNull(mel);
        boolean hasNonZero = false;
        for (int i = 0; i < mel.length && !hasNonZero; i++) {
            for (int j = 0; j < mel[i].length && !hasNonZero; j++) {
                if (mel[i][j] != 0.0f) hasNonZero = true;
            }
        }
        assertTrue(hasNonZero, "Mel spectrogram should have non-zero values for sine wave");
    }

    @Test
    void testComputePadsShortAudio() {
        float[] shortAudio = new float[1000];

        float[][] mel = MelSpectrogram.compute(shortAudio);

        assertNotNull(mel);
        assertEquals(MelSpectrogram.N_MELS, mel.length);
        assertEquals(MelSpectrogram.N_FRAMES, mel[0].length);
    }

    @Test
    void testConstants() {
        assertEquals(16000, MelSpectrogram.SAMPLE_RATE);
        assertEquals(400, MelSpectrogram.N_FFT);
        assertEquals(160, MelSpectrogram.HOP_LENGTH);
        assertEquals(80, MelSpectrogram.N_MELS);
        assertEquals(480000, MelSpectrogram.CHUNK_SAMPLES);
        assertEquals(3000, MelSpectrogram.N_FRAMES);
    }
}
