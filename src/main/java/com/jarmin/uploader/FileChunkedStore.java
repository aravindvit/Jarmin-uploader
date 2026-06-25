package com.jarmin.uploader;

import com.jarmin.uploader.exception.KeyNotFoundException;
import com.jarmin.uploader.exception.StoreException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * A {@link ChunkedStore} backed by a local directory instead of a remote server.
 *
 * <p>Mirrors the chunking pipeline of the HTTP client — split into fixed-size chunks, write each
 * chunk, then a manifest — but uses the filesystem as the backend. Selected via the
 * {@code storage.mode=file} client configuration. Layout: {@code <baseDir>/<key>/<index>} for each
 * chunk plus {@code <baseDir>/<key>/manifest.properties}.
 */
public final class FileChunkedStore implements ChunkedStore {

    private final Path baseDir;
    private final int chunkSize;
    private final long maxValueSize;

    public FileChunkedStore(Path baseDir, int chunkSize) {
        this(baseDir, chunkSize, StoreConfig.MAX_VALUE_SIZE);
    }

    public FileChunkedStore(Path baseDir, int chunkSize, long maxValueSize) {
        this.baseDir = Objects.requireNonNull(baseDir, "baseDir");
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be > 0, was " + chunkSize);
        }
        if (maxValueSize <= 0) {
            throw new IllegalArgumentException("maxValueSize must be > 0, was " + maxValueSize);
        }
        this.chunkSize = chunkSize;
        this.maxValueSize = maxValueSize;
    }

    @Override
    public void set(String key, InputStream value, long length) throws StoreException {
        requireKey(key);
        Objects.requireNonNull(value, "value");
        if (length < 0 || length > maxValueSize) {
            throw new IllegalArgumentException(
                    "length must be between 0 and " + maxValueSize + ", was " + length);
        }

        Path dir = keyDir(key);
        try {
            clearDir(dir);
            Files.createDirectories(dir);

            int index = 0;
            long total = 0;
            byte[] buffer = new byte[chunkSize];
            int n;
            while ((n = readFully(value, buffer)) > 0) {
                Files.write(dir.resolve(Integer.toString(index)), trim(buffer, n));
                total += n;
                index++;
                if (n < chunkSize) {
                    break; // short read => end of stream
                }
            }

            if (total != length) {
                clearDir(dir); // leave no partial object behind
                throw new StoreException("Declared length " + length
                        + " does not match actual bytes " + total + " for key " + key);
            }
            writeManifest(dir, index, total);
        } catch (IOException e) {
            throw new StoreException("Failed writing object " + key, e);
        }
    }

    @Override
    public InputStream get(String key) throws StoreException {
        requireKey(key);
        Path manifest = keyDir(key).resolve("manifest.properties");
        if (!Files.exists(manifest)) {
            throw new KeyNotFoundException(key);
        }
        int chunkCount = (int) readManifestLong(manifest, "chunkCount", key);
        return openChunks(keyDir(key), chunkCount);
    }

    @Override
    public void set(String key, byte[] value) throws StoreException {
        Objects.requireNonNull(value, "value");
        set(key, new ByteArrayInputStream(value), value.length);
    }

    @Override
    public byte[] getBytes(String key) throws StoreException {
        requireKey(key);
        Path manifest = keyDir(key).resolve("manifest.properties");
        if (!Files.exists(manifest)) {
            throw new KeyNotFoundException(key);
        }
        long totalSize = readManifestLong(manifest, "totalSize", key);
        if (totalSize > Integer.MAX_VALUE) {
            throw new StoreException("Object " + key + " is " + totalSize
                    + " bytes, too large for getBytes(); use the stream API");
        }
        try (InputStream in = get(key)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new StoreException("Failed reading object " + key, e);
        }
    }

    // ---- internals ----

    private InputStream openChunks(Path dir, int chunkCount) {
        Enumeration<InputStream> streams = new Enumeration<>() {
            private int i = 0;
            @Override public boolean hasMoreElements() {
                return i < chunkCount;
            }
            @Override public InputStream nextElement() {
                Path chunk = dir.resolve(Integer.toString(i++));
                try {
                    return Files.newInputStream(chunk);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        };
        return new SequenceInputStream(streams);
    }

    private void writeManifest(Path dir, int chunkCount, long totalSize) throws IOException {
        Properties props = new Properties();
        props.setProperty("chunkCount", Integer.toString(chunkCount));
        props.setProperty("totalSize", Long.toString(totalSize));
        try (OutputStream os = Files.newOutputStream(dir.resolve("manifest.properties"))) {
            props.store(os, "jarmin manifest");
        }
    }

    private long readManifestLong(Path manifest, String field, String key) throws StoreException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(manifest)) {
            props.load(in);
            return Long.parseLong(props.getProperty(field));
        } catch (IOException | NumberFormatException e) {
            throw new StoreException("Malformed manifest for key " + key, e);
        }
    }

    private Path keyDir(String key) {
        return baseDir.resolve(key.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    private static void clearDir(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    /** Reads up to buffer.length bytes, looping over partial reads. Returns bytes read (0 at EOF). */
    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n == -1) {
                break;
            }
            read += n;
        }
        return read;
    }

    private static byte[] trim(byte[] buffer, int n) {
        if (n == buffer.length) {
            return buffer;
        }
        byte[] out = new byte[n];
        System.arraycopy(buffer, 0, out, 0, n);
        return out;
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
    }
}
