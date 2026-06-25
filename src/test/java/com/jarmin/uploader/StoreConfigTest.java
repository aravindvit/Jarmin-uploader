package com.jarmin.uploader;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StoreConfigTest {

    private static final URI BASE = URI.create("https://example.test/api");

    @Test
    void rejectsNonPositiveChunkSize() {
        // CFG-1
        assertThrows(IllegalArgumentException.class,
                () -> StoreConfig.builder(BASE).chunkSize(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> StoreConfig.builder(BASE).chunkSize(-1).build());
    }

    @Test
    void rejectsNullBaseUri() {
        // CFG-2
        assertThrows(IllegalArgumentException.class, () -> StoreConfig.builder(null));
    }

    @Test
    void rejectsNegativeMaxRetries() {
        // CFG-3
        assertThrows(IllegalArgumentException.class,
                () -> StoreConfig.builder(BASE).maxRetries(-1).build());
    }

    @Test
    void appliesDefaults() {
        // CFG-4
        StoreConfig config = StoreConfig.builder(BASE).build();
        assertEquals(1024 * 1024, config.chunkSize());
        assertEquals(3, config.maxRetries());
        assertEquals(Duration.ofMillis(200), config.backoffBase());
        assertEquals(Duration.ofSeconds(10), config.backoffMax());
        assertEquals(BASE, config.baseUri());
        assertEquals(5L * 1024 * 1024 * 1024, config.maxValueSize());
    }

    @Test
    void maxValueSizeIsConfigurable() {
        StoreConfig config = StoreConfig.builder(BASE).maxValueSize(10_000_000L).build();
        assertEquals(10_000_000L, config.maxValueSize());
    }

    @Test
    void rejectsNonPositiveMaxValueSize() {
        assertThrows(IllegalArgumentException.class,
                () -> StoreConfig.builder(BASE).maxValueSize(0).build());
    }

    @Test
    void overridesAreHonoured() {
        StoreConfig config = StoreConfig.builder(BASE)
                .chunkSize(2048)
                .maxRetries(5)
                .backoffBase(Duration.ofMillis(50))
                .backoffMax(Duration.ofSeconds(2))
                .build();
        assertEquals(2048, config.chunkSize());
        assertEquals(5, config.maxRetries());
        assertEquals(Duration.ofMillis(50), config.backoffBase());
        assertEquals(Duration.ofSeconds(2), config.backoffMax());
    }
}
