package com.karaokedj.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.karaokedj.audio.StereoSource;
import com.karaokedj.audio.StemSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Modelo ONNX de Demucs (separación de stems).
 *
 * Ciclo de vida: instanciar por canción vía {@link com.karaokedj.service.AudioSeparationModel},
 * cargar, separar y liberar ({@link #close()}). Solo inferencia: la IO de archivos
 * vive en audio/WavChunkReader + StemSeparatorService.
 */
public class DemucsModel implements SeparationModel {

    private static final Logger log = LoggerFactory.getLogger(DemucsModel.class);

    /** Segmentos de ~7.8s a 44100 Hz que espera el modelo exportado. */
    public static final int SEGMENT_SAMPLES = 343980;

    private OrtSession session;

    @Override
    public void load(Path modelPath) throws OrtException {
        this.session = environment().createSession(modelPath.toString(), MlOptions.base());
        log.info("Demucs model loaded: {}", modelPath);
    }

    @Override
    public void separate(StereoSource input, StemSink output,
                         BiConsumer<Integer, Integer> progressCallback) throws Exception {
        if (session == null) throw new IllegalStateException("Demucs model not loaded");

        long totalFrames = Math.max(1, input.totalFrames());
        int nSegments = (int) Math.ceil((double) totalFrames / SEGMENT_SAMPLES);

        float[] left = new float[SEGMENT_SAMPLES];
        float[] right = new float[SEGMENT_SAMPLES];
        float[] vocalL = new float[SEGMENT_SAMPLES];
        float[] vocalR = new float[SEGMENT_SAMPLES];
        float[] instrL = new float[SEGMENT_SAMPLES];
        float[] instrR = new float[SEGMENT_SAMPLES];

        for (int seg = 0; seg < nSegments; seg++) {
            int len = input.read(left, right);
            if (len <= 0) break;

            // Zero-pad al tamaño fijo del segmento
            for (int i = len; i < SEGMENT_SAMPLES; i++) {
                left[i] = 0f;
                right[i] = 0f;
            }

            // Planar para la entrada del modelo [1, 2, S]
            float[] inputBuffer = new float[2 * SEGMENT_SAMPLES];
            System.arraycopy(left, 0, inputBuffer, 0, SEGMENT_SAMPLES);
            System.arraycopy(right, 0, inputBuffer, SEGMENT_SAMPLES, SEGMENT_SAMPLES);

            try (OnnxTensor inputTensor = OnnxTensor.createTensor(environment(),
                    FloatBuffer.wrap(inputBuffer),
                    TensorUtils.shape3D(1, 2, SEGMENT_SAMPLES));
                 OrtSession.Result result = session.run(Map.of("mix", inputTensor))) {

                OnnxTensor outTensor = (OnnxTensor) result.get(0);
                float[][][][] stemsBatch = (float[][][][]) outTensor.getValue();

                // Stem 3 = vocales
                System.arraycopy(stemsBatch[0][3][0], 0, vocalL, 0, len);
                System.arraycopy(stemsBatch[0][3][1], 0, vocalR, 0, len);

                // Instrumental = promedio de los otros tres stems
                for (int ch = 0; ch < 2; ch++) {
                    float[] instr = ch == 0 ? instrL : instrR;
                    for (int i = 0; i < len; i++) {
                        instr[i] = (stemsBatch[0][0][ch][i]
                                + stemsBatch[0][1][ch][i]
                                + stemsBatch[0][2][ch][i]) / 3.0f;
                    }
                }
            }

            output.stems(vocalL, vocalR, instrL, instrR, len);

            log.info("Demucs segment {}/{} done", seg + 1, nSegments);
            if (progressCallback != null) {
                progressCallback.accept(seg + 1, nSegments);
            }
        }
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
