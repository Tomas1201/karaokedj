package com.karaokedj.service;

import com.karaokedj.model.SongMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Service
public class SongRecognitionService {

    private static final Logger log = LoggerFactory.getLogger(SongRecognitionService.class);

    private static final String ACOUSTID_BASE_URL = "https://api.acoustid.org/v2/lookup";
    private static final String DEFAULT_CLIENT = "";

    private final ModelDownloadService modelDownloadService;
    private final ObjectMapper mapper = new ObjectMapper();

    private String clientId;

    public String getClientId() {
        return clientId;
    }

    public SongRecognitionService(ModelDownloadService modelDownloadService) {
        this.modelDownloadService = modelDownloadService;
    }

    @PostConstruct
    private void init() {
        // Resolver cliente: system prop > Preferences override > constante default
        String sys = System.getProperty("acoustid.client");
        if (sys != null && !sys.trim().isEmpty()) {
            this.clientId = sys;
        } else {
            // TODO: Preferences userNodeForPackage override if desired
            this.clientId = DEFAULT_CLIENT;
        }
        if (this.clientId == null || this.clientId.isBlank()) {
            log.warn("Sin clave AcoustID configurada; reconocimiento devolverá error 'clave no configurada'. Regístrate en acoustid.org y pasa la clave vía -Dacoustid.client=XXX oPreferences.");
        } else {
            log.info("Cliente AcoustID configurado");
        }
    }

    /** Intenta reconocer la canción. Devuelve {@link Optional#empty} si no hay suficiente información. */
    public Optional<RecognitionResult> recognize(Path audioFile) {
        if (clientId == null || clientId.isBlank()) {
            log.error("Clave AcoustID no configurada; imposible reconocer.");
            return Optional.empty();
        }

        try {
            String fpcalcPath = modelDownloadService.ensureFpcalc();
            ProcessBuilder pb = new ProcessBuilder(fpcalcPath, "-json", "-length", "120", audioFile.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String fpOutput = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            proc.waitFor();

            JsonNode fpJson = mapper.readTree(fpOutput);
            if (!fpJson.has("fingerprint")) {
                log.warn("fpcalc no devolvió huella para {}", audioFile);
                return Optional.empty();
            }
            String fingerprint = fpJson.get("fingerprint").asText();
            int duration = fpJson.has("duration") ? fpJson.get("duration").asInt() : 120;

            String url = ACOUSTID_BASE_URL + "?client=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&meta=recordings+releasegroups+compress"
                    + "&duration=" + duration
                    + "&fingerprint=" + URLEncoder.encode(fingerprint, StandardCharsets.UTF_8);

            String response = java.net.http.HttpClient.newHttpClient()
                    .send(java.net.http.HttpRequest.newBuilder(URI.create(url))
                            .GET()
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString()).body();

            JsonNode root = mapper.readTree(response);
            if (!"ok".equals(root.path("status").asText())) {
                log.error("Error AcoustID: {}", root);
                return Optional.empty();
            }

            RecognitionResult best = parseBestRecording(root);
            if (best != null) {
                return Optional.of(best);
            }
            return Optional.empty();

        } catch (IOException e) {
            log.error("Error de I/O durante reconocimiento", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Reconocimiento interrumpido", e);
        } catch (Exception e) {
            log.error("Error inesperado en reconocimiento", e);
        }
        return Optional.empty();
    }

    /**
     * Parsea la respuesta AcoustID y devuelve el mejor RecognitionResult, o null si no hay coincidencia.
     * Package-private para tests.
     */
    RecognitionResult parseBestRecording(JsonNode root) {
        var results = root.path("results");
        if (!results.isArray() || results.size() == 0) return null;

        // Los results vienen ordenados por score descendente; tomamos el primero válido
        for (var r : results) {
            var recording = r.path("recording");
            if (recording.isMissingNode()) continue;

            String title = nullSafe(recording.path("title"));
            if (title == null || title.isBlank()) continue;

            String artists = nullSafe(recording.path("artists"));
            String artistJoined = artists != null ? artists.replaceAll("\\|.*", "").trim() : "";
            if (artistJoined.isBlank()) continue; // exigimos artista no vacío

            String album = nullSafe(root.path("release") == null ? null : root.path("release").path("title"));
            // Algunas respuestas traen album en releases[0].title; por ahora intentamos root.release.title

            double score = 0.0;
            var scoreNode = r.path("score");
            if (scoreNode.isNumber()) score = scoreNode.asDouble();

            return new RecognitionResult(title, artistJoined, album.isBlank() ? null : album, score);
        }
        return null;
    }

    private static String nullSafe(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText();
    }

    /** Wrapper inmutable para el resultado del reconocimiento. */
    public record RecognitionResult(String title, String artist, String album, double score) {}
}