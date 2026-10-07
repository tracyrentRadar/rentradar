package com.rentradar.backend.service;

public class ServiceDegradedException extends RuntimeException {
    public ServiceDegradedException(String message, Throwable cause) {
        super(message, cause);
    }
}