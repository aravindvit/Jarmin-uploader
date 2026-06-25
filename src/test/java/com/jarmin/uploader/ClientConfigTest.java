package com.jarmin.uploader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientConfigTest {

    @TempDir
    Path tmp;

    private static ClientConfig load(String props) throws IOException {
        return ClientConfig.load(new ByteArrayInputStream(props.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesFileMode() throws IOException {
        ClientConfig cfg = load("""
                storage.mode=file
                client.path=/var/data/jarmin
                chunk.size=2048
                max.byte.size=1048576
                """);

        assertEquals(ClientConfig.Mode.FILE, cfg.mode());
        assertEquals(Path.of("/var/data/jarmin"), cfg.clientPath());
        assertEquals(2048, cfg.chunkSize());
        assertEquals(1048576L, cfg.maxByteSize());
    }

    @Test
    void parsesServerMode() throws IOException {
        ClientConfig cfg = load("""
                storage.mode=server
                server.endpoint=https://store.example.com/api
                chunk.size=1048576
                max.byte.size=5368709120
                """);

        assertEquals(ClientConfig.Mode.SERVER, cfg.mode());
        assertEquals(URI.create("https://store.example.com/api"), cfg.serverEndpoint());
        assertEquals(1048576, cfg.chunkSize());
        assertEquals(5368709120L, cfg.maxByteSize());
    }

    @Test
    void appliesDefaultsForChunkAndMaxSize() throws IOException {
        ClientConfig cfg = load("""
                storage.mode=file
                client.path=/tmp/x
                """);
        assertEquals(1024 * 1024, cfg.chunkSize());
        assertEquals(5L * 1024 * 1024 * 1024, cfg.maxByteSize());
    }

    @Test
    void rejectsUnknownMode() {
        assertThrows(IllegalArgumentException.class, () -> load("storage.mode=carrier-pigeon\n"));
    }

    @Test
    void fileModeRequiresClientPath() {
        assertThrows(IllegalArgumentException.class, () -> load("storage.mode=file\n"));
    }

    @Test
    void serverModeRequiresEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> load("storage.mode=server\n"));
    }

    @Test
    void loadsFromFilePath() throws IOException {
        Path file = tmp.resolve("client.properties");
        Files.writeString(file, "storage.mode=file\nclient.path=" + tmp.resolve("store") + "\n");
        ClientConfig cfg = ClientConfig.load(file);
        assertEquals(ClientConfig.Mode.FILE, cfg.mode());
    }

    @Test
    void createStoreReturnsFileBackedStoreInFileMode() throws IOException {
        ClientConfig cfg = load("storage.mode=file\nclient.path=" + tmp.resolve("store") + "\n");
        ChunkedStore store = cfg.createStore();
        assertInstanceOf(FileChunkedStore.class, store);
    }

    @Test
    void createStoreReturnsHttpStoreInServerMode() throws IOException {
        ClientConfig cfg = load("storage.mode=server\nserver.endpoint=http://localhost:8080\n");
        ChunkedStore store = cfg.createStore();
        assertInstanceOf(HttpChunkedStore.class, store);
    }
}
