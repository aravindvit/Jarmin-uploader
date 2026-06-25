package com.jarmin.uploader.exception;

/** Base type for all errors raised by the chunked store. */
public class StoreException extends Exception {

    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
