package com.rentradar.backend.service;

public class LocationNotResolvableException extends RuntimeException {
    public LocationNotResolvableException(String message) {
        super(message);
    }
}