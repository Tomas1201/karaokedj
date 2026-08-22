package com.karaokedj.audio;

/**
 * STFT/iSTFT complejo equivalente a torch.stft/torch.istft con center=True:
 * padding reflectante de nFft/2, ventana Hann periódica y normalización
 * por suma de ventana al cuadrado en la reconstrucción.
 *
 * Contrato verificado contra referencia Python (semántica exacta de PyTorch).
 */
public final class Stft {

    private Stft() {
    }

    /** Espectro de un canal: [bin][frame], bins = nFft/2+1. */
    public record Spectrum(float[][] real, float[][] imag, int nBins, int nFrames) {}

    /**
     * STFT forward.
     *
     * @param x   señal mono (el caller garantiza longitud >= hop para frames>=2)
     * @param nFft tamaño de ventana (potencia de 2)
     * @param hop salto entre frames
     */
    public static Spectrum forward(float[] x, int nFft, int hop) {
        int pad = nFft / 2;
        float[] padded = reflectPad(x, pad);
        int nFrames = 1 + x.length / hop;
        int nBins = nFft / 2 + 1;
        float[] window = hannPeriodic(nFft);

        float[][] real = new float[nBins][nFrames];
        float[][] imag = new float[nBins][nFrames];

        float[] frameRe = new float[nFft];
        float[] frameIm = new float[nFft];

        for (int t = 0; t < nFrames; t++) {
            int start = t * hop;
            for (int n = 0; n < nFft; n++) {
                frameRe[n] = padded[start + n] * window[n];
                frameIm[n] = 0f;
            }
            Fft.fftInPlace(frameRe, frameIm);

            for (int k = 0; k < nBins; k++) {
                real[k][t] = frameRe[k];
                imag[k][t] = frameIm[k];
            }
        }
        return new Spectrum(real, imag, nBins, nFrames);
    }

    /**
     * iSTFT con overlap-add normalizado por la suma de ventana².
     *
     * @param originalLength longitud de salida deseada (recorta el padding)
     */
    public static float[] inverse(Spectrum spec, int nFft, int hop, int originalLength) {
        int pad = nFft / 2;
        int totalLen = originalLength + 2 * pad;
        float[] window = hannPeriodic(nFft);

        float[] accum = new float[totalLen];
        float[] winSum = new float[totalLen];

        float[] frameRe = new float[nFft];
        float[] frameIm = new float[nFft];

        for (int t = 0; t < spec.nFrames(); t++) {
            // Reconstruye el espectro completo por simetría Hermitiana
            java.util.Arrays.fill(frameIm, 0f);
            for (int k = 0; k <= nFft / 2; k++) {
                frameRe[k] = spec.real()[k][t];
                frameIm[k] = spec.imag()[k][t];
            }
            for (int k = 1; k < nFft / 2; k++) {
                frameRe[nFft - k] = spec.real()[k][t];
                frameIm[nFft - k] = -spec.imag()[k][t];
            }

            Fft.ifftInPlace(frameRe, frameIm);

            int start = t * hop;
            for (int n = 0; n < nFft; n++) {
                accum[start + n] += frameRe[n] * window[n];
                winSum[start + n] += window[n] * window[n];
            }
        }

        float[] out = new float[originalLength];
        for (int i = 0; i < originalLength; i++) {
            float denom = winSum[pad + i];
            out[i] = denom > 1e-8f ? accum[pad + i] / denom : 0f;
        }
        return out;
    }

    /**
     * iSTFT leyendo el espectro directamente de un buffer plano (layout [bin][frame]
     * dentro de un plano mayor, p.ej. la salida [1,4,2,1025,801] del modelo ONNX).
     *
     * Variante sin asignaciones: escribe el resultado en {@code dst} y nunca
     * materializa arrays anidados — pensada para consumir OnnxTensor.getFloatBuffer().
     *
     * @param srcReal     buffer con las partes reales
     * @param srcImag     buffer con las partes imaginarias
     * @param planeOffset índice absoluto donde comienza el plano [bin][frame] de este stem/canal
     */
    public static void inverseFlat(java.nio.FloatBuffer srcReal, java.nio.FloatBuffer srcImag,
                                   int planeOffset, int nBins, int nFrames,
                                   int nFft, int hop, int originalLength, float[] dst) {
        int pad = nFft / 2;
        int totalLen = originalLength + 2 * pad;
        float[] window = hannPeriodic(nFft);

        float[] accum = new float[totalLen];
        float[] winSum = new float[totalLen];
        float[] frameRe = new float[nFft];
        float[] frameIm = new float[nFft];

        for (int t = 0; t < nFrames; t++) {
            java.util.Arrays.fill(frameIm, 0f);
            int colBase = planeOffset + t;
            for (int k = 0; k <= nFft / 2; k++) {
                int idx = colBase + k * nFrames;
                frameRe[k] = srcReal.get(idx);
                frameIm[k] = srcImag.get(idx);
            }
            for (int k = 1; k < nFft / 2; k++) {
                frameRe[nFft - k] = frameRe[k];
                frameIm[nFft - k] = -frameIm[k];
            }

            Fft.ifftInPlace(frameRe, frameIm);

            int start = t * hop;
            for (int n = 0; n < nFft; n++) {
                accum[start + n] += frameRe[n] * window[n];
                winSum[start + n] += window[n] * window[n];
            }
        }

        for (int i = 0; i < originalLength; i++) {
            float denom = winSum[pad + i];
            dst[i] = denom > 1e-8f ? accum[pad + i] / denom : 0f;
        }
    }

    /** Empaqueta un Spectrum al layout plano [ch][bin][frame] que espera el modelo ONNX. */
    public static void packChannel(Spectrum s, int channelIndex, float[] dstReal, float[] dstImag) {
        int plane = s.nBins() * s.nFrames();
        int baseR = channelIndex * plane;
        for (int bin = 0; bin < s.nBins(); bin++) {
            int rowBase = baseR + bin * s.nFrames();
            System.arraycopy(s.real()[bin], 0, dstReal, rowBase, s.nFrames());
            System.arraycopy(s.imag()[bin], 0, dstImag, rowBase, s.nFrames());
        }
    }

    private static float[] reflectPad(float[] x, int pad) {
        float[] padded = new float[x.length + 2 * pad];
        if (x.length > pad + 1) {
            for (int i = 0; i < pad; i++) {
                padded[i] = x[pad - i];
                padded[x.length + pad + i] = x[x.length - 2 - i];
            }
        }
        System.arraycopy(x, 0, padded, pad, x.length);
        return padded;
    }

    /** Ventana Hann periódica (igual a torch.hann_window(N)). */
    public static float[] hannPeriodic(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++) {
            w[i] = (float) (0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / n)));
        }
        return w;
    }
}
