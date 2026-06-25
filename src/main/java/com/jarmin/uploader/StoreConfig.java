package com.jarmin.uploader;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

/** Immutable configuration for {@link HttpChunkedStore}. Build via {@link #builder(URI)}. */
public final class StoreConfig {

    /** Default chunk size: 1 MB. */
    public static final int DEFAULT_CHUNK_SIZE = 1024 * 1024;
    /** Maximum supported value size: 5 GB. */
    public static final long MAX_VALUE_SIZE = 5L * 1024 * 1024 * 1024;

    private final URI baseUri;
    private final int chunkSize;
    private final long maxValueSize;
    private final int maxRetries;
    private final Duration backoffBase;
    private final Duration backoffMax;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    private StoreConfig(Builder b) {
        this.baseUri = b.baseUri;
        this.chunkSize = b.chunkSize;
        this.maxValueSize = b.maxValueSize;
        this.maxRetries = b.maxRetries;
        this.backoffBase = b.backoffBase;
        this.backoffMax = b.backoffMax;
        this.connectTimeout = b.connectTimeout;
        this.requestTimeout = b.requestTimeout;
        this.httpClient = b.httpClient;
    }

    public static Builder builder(URI baseUri) {
        return new Builder(baseUri);
    }

    public URI baseUri() {
        return baseUri;
    }

    public int chunkSize() {
        return chunkSize;
    }

    public long maxValueSize() {
        return maxValueSize;
    }

    public int maxRetries() {
        return maxRetries;
    }

    public Duration backoffBase() {
        return backoffBase;
    }

    public Duration backoffMax() {
        return backoffMax;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    /** Lazily builds a default {@link HttpClient} if none was supplied. */
    public HttpClient httpClient() {
        if (httpClient != null) {
            return httpClient;
        }
        return HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    public static final class Builder {
        private final URI baseUri;
        private int chunkSize = DEFAULT_CHUNK_SIZE;
        private long maxValueSize = MAX_VALUE_SIZE;
        private int maxRetries = 3;
        private Duration backoffBase = Duration.ofMillis(200);
        private Duration backoffMax = Duration.ofSeconds(10);
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration requestTimeout = Duration.ofSeconds(60);
        private HttpClient httpClient;

        private Builder(URI baseUri) {
            if (baseUri == null) {
                throw new IllegalArgumentException("baseUri must not be null");
            }
            this.baseUri = baseUri;
        }

        public Builder chunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
            return this;
        }

        public Builder maxValueSize(long maxValueSize) {
            this.maxValueSize = maxValueSize;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder backoffBase(Duration backoffBase) {
            this.backoffBase = Objects.requireNonNull(backoffBase, "backoffBase");
            return this;
        }

        public Builder backoffMax(Duration backoffMax) {
            this.backoffMax = Objects.requireNonNull(backoffMax, "backoffMax");
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public StoreConfig build() {
            if (chunkSize <= 0) {
                throw new IllegalArgumentException("chunkSize must be > 0, was " + chunkSize);
            }
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be >= 0, was " + maxRetries);
            }
            if (maxValueSize <= 0) {
                throw new IllegalArgumentException("maxValueSize must be > 0, was " + maxValueSize);
            }
            return new StoreConfig(this);
        }
    }
}
