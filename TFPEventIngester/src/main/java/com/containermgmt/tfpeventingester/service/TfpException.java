package com.containermgmt.tfpeventingester.service;

/**
 * Eccezione per errori di comunicazione con il sistema TFP.
 */
public class TfpException extends RuntimeException {

    private final int statusCode;

    public TfpException(String message) {
        super(message);
        this.statusCode = 0;
    }

    public TfpException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public TfpException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public boolean isUnauthorized() {
        return statusCode == 401;
    }
}
