package com.karaokedj.service;

import com.karaokedj.model.SongMetadata;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;

@Service
public class MetadataService {

    private static final Logger log = LoggerFactory.getLogger(MetadataService.class);

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "mp3", "flac", "ogg", "wav", "opus", "m4a", "aac", "wma", "aiff"
    );

    public SongMetadata extractMetadata(Path audioFilePath) throws Exception {
        File audioFile = audioFilePath.toFile();

        if (!audioFile.exists()) {
            throw new IllegalArgumentException("File not found: " + audioFilePath);
        }

        String extension = getFileExtension(audioFile.getName());
        if (!SUPPORTED_EXTENSIONS.contains(extension.toLowerCase())) {
            throw new IllegalArgumentException("Unsupported format: " + extension);
        }

        log.info("Reading metadata from: {}", audioFile.getName());

        AudioFile audioFileObj = AudioFileIO.read(audioFile);
        Tag tag = audioFileObj.getTag();
        int lengthSeconds = audioFileObj.getAudioHeader().getTrackLength();

        SongMetadata metadata = new SongMetadata();
        metadata.setFilePath(audioFilePath);
        metadata.setDurationSeconds(lengthSeconds);

        if (tag != null) {
            metadata.setTitle(getTagField(tag, FieldKey.TITLE, audioFile.getName()));
            metadata.setArtist(getTagField(tag, FieldKey.ARTIST, "Unknown Artist"));
            metadata.setAlbum(getTagField(tag, FieldKey.ALBUM, "Unknown Album"));
        } else {
            metadata.setTitle(removeExtension(audioFile.getName()));
            metadata.setArtist("Unknown Artist");
            metadata.setAlbum("Unknown Album");
        }

        log.info("Metadata extracted: {}", metadata);
        return metadata;
    }

    private String getTagField(Tag tag, FieldKey fieldKey, String defaultValue) {
        try {
            String value = tag.getFirst(fieldKey);
            return (value != null && !value.isBlank()) ? value.trim() : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex >= 0) ? fileName.substring(dotIndex + 1) : "";
    }

    private String removeExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex >= 0) ? fileName.substring(0, dotIndex) : fileName;
    }

    public boolean isSupportedFormat(Path filePath) {
        String ext = getFileExtension(filePath.getFileName().toString());
        return SUPPORTED_EXTENSIONS.contains(ext.toLowerCase());
    }
}
