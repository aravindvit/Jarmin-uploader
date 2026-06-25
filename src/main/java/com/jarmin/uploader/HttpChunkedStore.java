package com.jarmin.uploader;

import com.jarmin.uploader.exception.KeyNotFoundException;
import com.jarmin.uploader.exception.StoreException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

/** HTTP/REST implementation of {@link ChunkedStore}. */
public final class HttpChunkedStore implements ChunkedStore {

    /** Marks a non-2xx HTTP response so the retry policy can classify it. */
    private static final class HttpStatusException extends RuntimeException {
        final int status;
        HttpStatusException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private static final Predicate<Exception> RETRYABLE = e -> {
        if (e instanceof HttpStatusException hse) {
            return hse.status >= 500 || hse.status == 429;
        }
        return e instanceof IOException;
    };

    private final StoreConfig config;
    private final HttpClient http;
    private final RetryExecutor retry;

    public HttpChunkedStore(StoreConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.http = config.httpClient();
        this.retry = new RetryExecutor(config.maxRetries(), config.backoffBase(), config.backoffMax());
    }

    @Override
    public void set(String key, InputStream value, long length) throws StoreException {
        requireKey(key);
        Objects.requireNonNull(value, "value");
        if (length < 0 || length > config.maxValueSize()) {
            throw new IllegalArgumentException(
                    "length must be between 0 and " + config.maxValueSize() + ", was " + length);
        }

        ChunkReader reader = new ChunkReader(value, config.chunkSize());
        int index = 0;
        long total = 0;
        try {
            byte[] chunk;
            while ((chunk = reader.next()) != null) {
                putChunk(key, index, chunk);
                total += chunk.length;
                index++;
            }
        } catch (IOException e) {
            throw new StoreException("Failed reading source for key " + key, e);
        }

        if (total != length) {
            throw new StoreException(
                    "Declared length " + length + " does not match actual bytes " + total
                            + " for key " + key);
        }
        complete(key, index, total);
    }

    @Override
    public InputStream get(String key) throws StoreException {
        requireKey(key);
        Manifest manifest = fetchManifest(key);
        return new ReassemblingInputStream(this, key, manifest.chunkCount());
    }

    @Override
    public void set(String key, byte[] value) throws StoreException {
        Objects.requireNonNull(value, "value");
        set(key, new ByteArrayInputStream(value), value.length);
    }

    @Override
    public byte[] getBytes(String key) throws StoreException {
        requireKey(key);
        Manifest manifest = fetchManifest(key);
        if (manifest.totalSize() > Integer.MAX_VALUE) {
            throw new StoreException("Object " + key + " is " + manifest.totalSize()
                    + " bytes, too large for getBytes(); use the stream API");
        }
        try (InputStream in = new ReassemblingInputStream(this, key, manifest.chunkCount())) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new StoreException("Failed downloading object " + key, e);
        }
    }

    // ---- internals ----

    private void putChunk(String key, int index, byte[] chunk) throws StoreException {
        HttpRequest request = newRequest("/objects/" + enc(key) + "/chunks/" + index)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(chunk))
                .header("Content-Type", "application/octet-stream")
                .build();
        retry.execute(() -> {
            HttpResponse<Void> resp = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 != 2) {
                throw new HttpStatusException(resp.statusCode(),
                        "Chunk " + index + " upload returned HTTP " + resp.statusCode());
            }
            return null;
        }, RETRYABLE);
    }

    private void complete(String key, int chunkCount, long totalSize) throws StoreException {
        String body = "chunkCount=" + chunkCount + "\ntotalSize=" + totalSize;
        HttpRequest request = newRequest("/objects/" + enc(key) + "/complete")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        retry.execute(() -> {
            HttpResponse<Void> resp = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() / 100 != 2) {
                throw new HttpStatusException(resp.statusCode(),
                        "Complete returned HTTP " + resp.statusCode());
            }
            return null;
        }, RETRYABLE);
    }

    private Manifest fetchManifest(String key) throws StoreException {
        HttpRequest request = newRequest("/objects/" + enc(key) + "/manifest").GET().build();
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                throw new KeyNotFoundException(key);
            }
            if (resp.statusCode() / 100 != 2) {
                throw new StoreException("Manifest fetch returned HTTP " + resp.statusCode());
            }
            return Manifest.parse(resp.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new StoreException("Failed fetching manifest for key " + key, e);
        }
    }

    /** Opens a chunk's body stream for reassembly. Package-private for {@link ReassemblingInputStream}. */
    InputStream openChunk(String key, int index) throws IOException {
        HttpRequest request = newRequest("/objects/" + enc(key) + "/chunks/" + index).GET().build();
        try {
            HttpResponse<InputStream> resp = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                resp.body().close();
                throw new IOException("Chunk " + index + " download returned HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading chunk " + index, e);
        }
    }

    private HttpRequest.Builder newRequest(String path) {
        URI uri = URI.create(stripTrailingSlash(config.baseUri().toString()) + path);
        return HttpRequest.newBuilder(uri).timeout(config.requestTimeout());
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String enc(String key) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
    }
}
