package com.karaokedj.lyrics;

import com.karaokedj.ml.WhisperLanguage;
import com.karaokedj.model.LrcLyrics;
import com.karaokedj.model.SongMetadata;
import com.karaokedj.service.AudioSeparationModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orquesta la búsqueda de letras:
 *
 * 1. Karaoke palabra-a-palabra en cada proveedor (en orden @Order).
 * 2. Referencia (synced/plain) para verificación IA: LRCLIB primero, luego el resto.
 * 3. Si nada hay, pipeline IA (Demucs + Whisper) con la referencia encontrada.
 */
@Service
public class LyricsSearchService {

    private static final Logger log = LoggerFactory.getLogger(LyricsSearchService.class);

    private final List<LyricsProvider> providers;
    private final LrclibProvider lrclibProvider;
    private final AiLyricsPipeline aiPipeline;

    @Autowired
    public LyricsSearchService(List<LyricsProvider> providers,
                               LrclibProvider lrclibProvider,
                               AiLyricsPipeline aiPipeline) {
        this.providers = providers.stream()
                .sorted(java.util.Comparator.comparingInt(p ->
                        p.getClass().getAnnotation(org.springframework.core.annotation.Order.class).value()))
                .toList();
        this.lrclibProvider = lrclibProvider;
        this.aiPipeline = aiPipeline;
        log.info("Lyrics providers (orden): {}", this.providers.stream()
                .map(LyricsProvider::name).toList());
    }

    public LrcLyrics search(SongMetadata metadata, com.karaokedj.service.ProgressListener progress,
                            AudioSeparationModel separationEngine, WhisperLanguage language) {
        log.info("Searching lyrics for: {} - {}", metadata.getArtist(), metadata.getTitle());
        if (progress != null) {
            progress.onStep(String.format("Buscando \"%s\" - \"%s\"...",
                    metadata.getTitle(), metadata.getArtist()));
        }

        // === Fase 1: karaoke palabra-a-palabra en todos los proveedores ===
        for (LyricsProvider provider : providers) {
            LrcLyrics enhanced = tryEnhanced(provider, metadata, progress);
            if (enhanced != null) {
                log.info("Enhanced (word-level) lyrics found in {}", provider.name());
                return enhanced;
            }
        }

        // === Fase 2/3: referencia para la verificación IA (LRCLIB tiene prioridad) ===
        LrcLyrics apiReference = findReference(metadata, progress);
        if (apiReference != null) {
            log.info("Reference lyrics found in {}", apiReference.getSource());
        }

        // === Fase 4: pipeline IA (siempre corre si no hubo karaoke en repositorios) ===
        log.info("No enhanced lyrics from repos. Running AI pipeline...");
        if (progress != null) {
            progress.onStep("No hay karaoke en repositorios. Iniciando pipeline de IA...");
        }
        return aiPipeline.process(metadata, apiReference, progress, separationEngine, language);
    }

    private LrcLyrics tryEnhanced(LyricsProvider provider, SongMetadata metadata,
                                  com.karaokedj.service.ProgressListener progress) {
        if (progress != null) {
            progress.onStep("Consultando " + provider.name() + " (karaoke palabra-a-palabra)...");
        }
        LrcLyrics result = provider.fetchEnhanced(metadata);
        if (result != null && progress != null) {
            String source = result.getSource() != null ? result.getSource() : provider.name();
            progress.onStep("Karaoke palabra-a-palabra encontrado: " + source);
        }
        return result;
    }

    private LrcLyrics findReference(SongMetadata metadata,
                                    com.karaokedj.service.ProgressListener progress) {
        // LRCLIB primero (comportamiento histórico), luego el resto de proveedores
        for (LyricsProvider provider : referenceOrdered()) {
            if (progress != null) {
                progress.onStep("Buscando referencia sincronizada en " + provider.name() + "...");
            }
            LrcLyrics ref = provider.fetchReference(metadata);

            if (ref == null || (!ref.hasSyncedLyrics() && !ref.hasPlainLyrics())) {
                if (progress != null) progress.onDetail("Sin resultados en " + provider.name());
                continue;
            }

            String kind = ref.hasSyncedLyrics()
                    ? "letra sincronizada por línea"
                    : "letra plana (sin sincronizar)";
            log.info("{} reference ({}) for AI", provider.name(), kind);
            if (progress != null) progress.onDetail("Referencia encontrada: " + kind);
            return ref;
        }
        return null;
    }

    private List<LyricsProvider> referenceOrdered() {
        List<LyricsProvider> ordered = new java.util.ArrayList<>();
        ordered.add(lrclibProvider);
        for (LyricsProvider p : providers) {
            if (p != lrclibProvider && !(p instanceof KaralyrProvider)) {
                ordered.add(p); // Karalyr no aporta referencia
            }
        }
        return ordered;
    }
}
