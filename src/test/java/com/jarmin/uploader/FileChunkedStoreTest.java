package com.jarmin.uploader;

import com.jarmin.uploader.exception.KeyNotFoundException;
import com.jarmin.uploader.exception.StoreException;
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

class FileChunkedStoreTest {

    private static final int CHUNK = 1024;

    @TempDir
    Path dir;

    private ChunkedStore store() {
        return new FileChunkedStore(dir, CHUNK);
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }

    @Test
    void writesOneFilePerChunkAndRoundTrips() throws StoreException, IOException {
        ChunkedStore store = store();
        byte[] data = random(2 * CHUNK + CHUNK / 2); // 3 chunks

        store.set("obj", new ByteArrayInputStream(data), data.length);

        Path objDir = dir.resolve("obj");
        assertTrue(Files.exists(objDir.resolve("0")));
        assertTrue(Files.exists(objDir.resolve("1")));
        assertTrue(Files.exists(objDir.resolve("2")));
        assertEquals(CHUNK, Files.size(objDir.resolve("0")));
        assertEquals(CHUNK / 2, Files.size(objDir.resolve("2")));

        try (InputStream in = store.get("obj")) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test
    void emptyPayloadRoundTrips() throws StoreException, IOException {
        ChunkedStore store = store();
        store.set("empty", new byte[0]);
        try (InputStream in = store.get("empty")) {
            assertEquals(-1, in.read());
        }
    }

    @Test
    void getUnknownKeyThrows() {
        assertThrows(KeyNotFoundException.class, () -> store().get("nope"));
    }

    @Test
    void byteArrayApisRoundTrip() throws StoreException {
        ChunkedStore store = store();
        byte[] data = random(CHUNK + 7);
        store.set("k", data);
        assertArrayEquals(data, store.getBytes("k"));
    }

    @Test
    void overwritingKeyReplacesPreviousChunks() throws StoreException, IOException {
        ChunkedStore store = store();
        store.set("k", random(3 * CHUNK));   // 3 chunks
        store.set("k", random(CHUNK));        // now 1 chunk

        Path objDir = dir.resolve("k");
        assertTrue(Files.exists(objDir.resolve("0")));
        assertTrue(Files.notExists(objDir.resolve("1")), "stale chunk should be removed");
    }

    @Test
    void rejectsValueLargerThanConfiguredMax() {
        ChunkedStore store = new FileChunkedStore(dir, CHUNK, 100); // max 100 bytes
        assertThrows(IllegalArgumentException.class,
                () -> store.set("big", new ByteArrayInputStream(new byte[200]), 200));
    }
}
