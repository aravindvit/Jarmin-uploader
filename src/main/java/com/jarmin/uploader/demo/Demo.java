package com.jarmin.uploader.demo;

import com.jarmin.uploader.ChunkedStore;
import com.jarmin.uploader.ClientConfig;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

/**
 * Runnable demo client driven by {@code client.properties}. Loads the configuration, builds the
 * configured {@link ChunkedStore} (local file directory or remote server), then runs a real
 * set/get round trip.
 *
 * <p>Run with: {@code ./gradlew run}. Optionally pass a config path and payload size:
 * {@code ./gradlew run --args="client.properties 3000000"}.
 */
public final class Demo {

    public static void main(String[] args) throws Exception {
        Path configPath = Path.of(args.length > 0 ? args[0] : "client.properties");
        ClientConfig cfg = ClientConfig.load(configPath);
        int payloadSize = args.length > 1 ? Integer.parseInt(args[1]) : 3 * cfg.chunkSize() + 1234;

        System.out.println("=== Jarmin Uploader demo ===");
        System.out.println("Config file   : " + configPath.toAbsolutePath());
        System.out.println("Storage mode  : " + cfg.mode());
        if (cfg.mode() == ClientConfig.Mode.FILE) {
            System.out.println("Client path   : " + cfg.clientPath().toAbsolutePath());
        } else {
            System.out.println("Server URL    : " + cfg.serverEndpoint());
        }
        System.out.println("Chunk size    : " + cfg.chunkSize() + " bytes");
        System.out.println("Max byte size : " + cfg.maxByteSize() + " bytes");
        System.out.println("Payload size  : " + payloadSize + " bytes");
        System.out.println();

        ChunkedStore store = cfg.createStore();
        String key = "demo-object";
        byte[] payload = randomBytes(payloadSize);

        System.out.println("set(\"" + key + "\", " + payloadSize + " bytes) ...");
        store.set(key, new ByteArrayInputStream(payload), payload.length);

        if (cfg.mode() == ClientConfig.Mode.FILE) {
            Path objDir = cfg.clientPath().resolve(key);
            System.out.println("  -> chunk files written under " + objDir + ":");
            listChunkFiles(objDir);
        } else {
            System.out.println("  -> uploaded to server");
        }

        System.out.println("get(\"" + key + "\") ...");
        byte[] roundTripped;
        try (InputStream in = store.get(key)) {
            roundTripped = in.readAllBytes();
        }
        System.out.println("  -> downloaded " + roundTripped.length + " bytes");

        boolean ok = Arrays.equals(payload, roundTripped);
        System.out.println();
        System.out.println("Round-trip integrity: " + (ok ? "PASS ✓" : "FAIL ✗"));
        if (!ok) {
            System.exit(1);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }

    private static void listChunkFiles(Path objDir) throws Exception {
        if (!Files.exists(objDir)) {
            return;
        }
        try (var s = Files.list(objDir)) {
            s.sorted().forEach(p -> {
                try {
                    System.out.println("       " + p.getFileName() + "  (" + Files.size(p) + " bytes)");
                } catch (Exception ignored) {
                }
            });
        }
    }
}
