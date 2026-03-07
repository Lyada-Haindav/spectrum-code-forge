package com.spectrumforge;

final class AppException extends RuntimeException {
    private final int statusCode;

    AppException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    int statusCode() {
        return statusCode;
    }
}
