package dev.spanktube.catalog;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class UserPlaylistService {

    private final CatalogRepository repository;

    UserPlaylistService(CatalogRepository repository) {
        this.repository = repository;
    }

    List<LibraryModels.UserPlaylist> list(String username) {
        return repository.userPlaylists(username);
    }

    LibraryModels.UserPlaylist get(String playlistId) {
        return repository.userPlaylist(playlistId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Playlist not found"));
    }

    @Transactional
    public LibraryModels.UserPlaylist create(String username, String title, String videoId) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Playlist title must contain 1-200 characters");
        }
        if (videoId != null && !videoId.isBlank()) {
            validateVideoId(videoId);
        }
        String id = UUID.randomUUID().toString();
        repository.createUserPlaylist(id, username, normalized, videoId);
        return get(id);
    }

    @Transactional
    public void addVideo(String playlistId, String username, String videoId) {
        validateVideoId(videoId);
        LibraryModels.UserPlaylist playlist = get(playlistId);
        if (!playlist.ownerUsername().equals(username)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only playlist owner can add videos");
        }
        repository.addUserVideo(playlistId, videoId);
    }

    private static void validateVideoId(String videoId) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid videoId");
        }
    }
}
