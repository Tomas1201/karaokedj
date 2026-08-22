package com.karaokedj.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.karaokedj.model.LrcLyrics;
import com.karaokedj.model.SongMetadata;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Proveedor Karalyr (www.karalyr.com/api): karaoke palabra-a-palabra.
 * Solo ofrece enhanced; no aporta referencia para la verificación IA.
 */
@Component
@Order(2)
public class KaralyrProvider extends AbstractHttpProvider implements LyricsProvider {

    private static final String BASE_URL = "https://www.karalyr.com/api";

    @Override
    public String name() {
        return "Karalyr";
    }

    @Override
    public LrcLyrics fetchEnhanced(SongMetadata metadata) {
        try {
            String url = String.format("%s/get?artist_name=%s&track_name=%s",
                    BASE_URL,
                    urlEncode(metadata.getArtist()),
                    urlEncode(metadata.getTitle()));

            url = appendIfPresent(url, "album_name", metadata.getAlbum());
            url = appendDuration(url, metadata.getDurationSeconds());

            JsonNode node = mapper.readTree(getJson(url, name()));
            if (node == null) return null;

            boolean hasWordTiming = node.path("karalyr").path("has_word_timing").asBoolean(false);
            if (!hasWordTiming) {
                log.info("Karalyr: no word-level timing available");
                return null;
            }

            String syncedLyrics = node.path("syncedLyrics").asText(null);
            if (syncedLyrics == null || syncedLyrics.isBlank()) return null;

            LrcLyrics lyrics = new LrcLyrics();
            lyrics.setId(node.path("id").asInt(0));
            lyrics.setTrackName(node.path("trackName").asText(""));
            lyrics.setArtistName(node.path("artistName").asText(""));
            lyrics.setAlbumName(node.path("albumName").asText(""));
            lyrics.setDuration(node.path("duration").asInt(0));
            lyrics.setPlainLyrics(node.path("plainLyrics").asText(null));
            lyrics.setEnhancedLyrics(syncedLyrics);
            lyrics.setWordSynced(true);
            lyrics.setSource("Karalyr");
            lyrics.setSyncedFromRepo(true);
            return lyrics;

        } catch (Exception e) {
            log.warn("Karalyr error: {}", e.getMessage());
            return null;
        }
    }
}
