package com.karaokedj.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.karaokedj.model.LrcLyrics;
import com.karaokedj.model.SongMetadata;
import com.karaokedj.util.LrcTime;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proveedor LRCLIB (lrclib.net/api).
 *
 * Karaoke palabra-a-palabra desde el campo lyricsfile (YAML), con fallback
 * de búsqueda (/api/search) + obtención por ID cuando el match exacto falla.
 * También aporta la mejor referencia (synced/plain) para la verificación IA.
 */
public class LrclibProvider extends AbstractHttpProvider implements LyricsProvider {

    private static final String BASE_URL = "https://lrclib.net/api";

    private static final Pattern YAML_LINE_START = Pattern.compile("start_ms:\\s*(\\d+)");
    private static final Pattern YAML_WORD_TEXT = Pattern.compile("text:\\s*['\"](.+?)['\"]");

    @Override
    public String name() {
        return "LRCLIB";
    }

    @Override
    public LrcLyrics fetchEnhanced(SongMetadata metadata) {
        LrcLyrics exact = fetchEnhancedExact(buildGetUrl(metadata));
        if (exact != null) return exact;

        // Fallback: buscar por artista+título, elegir el mejor match y traer el registro por ID
        try {
            String query = metadata.getArtist() + " " + metadata.getTitle();
            String url = String.format("%s/search?q=%s", BASE_URL, urlEncode(query));

            JsonNode array = mapper.readTree(getJson(url, name()));
            if (array == null || !array.isArray() || array.isEmpty()) {
                log.info("LRCLIB enhanced: no search results for '{}'", query);
                return null;
            }

            List<LrcLyrics> results = toLrcLyricsList(array);
            LrcLyrics best = LyricMatchScorer.findBestMatch(results, metadata);
            if (best == null || best.getId() <= 0) return null;

            log.info("LRCLIB enhanced: best match by search id={} '{} - {}'",
                    best.getId(), best.getArtistName(), best.getTrackName());
            return fetchEnhancedExact(BASE_URL + "/get/" + best.getId());

        } catch (Exception e) {
            log.warn("LRCLIB enhanced fallback error: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public LrcLyrics fetchReference(SongMetadata metadata) {
        try {
            String body = getJson(buildGetUrl(metadata), name());
            if (body != null) {
                return lrcJsonToLrcLyrics(mapper.readTree(body));
            }
        } catch (Exception e) {
            log.warn("LRCLIB error: {}", e.getMessage());
        }

        // Fallback de búsqueda para metadatos imprecisos
        try {
            String query = metadata.getArtist() + " " + metadata.getTitle();
            String url = String.format("%s/search?q=%s", BASE_URL, urlEncode(query));
            String body = getJson(url, name());
            if (body != null && body.startsWith("[")) {
                List<LrcLyrics> results = toLrcLyricsList(mapper.readTree(body));
                return LyricMatchScorer.findBestMatch(results, metadata);
            }
        } catch (Exception e) {
            log.warn("LRCLIB search error: {}", e.getMessage());
        }

        return null;
    }

    private LrcLyrics fetchEnhancedExact(String url) {
        try {
            String responseBody = getJson(url, name());
            if (responseBody == null) return null;

            JsonNode node = mapper.readTree(responseBody);
            String lyricsfile = node.path("lyricsfile").asText(null);
            if (lyricsfile == null || lyricsfile.isBlank()) return null;

            String enhanced = parseLyricsfileYaml(lyricsfile);
            if (enhanced == null || enhanced.isBlank()) return null;

            LrcLyrics lyrics = lrcJsonToLrcLyrics(node);
            lyrics.setEnhancedLyrics(enhanced);
            lyrics.setWordSynced(true);
            lyrics.setSource("LRCLIB/lyricsfile");
            lyrics.setSyncedFromRepo(true);
            return lyrics;

        } catch (Exception e) {
            log.warn("LRCLIB enhanced error: {}", e.getMessage());
            return null;
        }
    }

    /** URL de /api/get omitiendo parámetros vacíos o sin sentido (duración<=0 evita HTTP 400). */
    private String buildGetUrl(SongMetadata metadata) {
        StringBuilder url = new StringBuilder(BASE_URL)
                .append("/get?track_name=").append(urlEncode(metadata.getTitle()))
                .append("&artist_name=").append(urlEncode(metadata.getArtist()));

        if (metadata.getAlbum() != null && !metadata.getAlbum().isBlank()) {
            url.append("&album_name=").append(urlEncode(metadata.getAlbum()));
        }
        if (metadata.getDurationSeconds() > 0) {
            url.append("&duration=").append((int) metadata.getDurationSeconds());
        }
        return url.toString();
    }

    /**
     * Convierte el YAML lyricsfile de LRCLIB a Enhanced LRC.
     * Parser propio por bloques "- text:"; los timestamps van en ms.
     */
    private String parseLyricsfileYaml(String yaml) {
        StringBuilder sb = new StringBuilder();
        String[] blocks = yaml.split("(?=- text:)");
        for (String block : blocks) {
            if (!block.contains("start_ms:")) continue;

            long lineStartMs = 0;
            Matcher lineStartMatcher = YAML_LINE_START.matcher(block);
            if (lineStartMatcher.find()) {
                lineStartMs = Long.parseLong(lineStartMatcher.group(1));
            }

            sb.append(LrcTime.lrc(lineStartMs));

            int wordsIdx = block.indexOf("words:");
            if (wordsIdx >= 0) {
                appendWordsFromBlock(sb, block.substring(wordsIdx));
            } else {
                appendPlainTextLine(sb, block);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void appendWordsFromBlock(StringBuilder sb, String wordsSection) {
        String[] wordBlocks = wordsSection.split("(?=\\s*- text:)");
        for (String wb : wordBlocks) {
            Matcher textM = YAML_WORD_TEXT.matcher(wb);
            Matcher startM = YAML_LINE_START.matcher(wb);
            if (textM.find() && startM.find()) {
                String text = textM.group(1);
                long wordStartMs = Long.parseLong(startM.group(1));
                LrcTime.appendEnhanced(sb, wordStartMs).append(text);
            }
        }
    }

    private void appendPlainTextLine(StringBuilder sb, String block) {
        String lineText = block.replaceAll("\\s*start_ms:.*", "").trim()
                .replaceAll("^.*?['\"]", "")
                .replaceAll("['\"]$", "").trim();
        if (!lineText.isEmpty()) {
            sb.append(lineText);
        }
    }

    private List<LrcLyrics> toLrcLyricsList(JsonNode array) {
        List<LrcLyrics> results = new ArrayList<>();
        for (JsonNode node : array) {
            LrcLyrics lyrics = lrcJsonToLrcLyrics(node);
            if (lyrics != null) {
                results.add(lyrics);
            }
        }
        return results;
    }

    private LrcLyrics lrcJsonToLrcLyrics(JsonNode node) {
        LrcLyrics lyrics = new LrcLyrics();
        lyrics.setId(node.path("id").asInt(0));
        lyrics.setTrackName(node.path("trackName").asText(""));
        lyrics.setArtistName(node.path("artistName").asText(""));
        lyrics.setAlbumName(node.path("albumName").asText(""));
        lyrics.setDuration(node.path("duration").asInt(0));
        lyrics.setInstrumental(node.path("instrumental").asBoolean(false));
        lyrics.setPlainLyrics(node.path("plainLyrics").asText(null));
        lyrics.setSyncedLyrics(node.path("syncedLyrics").asText(null));
        return lyrics;
    }
}
