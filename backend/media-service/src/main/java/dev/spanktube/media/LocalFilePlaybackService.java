package dev.spanktube.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

@Service
class LocalFilePlaybackService {

    private final LocalFileService files;
    private final OnDemandDashService onDemandDash;
    private final Path cacheRoot;

    LocalFilePlaybackService(LocalFileService files, OnDemandDashService onDemandDash,
            @Value("${spanktube.media.playback-cache:/tmp/spanktube-playback}") String cacheRoot) {
        this.files = files;
        this.onDemandDash = onDemandDash;
        this.cacheRoot = Path.of(cacheRoot).toAbsolutePath().normalize();
    }

    String manifest(String fileId) throws IOException {
        LocalFileService.LocalVideo video = files.video(fileId);
        Path cache = cacheDirectory(video);
        onDemandDash.prepare(video.source(), cache, "local-file", null, video.subtitles(), true);
        return Files.readString(cache.resolve("ondemand.mpd"))
                .replace("<Period", "<BaseURL>/media/files/" + fileId + "/segments/</BaseURL><Period");
    }

    FileSystemResource segment(String fileId, String filename) throws IOException {
        if (!filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Local media segment not found");
        }
        LocalFileService.LocalVideo video = files.video(fileId);
        return onDemandDash.segment(video.source(), cacheDirectory(video), "local-file", filename, null,
                video.subtitles(), true);
    }

    private Path cacheDirectory(LocalFileService.LocalVideo video) throws IOException {
        StringBuilder signature = new StringBuilder("browser-v11\0").append(video.path());
        appendSignature(signature, video.source());
        for (Path subtitle : video.subtitles()) {
            appendSignature(signature, subtitle);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(signature.toString().getBytes(StandardCharsets.UTF_8));
            return cacheRoot.resolve("files").resolve(HexFormat.of().formatHex(hash)).normalize();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void appendSignature(StringBuilder signature, Path path) throws IOException {
        signature.append('\0').append(path.toString())
                .append('\0').append(Files.size(path))
                .append('\0').append(Files.getLastModifiedTime(path).toMillis());
    }
}
