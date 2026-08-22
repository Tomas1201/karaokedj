package com.karaokedj.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ModelDownloadService {

    private static final Logger log = LoggerFactory.getLogger(ModelDownloadService.class);

    private static final Map<String, ModelInfo> MODELS = Map.of(
            "demucs", new ModelInfo(
                    "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/main/htdemucs_fp16weights.onnx",
                    "htdemucs_fp16weights.onnx",
                    "demucs"
            ),
            "bs_roformer", new ModelInfo(
                    "https://huggingface.co/silverdaw/bs-roformer-rhythm-onnx/resolve/main/bs_roformer_4stem_rhythm_fp16.onnx",
                    "bs_roformer_4stem_rhythm_fp16.onnx",
                    "bs_roformer"
            ),
            "mms_aligner", new ModelInfo(
                    "https://huggingface.co/romara-labs/mms-300m-1130-forced-aligner-ONNX/resolve/main/model.q8.onnx",
                    "model.q8.onnx",
                    "mms_aligner"
            ),
            "mms_aligner_vocab", new ModelInfo(
                    "https://huggingface.co/romara-labs/mms-300m-1130-forced-aligner-ONNX/resolve/main/vocab.json",
                    "vocab.json",
                    "mms_aligner"
            ),
            "whisper_encoder", new ModelInfo(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2",
                    "sherpa-onnx-whisper-small.tar.bz2",
                    "whisper"
            )
    );

    private final Path modelsDir;

    public ModelDownloadService() {
        String userHome = System.getProperty("user.home");
        this.modelsDir = Path.of(userHome, ".karaokedj", "models");
        try {
            Files.createDirectories(modelsDir);
        } catch (IOException e) {
            log.error("Failed to create models directory: {}", e.getMessage());
        }
    }

    public Path getModelPath(String modelName) {
        ModelInfo info = MODELS.get(modelName);
        if (info == null) throw new IllegalArgumentException("Unknown model: " + modelName);

        Path modelDir = modelsDir.resolve(info.subDir);
        return modelDir.resolve(info.fileName);
    }

    public boolean isModelDownloaded(String modelName) {
        return Files.exists(getModelPath(modelName));
    }

    public Path ensureModel(String modelName, ProgressCallback progress) throws IOException {
        Path modelPath = getModelPath(modelName);
        if (Files.exists(modelPath)) {
            log.info("Model '{}' already downloaded at {}", modelName, modelPath);
            return modelPath;
        }

        ModelInfo info = MODELS.get(modelName);
        if (info == null) throw new IllegalArgumentException("Unknown model: " + modelName);

        Path modelDir = modelsDir.resolve(info.subDir);
        Files.createDirectories(modelDir);

        Path tempPath = modelDir.resolve(info.fileName + ".downloading");
        try {
            log.info("Downloading model '{}' from {}", modelName, info.url);
            downloadFile(URI.create(info.url).toURL(), tempPath, progress);
            Files.move(tempPath, modelPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Model '{}' downloaded to {}", modelName, modelPath);
            return modelPath;
        } catch (Exception e) {
            Files.deleteIfExists(tempPath);
            throw e;
        }
    }

    private void downloadFile(URL url, Path target, ProgressCallback progress) throws IOException {
        try (InputStream in = url.openStream();
             java.io.FileOutputStream out = new java.io.FileOutputStream(target.toFile())) {
            long totalBytes = 0;
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
                if (progress != null) {
                    progress.onProgress(totalBytes);
                }
            }
        }
    }

    public Path ensureWhisperModels(ProgressCallback progress) throws IOException {
        Path tarPath = ensureModel("whisper_encoder", progress);

        Path whisperDir = modelsDir.resolve("whisper");
        if (Files.exists(whisperDir.resolve("small-encoder.onnx"))) {
            log.info("Whisper models already extracted");
            return whisperDir;
        }

        log.info("Extracting Whisper models from {}", tarPath);
        ProcessBuilder pb = new ProcessBuilder(
                "tar", "xjf", tarPath.toString(),
                "-C", modelsDir.resolve("whisper").toString(),
                "--strip-components=1"
        );
        pb.directory(modelsDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                throw new IOException("tar extraction failed (exit " + exitCode + "): " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Extraction interrupted", e);
        }

        log.info("Whisper models extracted to {}", whisperDir);
        return whisperDir;
    }

    public Path ensureDemucsModel(ProgressCallback progress) throws IOException {
        return ensureModel("demucs", progress);
    }

    public Path ensureBsRoformerModel(ProgressCallback progress) throws IOException {
        return ensureModel("bs_roformer", progress);
    }

    public Path ensureAlignerModel(ProgressCallback progress) throws IOException {
        return ensureModel("mms_aligner", progress);
    }

    public Path ensureAlignerVocab(ProgressCallback progress) throws IOException {
        return ensureModel("mms_aligner_vocab", progress);
    }

    public void ensureAlignerAssets(ProgressCallback progress) throws IOException {
        ensureAlignerModel(progress);
        ensureAlignerVocab(progress);
    }

    /** Ruta del alineador solo si ya está descargado; null si no. */
    public Path alignerModelIfPresent() {
        try {
            Path p = getModelPath("mms_aligner");
            return Files.exists(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    public Path alignerVocabIfPresent() {
        try {
            Path p = getModelPath("mms_aligner_vocab");
            return Files.exists(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void cleanTempFiles() {
        try {
            Files.walk(modelsDir)
                    .filter(p -> p.toString().endsWith(".downloading"))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    public interface ProgressCallback {
        void onProgress(long bytesDownloaded);
    }

    private record ModelInfo(String url, String fileName, String subDir) {}
}
