package com.jarmin.uploader.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * A minimal, disk-backed HTTP server implementing the Jarmin chunk protocol. Suitable as a local
 * reference backend for end-to-end client testing and demos.
 *
 * <p>Storage layout under {@code <root>/<key>/}: chunk {@code i} as file {@code i}, plus a
 * {@code manifest.properties} written on {@code POST /complete}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code PUT  /objects/{key}/chunks/{index}} — store a chunk</li>
 *   <li>{@code POST /objects/{key}/complete} — finalize (body: {@code chunkCount}, {@code totalSize})</li>
 *   <li>{@code GET  /objects/{key}/manifest} — chunk count + total size (404 if absent)</li>
 *   <li>{@code GET  /objects/{key}/chunks/{index}} — download a chunk</li>
 * </ul>
 */
public final class ReferenceServer implements AutoCloseable {

    private final HttpServer server;
    private final Path root;

    public ReferenceServer(Path root) throws IOException {
        this(root, 0);
    }

    public ReferenceServer(Path root, int port) throws IOException {
        this.root = root;
        Files.createDirectories(root);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/objects/", this::handle);
    }

    public ReferenceServer start() {
        server.start();
        return this;
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public Path storageRoot() {
        return root;
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String[] p = ex.getRequestURI().getPath().split("/"); // ["", "objects", key, seg, idx?]
            if (p.length < 3) {
                send(ex, 400, null);
                return;
            }
            String key = URLDecoder.decode(p[2], StandardCharsets.UTF_8);

            if (method.equals("PUT") && p.length == 5 && p[3].equals("chunks")) {
                putChunk(ex, key, Integer.parseInt(p[4]));
            } else if (method.equals("POST") && p.length == 4 && p[3].equals("complete")) {
                complete(ex, key);
            } else if (method.equals("GET") && p.length == 4 && p[3].equals("manifest")) {
                manifest(ex, key);
            } else if (method.equals("GET") && p.length == 5 && p[3].equals("chunks")) {
                getChunk(ex, key, Integer.parseInt(p[4]));
            } else {
                send(ex, 404, null);
            }
        } catch (RuntimeException e) {
            send(ex, 500, null);
        }
    }

    private Path keyDir(String key) {
        return root.resolve(key.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    private void putChunk(HttpExchange ex, String key, int index) throws IOException {
        Path dir = keyDir(key);
        Files.createDirectories(dir);
        try (InputStream in = ex.getRequestBody()) {
            Files.copy(in, dir.resolve(Integer.toString(index)), StandardCopyOption.REPLACE_EXISTING);
        }
        send(ex, 200, null);
    }

    private void complete(HttpExchange ex, String key) throws IOException {
        Properties props = new Properties();
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        for (String line : body.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                props.setProperty(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        Path dir = keyDir(key);
        Files.createDirectories(dir);
        try (OutputStream os = Files.newOutputStream(dir.resolve("manifest.properties"))) {
            props.store(os, "jarmin manifest");
        }
        send(ex, 200, null);
    }

    private void manifest(HttpExchange ex, String key) throws IOException {
        Path manifest = keyDir(key).resolve("manifest.properties");
        if (!Files.exists(manifest)) {
            send(ex, 404, null);
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(manifest)) {
            props.load(in);
        }
        int count = Integer.parseInt(props.getProperty("chunkCount", "0"));
        String size = props.getProperty("totalSize", "0");
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) csv.append(',');
            csv.append(Files.size(keyDir(key).resolve(Integer.toString(i))));
        }
        String out = "chunkCount=" + count + "\ntotalSize=" + size + "\nchunkSizes=" + csv;
        send(ex, 200, out.getBytes(StandardCharsets.UTF_8));
    }

    private void getChunk(HttpExchange ex, String key, int index) throws IOException {
        Path chunk = keyDir(key).resolve(Integer.toString(index));
        if (!Files.exists(chunk)) {
            send(ex, 404, null);
            return;
        }
        long len = Files.size(chunk);
        ex.sendResponseHeaders(200, len == 0 ? -1 : len);
        try (OutputStream os = ex.getResponseBody(); InputStream in = Files.newInputStream(chunk)) {
            in.transferTo(os);
        }
        ex.close();
    }

    private void send(HttpExchange ex, int status, byte[] body) throws IOException {
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

    /** Recursively deletes the storage directory (best-effort). */
    public void deleteStorage() throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}
