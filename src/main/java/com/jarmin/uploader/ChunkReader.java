package com.jarmin.uploader;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Splits a source {@link InputStream} into successive fixed-size chunks without buffering the
 * whole payload. At most one chunk is held in memory at a time.
 */
final class ChunkReader {

    private final InputStream source;
    private final int chunkSize;

    ChunkReader(InputStream source, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0, was " + chunkSize);
        }
        this.source = Objects.requireNonNull(source, "source");
        this.chunkSize = chunkSize;
    }

    /**
     * Reads up to {@code chunkSize} bytes from the source.
     *
     * @return the next chunk (length == bytes actually read; the final chunk may be shorter than
     *         {@code chunkSize}), or {@code null} once the source is exhausted.
     */
    byte[] next() throws IOException {
        byte[] buffer = new byte[chunkSize];
        int read = 0;
        while (read < chunkSize) {
            int n = source.read(buffer, read, chunkSize - read);
            if (n == -1) {
                break;
            }
            read += n;
        }
        if (read == 0) {
            return null;
        }
        return read == chunkSize ? buffer : Arrays.copyOf(buffer, read);
    }

    int chunkSize() {
        return chunkSize;
    }
}
