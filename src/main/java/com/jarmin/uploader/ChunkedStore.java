package com.jarmin.uploader;

import com.jarmin.uploader.exception.StoreException;

import java.io.InputStream;

/**
 * A memcache-style store that uploads/downloads byte payloads in fixed-size chunks.
 *
 * <p>The key is a client-set, unique identifier. Values may be up to 5 GB. The streaming methods
 * are the primary API and operate in constant memory; the {@code byte[]} overloads are convenience
 * wrappers bounded by {@link Integer#MAX_VALUE} and intended for small/medium payloads.
 */
public interface ChunkedStore {

    /**
     * Stores {@code value} under {@code key}, splitting it into chunks that are uploaded in order.
     *
     * @param length the exact number of bytes that {@code value} will produce; must be
     *               {@code 0 <= length <= 5 GB}.
     */
    void set(String key, InputStream value, long length) throws StoreException;

    /** Retrieves the payload for {@code key} as a lazily-streaming, reassembled stream. */
    InputStream get(String key) throws StoreException;

    /** Convenience overload for in-memory payloads. */
    void set(String key, byte[] value) throws StoreException;

    /**
     * Convenience overload that reads the whole object into memory.
     *
     * @throws StoreException if the object is larger than {@link Integer#MAX_VALUE} bytes.
     */
    byte[] getBytes(String key) throws StoreException;
}
