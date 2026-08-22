package com.karaokedj.audio;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * Escritor incremental de WAV PCM16: los stems procesados van directo a disco
 * chunk a chunk, sin acumular la canción completa en RAM.
 *
 * Si se conoce el total de frames de antemano la cabecera se escribe correcta
 * desde el inicio; si no, se escribe un placeholder y se parchea al cerrar.
 */
public final class WavChunkWriter implements AutoCloseable {

    private static final int BITS_PER_SAMPLE = 16;

    private final Path path;
    private final int channels;
    private final int sampleRate;
    private final BufferedOutputStream out;
    private final boolean headerNeedsPatch;
    private long framesWritten;

    public WavChunkWriter(Path path, int channels, int sampleRate, long totalFrames) throws IOException {
        this.path = path;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.out = new BufferedOutputStream(new FileOutputStream(path.toFile()), 65536);

        this.headerNeedsPatch = totalFrames < 0;
        long frames = headerNeedsPatch ? 0 : totalFrames;
        int dataSize = (int) Math.min(frames * channels * BITS_PER_SAMPLE / 8L, Integer.MAX_VALUE);
        out.write(WavIO.buildHeader(channels, sampleRate, BITS_PER_SAMPLE, dataSize));
    }

    /** Escribe {@code len} muestras de canales planos (intercala + PCM16). */
    public void write(float[][] planarChannels, int len) throws IOException {
        byte[] buf = new byte[planarChannels.length * 2];
        for (int i = 0; i < len; i++) {
            int b = 0;
            for (float[] channel : planarChannels) {
                float sample = Math.max(-1.0f, Math.min(1.0f, channel[i]));
                short s = (short) (sample * 32767);
                buf[b++] = (byte) (s & 0xFF);
                buf[b++] = (byte) ((s >> 8) & 0xFF);
            }
            out.write(buf);
        }
        framesWritten += len;
    }

    /** Variante sin array contenedor para el par estéreo habitual. */
    public void writeStereoPair(float[] left, float[] right, int len) throws IOException {
        byte[] buf = new byte[4];
        for (int i = 0; i < len; i++) {
            short l = (short) (Math.max(-1.0f, Math.min(1.0f, left[i])) * 32767);
            short r = (short) (Math.max(-1.0f, Math.min(1.0f, right[i])) * 32767);
            buf[0] = (byte) (l & 0xFF);
            buf[1] = (byte) ((l >> 8) & 0xFF);
            buf[2] = (byte) (r & 0xFF);
            buf[3] = (byte) ((r >> 8) & 0xFF);
            out.write(buf);
        }
        framesWritten += len;
    }

    public long framesWritten() {
        return framesWritten;
    }

    @Override
    public void close() throws IOException {
        out.close();
        if (headerNeedsPatch) {
            int dataSize = (int) (framesWritten * channels * BITS_PER_SAMPLE / 8);
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw")) {
                raf.seek(4);
                raf.write(leInt(36 + dataSize));
                raf.seek(40);
                raf.write(leInt(dataSize));
            }
        }
    }

    private static byte[] leInt(int value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
        };
    }
}
