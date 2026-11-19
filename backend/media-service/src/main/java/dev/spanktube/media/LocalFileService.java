package dev.spanktube.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class LocalFileService {

    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mkv", "mp4");
    private static final Set<String> SUBTITLE_EXTENSIONS = Set.of("srt", "ass", "ssa", "vtt");

    private final Path root;

    LocalFileService(@Value("${spanktube.media.local-files-root:/mnt/tank/data/torrents/finished}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    LocalDirectory browse(String relativePath) {
        Path directory = resolve(relativePath == null ? "" : relativePath);
        if (!Files.isDirectory(directory)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local directory not found");
        }
        String normalizedPath = relativePath(directory);
        String parentPath = directory.equals(realRoot()) ? null : relativePath(directory.getParent());
        try (var files = Files.list(directory)) {
            List<LocalFileEntry> entries = files.map(path -> entry(path))
                    .filter(entry -> entry != null)
                    .sorted(Comparator.comparingInt((LocalFileEntry entry) -> "DIRECTORY".equals(entry.kind()) ? 0 : 1)
                            .thenComparing(LocalFileEntry::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            return new LocalDirectory(normalizedPath, parentPath, entries);
        } catch (IOException error) {
            throw unavailable(error);
        }
    }

    LocalFileEntry open(String relativePath) {
        LocalFileEntry entry = entry(resolve(relativePath == null ? "" : relativePath));
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local file not found");
        }
        return entry;
    }

    LocalVideo video(String id) {
        String relativePath = decode(id);
        Path source = resolve(relativePath);
        if (!Files.isRegularFile(source) || !VIDEO_EXTENSIONS.contains(extension(source.getFileName().toString()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local video not found");
        }
        return new LocalVideo(id, relativePath, source, sidecarSubtitles(source));
    }

    private LocalFileEntry entry(Path path) {
        try {
            Path real = path.toRealPath();
            if (!real.startsWith(realRoot())) {
                return null;
            }
            if (Files.isDirectory(real)) {
                String relative = relativePath(real);
                String name = real.equals(realRoot()) ? "" : real.getFileName().toString();
                return new LocalFileEntry(encode(relative), name, relative, "DIRECTORY", null, null);
            }
            String name = real.getFileName().toString();
            if (!Files.isRegularFile(real) || !VIDEO_EXTENSIONS.contains(extension(name))) {
                return null;
            }
            String relative = relativePath(real);
            String id = encode(relative);
            return new LocalFileEntry(id, name, relative, "VIDEO", Files.size(real),
                    "/media/files/" + id + "/manifest.mpd");
        } catch (IOException error) {
            throw unavailable(error);
        }
    }

    private List<Path> sidecarSubtitles(Path video) {
        String filename = video.getFileName().toString();
        String stem = filename.substring(0, filename.length() - extension(filename).length() - 1);
        try (var files = Files.list(video.getParent())) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        int dot = name.lastIndexOf('.');
                        return dot > 0 && name.substring(0, dot).equals(stem)
                                && SUBTITLE_EXTENSIONS.contains(extension(name));
                    })
                    .map(path -> {
                        try {
                            Path real = path.toRealPath();
                            if (!real.startsWith(realRoot())) {
                                return null;
                            }
                            return real;
                        } catch (IOException error) {
                            throw unavailable(error);
                        }
                    })
                    .filter(path -> path != null)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException error) {
            throw unavailable(error);
        }
    }

    private Path resolve(String relativePath) {
        try {
            if (relativePath.indexOf('\0') >= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid local file path");
            }
            Path candidate = root.resolve(relativePath).normalize();
            if (!candidate.startsWith(root)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local file not found");
            }
            Path real = candidate.toRealPath();
            if (!real.startsWith(realRoot())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local file not found");
            }
            return real;
        } catch (InvalidPathException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid local file path", error);
        } catch (java.nio.file.NoSuchFileException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Local file not found", error);
        } catch (IOException error) {
            throw unavailable(error);
        }
    }

    private Path realRoot() {
        try {
            return root.toRealPath();
        } catch (IOException error) {
            throw unavailable(error);
        }
    }

    private String relativePath(Path path) {
        return java.util.stream.StreamSupport.stream(realRoot().relativize(path).spliterator(), false)
                .map(Path::toString)
                .reduce((left, right) -> left + "/" + right)
                .orElse("");
    }

    static String encode(String path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(path.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String id) {
        try {
            if (id == null || id.isBlank() || id.length() > 16_384) {
                throw new IllegalArgumentException("Invalid file id");
            }
            String path = new String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8);
            if (path.isBlank() || path.startsWith("/")) {
                throw new IllegalArgumentException("Invalid file id");
            }
            return path;
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid local file id", error);
        }
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1 ? ""
                : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static ResponseStatusException unavailable(IOException error) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Local file share is unavailable", error);
    }

    record LocalDirectory(String path, String parentPath, List<LocalFileEntry> entries) {
    }

    record LocalFileEntry(String id, String name, String path, String kind, Long sizeBytes, String manifestUrl) {
    }

    record LocalVideo(String id, String path, Path source, List<Path> subtitles) {
    }
}
