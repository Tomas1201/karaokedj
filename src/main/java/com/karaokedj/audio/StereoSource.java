package com.karaokedj.audio;

/** Fuente de audio estéreo 44.1kHz entregada por bloques (streaming desde disco). */
public interface StereoSource {

    /** Total de frames disponibles (para calcular progreso); puede ser aproximado. */
    long totalFrames();

    /**
     * Llena left/right con las siguientes muestras.
     * @return muestras leídas por canal; 0 = fin de archivo
     */
    int read(float[] left, float[] right) throws Exception;
}
