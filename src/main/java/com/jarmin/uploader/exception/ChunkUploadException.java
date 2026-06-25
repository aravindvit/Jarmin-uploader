package com.jarmin.uploader.exception;

/** Raised when a chunk could not be uploaded after all retries were exhausted. */
public class ChunkUploadException extends StoreException {

    public ChunkUploadException(String message, Throwable cause) {
        super(message, cause);
    }
}
