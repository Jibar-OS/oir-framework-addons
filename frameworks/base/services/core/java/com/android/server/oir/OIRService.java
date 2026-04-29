/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 *
 * OIR system service (v0.1). Registers "oir" binder service; proxies
 * submit/cancel to the oird worker process via the internal IOirWorker
 * AIDL. Worker is stubbed in Phase 3 and runs real llama.cpp in Phase 4 —
 * the proxy code is the same for both.
 */
package com.android.server.oir;

import android.content.Context;
import android.oir.IOIRService;
import android.oir.IOIRTokenCallback;
import android.oir.IOIRVectorCallback;
import android.oir.IOIRAudioStreamCallback;
import android.oir.IOIRBoundingBoxCallback;
import android.oir.IOIRRealtimeBooleanCallback;
import android.oir.BoundingBox;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.util.Log;

import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class OIRService extends SystemService {

    private static final String TAG = "OIRService";
    public static final String SERVICE_NAME = "oir";

    // v0.1 hardcoded; capability registry + OEM config lands in v0.2.
    private static final String DEFAULT_MODEL_PATH = "/product/etc/oir/qwen2.5-0.5b-instruct-q4_k_m.gguf";

    private final Context mContext;
    private final AtomicLong mNextRequestHandle = new AtomicLong(1);
    private final ConcurrentHashMap<Long, Long> mAppHandleToWorkerHandle = new ConcurrentHashMap<>();

    // Per-capability worker handle cache. See HandleRegistry — one flat
    // map keyed by capability name (globally unique per CapabilityRegistry
    // invariant). Kind of handle (text-gen / embed / whisper / ort / vlm /
    // vad / vision-embed) is derived from capability metadata at use sites.
    private final HandleRegistry mHandles = new HandleRegistry();

    // Concurrent-load dedup. See LoadDedup for the locking contract.
    private final LoadDedup mLoadDedup = new LoadDedup();

    private final CapabilityRegistry mCapabilityRegistry = new CapabilityRegistry();
    private final OirConfig mConfig = new OirConfig();

    /** v0.7: surface for cmd oir dumpsys config. Returns the live OirConfig
     *  instance (read-only callers only — call mConfig.getXxx accessors). */
    public OirConfig getOirConfig() { return mConfig; }
    // v0.5 V6: per-UID token-bucket rate limiter. Configured from mConfig at onStart.
    private final RateLimiter mRateLimiter = new RateLimiter();

    // Permission enforcement (declarative perm check, registry-driven).
    // Final but assigned in the constructor since it needs Context.
    private final PermissionEnforcer mEnforcer;

    // Worker binder lifecycle: owns the IOirWorker reference, the lock
    // protecting compound worker-state operations, the attach/retry
    // backoff, and the death recipient. Listener body runs OUTSIDE the
    // lock; onDeath acquires it internally as needed. Must declare
    // before mEnsurer (Java forward-reference rule on field initializers).
    private final WorkerLifecycle mLifecycle = new WorkerLifecycle(new WorkerLifecycle.Listener() {
        @Override public void onAttached(IOirWorker worker) {
            try {
                worker.setConfig(mConfig.getMemoryBudgetMb(), mConfig.getWarmTtlSeconds());
            } catch (RemoteException e) {
                Log.w(TAG, "setConfig failed", e);
            }
            // v0.5 V7: push per-capability tuning knobs. Unknown keys are
            // ignored by oird, so forward-compat with later additions costs
            // nothing.
            for (java.util.Map.Entry<String, Float> e : mConfig.getCapabilityTuning().entrySet()) {
                try {
                    worker.setCapabilityFloat(e.getKey(), e.getValue());
                } catch (RemoteException re) {
                    Log.w(TAG, "setCapabilityFloat " + e.getKey() + " failed", re);
                }
            }
            // v0.5 V7 full: string-valued knobs (whisper_language, family, etc.).
            for (java.util.Map.Entry<String, String> e : mConfig.getCapabilityTuningStrings().entrySet()) {
                try {
                    worker.setCapabilityString(e.getKey(), e.getValue());
                } catch (RemoteException re) {
                    Log.w(TAG, "setCapabilityString " + e.getKey() + " failed", re);
                }
            }
        }

        @Override public void onDeath() {
            // All cached handles point at freed contexts in the dead oird;
            // clear so the next submit per capability re-loads against the
            // new worker. Drain in-flight LoadFutures inside the same
            // critical section, then complete them outside the lock so
            // waiting threads wake without holding it.
            java.util.List<LoadFuture> orphaned;
            synchronized (mLifecycle.getLock()) {
                mHandles.clearAll();
                orphaned = mLoadDedup.drainAndClear();
            }
            for (LoadFuture f : orphaned) {
                f.complete(0L, "worker died mid-load");
            }
        }
    });

    // Single load-orchestration entry point — replaces the 7
    // ensureXxxModelFor methods that previously lived here.
    private final ModelEnsurer mEnsurer = new ModelEnsurer(
            mLifecycle, mCapabilityRegistry, mHandles, mLoadDedup);

    // Per-namespace dispatchers. Every IOIRService.Stub method below is a
    // thin namespace router that forwards to one of these. Each dispatcher
    // owns the per-capability submit logic for its namespace, sharing the
    // same DispatcherDeps bundle (lifecycle / ensurer / rate / perm /
    // registry / config / handle counter / cancel forwarder).
    //
    // Reserved slots for future dispatchers — v0.8 audio.observe /
    // vision.observe go alongside Audio/Vision (or as separate
    // *ObserveDispatcher classes); v1.0 world.observe is its own
    // namespace and gets its own WorldDispatcher.
    private final TextDispatcher   mText;
    private final AudioDispatcher  mAudio;
    private final VisionDispatcher mVision;

    public OIRService(Context context) {
        super(context);
        mContext = context;
        mEnforcer = new PermissionEnforcer(context, mCapabilityRegistry);
        DispatcherDeps deps = new DispatcherDeps(
                mLifecycle, mEnsurer, mRateLimiter, mEnforcer,
                mCapabilityRegistry, mConfig,
                mNextRequestHandle, mAppHandleToWorkerHandle,
                this::cancelOnBehalf);
        mText = new TextDispatcher(deps);
        mAudio = new AudioDispatcher(deps);
        mVision = new VisionDispatcher(deps);
    }

    /**
     * Single source of cancellation forwarding for every dispatcher. Pulls
     * the worker handle out of the routing map and invokes worker.cancel.
     * Reused via method reference {@code this::cancelOnBehalf} as the
     * {@link CallbackBridges.Canceler} in {@link DispatcherDeps}.
     */
    private void cancelOnBehalf(long appHandle) throws RemoteException {
        Long workerHandle = mAppHandleToWorkerHandle.remove(appHandle);
        if (workerHandle == null) return;
        IOirWorker worker;
        synchronized (mLifecycle.getLock()) { worker = mLifecycle.getWorkerLocked(); }
        if (worker == null) return;
        worker.cancel(workerHandle);
    }

    @Override
    public void onStart() {
        Log.i(TAG, "onStart: initializing OIR service");

        mCapabilityRegistry.load();
        mConfig.load();
        // v0.6.9: apply OEM default-model overrides from oir_config.xml
        // for capabilities that shipped with no platform default (today
        // only vision.describe fits — LLaVA / SmolVLM paths go here
        // instead of patching capabilities.xml).
        mCapabilityRegistry.applyConfigOverrides(mConfig);
        mRateLimiter.configure(mConfig.getRateLimitPerMinute(), mConfig.getRateLimitBurst());
        publishBinderService(SERVICE_NAME, mBinder);

        // Bug fix v0.2: SystemServer registers OIRService near the end of
        // startOtherServices() -- AFTER PHASE_SYSTEM_SERVICES_READY has
        // already ticked. onBootPhase(500) therefore never fires on us, so
        // we kick the worker attach directly from onStart. oird is usually
        // already registered by this point (init started it seconds earlier);
        // the retry loop in WorkerLifecycle handles the rare race where it isn't.
        mLifecycle.start();
        Log.i(TAG, "onStart: published and lifecycle started");
    }

    @Override
    public void onBootPhase(int phase) {
        // No-op. Worker attach is kicked from onStart via mLifecycle.start();
        // onBootPhase is retained for potential future hooks (e.g., warm-up
        // on PHASE_THIRD_PARTY_APPS_CAN_START).
    }

    // ========================================================================
    // AIDL stub. Thin namespace router: each method picks a dispatcher by
    // capability prefix and forwards. Pre-flight (validate / permit / rate /
    // ensure / worker snapshot) and the actual worker.submitX call live on
    // the dispatchers — see TextDispatcher / AudioDispatcher / VisionDispatcher.
    //
    // Methods that don't dispatch by namespace (cancel, isCapabilityRunnable,
    // warm, onShellCommand) keep their bodies inline below since they touch
    // OIRService-owned state directly.
    // ========================================================================
    private final IOIRService.Stub mBinder = new IOIRService.Stub() {

        @Override
        public long submit(String capability, String prompt, Bundle options,
                IOIRTokenCallback cb, ICancellationSignal cancel) {
            if (capability == null || capability.isEmpty()) capability = "text.complete";
            if (capability.startsWith("vision.describe")) {
                return mVision.submitDescribe(capability, prompt, cb, cancel);
            }
            if (capability.equals("audio.transcribe")
                    || capability.startsWith("audio.transcribe:")) {
                return mAudio.submitTranscribe(capability, prompt, cb, cancel);
            }
            // audio.synthesize / audio.vad have their own typed AIDL methods;
            // misrouting them through the token-shape submit() would feed an
            // audio file path to the whisper transcribe path. Reject early
            // with a pointer to the correct API (v0.6.7 audit fix).
            if (capability.startsWith("audio.")) {
                final String correctApi;
                if (capability.startsWith("audio.synthesize")) correctApi = "submitSynthesize";
                else if (capability.startsWith("audio.vad"))   correctApi = "submitVad";
                else                                           correctApi = "the capability-typed submit method";
                CallbackBridges.safeAppError(cb, OIRError.INVALID_INPUT,
                        "capability '" + capability + "' is not a token-stream shape; "
                                + "use " + correctApi + " instead of submit()");
                return 0L;
            }
            // text.complete + text.translate (TextDispatcher does the
            // translate prompt-template rewrite internally).
            return mText.submitToken(capability, prompt, options, cb, cancel);
        }

        @Override
        public void cancel(long requestHandle) {
            try {
                cancelOnBehalf(requestHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "cancel failed app=" + requestHandle, e);
            }
        }

        @Override
        public long submitEmbedText(String capability, String text,
                IOIRVectorCallback cb, ICancellationSignal cancel) {
            if (capability == null || capability.isEmpty()) capability = "text.embed";
            if (capability.startsWith("vision.embed")) {
                return mVision.submitVisionEmbed(capability, text, cb, cancel);
            }
            // text.embed + text.classify — TextDispatcher picks the right
            // worker.submitX based on capability prefix.
            return mText.submitVector(capability, text, cb, cancel);
        }

        @Override
        public long submitSynthesize(String capability, String text,
                IOIRAudioStreamCallback cb, ICancellationSignal cancel) {
            return mAudio.submitSynthesize(capability, text, cb, cancel);
        }

        @Override
        public long submitDetect(String capability, String imagePath,
                IOIRBoundingBoxCallback cb, ICancellationSignal cancel) {
            // vision.detect + vision.ocr — VisionDispatcher picks
            // submitDetect vs submitOcr by capability prefix.
            return mVision.submitDetect(capability, imagePath, cb, cancel);
        }

        @Override
        public long submitVad(String capability, String pcmPath,
                IOIRRealtimeBooleanCallback cb, ICancellationSignal cancel) {
            return mAudio.submitVad(capability, pcmPath, cb, cancel);
        }

        @Override
        public long submitRerank(String capability, String query, String[] candidates,
                IOIRVectorCallback cb, ICancellationSignal cancel) {
            return mText.submitRerank(capability, query, candidates, cb, cancel);
        }

        @Override
        public int isCapabilityRunnable(String capability) {
            if (capability == null || capability.isEmpty()) return 0;
            // Fallback-aware: a variant that resolves to base inherits the
            // base's runnability, matching what the dispatcher will actually
            // do when the app calls submit on the variant.
            Capability c = mCapabilityRegistry.getOrFallback(capability);
            if (c == null) return 0;                               // UNKNOWN_CAPABILITY
            if (c.defaultModelPath == null || c.defaultModelPath.isEmpty()) {
                return 2;                                          // NO_DEFAULT_MODEL
            }
            // v0.6.3: vision.describe (and any future VLM capability) uses
            // pipe-delimited `<mmproj>|<llm>` paths. Split and verify each
            // component; `File.isFile("a|b")` would always return false
            // because `a|b` isn't a real path, masking the actual state.
            for (String part : c.defaultModelPath.split("\\|")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;  // tolerate trailing pipe
                if (!isFileReadable(trimmed)) return 3;             // MODEL_MISSING
            }
            return 1;                                              // RUNNABLE
        }

        /**
         * v0.6.3: stat a model file with a worker-side fallback.
         *
         * `system_server`'s SELinux scope may not allow `getattr` on every
         * `oir_model_*_file` label that an OEM ships (cuttlefish has the
         * rule, non-cuttlefish products may not). When direct `File.isFile()`
         * returns false, ask oird — it runs in the `oird` domain which *does*
         * have read access to every model file by policy. Avoids spurious
         * `MODEL_MISSING` reports on partners who haven't picked up the
         * sepolicy generalization yet.
         */
        private boolean isFileReadable(String path) {
            // Fast path: no binder hop when the platform rule lets us stat directly.
            if (new java.io.File(path).isFile()) return true;
            final IOirWorker w;
            synchronized (mLifecycle.getLock()) { w = mLifecycle.getWorkerLocked(); }
            if (w == null) return false;
            try {
                return w.fileIsReadable(path);
            } catch (RemoteException re) {
                Log.w(TAG, "worker.fileIsReadable(" + path + ") failed", re);
                return false;
            }
        }

        @Override
        public void warm(String capability) {
            if (capability == null) capability = "text.complete";
            // Fallback-aware: warm("text.complete:fast") preloads the base
            // model when the variant isn't declared, matching what the
            // dispatcher will use when submit fires on the variant.
            Capability c = mCapabilityRegistry.getOrFallback(capability);
            if (c == null) throw new IllegalArgumentException("unknown capability: " + capability);
            mEnforcer.enforce(capability, Binder.getCallingUid(), Binder.getCallingPid());

            // ModelEnsurer dispatches on capability backend + name and
            // calls the right worker.loadX() — no more per-capability
            // if/else chain that drifts apart from the submit path.
            long h = mEnsurer.ensure(capability, ModelEnsurer.NO_REPORTER);
            if (h <= 0) return;

            // Re-snapshot worker — death may have happened between
            // ensure() returning and now. h>0 is no guarantee the worker
            // is still attached at this instant.
            IOirWorker w = mLifecycle.getWorker();
            if (w == null) return;
            try {
                w.warm(h);
                Log.i(TAG, "warm capability=" + capability
                        + " backend=" + c.backend + " handle=" + h);
            } catch (RemoteException e) {
                Log.w(TAG, "warm failed for " + capability, e);
            }
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback shellCallback, ResultReceiver receiver) {
            (new OIRShellCommand(OIRService.this, this, mCapabilityRegistry)).exec(
                    this, in, out, err, args, shellCallback, receiver);
        }
    };

    /**
     * Shell-only entry to submit with a simulated caller UID for permission testing.
     * Callable only from {@link Process#SHELL_UID} on userdebug/eng builds.
     */
    long submitAs(int asUid, String capability, String prompt, Bundle options, IOIRTokenCallback cb, ICancellationSignal cancel) throws RemoteException {
        if (Binder.getCallingUid() != Process.SHELL_UID) {
            throw new SecurityException("submitAs requires shell UID");
        }
        if (!Build.IS_DEBUGGABLE) {
            throw new SecurityException("submitAs unavailable on user builds");
        }
        int effectiveUid = (asUid > 0) ? asUid : Process.SHELL_UID;
        // Route through the mBinder submit with permission check as the effective UID.
        if (mCapabilityRegistry.get(capability == null ? "text.complete" : capability) == null) {
            CallbackBridges.safeAppError(cb, OIRError.INVALID_INPUT, "unknown capability: " + capability);
            return 0L;
        }
        try {
            mEnforcer.enforce(capability == null ? "text.complete" : capability,
                    effectiveUid, Binder.getCallingPid());
        } catch (SecurityException se) {
            CallbackBridges.safeAppError(cb, OIRError.PERMISSION_DENIED, se.getMessage());
            return 0L;
        }
        // v0.5 V6: rate-limit against the simulated UID so `cmd oir --as-uid <N>`
        // exercises the same throttling path an app would hit. mBinder.submit()
        // below sees the real (shell) UID and bypasses.
        if (!mRateLimiter.tryAcquire(effectiveUid)) {
            long waitMs = mRateLimiter.nextTokenWaitMs(effectiveUid);
            CallbackBridges.safeAppError(cb, OIRError.CAPABILITY_THROTTLED,
                    "rate limit exceeded for capability " + capability
                            + " (as-uid=" + effectiveUid + ") — retry after " + waitMs + "ms");
            return 0L;
        }
        // Delegate to the regular (permission-skipped on this thread) submit path.
        // Simplest: call mBinder.submit with capability already validated, but that
        // rechecks perm under the real UID. Inline the same post-perm logic instead.
        return ((IOIRService.Stub) mBinder).submit(capability, prompt, options, cb, cancel);
    }

    /** v0.4 S2: package-private helper for OIRShellCommand cmdDumpsysMemory. */
    com.android.server.oir.MemoryStats getInternalMemoryStats() throws RemoteException {
        synchronized (mLifecycle.getLock()) {
            IOirWorker w = mLifecycle.getWorkerLocked();
            if (w == null) return null;
            return w.getMemoryStats();
        }
    }

    CapabilityRegistry getCapabilityRegistry() { return mCapabilityRegistry; }
}
