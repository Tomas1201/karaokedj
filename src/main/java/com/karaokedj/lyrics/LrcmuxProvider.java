package com.karaokedj.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.karaokedj.model.LrcLyrics;
import com.karaokedj.model.SongMetadata;
import com.karaokedj.util.LrcTime;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Proveedor Lrcmux (api.lrcmux.dev): agrega KuGou/Musixmatch/NetEase/Genius.
 * Ofrece karaoke palabra-a-palabra y referencia por línea.
 */
@Component
@Order(1)
public class LrcmuxProvider extends AbstractHttpProvider implements LyricsProvider {

    private static final String BASE_URL = "https://api.lrcmux.dev";

    @Override
    public String name() {
        return "Lrcmux";
    }

    @Override
    public LrcLyrics fetchEnhanced(SongMetadata metadata) {
        try {
            String url = buildUrl(metadata, "word");

            JsonNode root = mapper.readTree(getJson(url, name()));
            if (root == null) return null;

            String syncLevel = root.at("/meta/level").asText("none");
            if (!"word".equals(syncLevel)) {
                log.info("Lrcmux returned sync level '{}', not word-level", syncLevel);
                return null;
            }

            String providerName = root.at("/meta/source/id").asText("unknown");
            String enhanced = parseWordLevel(root);
            if (enhanced == null || enhanced.isBlank()) return null;

            LrcLyrics lyrics = buildLrcLyrics(root);
            lyrics.setEnhancedLyrics(enhanced);
            lyrics.setWordSynced(true);
            lyrics.setSource("Lrcmux/" + providerName);
            lyrics.setSyncedFromRepo(true);
            return lyrics;

        } catch (Exception e) {
            log.warn("Lrcmux enhanced error: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public LrcLyrics fetchReference(SongMetadata metadata) {
        try {
            String url = buildUrl(metadata, "line");

            JsonNode root = mapper.readTree(getJson(url, name()));
            if (root == null) return null;

            String syncLevel = root.at("/meta/level").asText("none");
            if ("none".equals(syncLevel)) return null;

            String providerName = root.at("/meta/source/id").asText("unknown");
            String synced = parseLineLevel(root);
            String plain = root.at("/plainLyrics").asText(null);

            LrcLyrics lyrics = buildLrcLyrics(root);
            if (synced != null && !synced.isBlank()) {
                lyrics.setSyncedLyrics(synced);
            }
            if (plain != null && !plain.isBlank()) {
                lyrics.setPlainLyrics(plain);
            }
            lyrics.setSource("Lrcmux/" + providerName);
            lyrics.setSyncedFromRepo(true);
            return lyrics;

        } catch (Exception e) {
            log.warn("Lrcmux line-level error: {}", e.getMessage());
            return null;
        }
    }

    private String buildUrl(SongMetadata metadata, String level) {
        String url = String.format("%s/get?artist=%s&title=%s&level=%s&format=json",
                BASE_URL,
                urlEncode(metadata.getArtist()),
                urlEncode(metadata.getTitle()),
                level);

        url = appendIfPresent(url, "album", metadata.getAlbum());
        return appendDuration(url, metadata.getDurationSeconds());
    }

    private String parseWordLevel(JsonNode root) {
        JsonNode lines = root.at("/lines");
        if (lines.isMissingNode() || !lines.isArray()) return null;

        StringBuilder sb = new StringBuilder();
        for (JsonNode line : lines) {
            JsonNode words = line.at("/words");
            long lineStart = line.at("/start").asLong(0);

            if (words.isMissingNode() || !words.isArray() || words.isEmpty()) {
                sb.append(LrcTime.lrc(lineStart)).append(line.at("/text").asText("")).append("\n");
                continue;
            }

            sb.append(LrcTime.lrc(lineStart));
            for (JsonNode word : words) {
                String text = word.path("text").asText("");
                long wordStart = word.path("start").asLong(lineStart);
                LrcTime.appendEnhanced(sb, wordStart).append(text);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String parseLineLevel(JsonNode root) {
        JsonNode lines = root.at("/lines");
        if (lines.isMissingNode() || !lines.isArray()) return null;

        StringBuilder sb = new StringBuilder();
        for (JsonNode line : lines) {
            long startMs = line.at("/start").asLong(0);
            String text = line.at("/text").asText("");
            sb.append(LrcTime.lrc(startMs)).append(text).append("\n");
        }
        return sb.toString();
    }

    private LrcLyrics buildLrcLyrics(JsonNode root) {
        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setTrackName(root.at("/track/title").asText(root.path("title").asText("")));
        lyrics.setArtistName(root.at("/track/artist").asText(root.path("artist").asText("")));
        lyrics.setAlbumName(root.at("/track/album").asText(root.path("album").asText("")));
        lyrics.setDuration(root.at("/track/duration").asInt(root.path("duration").asInt(0)));
        lyrics.setInstrumental(root.at("/meta/instrumental").asBoolean(false));
        return lyrics;
    }
}
