# TODO — engine concurrency audit (2026-09-24)

From the async/concurrency audit run after the background-cleanup work (`nativeStream`, `SurfacePresence`,
`HandleLedger`). Already fixed then: the drain permit leak, cancellable residency releases, conversation vs
engine free races. Fixed one at a time below, each with a test and a green `aideCheck`.

- [x] **#1 Critical — closing one resident model unloads another.** `AcquireModelUseCase.LlmResidentModel.close`
      calls `engine.unload()`, which closes every engine; `LiteRtLmEngine.load(B)` frees A silently while A's
      slot stays `loadedFlag = true`. A's idle expiry then frees B under its holder.
- [x] **#6 Medium — cancelled acquire during a native load leaks untracked weights.** The slot is rolled back
      while the engine keeps the model.
- [x] **#8 Medium — speech weights outside residency.** `warmUpStt()` and Whisper's own VAD load natively with
      no slot; the assistant's VAD slot can close the VAD dictation is using.
- [x] **#7 Medium — Sherpa handles used across suspensions without the engine lock.** Only the refcount keeps
      `close()` from freeing a recognizer between mic chunks.
- [x] **#5 Medium — LiteRT turn permit held across tool dispatch.** A write-confirm prompt stalls every model
      switch and speech acquire until answered.
- [x] **#9 Medium — MCP client holds its mutex across network connect with no timeout;** a failed client is
      never closed.
- [x] **#10 Low — `runCatching`/`catch (Throwable)` around suspend calls swallow cancellation** (GPU→CPU retry
      after a cancel, Stop reported as a tool error).
- [x] **#11 Low — `AssistantVoiceController.release()` is dead code** that would cancel a singleton's scope.
- [x] **#12 Low — process-global LiteRT `ExperimentalFlags` written without a lock.**
- [x] **#14 Low — download bearer token stored in WorkManager `inputData`** (plaintext at rest).
- [x] **Shared OkHttp client** — every `HttpClient` builds its own `OkHttpClient` (own pool and threads).
      *Done:* one `ConnectionPool` per process in both apps; each client keeps its own engine and dispatcher.
- [x] **LiteRT-LM upgrade check** — pinned 0.11.0; GitHub shows 0.17.x. Confirm Maven availability and whether
      its `sendMessageAsync` Flow cancels native work before changing anything.
      *Checked:* Google Maven has 0.17.1; its Flow still closes with an empty `awaitClose {}`, so
      `settledStream` stays. The 0.11 → 0.17 bump is its own piece of work (API and model-format review plus
      device testing), not a concurrency fix.
- [x] **Large downloads vs Android 15 `dataSync` 6 h cap** — decide on user-initiated data transfer jobs.
      *Decided:* stay on WorkManager. Since 2.10 it stops a worker at the cap with
      `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT` (no ANR) and reschedules it; the worker keeps `.part` and resumes
      with HTTP Range. UIDT jobs would mean raw JobScheduler plus `RUN_USER_INITIATED_JOBS` for a case the
      resume already covers.
- [ ] **Device check** — long local reply, press Home: CPU/GPU idle within seconds, `released (hidden)` logged;
      keyboard transform stops on hide; assistant dismiss frees its models.
      *Build installed on the Pixel 2026-09-24; not driven because the phone was in use.*
