package dev.spanktube.archive;

final class ArchiveFilenames {

    private ArchiveFilenames() {
    }

    static String of(String title, String videoId, ArchiveModels.DownloadedFile file) {
        String resolution = file.videoCodec() == null ? "audio"
                : file.height() == null ? "unknown" : file.height() + "p";
        String name = String.valueOf(title).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isBlank()) {
            name = videoId;
        }
        if (name.length() > 160) {
            name = name.substring(0, 160);
        }
        return name + " [" + videoId + "] (" + resolution + ", " + codec(file.videoCodec()) + ", "
                + audioBitrate(file.audioBitrate()) + ", " + codec(file.audioCodec()) + ")." + file.extension();
    }

    private static String audioBitrate(Long value) {
        return value == null || value <= 0 ? "unknown" : Math.max(1, Math.round(value / 1000.0)) + "kbps";
    }

    private static String codec(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
