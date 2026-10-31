package dev.spanktube.upstream;

public class UpstreamException extends RuntimeException {

    private final Reason reason;

    private UpstreamException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public static UpstreamException unavailable(String message) {
        return new UpstreamException(Reason.EGRESS_UNAVAILABLE, message, null);
    }

    public static UpstreamException failed(String message) {
        return new UpstreamException(Reason.FAILED, message, null);
    }

    public static UpstreamException failed(String message, Throwable cause) {
        return new UpstreamException(Reason.FAILED, message, cause);
    }

    public static UpstreamException antiBot(String message) {
        return new UpstreamException(Reason.ANTIBOT, message, null);
    }

    public enum Reason {
        EGRESS_UNAVAILABLE,
        ANTIBOT,
        FAILED
    }
}
