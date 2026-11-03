package dev.spanktube.archive;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class PlaylistThumbnailService {

    private final Path archiveRoot;
    private final MediaClient mediaClient;

    PlaylistThumbnailService(@Value("${spanktube.archive.root:/archive}") String archiveRoot, MediaClient mediaClient) {
        this.archiveRoot = Path.of(archiveRoot).toAbsolutePath().normalize();
        this.mediaClient = mediaClient;
    }

    void save(String playlistId, String thumbnailUrl) {
        if (thumbnailUrl == null || thumbnailUrl.isBlank()) {
            return;
        }
        Path directory = archiveRoot.resolve("playlists").resolve(playlistId).normalize();
        if (!directory.startsWith(archiveRoot)) {
            throw new IllegalArgumentException("Playlist thumbnail path escapes archive root");
        }
        Path temporary = directory.resolve("thumbnail.tmp");
        try {
            Files.createDirectories(directory);
            mediaClient.downloadResource("/media/image?url=" + URLEncoder.encode(thumbnailUrl, StandardCharsets.UTF_8),
                    temporary);
            Files.move(temporary, directory.resolve("thumbnail"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            org.slf4j.LoggerFactory.getLogger(PlaylistThumbnailService.class)
                    .warn("Unable to save thumbnail for playlist {}: {}", playlistId, error.getMessage());
        }
    }
}
