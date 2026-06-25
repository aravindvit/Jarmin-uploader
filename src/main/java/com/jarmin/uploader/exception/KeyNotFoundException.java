package com.jarmin.uploader.exception;

/** Raised when {@code get} is requested for a key the server does not have. */
public class KeyNotFoundException extends StoreException {

    public KeyNotFoundException(String key) {
        super("No object found for key: " + key);
    }
}
