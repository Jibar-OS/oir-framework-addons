# oir-framework-addons — OIR platform code

Everything OIR-specific that lands under `frameworks/base/` in a JibarOS tree. New files only — no modifications to upstream AOSP files live here (those are in [`oir-patches`](https://github.com/jibar-os/oir-patches)).

## What's here

| Path (relative to `frameworks/base/`) | Contents |
|---|---|
| `core/java/android/oir/` | Public app-facing AIDL (`IOIRService`, `IOIRTokenCallback`, `IOIRVectorCallback`, etc.) |
| `oir/etc/` | `capabilities.xml` — platform capability declarations |
| `oir/aconfig/` | aconfig flags for feature rollouts |
| `services/core/java/com/android/server/oir/` | `OIRService` platform service + `CapabilityRegistry` + `OirConfig` + internal AIDL (`IOirWorker`) |

## How it's consumed

At tree-assembly time (by `manifest` or a bake script), these files are copied or linked into their `frameworks/base/` paths. `oir-framework-addons` never touches upstream AOSP files — that's the clean-port contract.

## Building

As part of a JibarOS tree:

```bash
m -j8 services framework-minus-apex
```

The OIR Java classes end up in `services.jar` (platform service) and `framework.jar` (public AIDL).

## See also

- [`oir-patches`](https://github.com/jibar-os/oir-patches) — the 5 small edits to upstream AOSP this repo depends on
- [`oir-sdk`](https://github.com/jibar-os/oir-sdk) — apps' client-side dependency
- [`github.com/Jibar-OS/jibar-os`](https://github.com/Jibar-OS/jibar-os) — architecture + capability model

## Migration status

🚧 Code migration in progress.
