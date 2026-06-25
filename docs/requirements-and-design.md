# Jarmin Uploader — Requirements & Design

## 1. Overview

Jarmin Uploader is a **client library** that provides a memcache-style wrapper for
storing and retrieving arbitrary binary payloads on a remote server. It exposes two
operations — `set(key, value)` and `get(key)` — and transparently splits each value
into fixed-size **1 MB chunks** that are uploaded individually over HTTP. The client
owns the key (a client-set, unique identifier); values may be up to **5 GB**. Because
a 5 GB payload cannot be held in a single Java `byte[]` (Java arrays are bounded by
`Integer.MAX_VALUE`, ~2 GB), the core API is **stream-based**, with `byte[]`
convenience overloads for small/medium payloads.

## 2. Functional Requirements

| Requirement | Detail |
|-------------|--------|
| `set(key, value)` | Client supplies a unique key; the library chunks `value` into 1 MB pieces and uploads each chunk to the server. |
| `get(key)` | Library fetches all chunks for `key` and reassembles the original payload in order. |
| Key | Client-set, **unique**, immutable identifier for an object. The client is responsible for uniqueness. |
| Value | Arbitrary bytes, **max size 5 GB**. |
| Chunk size | **1 MB = 1,048,576 bytes**. The final chunk may be smaller than 1 MB. |

## 3. Non-Functional Requirements

- **Memory:** Must stream. The full payload is never held in memory — chunks are read,
  uploaded, and discarded one at a time (both on upload and on reassembly).
- **Reliability:** Each chunk upload is retried on transient failures using a
  configurable number of attempts with exponential backoff. If a chunk still fails
  after retries are exhausted, the whole `set()` fails.
- **Configurability:** Chunk size, retry count, backoff timing, base URL, and timeouts
  are all configurable.

## 4. Public API (Java)

```java
interface ChunkedStore {
    // Core, streaming API — supports the full 5 GB range, constant memory.
    void set(String key, InputStream value, long length) throws StoreException;
    InputStream get(String key) throws StoreException;

    // Convenience overloads — for payloads that comfortably fit in memory.
    void set(String key, byte[] value) throws StoreException;
    byte[] getBytes(String key) throws StoreException; // throws if object > Integer.MAX_VALUE
}
```

**Notes**
- The `byte[]` overloads are bounded by `Integer.MAX_VALUE` (~2 GB) and are intended
  for small/medium payloads only. Large objects (up to 5 GB) must use the stream API.
- `set(String, InputStream, long)` takes an explicit `length` so the client can report
  the total size and chunk count to the server without buffering the whole stream.
- `get(String)` returns a lazily-streaming `InputStream` so the caller controls how the
  payload is consumed (e.g., written to disk) without materializing 5 GB in memory.

### Usage

```java
StoreConfig config = StoreConfig.builder(URI.create("https://host/api"))
        .chunkSize(1024 * 1024)   // 1 MB (default)
        .maxRetries(3)            // per-chunk retries (default)
        .build();

ChunkedStore store = new HttpChunkedStore(config);

// Upload (streaming — constant memory, up to 5 GB)
try (InputStream in = Files.newInputStream(path)) {
    store.set("my-key", in, Files.size(path));
}

// Download (lazily streamed, reassembled in order)
try (InputStream in = store.get("my-key")) {
    in.transferTo(Files.newOutputStream(out));
}

// Convenience overloads for small payloads
store.set("greeting", "hello".getBytes(UTF_8));
byte[] value = store.getBytes("greeting");
```

## 5. Chunking Protocol over HTTP/REST

> The transport is HTTP/REST. The endpoints below are the proposed contract and should
> be confirmed against the actual server implementation before coding.

| Method & Path | Purpose |
|---------------|---------|
| `PUT  /objects/{key}/chunks/{index}` | Upload chunk `index` (0-based). Body = raw chunk bytes. Headers carry `Content-Length` and the chunk index. |
| `POST /objects/{key}/complete` | Finalize the upload. Body sends total chunk count + total size so the server can verify every chunk was received. |
| `GET  /objects/{key}/manifest` | Returns chunk count and per-chunk sizes, used to drive reassembly. |
| `GET  /objects/{key}/chunks/{index}` | Download chunk `index` during `get()`. |

**Reassembly:** `get()` first reads the manifest, then streams chunks `0..n-1` in order
into the returned `InputStream` (a lazy `SequenceInputStream`-style composition — chunks
are fetched on demand, never buffered as a whole in memory).

## 6. `set()` Flow

1. Wrap the source `InputStream` in a 1 MB-bounded reader.
2. For each chunk (index `0..n-1`):
   - Read up to 1 MB from the source.
   - `PUT` the chunk to `/objects/{key}/chunks/{index}`.
   - On transient error, retry with configurable attempts + exponential backoff.
   - Advance to the next chunk.
3. After the last chunk succeeds, `POST /objects/{key}/complete` with the total chunk
   count and total size.
4. If any chunk fails after retries are exhausted, `set()` fails with
   `ChunkUploadException`.

## 7. Error Handling

A `StoreException` hierarchy with clear semantics:

| Exception | Meaning |
|-----------|---------|
| `StoreException` | Base type for all store errors. |
| `KeyNotFoundException` | `get()` requested a key the server does not have. |
| `ChunkUploadException` | A chunk could not be uploaded after retries were exhausted. |
| `IntegrityException` | Reserved for future checksum verification (see §10). |

## 8. Configuration

A `StoreConfig` object controls runtime behavior:

- **Base URL** of the server.
- **Chunk size** (default 1 MB).
- **Max retries** per chunk.
- **Backoff** base and max delay (exponential backoff).
- **Connect / read timeouts.**
- **HTTP client** choice (default `java.net.http.HttpClient`).

## 9. Implementation Roadmap

The code phase that follows this document:

1. **Convert the module to Gradle:** add `build.gradle`, `settings.gradle`, and the
   standard `src/main/java/...` + `src/test/java/...` layout with a JUnit 5 dependency.
   The current `src/Main.java` skeleton becomes a small `Example`/demo under the new
   layout or is removed.
2. **`ChunkedStore` interface** + **`HttpChunkedStore`** implementation using
   `java.net.http.HttpClient`.
3. **`ChunkReader`** helper that yields 1 MB chunks from an `InputStream`.
4. **Retry/backoff utility** for transient chunk-upload failures.
5. **Reassembling `InputStream`** for `get()` (lazy, per-chunk fetch).
6. **Tests** covering chunking boundaries (exact multiple of 1 MB, remainder, empty
   payload, and >2 GB streamed via a synthetic stream) and retry behavior, using a mock
   HTTP server (JDK `com.sun.net.httpserver.HttpServer` or WireMock).

## 10. Low-Level Design

### 10.1 Component Map

```
com.jarmin.uploader
├── ChunkedStore            (interface)         — public API
├── HttpChunkedStore        (class)             — HTTP/REST implementation
├── StoreConfig             (class, immutable)  — configuration + builder
├── ChunkReader             (class)             — splits an InputStream into 1 MB chunks
├── ReassemblingInputStream (class)             — lazy chunk-by-chunk download for get()
├── RetryExecutor           (class)             — retry + exponential backoff
├── Manifest                (record)            — chunk count + per-chunk sizes
└── exception
    ├── StoreException          (checked, base)
    ├── KeyNotFoundException
    ├── ChunkUploadException
    └── IntegrityException      (reserved, future)
```

Dependencies: `HttpChunkedStore` → `ChunkReader`, `RetryExecutor`,
`ReassemblingInputStream`, `StoreConfig`, `java.net.http.HttpClient`.

### 10.2 Constants

```java
final class Constants {
    static final int  ONE_MB         = 1 * 1024 * 1024;          // 1,048,576 bytes
    static final long MAX_VALUE_SIZE = 5L * 1024 * 1024 * 1024;  // 5 GB
}
```

### 10.3 `StoreConfig` (immutable, builder)

| Field | Type | Default | Notes |
|-------|------|---------|-------|
| `baseUri` | `URI` | — (required) | Server root, e.g. `https://host/api`. |
| `chunkSize` | `int` | `1 MB` | Must be `> 0`. |
| `maxRetries` | `int` | `3` | Attempts per chunk after the first try. |
| `backoffBase` | `Duration` | `200 ms` | First retry delay. |
| `backoffMax` | `Duration` | `10 s` | Cap on backoff. |
| `connectTimeout` | `Duration` | `10 s` | Passed to `HttpClient`. |
| `requestTimeout` | `Duration` | `60 s` | Per-request timeout. |
| `httpClient` | `HttpClient` | JDK default | Injectable for testing. |

Builder validation: `chunkSize > 0`, `maxRetries >= 0`, `baseUri != null`, non-negative durations.

### 10.4 `ChunkReader`

Splits a source `InputStream` into successive fixed-size chunks without buffering the whole payload.

```java
final class ChunkReader {
    ChunkReader(InputStream source, int chunkSize);

    /** Reads up to chunkSize bytes. Returns the chunk, or null at EOF.
     *  Returned array length == bytes actually read (last chunk may be < chunkSize). */
    byte[] next() throws IOException;

    int chunkSize();
}
```

Behavior: fully fills a `chunkSize` buffer using a read-loop (handles partial reads from the
underlying stream), trims the final short chunk, returns `null` exactly once at EOF. An empty
source yields zero chunks.

### 10.5 `RetryExecutor`

```java
final class RetryExecutor {
    RetryExecutor(int maxRetries, Duration base, Duration max);

    <T> T execute(Callable<T> action, Predicate<Exception> isRetryable) throws StoreException;
}
```

- Attempts `action` up to `1 + maxRetries` times.
- Retries only when `isRetryable` is true (e.g. `IOException`, HTTP 5xx, 429).
- Backoff: `min(max, base * 2^attempt)`, optionally jittered.
- On final failure wraps the last cause in `ChunkUploadException`; non-retryable exceptions propagate immediately.

### 10.6 `Manifest`

```java
record Manifest(int chunkCount, long totalSize, List<Long> chunkSizes) {}
```

Returned by `GET /objects/{key}/manifest`; drives `get()` reassembly.

### 10.7 `ReassemblingInputStream extends InputStream`

Lazily downloads chunks `0..n-1` and presents them as one continuous stream.

```java
final class ReassemblingInputStream extends InputStream {
    ReassemblingInputStream(HttpChunkedStore store, String key, Manifest manifest);

    @Override public int read() throws IOException;
    @Override public int read(byte[] b, int off, int len) throws IOException;
    @Override public void close() throws IOException;
}
```

Behavior: holds the current chunk index + the current chunk's stream. When the current chunk is
exhausted, fetches the next chunk's body stream. Never holds more than one chunk at a time.
`close()` aborts any in-flight chunk request.

### 10.8 `HttpChunkedStore implements ChunkedStore`

```java
final class HttpChunkedStore implements ChunkedStore {
    HttpChunkedStore(StoreConfig config);

    @Override public void set(String key, InputStream value, long length) throws StoreException;
    @Override public InputStream get(String key) throws StoreException;
    @Override public void set(String key, byte[] value) throws StoreException;   // overload
    @Override public byte[] getBytes(String key) throws StoreException;          // overload
}
```

### 10.9 Method Contracts

**`set(String key, InputStream value, long length)`**
- *Pre:* `key` non-blank; `value` non-null; `0 <= length <= 5 GB`.
- *Effect:* `ceil(length / chunkSize)` chunks PUT in order; then `/complete` called.
- *Throws:* `IllegalArgumentException` (bad args / length > 5 GB), `ChunkUploadException`
  (chunk failed after retries), `StoreException` (finalize failed).
- *Memory:* O(chunkSize), independent of `length`.

**`get(String key)`**
- *Effect:* fetches manifest, returns a `ReassemblingInputStream`.
- *Throws:* `KeyNotFoundException` (404 manifest), `StoreException` (other errors).
- *Memory:* O(chunkSize) while consumed.

**`set(String key, byte[] value)`** — delegates to the stream form with
`new ByteArrayInputStream(value)` and `length = value.length`. Bounded by `Integer.MAX_VALUE`.

**`getBytes(String key)`** — reads the `get()` stream fully into a `byte[]`. Throws
`StoreException` if manifest `totalSize > Integer.MAX_VALUE` (use the stream API instead).

### 10.10 Sequence — `set()` (stream form)

```
Client            HttpChunkedStore         RetryExecutor        Server
  │  set(key,in,len)   │                        │                 │
  ├───────────────────►│ validate args          │                 │
  │                    │ reader = ChunkReader(in, chunkSize)       │
  │           loop ┌── │ chunk = reader.next()  │                 │
  │                │   │ execute(PUT chunk[i]) ─►│ PUT /chunks/i ─►│
  │                │   │                         │◄── 200 / retry  │
  │           next └── │ i++ until null          │                 │
  │                    │ POST /complete(count,size) ───────────────►│
  │◄───────────────────│  (or throws)            │                 │
```

### 10.11 Sequence — `get()`

```
Client       HttpChunkedStore                 Server
  │ get(key)      │ GET /manifest ──────────────►│   (404 → KeyNotFoundException)
  │◄── InputStream│◄── Manifest{count,size,...}   │
  │ read()...     │ lazily: GET /chunks/0,1,...  ─►│
```

### 10.12 Edge-Case Rules

| Case | Rule |
|------|------|
| Empty value (`length == 0`) | Zero chunks PUT; still call `/complete` with `chunkCount=0`. `get()` returns an empty stream. |
| `length` not a multiple of chunkSize | Last chunk is the remainder (`length % chunkSize`). |
| `length` exact multiple of chunkSize | `length / chunkSize` chunks, no trailing partial chunk. |
| Declared `length` ≠ actual stream bytes | Detect mismatch at EOF; fail `set()` with `StoreException` (do not call `/complete`). |
| `length > 5 GB` | Reject up-front with `IllegalArgumentException`. |
| Server 5xx / 429 / IOException on chunk | Retryable → backoff + retry up to `maxRetries`. |
| Server 4xx (non-429) on chunk | Non-retryable → fail immediately. |
| Manifest 404 on `get()` | `KeyNotFoundException`. |
| `getBytes()` on object > 2 GB | `StoreException` — must use stream API. |

## 11. Test Cases

Framework: **JUnit 5**. HTTP interactions are tested against a mock server
(`com.sun.net.httpserver.HttpServer` or WireMock) so no real backend is required.

### 11.1 `ChunkReader` (unit)

| ID | Scenario | Expectation |
|----|----------|-------------|
| CR-1 | Source = exact multiple of 1 MB (e.g. 3 MB) | Exactly 3 chunks of 1 MB; 4th `next()` returns null. |
| CR-2 | Source = non-multiple (e.g. 2.5 MB) | 2 full chunks + 1 chunk of 0.5 MB; then null. |
| CR-3 | Source smaller than chunk (e.g. 100 B) | 1 chunk of 100 B; then null. |
| CR-4 | Empty source (0 B) | First `next()` returns null; zero chunks. |
| CR-5 | Underlying stream returns partial reads | Chunk still fully filled to chunkSize (read-loop works). |
| CR-6 | Exactly 1 byte | 1 chunk of length 1. |

### 11.2 `RetryExecutor` (unit)

| ID | Scenario | Expectation |
|----|----------|-------------|
| RX-1 | Action succeeds first try | Called once, returns value. |
| RX-2 | Retryable failure then success | Retries, returns value; attempt count correct. |
| RX-3 | Retryable failure every time | `ChunkUploadException` after `1 + maxRetries` attempts. |
| RX-4 | Non-retryable failure | Throws immediately, no retry. |
| RX-5 | Backoff growth | Delays follow `min(max, base*2^n)` (assert via injected sleeper). |
| RX-6 | `maxRetries = 0` | Exactly one attempt. |

### 11.3 `HttpChunkedStore.set()` (integration, mock server)

| ID | Scenario | Expectation |
|----|----------|-------------|
| SET-1 | 3 MB payload, all chunks 200 | 3 PUTs to indices 0,1,2 with correct bodies; 1 `/complete` (count=3, size=3 MB). |
| SET-2 | 2.5 MB payload | 3 PUTs (1MB,1MB,0.5MB); `/complete` size=2.5 MB. |
| SET-3 | Empty payload (0 B) | 0 PUTs; `/complete` with count=0, size=0. |
| SET-4 | Chunk 1 returns 503 twice then 200 | Chunk 1 retried, succeeds; upload completes. |
| SET-5 | Chunk 2 returns 503 beyond maxRetries | `ChunkUploadException`; `/complete` NOT called. |
| SET-6 | Chunk returns 400 | Immediate `ChunkUploadException`, no retry. |
| SET-7 | `length` > 5 GB | `IllegalArgumentException`, no network calls. |
| SET-8 | Declared length ≠ actual stream bytes | `StoreException`; `/complete` not called. |
| SET-9 | Chunk ordering | PUT indices strictly 0..n-1 in order. |
| SET-10 | `set(key, byte[])` overload | Equivalent to stream form; correct chunking. |
| SET-11 | Large streamed payload (>2 GB synthetic stream) | Constant memory (no OOM); correct chunk count. Uses a zero-filled stream, not a real array. |

### 11.4 `HttpChunkedStore.get()` / `ReassemblingInputStream` (integration)

| ID | Scenario | Expectation |
|----|----------|-------------|
| GET-1 | Key with 3 chunks | Reassembled bytes equal original; chunks fetched 0,1,2 in order. |
| GET-2 | Manifest 404 | `KeyNotFoundException`. |
| GET-3 | Single-chunk object | Correct bytes; one chunk GET. |
| GET-4 | Empty object (manifest count=0) | Returns empty stream (read → -1). |
| GET-5 | Lazy fetch | Chunk `i+1` not requested until chunk `i` fully consumed. |
| GET-6 | `getBytes()` on small object | Returns full byte[] equal to original. |
| GET-7 | `getBytes()` on object > 2 GB (manifest size) | `StoreException` before download. |
| GET-8 | `close()` mid-stream | In-flight chunk request aborted; no resource leak. |
| GET-9 | Chunk download 503 | Surfaces as `StoreException` (retry policy may apply if enabled for reads). |

### 11.5 Round-trip (integration)

| ID | Scenario | Expectation |
|----|----------|-------------|
| RT-1 | `set` then `get` of 2.5 MB random bytes (in-memory mock server) | Retrieved bytes byte-for-byte equal to sent. |
| RT-2 | `set` then `get` of empty payload | Round-trips to empty. |
| RT-3 | `set(byte[])` then `getBytes()` | Equal arrays. |

### 11.6 `StoreConfig` validation (unit)

| ID | Scenario | Expectation |
|----|----------|-------------|
| CFG-1 | `chunkSize <= 0` | `IllegalArgumentException`. |
| CFG-2 | null `baseUri` | `IllegalArgumentException`. |
| CFG-3 | negative `maxRetries` | `IllegalArgumentException`. |
| CFG-4 | defaults applied | chunkSize=1 MB, maxRetries=3, etc. |

### 11.7 Test Utilities

- **`InMemoryChunkServer`** — `HttpServer` handler storing PUT chunks in
  `Map<key, Map<index, byte[]>>`, serving manifest/chunk GETs. Backs RT-* tests.
- **`FaultyChunkServer`** — configurable to fail specific chunk indices N times / with given
  status codes. Backs SET-4..6, GET-9.
- **`ZeroInputStream`** — synthetic large stream for SET-11 without allocating multi-GB arrays.
- **Injected sleeper/clock** in `RetryExecutor` so backoff tests run instantly.

## 12. Future Enhancements

- **Resumable uploads:** query which chunks the server already received and resume an
  interrupted upload instead of restarting from chunk 0.
- **Parallel chunk upload:** upload N chunks concurrently via a thread pool for higher
  throughput.
- **Integrity checksums:** per-chunk and whole-object checksums (MD5/CRC32) verified on
  `get()` reassembly, surfaced via `IntegrityException`.
