package com.jarmin.uploader;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChunkReaderTest {

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (i % 256);
        }
        return b;
    }

    private static List<byte[]> readAll(ChunkReader reader) throws IOException {
        List<byte[]> chunks = new ArrayList<>();
        byte[] chunk;
        while ((chunk = reader.next()) != null) {
            chunks.add(chunk);
        }
        return chunks;
    }

    @Test
    void exactMultipleOfChunkSizeYieldsFullChunks() throws IOException {
        // CR-1: 3 chunks of size 4
        byte[] data = bytes(12);
        ChunkReader reader = new ChunkReader(new ByteArrayInputStream(data), 4);

        List<byte[]> chunks = readAll(reader);

        assertEquals(3, chunks.size());
        chunks.forEach(c -> assertEquals(4, c.length));
        assertNull(reader.next());
    }

    @Test
    void nonMultipleYieldsShorterFinalChunk() throws IOException {
        // CR-2: 4 + 4 + 2
        byte[] data = bytes(10);
        ChunkReader reader = new ChunkReader(new ByteArrayInputStream(data), 4);

        List<byte[]> chunks = readAll(reader);

        assertEquals(3, chunks.size());
        assertEquals(4, chunks.get(0).length);
        assertEquals(4, chunks.get(1).length);
        assertEquals(2, chunks.get(2).length);
    }

    @Test
    void sourceSmallerThanChunkYieldsSingleShortChunk() throws IOException {
        // CR-3
        byte[] data = bytes(3);
        ChunkReader reader = new ChunkReader(new ByteArrayInputStream(data), 10);

        List<byte[]> chunks = readAll(reader);

        assertEquals(1, chunks.size());
        assertArrayEquals(data, chunks.get(0));
    }

    @Test
    void emptySourceYieldsZeroChunks() throws IOException {
        // CR-4
        ChunkReader reader = new ChunkReader(new ByteArrayInputStream(new byte[0]), 4);
        assertNull(reader.next());
        assertEquals(0, readAll(new ChunkReader(new ByteArrayInputStream(new byte[0]), 4)).size());
    }

    @Test
    void handlesPartialReadsFromUnderlyingStream() throws IOException {
        // CR-5: a stream that returns at most 1 byte per read must still fill the chunk
        byte[] data = bytes(8);
        InputStream oneByteAtATime = new InputStream() {
            int pos = 0;
            @Override public int read() {
                return pos < data.length ? (data[pos++] & 0xff) : -1;
            }
            @Override public int read(byte[] b, int off, int len) {
                if (pos >= data.length) return -1;
                b[off] = data[pos++];
                return 1; // deliberately only ever returns 1 byte
            }
        };
        ChunkReader reader = new ChunkReader(oneByteAtATime, 4);

        List<byte[]> chunks = readAll(reader);

        assertEquals(2, chunks.size());
        assertEquals(4, chunks.get(0).length);
        assertEquals(4, chunks.get(1).length);
        assertArrayEquals(data, concat(chunks));
    }

    @Test
    void singleByteSource() throws IOException {
        // CR-6
        ChunkReader reader = new ChunkReader(new ByteArrayInputStream(new byte[]{42}), 4);
        List<byte[]> chunks = readAll(reader);
        assertEquals(1, chunks.size());
        assertArrayEquals(new byte[]{42}, chunks.get(0));
    }

    private static byte[] concat(List<byte[]> parts) {
        int total = parts.stream().mapToInt(p -> p.length).sum();
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }
}
