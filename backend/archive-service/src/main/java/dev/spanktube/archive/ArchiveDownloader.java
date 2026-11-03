package dev.spanktube.archive;

import java.nio.file.Path;
import java.util.function.Consumer;

interface ArchiveDownloader {

    ArchiveModels.DownloadedFile download(UpstreamClient.VideoDownload source,
            ArchiveModels.DownloadOptions options, Path directory, long estimatedBytes,
            Consumer<DownloadProgress.Snapshot> progress);

    ArchiveModels.DownloadedFile inspect(Path output, ArchiveModels.DownloadOptions options);

    void discard(ArchiveModels.ExistingVideo metadata);

    void discard(Path directory);
}
