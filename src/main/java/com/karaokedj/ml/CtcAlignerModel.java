package com.karaokedj.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import java.util.Map;

/**
 * Modelo de forced-alignment CTC (MMS-300M q8, romara-labs ONNX export).
 *
 * Recibe audio mono 16 kHz YA NORMALIZADO (media 0, varianza 1) y devuelve
 * los logits CTC [T][31]: una distribución por frame de 20 ms sobre el
 * vocabulario de caracteres (a-z, ', <blank>, ...).
 *
 * Ciclo de vida: instanciar por transcripción, cargar, alinear y liberar.
 */
public class CtcAlignerModel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CtcAlignerModel.class);

    /** Stride total del CNN frontend: muestras de audio por frame de salida. */
    public static final int SAMPLES_PER_FRAME = 320;

    private OrtSession session;

    public void load(Path modelPath) throws Exception {
        this.session = environment().createSession(modelPath.toString(), MlOptions.base());
        log.info("CTC aligner loaded: {}", modelPath);
    }

    /**
     * @param normalizedAudio audio mono 16 kHz con media 0 y varianza 1
     * @return matriz [T][31] de emisiones CTC (T = frames de 20 ms)
     */
    public float[][] logits(float[] normalizedAudio) throws Exception {
        if (session == null) throw new IllegalStateException("CTC aligner not loaded");

        int n = normalizedAudio.length;
        long[] ones = new long[n];
        java.util.Arrays.fill(ones, 1L);

        try (OnnxTensor values = OnnxTensor.createTensor(environment(),
                FloatBuffer.wrap(normalizedAudio), new long[]{1, n});
             OnnxTensor mask = OnnxTensor.createTensor(environment(),
                     LongBuffer.wrap(ones), new long[]{1, n});
             OrtSession.Result result = session.run(Map.of(
                     "input_values", values,
                     "attention_mask", mask))) {

            OnnxTensor logitsTensor = (OnnxTensor) result.get(0);
            float[][][] batched = (float[][][]) logitsTensor.getValue();
            return batched[0];
        }
    }

    /** Frames estimados para una cantidad dada de muestras. */
    public static int framesFor(int samples) {
        return samples / SAMPLES_PER_FRAME;
    }

    private static OrtEnvironment environment() {
        return OrtEnvironment.getEnvironment();
    }

    @Override
    public void close() {
        try {
            if (session != null) session.close();
        } catch (OrtException ignored) {
        }
        session = null;
    }
}
