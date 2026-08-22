package com.karaokedj.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.karaokedj.audio.StereoSource;
import com.karaokedj.audio.StemSink;
import com.karaokedj.audio.Stft;
import com.karaokedj.util.MemoryMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Modelo BS-RoFormer 4-stem (export host-STFT de silverdaw/bs-roformer-rhythm-onnx, MIT).
 *
 * Ciclo de vida: instanciar por canción vía {@link com.karaokedj.service.AudioSeparationModel}.
 *
 * El grafo ONNX consume el STFT precalculado y devuelve máscaras por stem:
 * - Entrada:  spec_real/spec_imag  float32 [1, 2, 1025, 801]
 * - Salida:   out_spec_real/imag   float32 [1, 4, 2, 1025, 801]
 *   Stems en orden: drums(0), bass(1), other(2), vocals(3).
 *
 * Chunking fijo: T=801 frames = 352.800 muestras (8 s @ 44.1 kHz), chunks contiguos
 * (la reconstrucción STFT/iSTFT es exacta, no requiere solape entre chunks).
 *
 * MEMORIA: las salidas se consumen vía OnnxTensor.getFloatBuffer() (vista sin copia)
 * y el iSTFT lee por índice absoluto — nunca se materializan los ~210 MB de arrays
 * anidados que produciría getValue(). Todos los buffers de trabajo se reutilizan.
 */
public class BsRoformerModel implements SeparationModel {

    private static final Logger log = LoggerFactory.getLogger(BsRoformerModel.class);

    public static final int SAMPLE_RATE = 44100;
    public static final int N_FFT = 2048;
    public static final int HOP_LENGTH = 441;
    /** Muestras por chunk: hop * (T-1) con T=801 frames. */
    public static final int CHUNK_SAMPLES = HOP_LENGTH * 800;
    private static final int N_BINS = N_FFT / 2 + 1;   // 1025
    private static final int N_FRAMES = CHUNK_SAMPLES / HOP_LENGTH + 1; // 801

    private static final int STEM_VOCALS = 3;
    private static final int[] STEMS_INSTRUMENTAL = {0, 1, 2}; // drums, bass, other

    private OrtSession session;

    @Override
    public void load(Path modelPath) throws Exception {
        this.session = environment().createSession(modelPath.toString(), MlOptions.base());
        log.info("BS-RoFormer model loaded: {}", modelPath);
    }

    @Override
    public void separate(StereoSource input, StemSink output,
                         BiConsumer<Integer, Integer> progressCallback) throws Exception {
        if (session == null) throw new IllegalStateException("BS-RoFormer model not loaded");

        long totalFrames = Math.max(1, input.totalFrames());
        int nChunks = (int) Math.ceil((double) totalFrames / CHUNK_SAMPLES);

        // Buffers de trabajo reutilizados en todos los chunks (~30 MB en total)
        float[] left = new float[CHUNK_SAMPLES];
        float[] right = new float[CHUNK_SAMPLES];
        float[] specReal = new float[2 * N_BINS * N_FRAMES];
        float[] specImag = new float[2 * N_BINS * N_FRAMES];
        float[] vocalL = new float[CHUNK_SAMPLES];
        float[] vocalR = new float[CHUNK_SAMPLES];
        float[] instrL = new float[CHUNK_SAMPLES];
        float[] instrR = new float[CHUNK_SAMPLES];
        float[] tmp = new float[CHUNK_SAMPLES];

        for (int chunk = 0; chunk < nChunks; chunk++) {
            int len = input.read(left, right);
            if (len <= 0) break;

            // Zero-pad al tamaño fijo del chunk
            Arrays.fill(left, len, CHUNK_SAMPLES, 0f);
            Arrays.fill(right, len, CHUNK_SAMPLES, 0f);

            // STFT -> layout plano [ch][bin][frame]
            packStft(left, specReal, specImag, 0);
            packStft(right, specReal, specImag, 1);

            try (OnnxTensor inReal = OnnxTensor.createTensor(environment(),
                    FloatBuffer.wrap(specReal), new long[]{1, 2, N_BINS, N_FRAMES});
                 OnnxTensor inImag = OnnxTensor.createTensor(environment(),
                         FloatBuffer.wrap(specImag), new long[]{1, 2, N_BINS, N_FRAMES});
                 OrtSession.Result result = session.run(Map.of(
                         "spec_real", inReal,
                         "spec_imag", inImag))) {

                OnnxTensor outRealT = (OnnxTensor) result.get("out_spec_real").orElseThrow();
                OnnxTensor outImagT = (OnnxTensor) result.get("out_spec_imag").orElseThrow();
                FloatBuffer fbReal = outRealT.getFloatBuffer();
                FloatBuffer fbImag = outImagT.getFloatBuffer();

                int plane = N_BINS * N_FRAMES;

                // Vocales = stem 3
                for (int ch = 0; ch < 2; ch++) {
                    int base = STEM_VOCALS * 2 * plane + ch * plane;
                    float[] dst = ch == 0 ? vocalL : vocalR;
                    Stft.inverseFlat(fbReal, fbImag, base, N_BINS, N_FRAMES,
                            N_FFT, HOP_LENGTH, len, dst);
                }

                // Instrumental = suma exacta de drums+bass+other
                Arrays.fill(instrL, 0, len, 0f);
                Arrays.fill(instrR, 0, len, 0f);
                for (int stem : STEMS_INSTRUMENTAL) {
                    for (int ch = 0; ch < 2; ch++) {
                        int base = stem * 2 * plane + ch * plane;
                        Stft.inverseFlat(fbReal, fbImag, base, N_BINS, N_FRAMES,
                                N_FFT, HOP_LENGTH, len, tmp);
                        float[] acc = ch == 0 ? instrL : instrR;
                        for (int i = 0; i < len; i++) {
                            acc[i] += tmp[i];
                        }
                    }
                }
            } // result.close() libera la memoria nativa de las salidas aquí

            output.stems(vocalL, vocalR, instrL, instrR, len);

            log.info("BS-RoFormer chunk {}/{} done", chunk + 1, nChunks);
            MemoryMonitor.log("BS-RoFormer chunk " + (chunk + 1) + "/" + nChunks);
            if (progressCallback != null) {
                progressCallback.accept(chunk + 1, nChunks);
            }
        }
    }

    private void packStft(float[] mono, float[] dstReal, float[] dstImag, int channelIndex) {
        Stft.Spectrum spectrum = Stft.forward(mono, N_FFT, HOP_LENGTH);
        Stft.packChannel(spectrum, channelIndex, dstReal, dstImag);
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
