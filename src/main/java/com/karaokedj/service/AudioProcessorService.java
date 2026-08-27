package com.karaokedj.service;

import com.karaokedj.model.StemSeparationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AudioProcessorService {

    private static final Logger log = LoggerFactory.getLogger(AudioProcessorService.class);

    /** Verifica que FFmpeg esté disponible en el PATH antes de iniciar pipelines largos. */
    public void verifyFfmpegAvailable() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-version");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("FFmpeg no encontrado. Instale FFmpeg y asegúrese de que esté en el PATH.");
        }
    }

    public Path extractVocalForWhisper(Path vocalWav, Path outputDir) throws IOException, InterruptedException {
        Path output = outputDir.resolve("vocals_16k.wav");
        List<String> cmd = List.of(
                "ffmpeg", "-y", "-i", vocalWav.toString(),
                "-ar", "16000", "-ac", "1", "-f", "wav",
                output.toString()
        );
        runCommand(cmd, "Extract vocal for Whisper (16kHz mono)");
        return output;
    }

    public Path extractAudioAsWav441k(Path audioPath, Path outputDir) throws IOException, InterruptedException {
        Path output = outputDir.resolve("audio_441k.wav");
        List<String> cmd = List.of(
                "ffmpeg", "-y", "-i", audioPath.toString(),
                "-ar", "44100", "-ac", "2", "-f", "wav",
                output.toString()
        );
        runCommand(cmd, "Extract audio as 44.1kHz stereo WAV");
        return output;
    }

    public void mixStemsToInstrumental(List<Path> stemPaths, Path output) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");
        for (Path stem : stemPaths) {
            cmd.add("-i");
            cmd.add(stem.toString());
        }
        StringBuilder filter = new StringBuilder();
        for (int i = 0; i < stemPaths.size(); i++) {
            if (i > 0) filter.append("+");
            filter.append("[").append(i).append(":a]");
        }
        cmd.add("-filter_complex");
        cmd.add(filter.toString() + "amix=inputs=" + stemPaths.size() + ":duration=longest");
        cmd.add("-f");
        cmd.add("wav");
        cmd.add(output.toString());

        runCommand(cmd, "Mix stems to instrumental");
    }

    private void runCommand(List<String> command, String description) throws IOException, InterruptedException {
        log.info("{}: {}", description, String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (var is = process.getInputStream()) {
            output = new String(is.readAllBytes());
        }

        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException(description + " timed out after 120s");
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            log.error("{} failed (exit {}): {}", description, exitCode,
                    output.substring(Math.max(0, output.length() - 500)));
            throw new IOException(description + " failed with exit code " + exitCode);
        }

        log.info("{} completed successfully", description);
    }
}
