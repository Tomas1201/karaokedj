package com.karaokedj.lyrics;

import com.karaokedj.model.LrcLyrics;
import com.karaokedj.model.SongMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Puntúa y selecciona el mejor candidato entre resultados de búsqueda de un repositorio. */
public final class LyricMatchScorer {

    private static final Logger log = LoggerFactory.getLogger(LyricMatchScorer.class);

    private LyricMatchScorer() {
    }

    public static LrcLyrics findBestMatch(List<LrcLyrics> results, SongMetadata metadata) {
        if (results == null || results.isEmpty()) return null;

        LrcLyrics bestMatch = null;
        int bestScore = -1;

        for (LrcLyrics candidate : results) {
            int score = calculateMatchScore(candidate, metadata);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = candidate;
            }
        }

        if (bestMatch != null) {
            log.info("Best match found: {} (score: {})", bestMatch.getTrackName(), bestScore);
        }
        return bestMatch;
    }

    static int calculateMatchScore(LrcLyrics candidate, SongMetadata metadata) {
        int score = 0;
        String candidateTitle = candidate.getTrackName().toLowerCase();
        String metaTitle = metadata.getTitle().toLowerCase();
        String candidateArtist = candidate.getArtistName().toLowerCase();
        String metaArtist = metadata.getArtist().toLowerCase();

        if (candidateTitle.equals(metaTitle)) score += 10;
        else if (candidateTitle.contains(metaTitle)) score += 5;

        if (candidateArtist.equals(metaArtist)) score += 10;
        else if (candidateArtist.contains(metaArtist)) score += 5;

        if (candidate.getAlbumName() != null && candidate.getAlbumName().equalsIgnoreCase(metadata.getAlbum())) {
            score += 5;
        }

        int durationDiff = Math.abs(candidate.getDuration() - (int) metadata.getDurationSeconds());
        if (durationDiff <= 2) score += 8;
        else if (durationDiff <= 5) score += 4;
        else if (durationDiff <= 10) score += 2;

        if (candidate.hasEnhancedLyrics()) score += 5;
        else if (candidate.hasSyncedLyrics()) score += 3;

        return score;
    }
}
