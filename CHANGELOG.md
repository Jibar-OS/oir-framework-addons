# Changelog

User-visible and API-visible changes to `oir-framework-addons` (the platform-service half of OIR — `OIRService` in `system_server`, the AIDL surface, parcelables, permissions, capability registry). Per-commit detail is in `git log`; this file is the "what shipped" view at release granularity.

Format loosely follows [Keep a Changelog](https://keepachangelog.com). Pre-v0.7 history is in commit messages and the [JibarOS roadmap](https://github.com/Jibar-OS/JibarOS/blob/main/docs/ROADMAP.md).

---

## [Unreleased] — v0.7

### OIRService decomposition (Phases 1–3)

OIRService.java went from 1901-line god-class to a 416-line thin AIDL router. Same logic, same behavior, better internal structure for the v0.8 ObserveSession + v1.0 world.observe work.

**Phase 1 — leaf extracts** (`5c8b953`):
- `RateLimiter` — promoted from inner static class to top-level so dispatchers can receive it via constructor injection.
- `LoadFuture` + `LoadDedup` — concurrent-load dedup registry. The CountDownLatch-backed future + the in-flight ConcurrentHashMap pulled out of OIRService; 21 callsites across the 7 ensureXxxModelFor methods migrated.
- `HandleRegistry` — collapses 7 separate per-backend ConcurrentHashMap<String, Long> handle caches into one map keyed by capability name. Capability names are globally unique per CapabilityRegistry's invariant; the "kind" of a handle is implicit in capability metadata. Removes the audit-fix risk class (v0.6.1 caught a missing `binderDied` clear for the VAD map; one map + `clearAll()` makes that bug impossible).
- `WorkerLifecycle` — owns the IOirWorker reference, the lock guarding compound worker-state operations, the attach-retry loop, and the death recipient. Listener callbacks let OIRService plug in attach-time config push + death-time cleanup.

**Phase 2 — orchestration** (`078bc7a`):
- `ModelEnsurer` — collapses the 7 ensureXxxModelFor methods into one `ensure(capability, ErrorReporter) → long` method. Picks the load shape via a `LoadKind` enum derived from `Capability.backend` + name prefix. Same lock + dedup + binder-RPC + publish pattern, same WORKER_UNAVAILABLE-vs-MODEL_ERROR distinction on failure, same VLM pipe-delim path parsing. ~545 lines of repeated code → ~150.
- `CallbackBridges` — 5 null-safe `safeAppXxxError` helpers + `wireCancellationSignal` extracted from OIRService. Naming mirrors the SDK's `com.oir.internal.CallbackBridges.kt` so consumers and framework maintainers see the same boundary on both sides of the binder.
- `PermissionEnforcer` — wraps the existing per-namespace permission check. Today's policy unchanged. Real abstraction (not a 5-line inline check) so v0.8 ObserveSession can layer composed-perm checks (USE_OBSERVE_AUDIO + foreground-service state) and device-class policy (phone-FGS-required vs embedded-vendor-signed) inside this class without touching dispatcher call sites.
- `CapabilityRegistry.getOrFallback(name)` — variant-aware lookup. Tries exact name first; if not found and the name carries a `:variant` suffix (e.g. `text.complete:fast`), strips the suffix and retries against the base. Logged on fallback. Today's capabilities.xml has no variants so behavior is identical to `get()`; dormant infrastructure for the v0.7 variant work.

**Phase 3 — namespace dispatchers** (`caea359`):
- `TextDispatcher` / `AudioDispatcher` / `VisionDispatcher` — every IOIRService.Stub method is now a thin namespace router that picks a dispatcher by capability prefix and forwards. Per-capability submit logic (validate + permit + rate-limit + ensure model + worker snapshot + actual submit + cancel-wire + error map) lives on the dispatcher for that namespace. Each dispatcher receives `DispatcherDeps` (a 9-field POJO bundling the cross-cutting services) at construction.
- 5 inner WorkerXxxCallbackBridge classes (Token / Vector / Audio / Bbox / Boolean) extracted from OIRService into static nested classes inside `CallbackBridges` so dispatchers can construct them without capturing OIRService.this.
- `DispatcherDeps` — bundles WorkerLifecycle, ModelEnsurer, RateLimiter, PermissionEnforcer, CapabilityRegistry, OirConfig, the request-handle counter, the app→worker handle map, and the cancellation forwarder. Same role as the Runtime struct extracted on the oird side in Phase 1.
- `OirConfig.getCapabilityCtxSize` — promoted from a private OIRService method since dispatchers all need it for the onStart Bundle's context_window field.
- `OIRService.cancelOnBehalf` — single source of cancellation forwarding, exposed as `CallbackBridges.Canceler` in DispatcherDeps via method reference.

Reserved architectural slots: v0.8 audio.observe / vision.observe land alongside Audio/Vision dispatchers (or as separate ObserveDispatcher classes); v1.0 world.observe gets its own WorldDispatcher under a new namespace.

### Throttle UX

- `RateLimiter.nextTokenWaitMs(uid)` — new read-only inspection method returning ms until the next token is available for this UID. Read-only; does NOT consume a token. Replaces a hardcoded 1000ms guess in the SDK's `OirThrottledException.retryAfterMs` regex parser. (`55ec7fe`)
- 4 throttle callsites (3 dispatcher preflights + `submitAs`) updated to call `nextTokenWaitMs` after a `tryAcquire` failure and append `" — retry after Xms"` to the throttle message. SDK's regex `(\d+)\s*ms` now picks up the real retry-after instead of falling back to the hardcoded default. (`55ec7fe`)

### Cache-key audit fix

- `ModelEnsurer.ensure` was caching the loaded handle keyed by the REQUESTED capability name rather than the RESOLVED `Capability.name`. With variants (`text.complete:fast` falling back to base `text.complete`), this caused duplicate worker.load() calls for the same physical model and split the dedup `LoadFuture` so concurrent variant + base callers raced separate loads. Fix uses `c.name` for both the handle cache and the loadKey prefix; variants share the base's cache entry and dedup. (`55ec7fe`)
- Audit follow-up: `e0d5c79` — Phase 3's preflight pipeline was duplicated 3× across the dispatchers and each copy did EXACT-match registry lookup, so a variant request died at the dispatcher's INVALID_INPUT before ever reaching ModelEnsurer. Extracted `NamespaceDispatcher` abstract base class that centralizes preflight (registry resolve via `getOrFallback` → permission enforce on RESOLVED capability name → rate-limit → ensure model). Three dispatchers extend it; future observe / world dispatchers will inherit the same correctness. Plus `CapabilityRegistry.getRequiredPermission`, `Stub.warm`, and `Stub.isCapabilityRunnable` switched from exact-match to `getOrFallback` so every entry point resolves variants identically.

Today's behavior unchanged (no variants ship in capabilities.xml); the entire fallback path becomes load-bearing when v0.7 variant capabilities land.

### AIDL surface

- `MemoryStats` parcelable extended — added `backendLabels` (String[]), `poolSizes` (int[]), `busyCounts` (int[]), `waitingCounts` (int[]) parallel arrays, length == `modelCount`. SDK consumers no longer need to call `dumpRuntimeStats` and TSV-parse to get pool telemetry; everything comes through one binder roundtrip on the typed parcelable. Append-only / backward-compatible: old readers stop at the previously-known last field. (`6e4d73a`)
- `OIRShellCommand.cmdDumpsysMemory` collapsed — pool info (backend / slots / busy / waiting) now prints inline with each model in the same loop, sourced from `MemoryStats` instead of the dual `getMemoryStats` + `dumpRuntimeStats` round-trip. The AIDL `dumpRuntimeStats` method stays for ad-hoc shell debugging where TSV is more convenient than a parcelable. (`6e4d73a`)

### Capability registry + permissions

- ✂ **`code.*` reserved namespace removed**, **`oir.permission.USE_CODE` removed**. Originally planned alongside `text.*` / `audio.*` / `vision.*`, dropped as YAGNI: code is text at runtime; apps that need code-aware completion go through `text.complete`. Apps that referenced `USE_CODE` in their manifest need to drop it. (`822ae98`)

### Configuration + observability

- `cmd oir dumpsys config` — surfaces all resolved tuning knobs at runtime. Prints the 5 globals + the per-capability tuning maps (`text.complete.*`, `text.embed.*`, `audio.transcribe.*`, `vision.*`, etc.) so OEMs can verify their `oir_config.xml` actually applied. (`a79c41f`)

### Build wiring

- aconfig flag wiring — `oir_v1_api` declared with `is_exported: true` and gated via `android:featureFlag` in the platform manifest. Several iterations to reconcile with AOSP's exported-flag lint check. End state: flag works, no lint baseline noise. (`62a2019`, `7d984f1`, `df39604`, `e8d10f6`)

### Documentation

- SECURITY.md aligned with main repo — drops placeholder email; GitHub Security Advisories is the only reporting channel. (`5d557ea`)

---

## v0.6.9 and earlier

Pre-v0.7 history lives in `git log` and the [JibarOS roadmap](https://github.com/Jibar-OS/JibarOS/blob/main/docs/ROADMAP.md). Highlights: `OIRService` → oird `attachWorker` retry loop (v0.6.x), 6 audit fixes from the v0.6.1 honesty audit (`mVadHandlesByCapability` clear in `binderDied`, `warm()` backend dispatch, cancellation across all 6 submit paths, `submitSynthesize` error mapping, capability `n_ctx` reads from runtime tuning, dead double-PCM-read fix), `cmd oir warm` + `cmd oir dumpsys capabilities` shell subcommands (v0.3), per-namespace permissions (`USE_TEXT`, `USE_AUDIO`, `USE_VISION` in v0.3).
