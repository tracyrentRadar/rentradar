package com.rentradar.backend.service;

public class UnknownMarketException extends RuntimeException {
    public UnknownMarketException(String message) {
        super(message);
    }
}