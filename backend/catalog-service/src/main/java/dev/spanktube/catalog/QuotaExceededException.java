package dev.spanktube.catalog;

class QuotaExceededException extends RuntimeException {

    QuotaExceededException(String message) {
        super(message);
    }
}
