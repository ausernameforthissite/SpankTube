package dev.spanktube.archive;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DownloadProgress {

    private DownloadProgress() {
    }

    record Snapshot(ArchiveModels.Stage stage, long downloadedBytes, long totalBytes, long speedBps) {
    }

    static final class Tracker {
        private static final Pattern PROGRESS = Pattern.compile(
                "\\[download\\]\\s+([\\d.]+)%\\s+of\\s+(?:~\\s*)?([\\d.]+)\\s*([KMGT]?i?B)\\s+at\\s+([\\d.]+)\\s*([KMGT]?i?B)/s");
        private long completedTrackBytes;
        private long currentTrackBytes;

        Optional<Snapshot> accept(String line, long estimatedBytes) {
            ArchiveModels.Stage stage = stage(line);
            if (stage != null && stage != ArchiveModels.Stage.DOWNLOADING) {
                return Optional.of(new Snapshot(stage, completedTrackBytes, estimatedBytes, 0));
            }
            if (line.contains("[download] Destination:") && !line.endsWith(".vtt")) {
                completedTrackBytes += currentTrackBytes;
                currentTrackBytes = 0;
            }
            Matcher matcher = PROGRESS.matcher(line);
            if (!matcher.find()) {
                return Optional.empty();
            }
            double percent = Double.parseDouble(matcher.group(1));
            long trackTotal = bytes(matcher.group(2), matcher.group(3));
            currentTrackBytes = Math.round(trackTotal * percent / 100);
            long total = estimatedBytes > 0 ? estimatedBytes : completedTrackBytes + trackTotal;
            long speed = bytes(matcher.group(4), matcher.group(5));
            return Optional.of(new Snapshot(ArchiveModels.Stage.DOWNLOADING,
                    completedTrackBytes + currentTrackBytes, total, speed));
        }

        private static ArchiveModels.Stage stage(String line) {
            if (line.contains("Writing video subtitles") || line.contains("[download] Destination:") && line.endsWith(".vtt")) {
                return ArchiveModels.Stage.SUBTITLES;
            }
            if (line.contains("[Merger]") || line.contains("[ffmpeg]") || line.contains("[Remux]")) {
                return ArchiveModels.Stage.MUXING;
            }
            if (line.contains("[SponsorBlock]") || line.contains("[ModifyChapters]")) {
                return ArchiveModels.Stage.CHAPTERS;
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
