package com.karaokedj.lyrics;

import com.karaokedj.ml.WhisperLanguage;
import com.karaokedj.model.LrcLyrics;
import com.karaokedj.model.SongMetadata;
import com.karaokedj.model.WordTiming;
import com.karaokedj.service.AudioProcessorService;
import com.karaokedj.service.AudioSeparationModel;
import com.karaokedj.service.LrcVerificationService;
import com.karaokedj.service.ModelDownloadService;
import com.karaokedj.service.ProgressListener;
import com.karaokedj.service.StemSeparatorService;
import com.karaokedj.service.TranscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Pipeline IA: Demucs (separar voz) + Whisper (transcribir) → Enhanced LRC verificado.
 * También el modo "vocal-only" cuando el usuario aporta un WAV de voz ya aislado.
 */
@Service
public class AiLyricsPipeline {

    private static final Logger log = LoggerFactory.getLogger(AiLyricsPipeline.class);

    private static final String SOURCE_AI = "IA (Demucs + Whisper)";
    private static final String SOURCE_VOCAL_ONLY = "Whisper (vocal directo)";

    @Autowired
    private StemSeparatorService stemSeparatorService;

    @Autowired
    private TranscriptionService transcriptionService;

    @Autowired
    private LrcVerificationService lrcVerificationService;

    @Autowired
    private AudioProcessorService audioProcessorService;

    @Autowired
    private ModelDownloadService modelDownloadService;

    /** Descarga el alineador CTC si falta; nunca interrumpe el pipeline. */
    private void ensureCtcAligner(ProgressListener progress,
                                  ModelDownloadService.ProgressCallback downloadCb) {
        try {
            if (progress != null && modelDownloadService.alignerModelIfPresent() == null) {
                progress.onStep("Verificando alineador CTC...");
            }
            modelDownloadService.ensureAlignerAssets(downloadCb);
        } catch (Exception e) {
            log.warn("Alineador CTC no disponible ({}): se usará alineación aproximada", e.getMessage());
        }
    }

    /** Pipeline completo sobre la canción seleccionada. */
    public LrcLyrics process(SongMetadata metadata, LrcLyrics apiLyrics, ProgressListener progress,
                             AudioSeparationModel separationEngine, WhisperLanguage language) {
        try {
            Path processingDir = processingDirFor(metadata);
            Files.createDirectories(processingDir);

            if (progress != null) progress.onStep("Verificando FFmpeg...");
            audioProcessorService.verifyFfmpegAvailable();

            ensureCtcAligner(progress,
                    downloadListener(progress, "Descargando alineador CTC (~340MB)..."));

            if (progress != null) progress.onStep("Separando voz e instrumentos...");
            var separation = stemSeparatorService.separate(
                    metadata.getFilePath(), processingDir,
                    downloadListener(progress, "Descargando modelo " + separationEngine.displayName() + "..."),
                    progress,
                    separationEngine);

            if (progress != null) progress.onStep("Transcribiendo vocals con Whisper...");
            List<WordTiming> words = transcribe(separation.getVocalPath(), processingDir, progress, language);

            log.info("AI pipeline complete for: {} - {}", metadata.getArtist(), metadata.getTitle());
            return buildResult(metadata, words, SOURCE_AI, apiLyrics);

        } catch (OutOfMemoryError e) {
            log.error("Out of memory during AI pipeline", e);
            System.gc();
            throw new RuntimeException("Memoria insuficiente para procesar con IA. "
                    + "Intente con una canción más corta o cierre otras aplicaciones.", e);
        } catch (Exception e) {
            log.error("AI pipeline failed: {}", e.getMessage(), e);
            throw new RuntimeException("Pipeline IA falló: " + e.getMessage(), e);
        }
    }

    /** Pipeline directo sobre un WAV de voz ya aislado (sin Demucs). */
    public LrcLyrics processVocalOnly(Path vocalWavPath, SongMetadata metadata, ProgressListener progress,
                                      WhisperLanguage language) {
        log.info("Vocal-only processing for: {} - {} ({})",
                metadata.getArtist(), metadata.getTitle(), vocalWavPath);
        try {
            Path processingDir = vocalWavPath.getParent();

            if (progress != null) progress.onStep("Verificando FFmpeg...");
            audioProcessorService.verifyFfmpegAvailable();

            ensureCtcAligner(progress,
                    downloadListener(progress, "Descargando alineador CTC (~340MB)..."));

            if (progress != null) progress.onStep("Transcribiendo vocal con Whisper...");
            List<WordTiming> words = transcribe(vocalWavPath, processingDir, progress, language);

            log.info("Vocal-only pipeline complete for: {} - {}", metadata.getArtist(), metadata.getTitle());
            return buildResult(metadata, words, SOURCE_VOCAL_ONLY, null);

        } catch (OutOfMemoryError e) {
            log.error("Out of memory during vocal-only pipeline", e);
            System.gc();
            throw new RuntimeException("Memoria insuficiente para transcribir el vocal. "
                    + "Intente con un archivo más corto o cierre otras aplicaciones.", e);
        } catch (Exception e) {
            log.error("Vocal-only pipeline failed: {}", e.getMessage(), e);
            throw new RuntimeException("Transcripción falló: " + e.getMessage(), e);
        }
    }

    private List<WordTiming> transcribe(Path vocalPath, Path outputDir, ProgressListener progress,
                                        WhisperLanguage language)
            throws Exception {
        return transcriptionService.transcribe(
                vocalPath, outputDir,
                downloadListener(progress, "Descargando modelo Whisper..."),
                progress,
                language);
    }

    private ModelDownloadService.ProgressCallback downloadListener(ProgressListener progress,
                                                                   String message) {
        if (progress == null) return null;
        return bytes -> progress.onProgress(message, bytes);
    }

    /** Construye el resultado común a ambos modos (antes duplicado en dos métodos). */
    private LrcLyrics buildResult(SongMetadata metadata, List<WordTiming> whisperWords,
                                  String source, LrcLyrics apiLyrics) {
        String verifiedLrc = lrcVerificationService.verifyAndCorrect(whisperWords, apiLyrics);

        LrcLyrics result = new LrcLyrics();
        result.setTrackName(metadata.getTitle());
        result.setArtistName(metadata.getArtist());
        result.setAlbumName(metadata.getAlbum());
        result.setDuration((int) metadata.getDurationSeconds());
        result.setEnhancedLyrics(verifiedLrc);
        result.setWordSynced(true);
        result.setSyncedFromRepo(false);
        result.setSource(source);

        if (apiLyrics != null) {
            if (apiLyrics.hasPlainLyrics()) result.setPlainLyrics(apiLyrics.getPlainLyrics());
            if (apiLyrics.hasSyncedLyrics()) result.setSyncedLyrics(apiLyrics.getSyncedLyrics());
        }
        return result;
    }

    private Path processingDirFor(SongMetadata metadata) {
        Path audioDir = metadata.getFilePath().getParent();
        return audioDir.resolve(".karaokedj_" + sanitizeFilename(metadata.getTitle()));
    }

    static String sanitizeFilename(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_").replaceAll("_+", "_");
    }
}
