package com.karaokedj.ml;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.Path;

/**
 * Modelo ONNX de Whisper (sherpa-onnx whisper-small, multilingüe).
 *
 * Ciclo de vida: cargar encoder+decoder por canción y liberar al terminar.
 * Encapsula todo el conocimiento del modelo: nombres de tensores, tokens
 * especiales y manejo de caches KV autoregresivos.
 */
public class WhisperModel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WhisperModel.class);

    // === Tokens especiales (vocabulario multilingüe estándar de OpenAI Whisper) ===
    public static final int EOS_TOKEN = 50257;              // <|endoftext|>
    public static final int SOT_TOKEN = 50258;              // <|startoftranscript|>
    // Los tokens de idioma (<|es|>=50262, <|en|>=50259, ...) viven en ml/WhisperLanguage
    public static final int TRANSCRIBE_TOKEN = 50359;       // <|transcribe|>
    /** Primer token de timestamp (= 0 ms); cada incremento suma 20 ms. */
    public static final int TIMESTAMP_TOKEN_BASE = 50364;

    /** Capacidad del cache KV del decoder exportado (posiciones). */
    public static final int KV_CACHE_POSITIONS = 448;

    private OrtSession encoderSession;
    private OrtSession decoderSession;

    public void loadEncoder(Path modelPath) throws OrtException {
        encoderSession = environment().createSession(modelPath.toString(), MlOptions.base());
        log.info("Whisper encoder loaded: {}", modelPath);
    }

    public void loadDecoder(Path modelPath) throws OrtException {
        decoderSession = environment().createSession(modelPath.toString(), MlOptions.base());
        log.info("Whisper decoder loaded: {}", modelPath);
    }

    /**
     * Salida del encoder: K/V cross-atención por capa.
     * Formas: [nLayers=12, batch=1, T=1500, dModel=768].
     */
    public record EncoderOutput(float[][][][] crossKey, float[][][][] crossValue) {}

    public EncoderOutput encode(float[][] melSpectrogram) throws OrtException {
        if (encoderSession == null) throw new IllegalStateException("Whisper encoder not loaded");

        int nMels = melSpectrogram.length;
        int nFrames = melSpectrogram[0].length;

        try (var inputTensor = ai.onnxruntime.OnnxTensor.createTensor(environment(),
                FloatBuffer.wrap(TensorUtils.flatten2D(melSpectrogram)),
                TensorUtils.shape3D(1, nMels, nFrames));
             OrtSession.Result result = encoderSession.run(Map.of("mel", inputTensor))) {

            float[][][][] crossK = TensorUtils.extract4DFloats(result.get("n_layer_cross_k").get());
            float[][][][] crossV = TensorUtils.extract4DFloats(result.get("n_layer_cross_v").get());

            log.debug("Encoder output: crossK [{}, {}, {}, {}]",
                    crossK.length, crossK[0].length, crossK[0][0].length, crossK[0][0][0].length);

            return new EncoderOutput(crossK, crossV);
        }
    }

    /**
     * Un paso incremental del decoder con cache KV.
     *
     * @param promptTokens   solo los tokens NUEVOS de este paso (el prompt completo en el primer paso)
     * @param selfKeyCache   cache [12][batch][448][768]; se actualiza in-place con la salida del modelo
     * @param offset         posición actual del cache (# tokens previamente procesados)
     * @return logits del último paso [51865]
     */
    public float[] decodeStep(EncoderOutput encOut,
                              int[] promptTokens,
                              float[][][][] selfKeyCache,
                              float[][][][] selfValueCache,
                              long offset) throws OrtException {
        if (decoderSession == null) throw new IllegalStateException("Whisper decoder not loaded");

        long[] inputIds = new long[promptTokens.length];
        for (int i = 0; i < promptTokens.length; i++) {
            inputIds[i] = promptTokens[i];
        }
        int n = inputIds.length;

        Map<String, ai.onnxruntime.OnnxTensor> inputs = new HashMap<>();
        try {
            inputs.put("tokens", ai.onnxruntime.OnnxTensor.createTensor(
                    environment(), LongBuffer.wrap(inputIds), new long[]{1, n}));
            inputs.put("in_n_layer_self_k_cache", ai.onnxruntime.OnnxTensor.createTensor(
                    environment(), FloatBuffer.wrap(TensorUtils.flatten4D(selfKeyCache)),
                    TensorUtils.shape4D(selfKeyCache)));
            inputs.put("in_n_layer_self_v_cache", ai.onnxruntime.OnnxTensor.createTensor(
                    environment(), FloatBuffer.wrap(TensorUtils.flatten4D(selfValueCache)),
                    TensorUtils.shape4D(selfValueCache)));
            inputs.put("n_layer_cross_k", ai.onnxruntime.OnnxTensor.createTensor(
                    environment(), FloatBuffer.wrap(TensorUtils.flatten4D(encOut.crossKey())),
                    TensorUtils.shape4D(encOut.crossKey())));
            inputs.put("n_layer_cross_v", ai.onnxruntime.OnnxTensor.createTensor(
                    environment(), FloatBuffer.wrap(TensorUtils.flatten4D(encOut.crossValue())),
                    TensorUtils.shape4D(encOut.crossValue())));
            inputs.put("offset", ai.onnxruntime.OnnxTensor.createTensor(environment(), new long[]{offset}));

            try (OrtSession.Result result = decoderSession.run(inputs)) {
                float[] logits = TensorUtils.extractLastLogits(result.get("logits").get());

                float[][][][] outKey = TensorUtils.extract4DFloats(result.get("out_n_layer_self_k_cache").get());
                float[][][][] outVal = TensorUtils.extract4DFloats(result.get("out_n_layer_self_v_cache").get());

                for (int l = 0; l < selfKeyCache.length; l++) {
                    selfKeyCache[l] = outKey[l];
                    selfValueCache[l] = outVal[l];
                }
                return logits;
            }
        } finally {
            for (ai.onnxruntime.OnnxTensor t : inputs.values()) {
                try { t.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static OrtEnvironment environment() {
        return OrtEnvironment.getEnvironment();
    }

    @Override
    public void close() {
        try {
            if (encoderSession != null) encoderSession.close();
        } catch (OrtException ignored) {
        }
        try {
            if (decoderSession != null) decoderSession.close();
        } catch (OrtException ignored) {
        }
        encoderSession = null;
        decoderSession = null;
    }
}
