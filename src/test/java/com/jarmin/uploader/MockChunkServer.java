package com.jarmin.uploader;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory HTTP server implementing the chunk protocol, for integration tests.
 *
 * <p>Supports fault injection (fail a chunk index N times with a status), a discard mode for
 * very large uploads (count bytes without storing them), and records the order of chunk PUTs.
 */
final class MockChunkServer implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, TreeMap<Integer, byte[]>> store = new ConcurrentHashMap<>();
    private final Map<String, Long> completedSize = new ConcurrentHashMap<>();
    private final Map<String, Integer> completedCount = new ConcurrentHashMap<>();

    // Fault injection: "PUT key index" -> remaining failures + status.
    private final Map<String, int[]> chunkFaults = new ConcurrentHashMap<>(); // value = {remaining, status}

    private final List<Integer> putOrder = new ArrayList<>();
    private boolean discardBodies = false;
    private final AtomicLong discardedBytes = new AtomicLong();
    private final AtomicLong discardedChunks = new AtomicLong();

    MockChunkServer() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/objects/", this::handle);
        this.server.setExecutor(null);
    }

    MockChunkServer start() {
        server.start();
        return this;
    }

    URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    MockChunkServer discardBodies(boolean discard) {
        this.discardBodies = discard;
        return this;
    }

    /** Make the first {@code times} PUTs for {@code index} respond with {@code status}. */
    MockChunkServer failChunk(String key, int index, int times, int status) {
        chunkFaults.put(key + " " + index, new int[]{times, status});
        return this;
    }

    synchronized List<Integer> putOrder() {
        return new ArrayList<>(putOrder);
    }

    Integer completedCount(String key) {
        return completedCount.get(key);
    }

    Long completedSize(String key) {
        return completedSize.get(key);
    }

    long discardedBytes() {
        return discardedBytes.get();
    }

    long discardedChunks() {
        return discardedChunks.get();
    }

    byte[] assembled(String key) {
        TreeMap<Integer, byte[]> chunks = store.get(key);
        if (chunks == null) {
            return null;
        }
        int total = chunks.values().stream().mapToInt(b -> b.length).sum();
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] c : chunks.values()) {
            System.arraycopy(c, 0, out, off, c.length);
            off += c.length;
        }
        return out;
    }

    /** Seed an object directly (no upload), so get()/manifest can be tested in isolation. */
    void seed(String key, byte[]... chunks) {
        TreeMap<Integer, byte[]> map = new TreeMap<>();
        long total = 0;
        for (int i = 0; i < chunks.length; i++) {
            map.put(i, chunks[i]);
            total += chunks[i].length;
        }
        store.put(key, map);
        completedCount.put(key, chunks.length);
        completedSize.put(key, total);
    }

    /** Register a manifest with a given total size and chunk count but no downloadable chunks. */
    void seedManifestOnly(String key, long totalSize, int chunkCount) {
        completedCount.put(key, chunkCount);
        completedSize.put(key, totalSize);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String[] parts = ex.getRequestURI().getPath().split("/"); // ["", "objects", key, ...]
            if (parts.length < 3) {
                respond(ex, 400, null);
                return;
            }
            String key = parts[2];

            if (method.equals("PUT") && parts.length == 5 && parts[3].equals("chunks")) {
                handlePut(ex, key, Integer.parseInt(parts[4]));
            } else if (method.equals("POST") && parts.length == 4 && parts[3].equals("complete")) {
                handleComplete(ex, key);
            } else if (method.equals("GET") && parts.length == 4 && parts[3].equals("manifest")) {
                handleManifest(ex, key);
            } else if (method.equals("GET") && parts.length == 5 && parts[3].equals("chunks")) {
                handleGetChunk(ex, key, Integer.parseInt(parts[4]));
            } else {
                respond(ex, 404, null);
            }
        } catch (RuntimeException e) {
            respond(ex, 500, null);
        }
    }

    private void handlePut(HttpExchange ex, String key, int index) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        synchronized (this) {
            putOrder.add(index); // record every attempt, including ones we fault below
        }
        int[] fault = chunkFaults.get(key + " " + index);
        if (fault != null && fault[0] > 0) {
            fault[0]--;
            respond(ex, fault[1], null);
            return;
        }
        if (discardBodies) {
            discardedBytes.addAndGet(body.length);
            discardedChunks.incrementAndGet();
        } else {
            store.computeIfAbsent(key, k -> new TreeMap<>()).put(index, body);
        }
        respond(ex, 200, null);
    }

    private void handleComplete(HttpExchange ex, String key) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        for (String line : body.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (k.equals("chunkCount")) completedCount.put(key, Integer.parseInt(v));
            if (k.equals("totalSize")) completedSize.put(key, Long.parseLong(v));
        }
        respond(ex, 200, null);
    }

    private void handleManifest(HttpExchange ex, String key) throws IOException {
        TreeMap<Integer, byte[]> chunks = store.get(key);
        if (chunks == null && !completedCount.containsKey(key)) {
            respond(ex, 404, null);
            return;
        }
        int count = completedCount.getOrDefault(key, chunks == null ? 0 : chunks.size());
        long size = completedSize.getOrDefault(key,
                chunks == null ? 0L : chunks.values().stream().mapToLong(b -> b.length).sum());
        StringBuilder sizes = new StringBuilder();
        if (chunks != null) {
            for (byte[] c : chunks.values()) {
                if (sizes.length() > 0) sizes.append(',');
                sizes.append(c.length);
            }
        }
        String body = "chunkCount=" + count + "\ntotalSize=" + size + "\nchunkSizes=" + sizes;
        respond(ex, 200, body.getBytes(StandardCharsets.UTF_8));
    }

    private void handleGetChunk(HttpExchange ex, String key, int index) throws IOException {
        TreeMap<Integer, byte[]> chunks = store.get(key);
        if (chunks == null || !chunks.containsKey(index)) {
            respond(ex, 404, null);
            return;
        }
        respond(ex, 200, chunks.get(index));
    }

    private void respond(HttpExchange ex, int status, byte[] body) throws IOException {
        byte[] payload = body == null ? new byte[0] : body;
        ex.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(payload);
            }
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
