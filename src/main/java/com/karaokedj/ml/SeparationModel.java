package com.karaokedj.ml;

import com.karaokedj.audio.StereoSource;
import com.karaokedj.audio.StemSink;

import java.nio.file.Path;
import java.util.function.BiConsumer;

/**
 * Un modelo de separación voz/instrumental (Strategy).
 *
 * API de streaming: el audio entra por chunks desde disco ({@link StereoSource})
 * y los stems procesados salen a disco por chunks ({@link StemSink}) — la canción
 * completa nunca reside en RAM.
 */
public interface SeparationModel extends AutoCloseable {

    void load(Path modelPath) throws Exception;

    /**
     * Separa voz e instrumental consumiendo {@code input} y escribiendo en {@code output}.
     *
     * @param progressCallback notifica (bloquesHechos, bloquesTotales); puede ser null
     */
    void separate(StereoSource input, StemSink output, BiConsumer<Integer, Integer> progressCallback)
            throws Exception;

    @Override
    void close();
}
