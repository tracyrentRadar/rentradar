package com.rentradar.backend.ml;

public class MlUnavailableException extends RuntimeException {
    public MlUnavailableException(String message,Throwable cause) {
        super(message,cause);
    }
}
