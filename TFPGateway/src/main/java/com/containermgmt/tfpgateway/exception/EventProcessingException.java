package com.containermgmt.tfpgateway.exception;

/**
 * Custom exception for event processing errors
 */
public class EventProcessingException extends RuntimeException {

    public EventProcessingException(String message) {
        super(message);
    }

    public EventProcessingException(String message, Throwable cause) {
        super(message, cause);
    }

}
