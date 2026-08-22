package com.karaokedj.service;

import com.karaokedj.audio.WavChunkReader;
import com.karaokedj.audio.WavChunkWriter;
import com.karaokedj.audio.StemSink;
import com.karaokedj.ml.SeparationModel;
import com.karaokedj.model.StemSeparationResult;
import com.karaokedj.util.MemoryMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Separación de voz e instrumentos con streaming a disco:
 * lee el WAV convertido por chunks, alimenta al modelo elegido y escribe
 * los stems incrementalmente — la canción completa nunca reside en RAM.
 */
@Service
public class StemSeparatorService {

    private static final Logger log = LoggerFactory.getLogger(StemSeparatorService.class);

    private static final int SAMPLE_RATE = 44100;

    @Autowired
    private ModelDownloadService modelDownloadService;

    @Autowired
    private AudioProcessorService audioProcessorService;

    public StemSeparationResult separate(Path audioFile, Path outputDir,
                                         ModelDownloadService.ProgressCallback progress,
                                         ProgressListener listener,
                                         AudioSeparationModel engine) throws Exception {
        Files.createDirectories(outputDir);

        log.info("Starting stem separation ({}) for: {}", engine.displayName(), audioFile.getFileName());
        MemoryMonitor.log("Before stem separation");

        if (listener != null) listener.onStep("Convirtiendo audio a WAV 44.1kHz...");
        Path wavPath = audioProcessorService.extractAudioAsWav441k(audioFile, outputDir);

        if (listener != null) listener.onStep("Verificando modelo " + engine.displayName() + "...");
        Path modelPath = switch (engine) {
            case DEMUCS -> modelDownloadService.ensureDemucsModel(progress);
            case BS_ROFORMER -> modelDownloadService.ensureBsRoformerModel(progress);
        };

        Path vocalsPath = outputDir.resolve("vocal.wav");
        Path instrumentalPath = outputDir.resolve("instrumental.wav");

        try (WavChunkReader reader = WavChunkReader.open44kStereo(wavPath);
             WavChunkWriter vocalWriter = new WavChunkWriter(vocalsPath, 2, SAMPLE_RATE, reader.totalFrames());
             WavChunkWriter instrumentalWriter = new WavChunkWriter(instrumentalPath, 2, SAMPLE_RATE, reader.totalFrames());
             SeparationModel model = engine.createModel()) {

            double duration = reader.totalFrames() / (double) SAMPLE_RATE;
            log.info("Audio: {} frames ({}s)", reader.totalFrames(), String.format("%.1f", duration));
            if (listener != null) {
                listener.onDetail(String.format("Audio cargado: %.1f segundos (streaming)", duration));
            }
            if (progress != null) progress.onProgress(0);

            if (listener != null) listener.onStep("Cargando modelo " + engine.displayName() + "...");
            model.load(modelPath);
            MemoryMonitor.log("After loading " + engine.displayName());

            if (listener != null) listener.onStep("Separando voz e instrumentos (" + engine.displayName() + ")...");

            StemSink sink = (vL, vR, iL, iR, len) -> {
                vocalWriter.writeStereoPair(vL, vR, len);
                instrumentalWriter.writeStereoPair(iL, iR, len);
            };

            model.separate(reader, sink, (done, total) -> {
                log.info("{} block {}/{} done", engine.displayName(), done, total);
                if (listener != null) {
                    listener.onDetail(String.format("Separando stems: bloque %d de %d", done, total));
                    listener.onFraction((double) done / total);
                }
            });
            MemoryMonitor.log("After " + engine.displayName() + " inference");

            if (listener != null) listener.onStep("Voz separada: vocal.wav + instrumental.wav");
            log.info("Stem separation complete: vocals={}, instrumental={}", vocalsPath, instrumentalPath);

            return new StemSeparationResult(vocalsPath, instrumentalPath, duration);
        } finally {
            System.gc();
            MemoryMonitor.log("After closing separation session");
        }
    }
}
