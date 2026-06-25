package com.jarmin.uploader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Client configuration loaded from a {@code client.properties} file. Selects the storage backend
 * and builds the matching {@link ChunkedStore} via {@link #createStore()}.
 *
 * <p>Recognised keys:
 * <ul>
 *   <li>{@code storage.mode} — {@code file} or {@code server} (required)</li>
 *   <li>{@code client.path} — local storage directory (required when mode = file)</li>
 *   <li>{@code server.endpoint} — base URL of the chunk server (required when mode = server)</li>
 *   <li>{@code chunk.size} — bytes per chunk (default 1 MB)</li>
 *   <li>{@code max.byte.size} — maximum value size in bytes (default 5 GB)</li>
 * </ul>
 */
public final class ClientConfig {

    public enum Mode { FILE, SERVER }

    private final Mode mode;
    private final Path clientPath;
    private final URI serverEndpoint;
    private final int chunkSize;
    private final long maxByteSize;

    private ClientConfig(Mode mode, Path clientPath, URI serverEndpoint, int chunkSize, long maxByteSize) {
        this.mode = mode;
        this.clientPath = clientPath;
        this.serverEndpoint = serverEndpoint;
        this.chunkSize = chunkSize;
        this.maxByteSize = maxByteSize;
    }

    public static ClientConfig load(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return load(in);
        }
    }

    public static ClientConfig load(InputStream in) throws IOException {
        Properties props = new Properties();
        props.load(in);
        return fromProperties(props);
    }

    public static ClientConfig fromProperties(Properties props) {
        Mode mode = parseMode(props.getProperty("storage.mode"));

        int chunkSize = parsePositiveInt(props.getProperty("chunk.size"),
                StoreConfig.DEFAULT_CHUNK_SIZE, "chunk.size");
        long maxByteSize = parsePositiveLong(props.getProperty("max.byte.size"),
                StoreConfig.MAX_VALUE_SIZE, "max.byte.size");

        Path clientPath = null;
        URI serverEndpoint = null;
        if (mode == Mode.FILE) {
            String path = require(props, "client.path", "storage.mode=file");
            clientPath = Path.of(path);
        } else {
            String endpoint = require(props, "server.endpoint", "storage.mode=server");
            serverEndpoint = URI.create(endpoint);
        }
        return new ClientConfig(mode, clientPath, serverEndpoint, chunkSize, maxByteSize);
    }

    /** Builds the {@link ChunkedStore} described by this configuration. */
    public ChunkedStore createStore() {
        return switch (mode) {
            case FILE -> new FileChunkedStore(clientPath, chunkSize, maxByteSize);
            case SERVER -> new HttpChunkedStore(StoreConfig.builder(serverEndpoint)
                    .chunkSize(chunkSize)
                    .maxValueSize(maxByteSize)
                    .build());
        };
    }

    public Mode mode() {
        return mode;
    }

    public Path clientPath() {
        return clientPath;
    }

    public URI serverEndpoint() {
        return serverEndpoint;
    }

    public int chunkSize() {
        return chunkSize;
    }

    public long maxByteSize() {
        return maxByteSize;
    }

    // ---- parsing helpers ----

    private static Mode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("storage.mode is required (file or server)");
        }
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown storage.mode '" + raw + "' (expected file or server)");
        }
    }

    private static String require(Properties props, String key, String because) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(key + " is required when " + because);
        }
        return v.trim();
    }

    private static int parsePositiveInt(String raw, int dflt, String key) {
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, was '" + raw + "'");
        }
        if (v <= 0) {
            throw new IllegalArgumentException(key + " must be > 0, was " + v);
        }
        return v;
    }

    private static long parsePositiveLong(String raw, long dflt, String key) {
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        long v;
        try {
            v = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, was '" + raw + "'");
        }
        if (v <= 0) {
            throw new IllegalArgumentException(key + " must be > 0, was " + v);
        }
        return v;
    }
}
