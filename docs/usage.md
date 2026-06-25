# Jarmin Uploader — Usage Guide

A memcache-style client for storing and retrieving binary payloads (up to **5 GB**) on a
remote server. Values are split into **1 MB chunks** and uploaded over HTTP, with per-chunk
retry. The core API is streaming (constant memory); `byte[]` overloads are provided for
small/medium payloads.

For the full design and protocol, see [requirements-and-design.md](./requirements-and-design.md).

---

## 1. Requirements

- **Java 21+** (the library targets Java 21; built and tested on Temurin 26).
- No third-party runtime dependencies — it uses the JDK's built-in
  `java.net.http.HttpClient`.

---

## 2. Add the library to your project

### Build from source

```bash
./gradlew build          # compiles, tests, and produces the jar
# -> build/libs/jarmin-uploader-0.1.0.jar
```

### Publish to your local Maven cache (optional)

If you want to depend on it from another Gradle/Maven project, add the
`maven-publish` plugin or simply drop the produced jar onto your classpath:

```gradle
dependencies {
    implementation files('libs/jarmin-uploader-0.1.0.jar')
}
```

All public types live in the package `com.jarmin.uploader`
(exceptions in `com.jarmin.uploader.exception`).

---

## 3. Quick start

```java
import com.jarmin.uploader.*;
import java.net.URI;

StoreConfig config = StoreConfig.builder(URI.create("https://storage.example.com/api"))
        .build();                       // sensible defaults (see §5)

ChunkedStore store = new HttpChunkedStore(config);

// Store a small value
store.set("greeting", "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8));

// Read it back
byte[] value = store.getBytes("greeting");
```

---

## 4. The API

`ChunkedStore` exposes four methods:

| Method | Use when |
|--------|----------|
| `void set(String key, InputStream value, long length)` | **Primary** upload. Streams `value` in chunks; supports the full 5 GB range with constant memory. `length` must be the exact byte count. |
| `InputStream get(String key)` | **Primary** download. Returns a lazily-streaming, reassembled stream — consume it (e.g. copy to a file/stream) and close it. |
| `void set(String key, byte[] value)` | Convenience for in-memory payloads. |
| `byte[] getBytes(String key)` | Convenience download into memory. Throws if the object is larger than `Integer.MAX_VALUE` (~2 GB) — use `get` instead. |

The **key** is client-set and must be unique; you own uniqueness.

### Uploading a large file (streaming)

```java
import java.nio.file.*;
import java.io.InputStream;

Path file = Path.of("/data/backup.tar");
try (InputStream in = Files.newInputStream(file)) {
    store.set("backup-2026-06-24", in, Files.size(file));
}
```

Memory stays at one chunk (default 1 MB) regardless of file size.

### Downloading a large object (streaming)

```java
import java.nio.file.*;
import java.io.InputStream;

try (InputStream in = store.get("backup-2026-06-24")) {
    Files.copy(in, Path.of("/restore/backup.tar"), StandardCopyOption.REPLACE_EXISTING);
}
```

Chunks are fetched on demand as you read — the whole object is never held in memory.

### Small in-memory values

```java
store.set("config.json", jsonBytes);     // byte[] overload
byte[] back = store.getBytes("config.json");
```

---

## 4b. Configuration file (`client.properties`)

Instead of wiring a store by hand, you can describe the backend in a `client.properties`
file and let `ClientConfig` build the right `ChunkedStore`:

```properties
# Storage backend: "file" (local directory) or "server" (remote HTTP endpoint)
storage.mode=file

# Local storage directory — used when storage.mode=file
client.path=./build/jarmin-store

# Base URL of the chunk server — used when storage.mode=server
server.endpoint=http://localhost:8080

# Bytes per chunk (default 1 MB)
chunk.size=1048576

# Maximum allowed value size in bytes (default 5 GB)
max.byte.size=5368709120
```

| Key | Required | Default | Notes |
|-----|----------|---------|-------|
| `storage.mode` | yes | — | `file` or `server`. |
| `client.path` | when `file` | — | Local directory; chunks go to `<path>/<key>/<index>`. |
| `server.endpoint` | when `server` | — | Base URL passed to `HttpChunkedStore`. |
| `chunk.size` | no | 1 MB | Bytes per chunk. |
| `max.byte.size` | no | 5 GB | Max value size; rejected above this. |

```java
ClientConfig cfg = ClientConfig.load(Path.of("client.properties"));
ChunkedStore store = cfg.createStore();   // FileChunkedStore or HttpChunkedStore

store.set("my-key", data);
byte[] value = store.getBytes("my-key");
```

`file` mode yields a `FileChunkedStore` (chunks on local disk — handy for local
development and integration testing); `server` mode yields an `HttpChunkedStore` pointed
at `server.endpoint`. The same `ChunkedStore` API works regardless of backend.

## 5. Configuration

`StoreConfig` is immutable and built via `StoreConfig.builder(baseUri)`:

```java
import java.time.Duration;

StoreConfig config = StoreConfig.builder(URI.create("https://storage.example.com/api"))
        .chunkSize(1024 * 1024)          // bytes per chunk (default 1 MB)
        .maxRetries(3)                   // retries per chunk after first attempt (default 3)
        .backoffBase(Duration.ofMillis(200))  // first retry delay (default 200 ms)
        .backoffMax(Duration.ofSeconds(10))   // backoff cap (default 10 s)
        .connectTimeout(Duration.ofSeconds(10))
        .requestTimeout(Duration.ofSeconds(60))
        .httpClient(myHttpClient)        // optional: bring your own HttpClient
        .build();
```

| Setting | Default | Notes |
|---------|---------|-------|
| `chunkSize` | 1 MB | Must be `> 0`. |
| `maxRetries` | 3 | Must be `>= 0`. Applies per chunk and to the finalize call. |
| `backoffBase` | 200 ms | Exponential: `min(backoffMax, base * 2^attempt)`. |
| `backoffMax` | 10 s | Upper bound on a single backoff. |
| `connectTimeout` | 10 s | Used when building the default `HttpClient`. |
| `requestTimeout` | 60 s | Per-request timeout. |
| `httpClient` | JDK default | Inject a preconfigured client (proxy, TLS, auth, etc.). |

---

## 6. Retry behaviour

Each chunk upload (and the finalize call) is retried automatically on **transient**
failures:

- **Retried:** `IOException` (network errors) and HTTP **5xx** / **429** responses.
- **Not retried:** HTTP **4xx** (other than 429) — these fail immediately.

If a chunk still fails after `maxRetries`, `set` throws `ChunkUploadException` and the
upload is **not** finalized (no `/complete` call), so the server can reject a partial object.

---

## 7. Error handling

All failures are subclasses of `StoreException` (a checked exception):

| Exception | Meaning |
|-----------|---------|
| `KeyNotFoundException` | `get` / `getBytes` requested a key the server does not have. |
| `ChunkUploadException` | A chunk failed to upload after retries were exhausted, or a non-retryable HTTP error occurred. |
| `StoreException` | Base type — also used for manifest errors, declared-length mismatch, and an object too large for `getBytes`. |

Argument problems throw unchecked `IllegalArgumentException` (e.g. blank key, negative
length, or `length > 5 GB`).

```java
try {
    byte[] data = store.getBytes("user-42");
} catch (KeyNotFoundException e) {
    // not present
} catch (StoreException e) {
    // upload/download/transport failure
}
```

---

## 8. Server protocol (what the client expects)

The client talks to these HTTP endpoints (base URL + path). Confirm these match your
server before going to production:

| Method & Path | Purpose |
|---------------|---------|
| `PUT  /objects/{key}/chunks/{index}` | Upload chunk `index` (0-based). Body = raw chunk bytes. |
| `POST /objects/{key}/complete` | Finalize. Body: `chunkCount=<n>\ntotalSize=<bytes>`. |
| `GET  /objects/{key}/manifest` | Returns `chunkCount=<n>\ntotalSize=<bytes>\nchunkSizes=<csv>`. `404` ⇒ `KeyNotFoundException`. |
| `GET  /objects/{key}/chunks/{index}` | Download chunk `index` for reassembly. |

Keys are URL-encoded in the path. A `2xx` indicates success; `5xx`/`429` trigger retry.

---

## 9. Notes & limits

- **Max value size:** 5 GB. Larger values are rejected up-front.
- **`getBytes` cap:** objects larger than `Integer.MAX_VALUE` bytes (~2 GB) must use the
  streaming `get` — `getBytes` will throw rather than risk an OOM.
- **Thread safety:** a `ChunkedStore` instance is safe to reuse across calls; each
  `set`/`get` is independent. (Uploads are sequential per call in this version.)
- **Not yet supported (see design doc §12):** resumable uploads, parallel chunk upload,
  and integrity checksums.
