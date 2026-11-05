package dev.spanktube.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Primary
@Component
class NewPipeDownloader implements ArchiveDownloader {

    private final ArchiveProperties properties;
    private final MediaClient mediaClient;
    private final SponsorBlockClient sponsorBlockClient;
    private final YtDlpDownloader processes;
    private final ObjectMapper objectMapper;
    private final String ffmpegExecutable;

    NewPipeDownloader(ArchiveProperties properties, MediaClient mediaClient, SponsorBlockClient sponsorBlockClient,
            YtDlpDownloader processes, ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${spanktube.archive.ffmpeg-executable:ffmpeg}")
            String ffmpegExecutable) {
        this.properties = properties;
        this.mediaClient = mediaClient;
        this.sponsorBlockClient = sponsorBlockClient;
        this.processes = processes;
        this.objectMapper = objectMapper;
        this.ffmpegExecutable = ffmpegExecutable;
    }

    @Override
    public ArchiveModels.DownloadedFile download(UpstreamClient.VideoDownload source,
            ArchiveModels.DownloadOptions options, Path directory, long estimatedBytes,
            Consumer<DownloadProgress.Snapshot> progress) {
        Path root = Path.of(properties.root()).toAbsolutePath().normalize();
        directory = directory.toAbsolutePath().normalize();
        if (!directory.startsWith(root)) {
            throw new ArchiveDownloadException("Download directory escapes archive root");
        }
        long deadline = System.nanoTime() + properties.downloadTimeout().toNanos();
        try {
            Files.createDirectories(directory);
            UpstreamClient.Selection selection = source.select(options);
            boolean combinedVideo = selection.video() != null && selection.video().hasAudio();
            if (!options.audioOnly() && selection.video() == null
                    || !options.videoOnly() && selection.audio() == null && !combinedVideo) {
                throw new ArchiveDownloadException("NewPipe did not return the requested media tracks");
            }
            ArchiveModels.ExistingVideo metadata = source.metadata();
            DownloadProgress.Tracker tracker = new DownloadProgress.Tracker(options);
            List<Path> inputs = new ArrayList<>();
            if (selection.video() != null) {
                Path track = directory.resolve("video-track" + extension(selection.video().mimeType()));
                Files.deleteIfExists(track);
                ensureTimeRemaining(deadline);
                mediaClient.downloadTrack(metadata.videoId(), selection.video().formatId(), track,
                        size(selection.video(), metadata.durationSeconds()), ArchiveModels.Stage.DOWNLOADING_VIDEO,
                        tracker, progress);
                inputs.add(track);
            }
            if (selection.audio() != null) {
                Path track = directory.resolve("audio-track" + extension(selection.audio().mimeType()));
                Files.deleteIfExists(track);
                ensureTimeRemaining(deadline);
                mediaClient.downloadTrack(metadata.videoId(), selection.audio().formatId(), track,
                        size(selection.audio(), metadata.durationSeconds()), ArchiveModels.Stage.DOWNLOADING_AUDIO,
                        tracker, progress);
                inputs.add(track);
            }

            List<Path> subtitles = new ArrayList<>();
            if (options.subtitles() && source.subtitles() != null) {
                progress.accept(tracker.stage(ArchiveModels.Stage.SUBTITLES, 0, 0));
                int index = 0;
                for (UpstreamClient.SubtitleOption subtitle : source.subtitles()) {
                    ensureTimeRemaining(deadline);
                    String language = subtitle.language() == null ? "unknown"
                            : subtitle.language().replaceAll("[^A-Za-z0-9_-]", "-");
                    Path file = directory.resolve("subtitle-" + language + "-" + index++ + ".vtt");
                    mediaClient.downloadResource("/media/v/" + metadata.videoId() + "/sub/" + subtitle.trackId(), file);
                    subtitles.add(file);
                }
            }

            List<Chapter> chapters = new ArrayList<>();
            if (source.chapters() != null) {
                for (UpstreamClient.ChapterOption chapter : source.chapters()) {
                    if (chapter.startSeconds() != null && chapter.startSeconds() >= 0) {
                        chapters.add(new Chapter(chapter.title() == null ? "Chapter" : chapter.title(),
                                chapter.startSeconds() * 1000, 0));
                    }
                }
            }
            if (options.sponsorBlock()) {
                progress.accept(tracker.stage(ArchiveModels.Stage.SPONSORBLOCK, 0, 0));
                for (SponsorBlockClient.Segment segment : sponsorBlockClient.segments(metadata.videoId())) {
                    chapters.add(new Chapter(segment.category(), Math.round(segment.startSeconds() * 1000),
                            Math.round(segment.endSeconds() * 1000)));
                }
            }
            Path chapterFile = directory.resolve("chapters.ffmeta");
            Files.writeString(chapterFile, chapterMetadata(chapters, metadata.durationSeconds()));
            inputs.add(chapterFile);

            Path output = directory.resolve(options.audioOnly() ? "audio.mp3" : "video.mkv");
            Files.deleteIfExists(output);
            progress.accept(tracker.stage(ArchiveModels.Stage.MUXING, 0, 0));
            List<String> command = new ArrayList<>();
            command.add(ffmpegExecutable);
            command.add("-y");
            int trackInputs = inputs.size() - 1;
            for (Path input : inputs) {
                command.add("-i");
                command.add(input.toString());
            }
            if (options.audioOnly()) {
                command.addAll(List.of("-map", "0:a:0", "-vn", "-c:a", "libmp3lame", "-q:a", "0"));
            } else {
                command.addAll(List.of("-map", "0:v:0?", "-map", "0:a:0?", "-c", "copy"));
                if (selection.audio() != null) {
                    command.addAll(List.of("-map", "1:a:0?"));
                }
                command.addAll(List.of("-c:s", "srt"));
            }
            if (!options.audioOnly()) {
                for (int index = 0; index < subtitles.size(); index++) {
                    command.addAll(List.of("-map", trackInputs + index + ":0"));
                }
            }
            int metadataIndex = inputs.size() - 1;
            command.addAll(List.of("-map_metadata", String.valueOf(metadataIndex),
                    "-map_chapters", String.valueOf(metadataIndex)));
            Instant publicationDate = PublicationDate.parse(metadata.publishedAt());
            if (publicationDate != null) {
                command.addAll(List.of("-metadata", "creation_time=" + publicationDate));
            }
            command.add(output.toString());
            ensureTimeRemaining(deadline);
            processes.run(command, directory, Duration.ofMinutes(15), estimatedBytes, progress, tracker);
            if (publicationDate != null) {
                Files.setLastModifiedTime(output, java.nio.file.attribute.FileTime.from(publicationDate));
            }
            writeInfoJson(source, selection, options, directory.resolve("info.json"));
            try {
                mediaClient.downloadResource("/media/v/" + metadata.videoId() + "/thumbnail",
                        directory.resolve("video.jpg"));
            } catch (RuntimeException error) {
                org.slf4j.LoggerFactory.getLogger(NewPipeDownloader.class)
                        .warn("Unable to archive thumbnail for {}: {}", metadata.videoId(), error.getMessage());
            }
            return inspect(output, options);
        } catch (IOException error) {
            throw new ArchiveDownloadException("Unable to write NewPipe archive: " + error.getMessage(), error);
        }
    }

    @Override
    public ArchiveModels.DownloadedFile inspect(Path output, ArchiveModels.DownloadOptions options) {
        return processes.inspect(output, options);
    }

    @Override
    public void discard(ArchiveModels.ExistingVideo metadata) {
        processes.discard(metadata);
    }

    @Override
    public void discard(Path directory) {
        processes.discard(directory);
    }

    private void writeInfoJson(UpstreamClient.VideoDownload source, UpstreamClient.Selection selection,
            ArchiveModels.DownloadOptions options, Path output) throws IOException {
        ArchiveModels.ExistingVideo metadata = source.metadata();
        ObjectNode info = objectMapper.createObjectNode();
        info.put("id", metadata.videoId());
        info.put("title", metadata.title());
        info.put("description", metadata.description());
        info.put("channel_id", metadata.channelId());
        info.put("channel", metadata.channelName());
        info.put("uploader", metadata.channelName());
        info.put("extractor", "NewPipeExtractor");
        info.put("webpage_url", "https://www.youtube.com/watch?v=" + metadata.videoId());
        if (metadata.publishedAt() != null) info.put("upload_date", metadata.publishedAt());
        if (metadata.durationSeconds() != null) info.put("duration", metadata.durationSeconds());
        String formatId = options.audioOnly() ? selection.audio().formatId()
                : selection.video().formatId() + (selection.audio() == null ? "" : "+" + selection.audio().formatId());
        info.put("format_id", formatId);
        if (selection.audio() != null && selection.audio().bitrate() != null) {
            info.put("abr", selection.audio().bitrate() / 1000.0);
        }
        objectMapper.writeValue(output.toFile(), info);
    }

    private static String chapterMetadata(List<Chapter> source, Long durationSeconds) {
        List<Chapter> chapters = new ArrayList<>(source);
        chapters.sort(Comparator.comparingLong(Chapter::start));
        long duration = durationSeconds == null ? 0 : durationSeconds * 1000;
        StringBuilder metadata = new StringBuilder(";FFMETADATA1\n");
        for (int index = 0; index < chapters.size(); index++) {
            Chapter chapter = chapters.get(index);
            long next = index + 1 < chapters.size() ? chapters.get(index + 1).start() : duration;
            long end = chapter.end() > 0 ? chapter.end() : next;
            if (next > 0) end = Math.min(end, next);
            if (end <= chapter.start()) continue;
            metadata.append("[CHAPTER]\nTIMEBASE=1/1000\nSTART=").append(chapter.start())
                    .append("\nEND=").append(end)
                    .append("\ntitle=").append(escape(chapter.title())).append("\n\n");
        }
        return metadata.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace(";", "\\;")
                .replace("#", "\\#").replace("=", "\\=");
    }

    private static long size(UpstreamClient.FormatOption format, Long durationSeconds) {
        if (format.contentLength() != null && format.contentLength() > 0) return format.contentLength();
        if (format.bitrate() == null || durationSeconds == null) return 0;
        return Math.round(format.bitrate() * durationSeconds / 8.0);
    }

    private static String extension(String mimeType) {
        return mimeType != null && mimeType.toLowerCase(java.util.Locale.ROOT).contains("webm") ? ".webm" : ".mp4";
    }

    private static void ensureTimeRemaining(long deadline) {
        if (System.nanoTime() >= deadline) {
            throw new ArchiveDownloadException("NewPipe archive download timed out");
        }
    }

    private record Chapter(String title, long start, long end) {
    }
}
