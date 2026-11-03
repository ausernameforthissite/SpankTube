package dev.spanktube.catalog;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UserPlaylistController {

    private final UserPlaylistService playlists;
    private final IdentityService identityService;

    UserPlaylistController(UserPlaylistService playlists, IdentityService identityService) {
        this.playlists = playlists;
        this.identityService = identityService;
    }

    @GetMapping("/api/v1/library/user-playlists")
    List<LibraryModels.UserPlaylist> list(HttpServletRequest request) {
        return playlists.list(identityService.required(request).username());
    }

    @PostMapping("/api/v1/library/user-playlists")
    ResponseEntity<LibraryModels.UserPlaylist> create(@RequestBody LibraryModels.CreateUserPlaylistRequest body,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(playlists.create(identityService.required(request).username(), body.title(), body.videoId()));
    }

    @GetMapping("/api/v1/library/user-playlists/{playlistId}")
    LibraryModels.UserPlaylist get(@PathVariable String playlistId, HttpServletRequest request) {
        identityService.required(request);
        return playlists.get(playlistId);
    }

    @PostMapping("/api/v1/library/user-playlists/{playlistId}/videos")
    ResponseEntity<Void> addVideo(@PathVariable String playlistId,
            @RequestBody LibraryModels.AddVideoRequest body, HttpServletRequest request) {
        playlists.addVideo(playlistId, identityService.required(request).username(), body.videoId());
        return ResponseEntity.noContent().build();
    }
}
