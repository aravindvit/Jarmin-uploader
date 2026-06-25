package com.jarmin.uploader;

import java.io.IOException;
import java.io.InputStream;

/**
 * Presents an object's chunks {@code 0..chunkCount-1} as one continuous stream, fetching each
 * chunk lazily and holding at most one chunk's stream in memory at a time.
 */
final class ReassemblingInputStream extends InputStream {

    private final HttpChunkedStore store;
    private final String key;
    private final int chunkCount;

    private int nextChunk = 0;
    private InputStream current;
    private boolean closed = false;

    ReassemblingInputStream(HttpChunkedStore store, String key, int chunkCount) {
        this.store = store;
        this.key = key;
        this.chunkCount = chunkCount;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n == -1 ? -1 : (one[0] & 0xff);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        while (true) {
            if (current == null) {
                if (nextChunk >= chunkCount) {
                    return -1;
                }
                current = store.openChunk(key, nextChunk++);
            }
            int n = current.read(b, off, len);
            if (n != -1) {
                return n;
            }
            current.close();
            current = null; // advance to the next chunk on the next loop iteration
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (current != null) {
            current.close();
            current = null;
        }
    }
}
