# Open Intelligence Runtime Service (`oir-framework-addons`)

The platform-side component of the Open Intelligence Runtime. `OIRService` registers in `system_server` as the binder service `oir`, hosts the public AIDL surface (`IOIRService` and the 5 callback shapes), enforces per-namespace permissions, applies per-UID rate limits, dispatches every capability call to [oird](https://github.com/Jibar-OS/oird) (the [Open Intelligence Runtime Daemon](https://github.com/Jibar-OS/oird)), and keeps a thin client-side cache of worker handles for cancel routing.

This repo ships everything OIR-specific that lands under `frameworks/base/` in a JibarOS tree — new files only, no modifications to upstream AOSP. Those upstream tweaks live in [`oir-patches`](https://github.com/Jibar-OS/oir-patches) (five small edits, ~69 lines).

## What's in here

```
frameworks/base/
├── core/java/android/oir/                  # Public app-facing AIDL surface
│   ├── IOIRService.aidl                    # The binder service apps talk to
│   ├── IOIRTokenCallback.aidl              # Token-shape callbacks (text.complete, etc.)
│   ├── IOIRVectorCallback.aidl             # Vector-shape (text.embed, vision.embed, etc.)
│   ├── IOIRAudioStreamCallback.aidl        # PCM stream (audio.synthesize)
│   ├── IOIRBoundingBoxCallback.aidl        # Bbox shape (vision.detect, vision.ocr)
│   ├── IOIRRealtimeBooleanCallback.aidl    # Realtime boolean (audio.vad)
│   ├── BoundingBox.aidl                    # Parcelable for bbox results
│   └── MemoryStats.aidl                    # Dumpsys memory snapshot parcelable
├── oir/etc/capabilities.xml                # Platform-side capability declarations
├── oir/aconfig/                            # aconfig flags (oir_v1_api etc.)
└── services/core/java/com/android/server/oir/
    ├── OIRService.java                     # AIDL stub class, thin namespace router (~416 lines)
    ├── OIRShellCommand.java                # `cmd oir ...` shell-side handlers
    ├── CapabilityRegistry.java             # Loads capabilities.xml; OEM fragment merging
    ├── OirConfig.java                      # Loads oir_config.xml; per-capability tuning knobs
    ├── Capability.java / OIRError.java     # Plain data
    │
    ├── # Phase 1 leaf infrastructure (extracted from OIRService):
    ├── WorkerLifecycle.java                # IOirWorker attach/death/retry
    ├── HandleRegistry.java                 # Per-capability worker-handle cache
    ├── LoadDedup.java + LoadFuture.java    # Concurrent-load dedup
    ├── RateLimiter.java                    # Per-UID token bucket
    │
    ├── # Phase 2 orchestration:
    ├── ModelEnsurer.java                   # Single load-orchestration entry point
    ├── PermissionEnforcer.java             # Capability → required permission check
    ├── CallbackBridges.java                # 5 worker→app callback bridges + helpers
    │
    ├── # Phase 3 namespace dispatchers:
    ├── DispatcherDeps.java                 # Bundles cross-cutting services
    ├── TextDispatcher.java                 # text.{complete,embed,classify,rerank,translate}
    ├── AudioDispatcher.java                # audio.{transcribe,synthesize,vad}
    └── VisionDispatcher.java               # vision.{detect,embed,ocr,describe}
```

## Why this exists

The platform-service half of OIR is the **only thing apps see**. Apps bind to `IOIRService` (registered as `oir`); everything beyond that — oird's binder, the model files, the per-backend pools — is internal and can change without breaking apps.

So OIRService takes on five responsibilities:

1. **Hosting the public AIDL surface.** Stable contract; apps compiled against `framework.jar` keep working across runtime changes.
2. **Permission enforcement.** Each capability declares a required `oir.permission.USE_*`; OIRService rejects unauthorized callers before forwarding to oird.
3. **Per-UID rate limiting.** Token bucket (60/min, burst 10 by default; OEM-tunable). Defends oird from a single misbehaving app.
4. **Capability registry.** Reads `capabilities.xml` (platform + OEM fragments) so apps can discover what's supported, query runnability, and warm models ahead of use.
5. **Lazy load + dedup orchestration.** Apps don't manage model lifecycles — first submit on a capability triggers the load; subsequent submits reuse the handle; death of oird invalidates everything cleanly.

## Architecture

After the v0.7 decomposition (Phases 1–3, 1485 lines moved out of OIRService) the design looks like this:

```
                       app (binder via IOIRService)
                                 │
           ┌─────────────────────┴─────────────────────┐
           │           OIRService (AIDL stub)           │
           │  thin namespace router — submit/embed/     │
           │  detect/synth/vad/rerank/cancel/warm       │
           └──┬──────────────┬──────────────┬───────────┘
              │              │              │
              ▼              ▼              ▼
       TextDispatcher  AudioDispatcher  VisionDispatcher
       text.*           audio.*           vision.*
       │                │                 │
       └─────┬──────────┴────────┬────────┘
             │                   │
             ▼                   ▼
      ┌──────────────────────────────────────────────┐
      │              DispatcherDeps                  │
      │  WorkerLifecycle  PermissionEnforcer         │
      │  ModelEnsurer     CapabilityRegistry         │
      │  RateLimiter      OirConfig                  │
      │  HandleRegistry   nextRequestHandle counter  │
      │  LoadDedup        appHandleToWorkerHandle    │
      └──────────────────────────────────────────────┘
                              │
                              ▼
                     IOirWorker (binder to oird)
```

The Stub methods are 1–10 lines each (capability-prefix branch + forward to dispatcher). Per-capability work — validate input, check permission, check rate, ensure model loaded, snapshot worker, build onStart bundle, call worker.submitX, track handle for cancel, wire ICancellationSignal, map errors — lives in the dispatcher for that namespace.

### Concurrency model

- **Inbound binder calls** run on `system_server`'s binder thread pool (sized by AOSP, typically 16 threads). Each Stub method is called concurrently from those threads.
- **mWorkerLock** (now owned by `WorkerLifecycle`) guards compound state operations: "check worker non-null AND claim a LoadFuture in the same critical section." Held briefly; released before any binder RPC to oird.
- **Slow paths drop the lock**: `worker.loadX()` and `worker.submitX()` are unlocked binder RPCs. Concurrent calls for *different* capabilities don't serialize.
- **Same-capability concurrent loads** dedup via `LoadDedup`: first caller claims a `LoadFuture`, runs the load, publishes; subsequent callers find the in-flight future and `awaitHandle()`.

### Cancellation

Apps pass an `ICancellationSignal` with every submit. `CallbackBridges.wireCancellationSignal` registers an `OnCancelListener` that calls `OIRService.Stub.cancel(appHandle)` on signal trigger; that looks up the app's request handle in `mAppHandleToWorkerHandle`, dereferences to the worker-side handle, and forwards to `IOirWorker.cancel`. Worker tears down the in-flight inference cleanly.

### Rate limiting

`RateLimiter` is a per-UID token bucket. Each `tryAcquire(uid)` consumes one token if available; refills at `rate_limit_per_minute / 60000` per millisecond up to `rate_limit_burst`. SHELL_UID and SYSTEM_UID bypass entirely.

When a request is throttled, the runtime computes the actual wait via `RateLimiter.nextTokenWaitMs(uid)` and embeds it in the `OirThrottledException` message; the SDK's `retryOnThrottle` helper backs off exactly that long. There is no enqueue-and-wait — that would let one UID pin all binder threads.

### Capability variants (v0.7 prep)

`CapabilityRegistry.getOrFallback(name)` resolves variants like `text.complete:fast` to the base `text.complete` when the variant isn't declared. `ModelEnsurer` keys its cache by the resolved `Capability.name`, so variants and base share the same loaded model. Today no `:variant` entries ship in `capabilities.xml`; the infrastructure is dormant until v0.7 variant work lands.

## How it's consumed

At tree-assembly time, these files are copied into their `frameworks/base/` paths by the JibarOS apply script. `oir-framework-addons` never touches upstream AOSP files; that's the clean-port contract.

## Building

As part of a JibarOS / AOSP tree:

```bash
m -j8 services framework-minus-apex
```

The OIR Java classes end up in `services.jar` (platform service) and `framework.jar` (public AIDL).

For incremental dev — push services to a running cvd:

```bash
adb root && adb remount
adb push out/target/product/vsoc_x86_64/system/framework/services.jar /system/framework/services.jar
adb push out/target/product/vsoc_x86_64/system/framework/oat/x86_64/services.* /system/framework/oat/x86_64/
adb reboot
# (and after boot — push services.jar a second time + reboot once more,
#  the first push lands in overlayfs which doesn't persist until reboot
#  enables the overlay)
```

## Testing

`cmd oir` shell subcommands (handled by `OIRShellCommand`) cover every capability:

```bash
adb shell cmd oir submit "What is 2+2?"          # text.complete
adb shell cmd oir embed "hello"                   # text.embed
adb shell cmd oir translate --src en --tgt es "hello"  # text.translate
adb shell cmd oir transcribe /sdcard/voice.wav   # audio.transcribe
adb shell cmd oir synthesize "hello world"        # audio.synthesize
adb shell cmd oir vad /sdcard/voice.pcm          # audio.vad
adb shell cmd oir detect /sdcard/photo.jpg       # vision.detect
adb shell cmd oir vembed /sdcard/photo.jpg       # vision.embed
adb shell cmd oir ocr /sdcard/document.jpg       # vision.ocr
adb shell cmd oir describe /sdcard/photo.jpg "what's in this?"  # vision.describe
adb shell cmd oir warm text.complete             # warm a model
adb shell cmd oir memory                          # dumpsys
adb shell cmd oir dumpsys capabilities           # capability runnability table
adb shell cmd oir dumpsys config                 # resolved tuning knobs
```

Plus the [`oir-demo`](https://github.com/Jibar-OS/oir-demo) sample app exercises every capability through the SDK with concurrent-call stress, priority-race, and cancel-all flows.

## Dependencies

- [`oird`](https://github.com/Jibar-OS/oird) — the worker process behind every capability call. OIRService binds to it as the `oir_worker` service.
- [`oir-patches`](https://github.com/Jibar-OS/oir-patches) — five small upstream-AOSP edits this repo depends on (SystemServer registration, manifest/string entries, Android.bp wiring, sepolicy fragment).
- [`oir-sdk`](https://github.com/Jibar-OS/oir-sdk) — apps' client-side dependency; the public surface this addon hosts on the platform side.

## See also

- [JibarOS](https://github.com/Jibar-OS/JibarOS) — top-level architecture, capability model, [ROADMAP](https://github.com/Jibar-OS/JibarOS/blob/main/docs/ROADMAP.md), [KNOBS.md](https://github.com/Jibar-OS/JibarOS/blob/main/docs/KNOBS.md), [CAPABILITIES.md](https://github.com/Jibar-OS/JibarOS/blob/main/docs/CAPABILITIES.md), [SDK.md](https://github.com/Jibar-OS/JibarOS/blob/main/docs/SDK.md).
- [CHANGELOG.md](./CHANGELOG.md) — what shipped per release.
