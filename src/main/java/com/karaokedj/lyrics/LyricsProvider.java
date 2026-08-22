package com.karaokedj.lyrics;

import com.karaokedj.model.LrcLyrics;
import com.karaokedj.model.SongMetadata;

/**
 * Un repositorio de letras (Strategy).
 *
 * Cada proveedor es dueño de sus URLs y del parseo de sus respuestas.
 * Agregar un repositorio nuevo = crear una clase que implemente esta interfaz
 * y registrarla como bean; {@link LyricsSearchService} la descubre automáticamente.
 */
public interface LyricsProvider {

    /** Nombre corto para logs y mensajes de progreso. */
    String name();

    /**
     * Karaoke palabra-a-palabra si está disponible en este repositorio;
     * null si no hay o si falla la consulta.
     */
    LrcLyrics fetchEnhanced(SongMetadata metadata);

    /**
     * Letra de referencia (sincronizada por línea o texto plano) para verificar/corregir
     * la transcripción IA; null si no hay. No todos los proveedores la ofrecen.
     */
    default LrcLyrics fetchReference(SongMetadata metadata) {
        return null;
    }
}
