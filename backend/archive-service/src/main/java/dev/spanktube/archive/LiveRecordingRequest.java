package dev.spanktube.archive;

record LiveRecordingRequest(Integer height, Long bandwidth) {

    static LiveRecordingRequest bestAvailable() {
        return new LiveRecordingRequest(null, null);
    }
}
