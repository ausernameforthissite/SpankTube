package dev.spanktube.archive;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DownloadProgress {

    private DownloadProgress() {
    }

    record Snapshot(ArchiveModels.Stage stage, long downloadedBytes, long totalBytes, long speedBps,
            int completedCount) {
        Snapshot(ArchiveModels.Stage stage, long downloadedBytes, long totalBytes, long speedBps) {
            this(stage, downloadedBytes, totalBytes, speedBps, -1);
        }
    }

    static final class Tracker {
        private static final Pattern PROGRESS = Pattern.compile(
                "\\[download\\]\\s+([\\d.]+)%\\s+of\\s+(?:~\\s*)?([\\d.]+)\\s*([KMGT]?i?B)\\s+at\\s+([\\d.]+)\\s*([KMGT]?i?B)/s");
        private final ArchiveModels.DownloadOptions options;
        private final java.util.Set<ArchiveModels.Stage> completed =
                java.util.EnumSet.of(ArchiveModels.Stage.METADATA);
        private ArchiveModels.Stage currentStage;
        private long currentTrackBytes;

        Tracker() {
            this(ArchiveModels.DownloadOptions.archiveDefaults());
        }

        Tracker(ArchiveModels.DownloadOptions options) {
            this.options = options;
        }

        synchronized Optional<Snapshot> accept(String line, long estimatedBytes) {
            ArchiveModels.Stage stage = stage(line);
            if (stage != null) {
                changeStage(stage);
                if (stage != ArchiveModels.Stage.DOWNLOADING_VIDEO
                        && stage != ArchiveModels.Stage.DOWNLOADING_AUDIO) {
                    return Optional.of(new Snapshot(stage, 0, 0, 0, completed.size()));
                }
            }
            Matcher matcher = PROGRESS.matcher(line);
            if (!matcher.find()) {
                return Optional.empty();
            }
            ArchiveModels.Stage downloading = currentStage == ArchiveModels.Stage.DOWNLOADING_VIDEO
                    || currentStage == ArchiveModels.Stage.DOWNLOADING_AUDIO ? currentStage
                    : options.audioOnly() ? ArchiveModels.Stage.DOWNLOADING_AUDIO : ArchiveModels.Stage.DOWNLOADING_VIDEO;
            changeStage(downloading);
            double percent = Double.parseDouble(matcher.group(1));
            long trackTotal = bytes(matcher.group(2), matcher.group(3));
            currentTrackBytes = Math.round(trackTotal * percent / 100);
            long total = trackTotal > 0 ? trackTotal : estimatedBytes;
            long speed = bytes(matcher.group(4), matcher.group(5));
            return Optional.of(new Snapshot(downloading, currentTrackBytes, total, speed, completed.size()));
        }

        synchronized Snapshot stage(ArchiveModels.Stage stage, long downloadedBytes, long totalBytes) {
            return stage(stage, downloadedBytes, totalBytes, 0);
        }

        synchronized Snapshot stage(ArchiveModels.Stage stage, long downloadedBytes, long totalBytes, long speedBps) {
            changeStage(stage);
            return new Snapshot(stage, downloadedBytes, totalBytes, speedBps, completed.size());
        }

        private void changeStage(ArchiveModels.Stage next) {
            if (currentStage == next) {
                return;
            }
            if (currentStage != null) {
                completed.add(currentStage);
            }
            currentStage = next;
            currentTrackBytes = 0;
        }

        private ArchiveModels.Stage stage(String line) {
            if (line.contains("Writing video subtitles") || line.contains("[download] Destination:") && line.endsWith(".vtt")) {
                return ArchiveModels.Stage.SUBTITLES;
            }
            if (line.contains("[download] Destination:")) {
                if (options.videoOnly()) return ArchiveModels.Stage.DOWNLOADING_VIDEO;
                if (options.audioOnly()) return ArchiveModels.Stage.DOWNLOADING_AUDIO;
                if (options.audioFormatId() != null && line.contains(".f" + options.audioFormatId() + ".")) {
                    return ArchiveModels.Stage.DOWNLOADING_AUDIO;
                }
                return ArchiveModels.Stage.DOWNLOADING_VIDEO;
            }
            if (line.contains("[Merger]") || line.contains("[ffmpeg]") || line.contains("[Remux]")) {
                return ArchiveModels.Stage.MUXING;
            }
            if (line.contains("[ModifyChapters]")) {
                return ArchiveModels.Stage.CHAPTERS;
            }
            if (line.contains("[SponsorBlock]")) {
                return ArchiveModels.Stage.SPONSORBLOCK;
            }
            return null;
        }

        private static long bytes(String value, String unit) {
            double number = Double.parseDouble(value);
            int power = switch (unit.charAt(0)) {
                case 'K' -> 1;
                case 'M' -> 2;
                case 'G' -> 3;
                case 'T' -> 4;
                default -> 0;
            };
            double base = unit.endsWith("iB") ? 1024 : 1000;
            return Math.round(number * Math.pow(base, power));
        }
    }
}
