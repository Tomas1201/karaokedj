package com.karaokedj.ml;

public class MelSpectrogram {

    public static final int SAMPLE_RATE = 16000;
    public static final int N_FFT = 400;
    public static final int HOP_LENGTH = 160;
    public static final int N_MELS = 80;
    public static final int CHUNK_SAMPLES = SAMPLE_RATE * 30;
    public static final int N_FRAMES = CHUNK_SAMPLES / HOP_LENGTH;
    private static final int N_BINS = N_FFT / 2 + 1;

    private static final float[] HANN_WINDOW = computeHannWindow();
    private static final float[][] COS_TABLE = computeCosTable();
    private static final float[][] SIN_TABLE = computeSinTable();
    private static final float[][] MEL_FILTERBANK = computeMelFilterbank();

    /** Mel de 30 s fijo (rellena o recorta a CHUNK_SAMPLES). */
    public static float[][] compute(float[] audio) {
        return melFrom(padOrTrim(audio), CHUNK_SAMPLES);
    }

    /**
     * Mel con la longitud real del audio, sin rellenar a 30 s.
     * Requiere audio.length >= HOP_LENGTH*2. Los chunks cortos procesan más rápido
     * y sin cola artificial de silencio (el encoder admite T variable ≤ 3000 frames).
     */
    public static float[][] computeExact(float[] audio) {
        if (audio.length < HOP_LENGTH * 2) {
            throw new IllegalArgumentException("Audio too short for mel spectrogram: " + audio.length);
        }
        return melFrom(audio, audio.length);
    }

    private static float[][] melFrom(float[] padded, int sourceLen) {
        int padSize = N_FFT / 2;
        float[] paddedWithBorder = new float[sourceLen + padSize * 2];
        if (sourceLen > padSize + 1) {
            for (int i = 0; i < padSize; i++) {
                paddedWithBorder[i] = padded[padSize - i];
                paddedWithBorder[sourceLen + padSize + i] = padded[sourceLen - 2 - i];
            }
        }
        System.arraycopy(padded, 0, paddedWithBorder, padSize, sourceLen);

        int nFrames = sourceLen / HOP_LENGTH;
        float[] magnitudesFlat = new float[nFrames * N_BINS];

        for (int frame = 0; frame < nFrames; frame++) {
            int start = frame * HOP_LENGTH;
            int mBase = frame * N_BINS;

            for (int k = 0; k < N_BINS; k++) {
                float sumR = 0;
                float sumI = 0;
                float[] cosRow = COS_TABLE[k];
                float[] sinRow = SIN_TABLE[k];
                for (int n = 0; n < N_FFT; n++) {
                    float v = paddedWithBorder[start + n] * HANN_WINDOW[n];
                    sumR += v * cosRow[n];
                    sumI += v * sinRow[n];
                }
                magnitudesFlat[mBase + k] = sumR * sumR + sumI * sumI;
            }
        }

        float[] melSpecFlat = new float[N_MELS * nFrames];

        for (int j = 0; j < nFrames; j++) {
            int magBase = j * N_BINS;
            for (int i = 0; i < N_MELS; i++) {
                float[] filterbank = MEL_FILTERBANK[i];
                float sum = 0;
                for (int k = 0; k < N_BINS; k++) {
                    sum += filterbank[k] * magnitudesFlat[magBase + k];
                }
                melSpecFlat[i * nFrames + j] = (float) Math.log10(Math.max(sum, 1e-10f));
            }
        }

        float maxVal = Float.NEGATIVE_INFINITY;
        for (float v : melSpecFlat) {
            if (v > maxVal) maxVal = v;
        }
        float cutoff = maxVal - 8.0f;
        for (int i = 0; i < melSpecFlat.length; i++) {
            if (melSpecFlat[i] < cutoff) melSpecFlat[i] = cutoff;
            melSpecFlat[i] = (melSpecFlat[i] + 4.0f) / 4.0f;
        }

        float[][] melSpec = new float[N_MELS][nFrames];
        for (int i = 0; i < N_MELS; i++) {
            System.arraycopy(melSpecFlat, i * nFrames, melSpec[i], 0, nFrames);
        }
        return melSpec;
    }

    private static float[] padOrTrim(float[] audio) {
        if (audio.length == CHUNK_SAMPLES) return audio;
        float[] result = new float[CHUNK_SAMPLES];
        int copyLen = Math.min(audio.length, CHUNK_SAMPLES);
        System.arraycopy(audio, 0, result, 0, copyLen);
        return result;
    }

    private static float[] computeHannWindow() {
        float[] window = new float[N_FFT];
        for (int i = 0; i < N_FFT; i++) {
            window[i] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / N_FFT)));
        }
        return window;
    }

    private static float[][] computeCosTable() {
        float[][] table = new float[N_BINS][N_FFT];
        for (int k = 0; k < N_BINS; k++) {
            for (int n = 0; n < N_FFT; n++) {
                table[k][n] = (float) Math.cos(2.0 * Math.PI * k * n / N_FFT);
            }
        }
        return table;
    }

    private static float[][] computeSinTable() {
        float[][] table = new float[N_BINS][N_FFT];
        for (int k = 0; k < N_BINS; k++) {
            for (int n = 0; n < N_FFT; n++) {
                table[k][n] = (float) Math.sin(2.0 * Math.PI * k * n / N_FFT);
            }
        }
        return table;
    }

    private static double hzToMel(double hz) {
        double fSp = 200.0 / 3.0;
        double minLogHz = 1000.0;
        double minLogMel = minLogHz / fSp;
        double logstep = Math.log(6.4) / 27.0;
        double mel = hz / fSp;
        if (hz >= minLogHz) {
            mel = minLogMel + Math.log(hz / minLogHz) / logstep;
        }
        return mel;
    }

    private static double melToHz(double mel) {
        double fSp = 200.0 / 3.0;
        double minLogHz = 1000.0;
        double minLogMel = minLogHz / fSp;
        double logstep = Math.log(6.4) / 27.0;
        double hz = mel * fSp;
        if (mel >= minLogMel) {
            hz = minLogHz * Math.exp(logstep * (mel - minLogMel));
        }
        return hz;
    }

    private static float[][] computeMelFilterbank() {
        double[] fftFreqs = new double[N_BINS];
        for (int j = 0; j < N_BINS; j++) {
            fftFreqs[j] = (double) j * SAMPLE_RATE / N_FFT;
        }

        double[] melPoints = new double[N_MELS + 2];
        double lowMel = hzToMel(0.0);
        double highMel = hzToMel(SAMPLE_RATE / 2.0);
        for (int i = 0; i < N_MELS + 2; i++) {
            melPoints[i] = melToHz(lowMel + i * (highMel - lowMel) / (N_MELS + 1));
        }

        float[][] filterbank = new float[N_MELS][N_BINS];
        for (int i = 0; i < N_MELS; i++) {
            double fdiffLow = melPoints[i + 1] - melPoints[i];
            double fdiffHigh = melPoints[i + 2] - melPoints[i + 1];
            double enorm = 2.0 / (melPoints[i + 2] - melPoints[i]);

            for (int j = 0; j < N_BINS; j++) {
                double lower = (fftFreqs[j] - melPoints[i]) / fdiffLow;
                double upper = (melPoints[i + 2] - fftFreqs[j]) / fdiffHigh;
                double w = Math.max(0.0, Math.min(lower, upper));
                filterbank[i][j] = (float) (w * enorm);
            }
        }
        return filterbank;
    }
}
