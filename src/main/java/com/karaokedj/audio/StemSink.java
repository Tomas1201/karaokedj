package com.karaokedj.audio;

/**
 * Destino de stems procesados: se invoca una vez por bloque,
 * escribiendo directamente a disco sin acumular en RAM.
 */
public interface StemSink {

    void stems(float[] vocalL, float[] vocalR,
               float[] instrumentalL, float[] instrumentalR, int len) throws Exception;
}
