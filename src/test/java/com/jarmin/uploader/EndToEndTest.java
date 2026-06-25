package com.jarmin.uploader;

import com.jarmin.uploader.exception.KeyNotFoundException;
import com.jarmin.uploader.exception.StoreException;
import com.jarmin.uploader.server.ReferenceServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full client-to-server round trips: the real {@link HttpChunkedStore} client talking over HTTP to
 * the disk-backed {@link ReferenceServer}, with chunks persisted to a local directory.
 */
class EndToEndTest {

    private static final int CHUNK = 64 * 1024; // 64 KB chunks

    @TempDir
    Path storage;

    private ReferenceServer server;
    private ChunkedStore client;

    @BeforeEach
    void setUp() throws IOException {
        server = new ReferenceServer(storage).start();
        StoreConfig config = StoreConfig.builder(server.baseUri()).chunkSize(CHUNK).build();
        client = new HttpChunkedStore(config);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }

    @Test
    void streamRoundTripPersistsChunksToDiskAndReassembles() throws StoreException, IOException {
        byte[] data = random(2 * CHUNK + CHUNK / 3); // 3 chunks
        client.set("report.bin", new ByteArrayInputStream(data), data.length);

        // chunks landed on disk under the server's storage dir
        Path objDir = storage.resolve("report.bin");
        assertTrue(Files.exists(objDir.resolve("0")));
        assertTrue(Files.exists(objDir.resolve("2")));
        assertTrue(Files.exists(objDir.resolve("manifest.properties")));

        try (InputStream in = client.get("report.bin")) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test
    void byteArrayRoundTrip() throws StoreException {
        byte[] data = random(CHUNK + 123);
        client.set("blob", data);
        assertArrayEquals(data, client.getBytes("blob"));
    }

    @Test
    void emptyValueRoundTrips() throws StoreException, IOException {
        client.set("empty", new byte[0]);
        try (InputStream in = client.get("empty")) {
            assertEquals(-1, in.read());
        }
    }

    @Test
    void getUnknownKeyThrowsKeyNotFound() {
        assertThrows(KeyNotFoundException.class, () -> client.get("missing"));
    }

    @Test
    void multipleKeysAreIndependent() throws StoreException {
        byte[] a = random(CHUNK);
        byte[] b = random(3 * CHUNK);
        client.set("a", a);
        client.set("b", b);
        assertArrayEquals(a, client.getBytes("a"));
        assertArrayEquals(b, client.getBytes("b"));
    }
}
