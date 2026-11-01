package dev.spanktube.catalog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class LibraryController {

    private final LibraryService libraryService;
    private final IdentityService identityService;

    LibraryController(LibraryService libraryService, IdentityService identityService) {
        this.libraryService = libraryService;
        this.identityService = identityService;
    }

    @GetMapping("/api/v1/library")
    LibraryModels.LibraryResponse library(HttpServletRequest request,
            @RequestParam(required = false) String channelId,
            @RequestParam(defaultValue = "size") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        return libraryService.library(identityService.required(request).username(), channelId, sort, direction);
    }

    @GetMapping("/internal/v1/library/existing/{videoId}")
    ResponseEntity<ArchiveCompletedEvent> existing(@PathVariable String videoId, @RequestParam String username) {
        ArchiveCompletedEvent event = libraryService.existing(username, videoId);
        return event == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(event);
    }

    @GetMapping("/internal/v1/library/owned/{videoId}")
    ResponseEntity<ArchiveCompletedEvent> owned(@PathVariable String videoId, @RequestParam String username) {
        if (!libraryService.hasLink(username, videoId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(libraryService.existing(username, videoId));
    }

    @GetMapping("/internal/v1/library/links/{videoId}")
    ResponseEntity<Void> link(@PathVariable String videoId, @RequestParam String username) {
        return libraryService.hasLink(username, videoId)
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/internal/v1/library/usage/{username}")
    LibraryModels.UsageResponse usage(@PathVariable String username) {
        return new LibraryModels.UsageResponse(libraryService.usage(username));
    }

    @PostMapping("/internal/v1/library/prepare")
    ResponseEntity<Void> prepare(@RequestBody LibraryModels.PrepareRequest request) {
        libraryService.prepare(request.username(), request.requiredBytes(), request.videoId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/library/{videoId}/file")
    ResponseEntity<InputStreamResource> file(@PathVariable String videoId, HttpServletRequest request) throws Exception {
        Path file = libraryService.file(identityService.required(request).username(), videoId);
        if (file == null || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Archived file not found");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/x-matroska"))
                .contentLength(Files.size(file))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(videoId + ".mkv").build().toString())
                .body(new InputStreamResource(Files.newInputStream(file)));
    }

    @DeleteMapping("/api/v1/library/{videoId}")
    ResponseEntity<Void> delete(@PathVariable String videoId, HttpServletRequest request) {
        boolean deleted = libraryService.delete(identityService.required(request).username(), videoId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/api/v1/admin/library")
    Object adminLibrary(HttpServletRequest request,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String channelId,
            @RequestParam(defaultValue = "size") String sort,
            @RequestParam(defaultValue = "desc") String direction) {
        identityService.admin(request);
        if (username != null && !username.isBlank()) {
            return libraryService.library(username, channelId, sort, direction);
        }
        List<LibraryModels.LibraryChannel> channels = libraryService.storageUsers().stream()
                .map(LibraryModels.StorageUser::username)
                .flatMap(owner -> libraryService.library(owner, channelId, sort, direction).channels().stream())
                .toList();
        return Map.of("channels", channels);
    }

    @DeleteMapping("/api/v1/admin/library/{videoId}")
    ResponseEntity<Void> adminDelete(@PathVariable String videoId,
            @RequestParam String username, HttpServletRequest request) {
        identityService.admin(request);
        boolean deleted = libraryService.delete(username, videoId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/api/v1/admin/storage")
    LibraryModels.StorageResponse storage(HttpServletRequest request) {
        identityService.admin(request);
        Path root = Path.of("/archive");
        return new LibraryModels.StorageResponse(root.toFile().getTotalSpace(), root.toFile().getUsableSpace(), libraryService.storageUsers());
    }
}
