package com.karaokedj.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Códec PCM16 WAV del proyecto. Reemplaza las 3 implementaciones manuales
 * (lectura mono, lectura estéreo y escritura) dispersas entre servicios y ml/.
 */
public final class WavIO {

    private static final Logger log = LoggerFactory.getLogger(WavIO.class);

    private WavIO() {
    }

    /** Lee un WAV y lo convierte a float[] mono PCM16 al sample rate indicado. */
    public static float[] readMonoFloat(Path wavPath, float targetSampleRate) throws Exception {
        AudioInputStream ais = AudioSystem.getAudioInputStream(wavPath.toFile());

        AudioFormat targetFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                targetSampleRate,
                16,
                1,
                2,
                targetSampleRate,
                false
        );
        ais = AudioSystem.getAudioInputStream(targetFormat, ais);

        byte[] allBytes = ais.readAllBytes();
        ais.close();

        int nSamples = allBytes.length / 2;
        float[] audio = new float[nSamples];
        for (int i = 0; i < nSamples; i++) {
            short sample = (short) ((allBytes[i * 2] & 0xFF) | (allBytes[i * 2 + 1] << 8));
            audio[i] = sample / 32768.0f;
        }
        return audio;
    }

    /**
     * Lee un WAV como estéreo intercalado [L,R,L,R...] en floats.
     * Convierte a PCM16 firmado si hace falta; mono se duplica a ambos canales.
     * El caller conoce el sample rate del archivo por separado.
     */
    public static float[] readStereoInterleaved(Path wavPath) throws Exception {
        AudioInputStream ais = AudioSystem.getAudioInputStream(wavPath.toFile());
        AudioFormat format = ais.getFormat();

        if (format.getSampleSizeInBits() != 16 || format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            AudioFormat targetFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    format.getSampleRate(),
                    16,
                    format.getChannels(),
                    format.getChannels() * 2,
                    format.getSampleRate(),
                    false
            );
            ais = AudioSystem.getAudioInputStream(targetFormat, ais);
            format = targetFormat;
        }

        byte[] allBytes = ais.readAllBytes();
        ais.close();

        int nSamples = allBytes.length / 2;
        float[] audio = new float[nSamples];
        for (int i = 0; i < nSamples; i++) {
            short sample = (short) ((allBytes[i * 2] & 0xFF) | (allBytes[i * 2 + 1] << 8));
            audio[i] = sample / 32768.0f;
        }

        if (format.getChannels() == 1) {
            float[] stereo = new float[nSamples * 2];
            for (int i = 0; i < nSamples; i++) {
                stereo[i * 2] = audio[i];
                stereo[i * 2 + 1] = audio[i];
            }
            return stereo;
        }

        return audio;
    }

    /** Escribe canales planos float [-1..1] como WAV PCM16 intercalado de N canales. */
    public static void writeWav(float[][] planarChannels, int sampleRate, Path outputPath) throws IOException {
        int nSamples = planarChannels[0].length;
        int nChannels = planarChannels.length;
        int bitsPerSample = 16;
        int byteRate = sampleRate * nChannels * bitsPerSample / 8;
        int blockAlign = nChannels * bitsPerSample / 8;
        int dataSize = nSamples * blockAlign;

        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outputPath.toFile()), 65536)) {
            bos.write(buildHeader(nChannels, sampleRate, bitsPerSample, dataSize));

            byte[] sampleBuf = new byte[nChannels * 2];
            for (int i = 0; i < nSamples; i++) {
                int bufIdx = 0;
                for (int ch = 0; ch < nChannels; ch++) {
                    float sample = Math.max(-1.0f, Math.min(1.0f, planarChannels[ch][i]));
                    short s = (short) (sample * 32767);
                    sampleBuf[bufIdx++] = (byte) (s & 0xFF);
                    sampleBuf[bufIdx++] = (byte) ((s >> 8) & 0xFF);
                }
                bos.write(sampleBuf);
            }
        }

        log.info("Saved WAV: {} ({} samples, {}Hz)", outputPath, nSamples, sampleRate);
    }

    static byte[] buildHeader(int nChannels, int sampleRate, int bitsPerSample, int dataSize) {
        int byteRate = sampleRate * nChannels * bitsPerSample / 8;
        int blockAlign = nChannels * bitsPerSample / 8;

        byte[] header = new byte[44];
        writeString(header, 0, "RIFF");
        writeInt(header, 4, 36 + dataSize);
        writeString(header, 8, "WAVE");
        writeString(header, 12, "fmt ");
        writeInt(header, 16, 16);
        writeShort(header, 20, (short) 1);
        writeShort(header, 22, (short) nChannels);
        writeInt(header, 24, sampleRate);
        writeInt(header, 28, byteRate);
        writeShort(header, 32, (short) blockAlign);
        writeShort(header, 34, (short) bitsPerSample);
        writeString(header, 36, "data");
        writeInt(header, 40, dataSize);
        return header;
    }

    static void writeString(byte[] data, int offset, String s) {
        for (int i = 0; i < s.length(); i++) {
            data[offset + i] = (byte) s.charAt(i);
        }
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeShort(byte[] data, int offset, short value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
