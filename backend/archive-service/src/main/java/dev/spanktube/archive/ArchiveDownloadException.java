package dev.spanktube.archive;

class ArchiveDownloadException extends RuntimeException {

    ArchiveDownloadException(String message) {
        super(message);
    }

    ArchiveDownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
