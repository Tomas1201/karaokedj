package com.karaokedj.audio;

/**
 * FFT iterativa radix-2 (decimación temporal, in-place).
 * Compartida por MelSpectrogram y Stft (antes duplicada).
 */
public final class Fft {

    private Fft() {
    }

    /** longitudes admitidas: potencias de 2. */
    public static void fftInPlace(float[] real, float[] imag) {
        int n = real.length;
        if (n == 0 || Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("FFT length must be a power of 2, got " + n);
        }

        int bits = Integer.numberOfTrailingZeros(n);
        for (int i = 0; i < n; i++) {
            int j = Integer.reverse(i) >>> (32 - bits);
            if (i < j) {
                float tempR = real[i]; real[i] = real[j]; real[j] = tempR;
                float tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI;
            }
        }

        for (int size = 2; size <= n; size <<= 1) {
            int halfSize = size / 2;
            float angle = (float) (-2.0 * Math.PI / size);
            float wR = (float) Math.cos(angle);
            float wI = (float) Math.sin(angle);

            for (int start = 0; start < n; start += size) {
                float curR = 1.0f;
                float curI = 0.0f;

                for (int k = 0; k < halfSize; k++) {
                    int even = start + k;
                    int odd = start + k + halfSize;

                    float tR = curR * real[odd] - curI * imag[odd];
                    float tI = curR * imag[odd] + curI * real[odd];

                    real[odd] = real[even] - tR;
                    imag[odd] = imag[even] - tI;
                    real[even] += tR;
                    imag[even] += tI;

                    float newCurR = curR * wR - curI * wI;
                    curI = curR * wI + curI * wR;
                    curR = newCurR;
                }
            }
        }
    }

    /** IFFT vía conjugación: ifft(x) = conj(fft(conj(x)))/N. */
    public static void ifftInPlace(float[] real, float[] imag) {
        for (int i = 0; i < real.length; i++) {
            imag[i] = -imag[i];
        }
        fftInPlace(real, imag);
        float invN = 1.0f / real.length;
        for (int i = 0; i < real.length; i++) {
            real[i] *= invN;
            imag[i] = -imag[i];
        }
    }
}
