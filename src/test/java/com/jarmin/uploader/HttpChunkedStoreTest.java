package com.jarmin.uploader;

import com.jarmin.uploader.exception.ChunkUploadException;
import com.jarmin.uploader.exception.KeyNotFoundException;
import com.jarmin.uploader.exception.StoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpChunkedStoreTest {

    private static final int CHUNK = 1024; // small chunk for fast tests

    private MockChunkServer server;
    private ChunkedStore store;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockChunkServer().start();
        store = newStore();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private ChunkedStore newStore() {
        StoreConfig config = StoreConfig.builder(server.baseUri())
                .chunkSize(CHUNK)
                .maxRetries(3)
                .backoffBase(Duration.ofMillis(1))
                .backoffMax(Duration.ofMillis(2))
                .build();
        return new HttpChunkedStore(config);
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }

    private void set(String key, byte[] data) throws StoreException {
        store.set(key, new ByteArrayInputStream(data), data.length);
    }

    // ---- SET ----

    @Test
    void uploadsExactMultipleAndCompletes() throws StoreException {
        // SET-1: 3 chunks
        byte[] data = random(3 * CHUNK);
        set("k1", data);

        assertEquals(List.of(0, 1, 2), server.putOrder());
        assertEquals(3, server.completedCount("k1"));
        assertEquals(3L * CHUNK, server.completedSize("k1"));
        assertArrayEquals(data, server.assembled("k1"));
    }

    @Test
    void uploadsRemainderChunk() throws StoreException {
        // SET-2: 2.5 chunks
        byte[] data = random(2 * CHUNK + CHUNK / 2);
        set("k2", data);

        assertEquals(3, server.completedCount("k2"));
        assertEquals(data.length, server.completedSize("k2").intValue());
        assertArrayEquals(data, server.assembled("k2"));
    }

    @Test
    void emptyPayloadUploadsZeroChunksButCompletes() throws StoreException {
        // SET-3
        set("empty", new byte[0]);
        assertEquals(List.of(), server.putOrder());
        assertEquals(0, server.completedCount("empty"));
        assertEquals(0L, server.completedSize("empty"));
    }

    @Test
    void retriesTransientChunkFailureThenSucceeds() throws StoreException {
        // SET-4: chunk 1 fails 503 twice then succeeds
        server.failChunk("k4", 1, 2, 503);
        byte[] data = random(3 * CHUNK);
        set("k4", data);

        assertEquals(3, server.completedCount("k4"));
        assertArrayEquals(data, server.assembled("k4"));
    }

    @Test
    void failsWhenChunkExceedsRetriesAndDoesNotComplete() {
        // SET-5: chunk 2 fails beyond maxRetries (4 attempts) -> fail, no /complete
        server.failChunk("k5", 2, 99, 503);
        byte[] data = random(3 * CHUNK);

        assertThrows(ChunkUploadException.class, () -> set("k5", data));
        assertNull(server.completedCount("k5"));
    }

    @Test
    void clientErrorOnChunkIsNotRetried() {
        // SET-6: 400 is non-retryable -> exactly one PUT for that index
        server.failChunk("k6", 0, 99, 400);
        byte[] data = random(2 * CHUNK);

        assertThrows(ChunkUploadException.class, () -> set("k6", data));
        assertEquals(1, server.putOrder().stream().filter(i -> i == 0).count());
    }

    @Test
    void rejectsValueLargerThanFiveGb() {
        // SET-7
        assertThrows(IllegalArgumentException.class,
                () -> store.set("big", new ZeroInputStream(0), StoreConfig.MAX_VALUE_SIZE + 1));
        assertEquals(List.of(), server.putOrder());
    }

    @Test
    void failsWhenDeclaredLengthExceedsActualBytes() {
        // SET-8: declares more than the stream provides -> StoreException, no complete
        byte[] data = random(CHUNK);
        assertThrows(StoreException.class,
                () -> store.set("k8", new ByteArrayInputStream(data), CHUNK * 5L));
        assertNull(server.completedCount("k8"));
    }

    @Test
    void byteArrayOverloadChunksLikeStream() throws StoreException {
        // SET-10
        byte[] data = random(2 * CHUNK + 10);
        store.set("k10", data);
        assertArrayEquals(data, server.assembled("k10"));
        assertEquals(3, server.completedCount("k10"));
    }

    @Test
    void streamsLargePayloadWithConstantMemory() throws StoreException {
        // SET-11: 2.5 GB streamed, server discards bodies. Must not OOM.
        server.discardBodies(true);
        long size = 2L * 1024 * 1024 * 1024 + 500; // > Integer.MAX_VALUE
        StoreConfig config = StoreConfig.builder(server.baseUri())
                .chunkSize(8 * 1024 * 1024) // 8 MB chunks to keep request count sane
                .build();
        ChunkedStore bigStore = new HttpChunkedStore(config);

        bigStore.set("huge", new ZeroInputStream(size), size);

        long expectedChunks = (size + (8L * 1024 * 1024) - 1) / (8L * 1024 * 1024);
        assertEquals(expectedChunks, server.discardedChunks());
        assertEquals(size, server.discardedBytes());
        assertEquals(size, server.completedSize("huge"));
    }

    // ---- GET ----

    @Test
    void getReassemblesChunksInOrder() throws StoreException, IOException {
        // GET-1
        byte[] a = random(CHUNK);
        byte[] b = random(CHUNK);
        byte[] c = random(CHUNK / 2);
        server.seed("g1", a, b, c);

        byte[] got = readAll(store.get("g1"));
        assertArrayEquals(concat(a, b, c), got);
    }

    @Test
    void getUnknownKeyThrowsKeyNotFound() {
        // GET-2
        assertThrows(KeyNotFoundException.class, () -> store.get("missing"));
    }

    @Test
    void getSingleChunkObject() throws StoreException, IOException {
        // GET-3
        byte[] a = random(500);
        server.seed("g3", a);
        assertArrayEquals(a, readAll(store.get("g3")));
    }

    @Test
    void getEmptyObjectReturnsEmptyStream() throws StoreException, IOException {
        // GET-4
        server.seed("g4"); // zero chunks
        assertEquals(-1, store.get("g4").read());
    }

    @Test
    void getBytesReturnsFullArray() throws StoreException {
        // GET-6
        byte[] a = random(CHUNK);
        byte[] b = random(300);
        server.seed("g6", a, b);
        assertArrayEquals(concat(a, b), store.getBytes("g6"));
    }

    @Test
    void getBytesRejectsObjectLargerThanIntMax() {
        // GET-7: manifest reports > Integer.MAX_VALUE -> refuse before download
        server.seedManifestOnly("g7", 3L * 1024 * 1024 * 1024, 3);
        assertThrows(StoreException.class, () -> store.getBytes("g7"));
    }

    // ---- Round trip ----

    @Test
    void roundTripRandomBytes() throws StoreException, IOException {
        // RT-1
        byte[] data = random(2 * CHUNK + CHUNK / 2);
        set("rt1", data);
        assertArrayEquals(data, readAll(store.get("rt1")));
    }

    @Test
    void roundTripEmpty() throws StoreException, IOException {
        // RT-2
        set("rt2", new byte[0]);
        assertEquals(-1, store.get("rt2").read());
    }

    @Test
    void roundTripByteArrayApis() throws StoreException {
        // RT-3
        byte[] data = random(CHUNK + 7);
        store.set("rt3", data);
        assertArrayEquals(data, store.getBytes("rt3"));
    }

    // ---- helpers ----

    private static byte[] readAll(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
