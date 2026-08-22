package com.karaokedj.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Lector incremental de WAV estéreo 44.1kHz PCM16.
 *
 * Permite procesar canciones completas sin cargarlas en RAM: cada llamada a
 * {@link #read(float[], float[])} trae solo el siguiente bloque de muestras.
 */
public final class WavChunkReader implements AutoCloseable, StereoSource {

    private final AudioInputStream ais;
    private final long totalFrames;
    private byte[] byteBuf;

    private WavChunkReader(AudioInputStream ais, long totalFrames) {
        this.ais = ais;
        this.totalFrames = totalFrames;
    }

    /** Abre un WAV convertido (44.1kHz estéreo PCM16, p.ej. salida de FFmpeg). */
    public static WavChunkReader open44kStereo(Path wavPath) throws Exception {
        AudioInputStream ais = AudioSystem.getAudioInputStream(wavPath.toFile());
        AudioFormat format = ais.getFormat();

        if (format.getSampleRate() != 44100f || format.getChannels() != 2
                || format.getSampleSizeInBits() != 16
                || format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            AudioFormat target = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, 2, 4, 44100f, false);
            ais = AudioSystem.getAudioInputStream(target, ais);
            format = target;
        }

        long frames = ais.getFrameLength();
        if (frames <= 0) {
            // Fallback: deducir del tamaño del archivo menos cabecera estándar
            frames = Math.max(0, (java.nio.file.Files.size(wavPath) - 44) / 4);
        }
        return new WavChunkReader(ais, frames);
    }

    public long totalFrames() {
        return totalFrames;
    }

    /**
     * Lee hasta dst.length muestras por canal.
     * @return muestras leídas por canal; 0 si fin de archivo
     */
    public int read(float[] left, float[] right) throws IOException {
        int want = Math.min(left.length, right.length);
        int bytesNeeded = want * 4; // estéreo * 2 bytes

        if (byteBuf == null || byteBuf.length < bytesNeeded) {
            byteBuf = new byte[bytesNeeded];
        }

        int off = 0;
        while (off < bytesNeeded) {
            int n = ais.read(byteBuf, off, bytesNeeded - off);
            if (n == -1) break;
            off += n;
        }
        int frames = off / 4;

        for (int i = 0; i < frames; i++) {
            short l = (short) ((byteBuf[i * 4] & 0xFF) | (byteBuf[i * 4 + 1] << 8));
            short r = (short) ((byteBuf[i * 4 + 2] & 0xFF) | (byteBuf[i * 4 + 3] << 8));
            left[i] = l / 32768.0f;
            right[i] = r / 32768.0f;
        }
        return frames;
    }

    @Override
    public void close() throws IOException {
        ais.close();
    }
}
