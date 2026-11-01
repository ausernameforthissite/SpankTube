package dev.spanktube.archive;

import java.util.List;

record ArchiveRequested(String jobId, String ownerUsername, String kind,
        String targetId, List<String> videoIds) {
}
