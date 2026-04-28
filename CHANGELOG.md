# Changelog

User-visible and API-visible changes to `oir-framework-addons` (the platform-service half of OIR — `OIRService` in `system_server`, the AIDL surface, parcelables, permissions, capability registry). Per-commit detail is in `git log`; this file is the "what shipped" view at release granularity.

Format loosely follows [Keep a Changelog](https://keepachangelog.com). Pre-v0.7 history is in commit messages and the [JibarOS roadmap](https://github.com/Jibar-OS/JibarOS/blob/main/docs/ROADMAP.md).

---

## [Unreleased] — v0.7

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
