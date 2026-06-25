# Jarmin Uploader — Project Summary

A running summary of what was built, the decisions behind it, and the current state of the
project. For deeper detail see [requirements-and-design.md](./requirements-and-design.md)
(requirements + low-level design + test cases) and [usage.md](./usage.md) (how to use it).

---

## 1. Goal

Build a **client library** with a memcache-style API — `set(key, value)` and `get(key)` —
that stores arbitrary binary payloads (up to **5 GB**) by splitting them into **1 MB chunks**
and uploading each chunk. The key is client-set and unique.

---

## 2. Key decisions (agreed during the conversation)

| Decision | Choice | Why |
|----------|--------|-----|
| Transport | **HTTP/REST** | Simple, language-agnostic, easy to mock/test. |
| Value type | **Streaming + `byte[]` overloads** | A 5 GB value can't fit a Java `byte[]` (~2 GB cap); streaming is the real path, `byte[]` is convenience. |
| Build tool | **Gradle** (`java-library` + `application`) | Standard library layout, JUnit 5, runnable demo. |
| v1 reliability | **Per-chunk retry + backoff only** | Resumable uploads, parallel upload, and checksums deferred. |
| Backend selection | **Configurable: `file` or `server`** | Local-directory backend for dev/testing; HTTP backend for production. |

---

## 3. What was built

### Core library (`com.jarmin.uploader`)
- **`ChunkedStore`** — public interface (streaming `set`/`get` + `byte[]` overloads).
- **`HttpChunkedStore`** — HTTP/REST client: chunks sequentially, finalizes via `/complete`,
  reassembles lazily via manifest + per-chunk GETs.
- **`FileChunkedStore`** — local-directory backend (chunks written as files + manifest); used
  for `storage.mode=file`.
- **`ChunkReader`** — splits an `InputStream` into fixed-size chunks (partial-read safe).
- **`RetryExecutor`** — bounded retries with exponential backoff (injectable sleeper for tests).
- **`ReassemblingInputStream`** — lazy, constant-memory reassembly for `get()`.
- **`StoreConfig`** — immutable builder (base URI, chunk size, retries, backoff, timeouts,
  **configurable max value size**).
- **`ClientConfig`** — loads `client.properties`, validates, and builds the configured store.
- **`Manifest`** (record) and the **`StoreException`** hierarchy
  (`KeyNotFoundException`, `ChunkUploadException`).

### Server / demo
- **`ReferenceServer`** (`com.jarmin.uploader.server`) — a minimal, disk-backed HTTP server
  implementing the chunk protocol; a local reference backend for end-to-end testing.
- **`Demo`** (`com.jarmin.uploader.demo`) — runnable, config-driven client
  (`./gradlew run`) that loads `client.properties` and performs a real set/get round-trip.

### Configuration
- **`client.properties`** (project root) with keys: `storage.mode`, `client.path`,
  `server.endpoint`, `chunk.size`, `max.byte.size`.

---

## 4. HTTP chunk protocol

| Method & Path | Purpose |
|---------------|---------|
| `PUT  /objects/{key}/chunks/{index}` | Upload chunk `index` (body = raw bytes). |
| `POST /objects/{key}/complete` | Finalize (`chunkCount`, `totalSize`). |
| `GET  /objects/{key}/manifest` | Chunk count + total size (404 ⇒ key not found). |
| `GET  /objects/{key}/chunks/{index}` | Download a chunk for reassembly. |

Retry policy: HTTP **5xx**/**429** and `IOException` are retried; other **4xx** fail
immediately. A failed chunk (after retries) aborts the upload and skips `/complete`.

---

## 5. Testing

Built **test-first (TDD)** throughout — **59 tests, 0 failures**.

| Suite | Count | Scope |
|-------|-------|-------|
| `ChunkReaderTest` | 6 | Chunk boundaries, partial reads, empty/single-byte. |
| `RetryExecutorTest` | 7 | Retry/backoff, exhaustion, non-retryable, capping. |
| `StoreConfigTest` | 7 | Validation, defaults, configurable max value size. |
| `HttpChunkedStoreTest` | 19 | Client vs in-process mock server (uploads, retries, large streamed payload, get/reassembly). |
| `EndToEndTest` | 5 | Real client ↔ real `ReferenceServer`, chunks on local disk. |
| `FileChunkedStoreTest` | 6 | File backend round-trips, overwrite, max-size. |
| `ClientConfigTest` | 9 | Properties parsing, defaults, validation, factory. |

Test fixtures: `MockChunkServer` (in-process HTTP mock with fault injection + body-discard
mode) and `ZeroInputStream` (synthetic multi-GB stream, no allocation).

**Verification runs:** `./gradlew test` is green; `./gradlew run` performs a live
round-trip (e.g. 3.1 MB → 4 chunk files on disk → downloaded → `PASS`).

---

## 6. Current status & what's deferred

**Done:** full client library, both backends (file + server), configuration file, reference
server, runnable demo, and a complete test suite.

**Open / to confirm:** the HTTP endpoint and manifest wire format are the proposed contract
(mirrored by `ReferenceServer`) — confirm against the real production server before integrating.

**Deferred (future enhancements):**
- Resumable uploads (query received chunks, resume an interrupted upload).
- Parallel chunk upload (thread pool for throughput).
- Integrity checksums (per-chunk + whole-object, verified on `get()`).

---

## 7. Documentation map

| File | Contents |
|------|----------|
| `docs/requirements-and-design.md` | Requirements, public API, protocol, low-level design, test-case matrix, future enhancements. |
| `docs/usage.md` | How to build, configure (`client.properties`), and use the library. |
| `docs/project-summary.md` | This summary. |
