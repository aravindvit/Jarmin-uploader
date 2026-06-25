package com.jarmin.uploader;

import java.io.InputStream;

/** A synthetic stream that yields {@code length} zero bytes without allocating them. */
final class ZeroInputStream extends InputStream {

    private long remaining;

    ZeroInputStream(long length) {
        this.remaining = length;
    }

    @Override
    public int read() {
        if (remaining <= 0) {
            return -1;
        }
        remaining--;
        return 0;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        if (remaining <= 0) {
            return -1;
        }
        int n = (int) Math.min(len, remaining);
        // b is already zero-initialised for fresh buffers; fill explicitly to be safe.
        for (int i = 0; i < n; i++) {
            b[off + i] = 0;
        }
        remaining -= n;
        return n;
    }
}
