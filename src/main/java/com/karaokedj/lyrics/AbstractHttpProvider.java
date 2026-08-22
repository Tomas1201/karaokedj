package com.karaokedj.lyrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Infraestructura HTTP común a todos los proveedores de letras:
 * cliente con timeouts y redirects, User-Agent identificado, manejo de estados
 * (404 = sin resultados, 429 = rate limit) y encoding de parámetros.
 */
public abstract class AbstractHttpProvider {

    protected static final String USER_AGENT =
            "Karaokedj/1.0 (https://github.com/Tomas1201/karaokedj)";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient http;

    protected AbstractHttpProvider() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * GET que devuelve el cuerpo como texto; null si no hay contenido utilizable.
     * 404 se considera "sin resultados" (normal), otros códigos se registran como advertencia.
     */
    protected String getJson(String url, String repoName) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        return switch (response.statusCode()) {
            case 200 -> response.body();
            case 404 -> {
                log.info("{}: No lyrics found (404)", repoName);
                yield null;
            }
            case 429 -> {
                log.warn("{}: Rate limited", repoName);
                Thread.sleep(1000);
                yield null;
            }
            default -> {
                log.warn("{}: Returned status {}", repoName, response.statusCode());
                yield null;
            }
        };
    }

    /** Agrega el parámetro solo cuando el valor es significativo. */
    protected static String appendIfPresent(String url, String param, String value) {
        if (value != null && !value.isBlank()) {
            url += "&" + param + "=" + urlEncode(value);
        }
        return url;
    }

    protected static String appendDuration(String url, double durationSeconds) {
        if (durationSeconds > 0) {
            url += "&duration=" + (int) durationSeconds;
        }
        return url;
    }

    protected static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
