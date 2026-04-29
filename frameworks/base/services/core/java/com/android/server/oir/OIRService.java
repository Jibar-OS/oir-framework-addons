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
import android.content.pm.PackageManager;
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

    // Worker binder lifecycle: attach/retry, mWorker holding, death recipient.
    // Listener body runs OUTSIDE the lock; onDeath acquires it as needed.
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

    public OIRService(Context context) {
        super(context);
        mContext = context;
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
        // No-op. attachWorker is triggered from onStart; onBootPhase is
        // retained for potential future hooks (e.g., warm-up on
        // PHASE_THIRD_PARTY_APPS_CAN_START).
    }

    /**
     * v0.4 lazy-load: look up or load the model for this capability.
     * Returns worker handle, or 0 on failure (caller sends onError).
     * Thread-safe via ConcurrentHashMap.computeIfAbsent + mWorkerLock.
     */
    private long ensureModelFor(String capability, IOIRTokenCallback cb) {
        Capability c = mCapabilityRegistry.get(capability);
        if (c == null) {
            safeAppError(cb, OIRError.INVALID_INPUT, "unknown capability: " + capability);
            return 0L;
        }
        Long existing = mHandles.get(capability);
        if (existing != null) return existing;

        // v0.6.9: only hold mWorkerLock long enough to claim a LoadFuture or
        // read mWorker; release BEFORE the binder RPC so concurrent submits
        // on *other* capabilities don't serialize behind our slow load.
        // Key includes a kind prefix so a misrouted caller using a
        // different ensure*ModelFor() for the same capability name doesn't
        // collide with ours.
        final String loadKey = "load:" + capability;
        IOirWorker worker;
        String path;
        LoadFuture ours = null;
        LoadFuture waitOn = null;
        synchronized (mLifecycle.getLock()) {
            existing = mHandles.get(capability);
            if (existing != null) return existing;
            if (mLifecycle.getWorkerLocked() == null) {
                safeAppError(cb, OIRError.WORKER_UNAVAILABLE, "worker not attached");
                return 0L;
            }
            waitOn = mLoadDedup.get(loadKey);
            if (waitOn == null) {
                path = c.defaultModelPath;
                if (path == null || path.isEmpty()) {
                    safeAppError(cb, OIRError.INVALID_INPUT,
                            "capability " + capability + " has no default-model; OEM must supply");
                    return 0L;
                }
                ours = new LoadFuture();
                mLoadDedup.put(loadKey, ours);
                worker = mLifecycle.getWorkerLocked();
            } else {
                worker = null;
                path = null;
            }
        }

        if (ours == null) {
            // Someone else is loading; wait on their LoadFuture.
            try {
                long h = waitOn.awaitHandle();
                if (h > 0) return h;
                safeAppError(cb, OIRError.MODEL_ERROR,
                        "concurrent load failed: " + waitOn.errMsg());
                return 0L;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                safeAppError(cb, OIRError.WORKER_UNAVAILABLE, "interrupted during load wait");
                return 0L;
            }
        }

        // We own the load. Binder RPC UNLOCKED.
        long h = 0L;
        String err = null;
        try {
            h = worker.load(path);
            if (h <= 0) err = "worker.load returned " + h + " for " + path;
        } catch (RemoteException e) {
            err = "load: " + e.getMessage();
            Log.w(TAG, "worker.load failed for " + capability, e);
        }

        synchronized (mLifecycle.getLock()) {
            if (h > 0) mHandles.put(capability, h);
            mLoadDedup.remove(loadKey);
        }
        ours.complete(h, err);

        if (h <= 0) {
            safeAppError(cb,
                    err != null && err.startsWith("load:") ? OIRError.WORKER_UNAVAILABLE
                                                           : OIRError.MODEL_ERROR,
                    err != null ? err : "load failed");
            return 0L;
        }
        Log.i(TAG, "lazy-loaded capability=" + capability + " handle=" + h + " path=" + path);
        return h;
    }

    /** v0.4 H4-A: same pattern as ensureModelFor but calls mWorker.loadEmbed(). */
    private long ensureEmbedModelFor(String capability, IOIRVectorCallback cb) {
        Capability c = mCapabilityRegistry.get(capability);
        if (c == null) {
            safeAppVectorError(cb, OIRError.INVALID_INPUT, "unknown capability: " + capability);
            return 0L;
        }
        Long existing = mHandles.get(capability);
        if (existing != null) return existing;

        // v0.6.9: release mWorkerLock around binder RPC (see ensureModelFor).
        final String loadKey = "loadEmbed:" + capability;
        IOirWorker worker;
        String path;
        LoadFuture ours = null;
        LoadFuture waitOn;
        synchronized (mLifecycle.getLock()) {
            existing = mHandles.get(capability);
            if (existing != null) return existing;
            if (mLifecycle.getWorkerLocked() == null) {
                safeAppVectorError(cb, OIRError.WORKER_UNAVAILABLE, "worker not attached");
                return 0L;
            }
            waitOn = mLoadDedup.get(loadKey);
            if (waitOn == null) {
                path = c.defaultModelPath;
                if (path == null || path.isEmpty()) {
                    safeAppVectorError(cb, OIRError.INVALID_INPUT,
                            "capability " + capability + " has no default-model; OEM must supply");
                    return 0L;
                }
                ours = new LoadFuture();
                mLoadDedup.put(loadKey, ours);
                worker = mLifecycle.getWorkerLocked();
            } else {
                worker = null;
                path = null;
            }
        }

        if (ours == null) {
            try {
                long h = waitOn.awaitHandle();
                if (h > 0) return h;
                safeAppVectorError(cb, OIRError.MODEL_ERROR,
                        "concurrent embed load failed: " + waitOn.errMsg());
                return 0L;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                safeAppVectorError(cb, OIRError.WORKER_UNAVAILABLE, "interrupted during load wait");
                return 0L;
            }
        }

        long h = 0L;
        String err = null;
        try {
            h = worker.loadEmbed(path);
            if (h <= 0) err = "worker.loadEmbed returned " + h + " for " + path;
        } catch (RemoteException e) {
            err = "loadEmbed: " + e.getMessage();
            Log.w(TAG, "worker.loadEmbed failed for " + capability, e);
        }

        synchronized (mLifecycle.getLock()) {
            if (h > 0) mHandles.put(capability, h);
            mLoadDedup.remove(loadKey);
        }
        ours.complete(h, err);

        if (h <= 0) {
            safeAppVectorError(cb,
                    err != null && err.startsWith("loadEmbed:") ? OIRError.WORKER_UNAVAILABLE
                                                                : OIRError.MODEL_ERROR,
                    err != null ? err : "loadEmbed failed");
            return 0L;
        }
        Log.i(TAG, "lazy-loaded embed capability=" + capability + " handle=" + h + " path=" + path);
        return h;
    }

    /** v0.4 H1: lazy-load whisper model for audio.* capability. */
    private long ensureWhisperModelFor(String capability, IOIRTokenCallback cb) {
        Capability c = mCapabilityRegistry.get(capability);
        if (c == null) {
            safeAppError(cb, OIRError.INVALID_INPUT, "unknown capability: " + capability);
            return 0L;
        }
        Long existing = mHandles.get(capability);
        if (existing != null) return existing;

        final String loadKey = "loadWhisper:" + capability;
        IOirWorker worker;
        String path;
        LoadFuture ours = null;
        LoadFuture waitOn;
        synchronized (mLifecycle.getLock()) {
            existing = mHandles.get(capability);
            if (existing != null) return existing;
            if (mLifecycle.getWorkerLocked() == null) {
                safeAppError(cb, OIRError.WORKER_UNAVAILABLE, "worker not attached");
                return 0L;
            }
            waitOn = mLoadDedup.get(loadKey);
            if (waitOn == null) {
                path = c.defaultModelPath;
                if (path == null || path.isEmpty()) {
                    safeAppError(cb, OIRError.INVALID_INPUT,
                            "capability " + capability + " has no default-model; OEM must supply");
                    return 0L;
                }
                ours = new LoadFuture();
                mLoadDedup.put(loadKey, ours);
                worker = mLifecycle.getWorkerLocked();
            } else {
                worker = null;
                path = null;
            }
        }

        if (ours == null) {
            try {
                long h = waitOn.awaitHandle();
                if (h > 0) return h;
                safeAppError(cb, OIRError.MODEL_ERROR,
                        "concurrent whisper load failed: " + waitOn.errMsg());
                return 0L;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                safeAppError(cb, OIRError.WORKER_UNAVAILABLE, "interrupted during load wait");
                return 0L;
            }
        }

        long h = 0L;
        String err = null;
        try {
            h = worker.loadWhisper(path);
            if (h <= 0) err = "worker.loadWhisper returned " + h + " for " + path;
        } catch (RemoteException e) {
            err = "loadWhisper: " + e.getMessage();
            Log.w(TAG, "worker.loadWhisper failed for " + capability, e);
        }

        synchronized (mLifecycle.getLock()) {
            if (h > 0) mHandles.put(capability, h);
            mLoadDedup.remove(loadKey);
        }
        ours.complete(h, err);

        if (h <= 0) {
            safeAppError(cb,
                    err != null && err.startsWith("loadWhisper:") ? OIRError.WORKER_UNAVAILABLE
                                                                  : OIRError.MODEL_ERROR,
                    err != null ? err : "loadWhisper failed");
            return 0L;
        }
        Log.i(TAG, "lazy-loaded whisper capability=" + capability + " handle=" + h + " path=" + path);
        return h;
    }

    /**
     * v0.4 H2/H3: lazy-load ONNX model. isDetection toggles ORT session
     * options (aggressive graph optimization for detect vs. conservative
     * for synth). Errors go through the provided callback, which is either
     * IOIRAudioStreamCallback (synth) or IOIRBoundingBoxCallback (detect);
     * callers use the right safeApp*Error wrapper.
     */
    private long ensureOnnxModelFor(String capability, boolean isDetection) {
        Capability c = mCapabilityRegistry.get(capability);
        if (c == null) return 0L;
        Long existing = mHandles.get(capability);
        if (existing != null) return existing;

        final String loadKey = (isDetection ? "loadOnnxDet:" : "loadOnnxSynth:") + capability;
        IOirWorker worker;
        String path;
        LoadFuture ours = null;
        LoadFuture waitOn;
        synchronized (mLifecycle.getLock()) {
            existing = mHandles.get(capability);
            if (existing != null) return existing;
            if (mLifecycle.getWorkerLocked() == null) return 0L;
            waitOn = mLoadDedup.get(loadKey);
            if (waitOn == null) {
                path = c.defaultModelPath;
                if (path == null || path.isEmpty()) return 0L;
                ours = new LoadFuture();
                mLoadDedup.put(loadKey, ours);
                worker = mLifecycle.getWorkerLocked();
            } else {
                worker = null;
                path = null;
            }
        }

        if (ours == null) {
            try {
                return waitOn.awaitHandle();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return 0L;
            }
        }

        long h = 0L;
        String err = null;
        try {
            h = worker.loadOnnx(path, isDetection);
            if (h <= 0) err = "loadOnnx returned " + h;
        } catch (RemoteException e) {
            err = "loadOnnx: " + e.getMessage();
            Log.w(TAG, "worker.loadOnnx failed for " + capability, e);
        }

        synchronized (mLifecycle.getLock()) {
            if (h > 0) mHandles.put(capability, h);
            mLoadDedup.remove(loadKey);
        }
        ours.complete(h, err);

        if (h <= 0) return 0L;
        Log.i(TAG, "lazy-loaded onnx capability=" + capability
                + " kind=" + (isDetection ? "detect" : "synth")
                + " handle=" + h + " path=" + path);
        return h;
    }

    /** v0.5 V5: lazy-load audio.vad ONNX (Silero). */
    private long ensureVadModelFor(String capability) {
        Capability c = mCapabilityRegistry.get(capability);
        if (c == null) return 0L;
        Long existing = mHandles.get(capability);
        if (existing != null) return existing;

        final String loadKey = "loadVad:" + capability;
        IOirWorker worker;
        String path;
        LoadFuture ours = null;
        LoadFuture waitOn;
        synchronized (mLifecycle.getLock()) {
            existing = mHandles.get(capability);
            if (existing != null) return existing;
            if (mLifecycle.getWorkerLocked() == null) return 0L;
            waitOn = mLoadDedup.get(loadKey);
            if (waitOn == null) {
                path = c.defaultModelPath;
                if (path == null || path.isEmpty()) return 0L;
                ours = new LoadFuture();
                mLoadDedup.put(loadKey, ours);
                worker = mLifecycle.getWorkerLocked();
            } else {
                worker = null;
                path = null;
            }
        }

        if (ours == null) {
            try {
                return waitOn.awaitHandle();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return 0L;
            }
        }

        long h = 0L;
        String err = null;
        try {
            h = worker.loadVad(path);
            if (h <= 0) err = "loadVad returned " + h;
        } catch (RemoteException e) {
            err = "loadVad: " + e.getMessage();
            Log.w(TAG, "worker.loadVad failed for " + capability, e);
        }

        synchronized (mLifecycle.getLock()) {
            if (h > 0) mHandles.put(capability, h);
            mLoadDedup.remove(loadKey);
        }
        ours.complete(h, err);

        if (h <= 0) return 0L;
        Log.i(TAG, "lazy-loaded vad capability=" + capability + " handle=" + h + " path=" + path);
        return h;
    }

    /** v0.4 H6: lazy-load VLM. capability.defaultModelPath is pipe-delimited "clip|llm". */
    private long ensureVlmModelFor(String capability, IOIRTokenCallback cb) {
        Capability c = mCapabilityRegistry.get(capability);
        if (c == null) {
            safeAppError(cb, OIRError.INVALID_INPUT, "unknown capability: " + capability);
            return 0L;
        }
        Long existing = mHandles.get(capability);
        if (existing != null) return existing;

        final String loadKey = "loadVlm:" + capability;
        IOirWorker worker;
        String clipPath;
        String llmPath;
        LoadFuture ours = null;
        LoadFuture waitOn;
        synchronized (mLifecycle.getLock()) {
            existing = mHandles.get(capability);
            if (existing != null) return existing;
            if (mLifecycle.getWorkerLocked() == null) {
                safeAppError(cb, OIRError.WORKER_UNAVAILABLE, "worker not attached");
                return 0L;
            }
            waitOn = mLoadDedup.get(loadKey);
            if (waitOn == null) {
                String path = c.defaultModelPath;
                if (path == null || path.isEmpty() || !path.contains("|")) {
                    safeAppError(cb, OIRError.INVALID_INPUT,
                            "vision.describe default-model must be pipe-delimited 'clip|llm': got '"
                                    + path + "'");
                    return 0L;
                }
                int bar = path.indexOf('|');
                clipPath = path.substring(0, bar);
                llmPath = path.substring(bar + 1);
                ours = new LoadFuture();
                mLoadDedup.put(loadKey, ours);
                worker = mLifecycle.getWorkerLocked();
            } else {
                worker = null;
                clipPath = null;
                llmPath = null;
            }
        }

        if (ours == null) {
            try {
                long h = waitOn.awaitHandle();
                if (h > 0) return h;
                safeAppError(cb, OIRError.MODEL_ERROR,
                        "concurrent VLM load failed: " + waitOn.errMsg());
                return 0L;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                safeAppError(cb, OIRError.WORKER_UNAVAILABLE, "interrupted during load wait");
                return 0L;
            }
        }

        long h = 0L;
        String err = null;
        try {
            h = worker.loadVlm(clipPath, llmPath);
            if (h <= 0) err = "worker.loadVlm returned " + h;
        } catch (RemoteException e) {
            err = "loadVlm: " + e.getMessage();
            Log.w(TAG, "worker.loadVlm failed for " + capability, e);
        }

        synchronized (mLifecycle.getLock()) {
            if (h > 0) mHandles.put(capability, h);
            mLoadDedup.remove(loadKey);
        }
        ours.complete(h, err);

        if (h <= 0) {
            safeAppError(cb,
                    err != null && err.startsWith("loadVlm:") ? OIRError.WORKER_UNAVAILABLE
                                                              : OIRError.MODEL_ERROR,
                    err != null ? err : "loadVlm failed");
            return 0L;
        }
        Log.i(TAG, "lazy-loaded VLM capability=" + capability + " handle=" + h
                + " clip=" + clipPath + " llm=" + llmPath);
        return h;
    }

    /** v0.4 H4-B: lazy-load vision encoder (SigLIP/CLIP ONNX). */
    private long ensureVisionEmbedModelFor(String capability, IOIRVectorCallback cb) {
        Capability c = mCapabilityRegistry.get(capability);
        if (c == null) {
            safeAppVectorError(cb, OIRError.INVALID_INPUT, "unknown capability: " + capability);
            return 0L;
        }
        Long existing = mHandles.get(capability);
        if (existing != null) return existing;

        final String loadKey = "loadVisionEmbed:" + capability;
        IOirWorker worker;
        String path;
        LoadFuture ours = null;
        LoadFuture waitOn;
        synchronized (mLifecycle.getLock()) {
            existing = mHandles.get(capability);
            if (existing != null) return existing;
            if (mLifecycle.getWorkerLocked() == null) {
                safeAppVectorError(cb, OIRError.WORKER_UNAVAILABLE, "worker not attached");
                return 0L;
            }
            waitOn = mLoadDedup.get(loadKey);
            if (waitOn == null) {
                path = c.defaultModelPath;
                if (path == null || path.isEmpty()) {
                    safeAppVectorError(cb, OIRError.INVALID_INPUT,
                            "capability " + capability + " has no default-model; OEM must supply");
                    return 0L;
                }
                ours = new LoadFuture();
                mLoadDedup.put(loadKey, ours);
                worker = mLifecycle.getWorkerLocked();
            } else {
                worker = null;
                path = null;
            }
        }

        if (ours == null) {
            try {
                long h = waitOn.awaitHandle();
                if (h > 0) return h;
                safeAppVectorError(cb, OIRError.MODEL_ERROR,
                        "concurrent vision-embed load failed: " + waitOn.errMsg());
                return 0L;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                safeAppVectorError(cb, OIRError.WORKER_UNAVAILABLE, "interrupted during load wait");
                return 0L;
            }
        }

        long h = 0L;
        String err = null;
        try {
            h = worker.loadVisionEmbed(path);
            if (h <= 0) err = "worker.loadVisionEmbed returned " + h + " for " + path;
        } catch (RemoteException e) {
            err = "loadVisionEmbed: " + e.getMessage();
            Log.w(TAG, "worker.loadVisionEmbed failed for " + capability, e);
        }

        synchronized (mLifecycle.getLock()) {
            if (h > 0) mHandles.put(capability, h);
            mLoadDedup.remove(loadKey);
        }
        ours.complete(h, err);

        if (h <= 0) {
            safeAppVectorError(cb,
                    err != null && err.startsWith("loadVisionEmbed:") ? OIRError.WORKER_UNAVAILABLE
                                                                      : OIRError.MODEL_ERROR,
                    err != null ? err : "loadVisionEmbed failed");
            return 0L;
        }
        Log.i(TAG, "lazy-loaded vision embed capability=" + capability + " handle=" + h + " path=" + path);
        return h;
    }

    private final IOIRService.Stub mBinder = new IOIRService.Stub() {

        @Override
        public long submit(String capability, String prompt, Bundle options,
                IOIRTokenCallback appCallback, ICancellationSignal cancel) {
            if (capability == null || capability.isEmpty()) capability = "text.complete";
            if (mCapabilityRegistry.get(capability) == null) {
                safeAppError(appCallback, OIRError.INVALID_INPUT, "unknown capability: " + capability);
                return 0L;
            }
            try {
                enforceOirPermission(capability, Binder.getCallingUid(), Binder.getCallingPid());
            } catch (SecurityException se) {
                safeAppError(appCallback, OIRError.PERMISSION_DENIED, se.getMessage());
                return 0L;
            }

            // v0.5 V6: per-UID rate limit check.
            if (!mRateLimiter.tryAcquire(Binder.getCallingUid())) {
                safeAppError(appCallback, OIRError.CAPABILITY_THROTTLED,
                        "rate limit exceeded for capability " + capability);
                return 0L;
            }

            if (prompt == null || prompt.isEmpty()) {
                safeAppError(appCallback, OIRError.INVALID_INPUT, "prompt is empty");
                return 0L;
            }

            // v0.4 H6: vision.describe routes to VLM; prompt field carries image path
            // (plus optional " | <user-prompt>" suffix for custom prompt text).
            if (capability.startsWith("vision.describe")) {
                String imagePath = prompt;
                String userPrompt = "";
                int bar = prompt.indexOf(" | ");
                if (bar > 0) {
                    imagePath = prompt.substring(0, bar);
                    userPrompt = prompt.substring(bar + 3);
                }
                final long vlmHandle = ensureVlmModelFor(capability, appCallback);
                if (vlmHandle == 0L) return 0L;
                final IOirWorker vw;
                synchronized (mLifecycle.getLock()) { vw = mLifecycle.getWorkerLocked(); }
                if (vw == null) {
                    safeAppError(appCallback, OIRError.WORKER_UNAVAILABLE, "worker not attached");
                    return 0L;
                }
                final long appHandleD = mNextRequestHandle.getAndIncrement();
                try {
                    Bundle meta = new Bundle();
                    meta.putLong("handle", appHandleD);
                    meta.putString("backend", "oird-vlm");
                    meta.putInt("prompt_tokens", 0);
                    // v0.6.1 audit: was hardcoded 4096. Honor the OEM's
                    // <capability_tuning><vision.describe.n_ctx> override so
                    // AgentKit's compaction math uses the actual ctx size
                    // the worker allocates. Falls back to the long-standing
                    // vision.describe default of 4096 if no override set.
                    meta.putInt("context_window", getCapabilityCtxSize(capability, 4096));
                    appCallback.onStart(meta);
                    long workerHandle = vw.submitDescribeImage(vlmHandle, imagePath, userPrompt,
                            new WorkerCallbackBridge(appHandleD, appCallback));
                    mAppHandleToWorkerHandle.put(appHandleD, workerHandle);
                    wireCancellationSignal(cancel, appHandleD);
                    return appHandleD;
                } catch (RemoteException e) {
                    Log.w(TAG, "submitDescribeImage failed app=" + appHandleD, e);
                    safeAppError(appCallback, OIRError.WORKER_UNAVAILABLE, "submitDescribeImage: " + e.getMessage());
                    return 0L;
                }
            }

            // v0.6 Phase B: text.translate reuses the text.complete llama pool
            // via prompt-template rewriting. Keeps zero marginal memory cost —
            // OEMs who want dedicated seq2seq swap default-model in
            // /vendor/etc/oir/. Apps pass Options{"sourceLang","targetLang"};
            // both default to "auto"/"en" when omitted.
            if (capability.equals("text.translate")
                    || capability.startsWith("text.translate:")) {
                String src = (options != null)
                        ? options.getString("sourceLang", "auto") : "auto";
                String tgt = (options != null)
                        ? options.getString("targetLang", "en") : "en";
                prompt = "Translate the following text from " + src
                        + " to " + tgt
                        + ". Return only the translation, with no commentary or"
                        + " preamble.\n\nText: " + prompt + "\n\nTranslation:";
            }

            // audio.synthesize and audio.vad are not token-stream shapes;
            // they have their own typed entry points (submitSynthesize /
            // submitVad). If an app hits the generic submit() with one of
            // those capabilities by mistake, the pre-v0.6.7 code would
            // happily feed the audio-file prompt to the whisper transcribe
            // path and produce confusing "whisper failed" errors. Now we
            // reject with a clear pointer to the correct API.
            if (capability.startsWith("audio.")
                    && !capability.equals("audio.transcribe")
                    && !capability.startsWith("audio.transcribe:")) {
                final String correctApi;
                if (capability.startsWith("audio.synthesize")) correctApi = "submitSynthesize";
                else if (capability.startsWith("audio.vad"))   correctApi = "submitVad";
                else                                           correctApi = "the capability-typed submit method";
                safeAppError(appCallback, OIRError.INVALID_INPUT,
                        "capability '" + capability + "' is not a token-stream shape; "
                                + "use " + correctApi + " instead of submit()");
                return 0L;
            }

            // audio.transcribe routes to whisper; prompt carries audio file path.
            if (capability.equals("audio.transcribe")
                    || capability.startsWith("audio.transcribe:")) {
                final long whisperHandle = ensureWhisperModelFor(capability, appCallback);
                if (whisperHandle == 0L) return 0L;
                final IOirWorker wworker;
                synchronized (mLifecycle.getLock()) { wworker = mLifecycle.getWorkerLocked(); }
                if (wworker == null) {
                    safeAppError(appCallback, OIRError.WORKER_UNAVAILABLE, "worker not attached");
                    return 0L;
                }
                final long appHandleA = mNextRequestHandle.getAndIncrement();
                try {
                    Bundle meta = new Bundle();
                    meta.putLong("handle", appHandleA);
                    meta.putString("backend", "oird-whisper");
                    // v0.4 §3.5: audio has no text prompt; context_window is the
                    // whisper-internal 30s audio window expressed in seconds * 50 frames
                    // — approximate to 0 for v0.4 (not meaningful for AgentKit use).
                    meta.putInt("prompt_tokens", 0);
                    meta.putInt("context_window", 0);
                    appCallback.onStart(meta);
                    long workerHandle = wworker.submitTranscribe(whisperHandle, prompt,
                            new WorkerCallbackBridge(appHandleA, appCallback));
                    mAppHandleToWorkerHandle.put(appHandleA, workerHandle);
                    wireCancellationSignal(cancel, appHandleA);
                    return appHandleA;
                } catch (RemoteException e) {
                    Log.w(TAG, "submitTranscribe failed app=" + appHandleA, e);
                    safeAppError(appCallback, OIRError.WORKER_UNAVAILABLE, "submitTranscribe: " + e.getMessage());
                    return 0L;
                }
            }

            final long modelHandle = ensureModelFor(capability, appCallback);
            if (modelHandle == 0L) return 0L;  // error already reported by ensureModelFor
            final IOirWorker worker;
            synchronized (mLifecycle.getLock()) {
                worker = mLifecycle.getWorkerLocked();
            }

            if (worker == null) {
                safeAppError(appCallback, OIRError.WORKER_UNAVAILABLE,
                        "worker not attached; respawn in progress");
                return 0L;
            }

            final long appHandle = mNextRequestHandle.getAndIncrement();
            try {
                Bundle meta = new Bundle();
                meta.putLong("handle", appHandle);
                meta.putString("backend", "oird");
                // v0.4 §3.5: AgentKit compaction hints. prompt_tokens=0 until
                // worker exposes tokenize+count sync AIDL. v0.6.1 audit: read
                // the capability-specific n_ctx from the parsed OirConfig
                // rather than a hardcoded 2048 so the OEM's
                // <capability_tuning><text.complete.n_ctx>8192</...> actually
                // reaches apps. Works for text.translate (reuses text.complete
                // model → same knob via startsWith in the resolver).
                meta.putInt("prompt_tokens", 0);
                meta.putInt("context_window", getCapabilityCtxSize(capability, 2048));
                appCallback.onStart(meta);
            } catch (RemoteException e) {
                Log.w(TAG, "onStart failed app=" + appHandle, e);
                return 0L;
            }

            // Extract the two knobs v0.1's internal AIDL supports; anything
            // else in the Bundle is ignored for now. v0.2 will promote options
            // to a proper AIDL parcelable type.
            int maxTokens = (options != null) ? options.getInt("maxTokens", 256) : 256;
            float temperature = (options != null) ? options.getFloat("temperature", 0.7f) : 0.7f;

            try {
                long workerHandle = worker.submit(modelHandle, prompt,
                        maxTokens, temperature,
                        new WorkerCallbackBridge(appHandle, appCallback));
                mAppHandleToWorkerHandle.put(appHandle, workerHandle);

                // v0.6.1: use the shared helper so cancellation semantics
                // stay uniform across every submit* method.
                wireCancellationSignal(cancel, appHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "worker.submit failed app=" + appHandle, e);
                safeAppError(appCallback, OIRError.MODEL_ERROR,
                        "worker.submit failed: " + e.getMessage());
                return 0L;
            }

            Log.i(TAG, "submit app=" + appHandle + " uid=" + Binder.getCallingUid()
                    + " prompt.len=" + prompt.length());
            return appHandle;
        }

        @Override
        public void cancel(long requestHandle) {
            Long workerHandle = mAppHandleToWorkerHandle.remove(requestHandle);
            if (workerHandle == null) return;
            IOirWorker worker;
            synchronized (mLifecycle.getLock()) { worker = mLifecycle.getWorkerLocked(); }
            if (worker == null) return;
            try { worker.cancel(workerHandle); }
            catch (RemoteException e) { Log.w(TAG, "cancel failed app=" + requestHandle, e); }
        }

        @Override
        public long submitEmbedText(String capability, String text,
                IOIRVectorCallback appCallback, ICancellationSignal cancel) {
            if (capability == null || capability.isEmpty()) capability = "text.embed";
            if (mCapabilityRegistry.get(capability) == null) {
                safeAppVectorError(appCallback, OIRError.INVALID_INPUT, "unknown capability: " + capability);
                return 0L;
            }
            try {
                enforceOirPermission(capability, Binder.getCallingUid(), Binder.getCallingPid());
            } catch (SecurityException se) {
                safeAppVectorError(appCallback, OIRError.PERMISSION_DENIED, se.getMessage());
                return 0L;
            }
            // v0.5 V6: per-UID rate limit check.
            if (!mRateLimiter.tryAcquire(Binder.getCallingUid())) {
                safeAppVectorError(appCallback, OIRError.CAPABILITY_THROTTLED,
                        "rate limit exceeded for capability " + capability);
                return 0L;
            }
            if (text == null || text.isEmpty()) {
                safeAppVectorError(appCallback, OIRError.INVALID_INPUT, "text is empty");
                return 0L;
            }

            // v0.4 H4-B: vision.embed routes to vision encoder (text carries imagePath).
            if (capability.startsWith("vision.embed")) {
                final long vHandle = ensureVisionEmbedModelFor(capability, appCallback);
                if (vHandle == 0L) return 0L;
                final IOirWorker vw;
                synchronized (mLifecycle.getLock()) { vw = mLifecycle.getWorkerLocked(); }
                if (vw == null) {
                    safeAppVectorError(appCallback, OIRError.WORKER_UNAVAILABLE, "worker not attached");
                    return 0L;
                }
                final long appHandleV = mNextRequestHandle.getAndIncrement();
                try {
                    long workerHandle = vw.submitVisionEmbed(vHandle, text,
                            new WorkerVectorCallbackBridge(appHandleV, appCallback));
                    mAppHandleToWorkerHandle.put(appHandleV, workerHandle);
                    wireCancellationSignal(cancel, appHandleV);
                    return appHandleV;
                } catch (RemoteException e) {
                    safeAppVectorError(appCallback, OIRError.WORKER_UNAVAILABLE, "submitVisionEmbed: " + e.getMessage());
                    return 0L;
                }
            }

            // v0.6 Phase B: text.classify routes to an ONNX classifier
            // (logits head → Vector of per-label scores). Separate ONNX pool
            // because the classifier is an encoder-only transformer, not
            // the llama embedder. No default model bakes in — capability
            // declared unbacked; OEMs ship their classifier via /product/
            // or /vendor/ overrides. Surface NO_MODEL cleanly so apps can
            // feature-detect.
            if (capability.startsWith("text.classify")) {
                final long cHandle = ensureOnnxModelFor(capability, /*isDetection=*/false);
                if (cHandle == 0L) {
                    safeAppVectorError(appCallback,
                            OIRError.CAPABILITY_UNAVAILABLE_NO_MODEL,
                            "no default classifier for " + capability
                                    + " — OEM must bake-in via /product/etc/oir/");
                    return 0L;
                }
                final IOirWorker cw;
                synchronized (mLifecycle.getLock()) { cw = mLifecycle.getWorkerLocked(); }
                if (cw == null) {
                    safeAppVectorError(appCallback, OIRError.WORKER_UNAVAILABLE, "worker not attached");
                    return 0L;
                }
                final long appHandleC = mNextRequestHandle.getAndIncrement();
                try {
                    long workerHandle = cw.submitClassify(cHandle, text,
                            new WorkerVectorCallbackBridge(appHandleC, appCallback));
                    mAppHandleToWorkerHandle.put(appHandleC, workerHandle);
                    wireCancellationSignal(cancel, appHandleC);
                    return appHandleC;
                } catch (RemoteException e) {
                    Log.w(TAG, "submitClassify failed app=" + appHandleC, e);
                    safeAppVectorError(appCallback, OIRError.WORKER_UNAVAILABLE, "submitClassify: " + e.getMessage());
                    return 0L;
                }
            }

            final long modelHandle = ensureEmbedModelFor(capability, appCallback);
            if (modelHandle == 0L) return 0L;
            final IOirWorker worker;
            synchronized (mLifecycle.getLock()) { worker = mLifecycle.getWorkerLocked(); }
            if (worker == null) {
                safeAppVectorError(appCallback, OIRError.WORKER_UNAVAILABLE, "worker not attached");
                return 0L;
            }

            final long appHandle = mNextRequestHandle.getAndIncrement();
            try {
                long workerHandle = worker.submitEmbed(modelHandle, text,
                        new WorkerVectorCallbackBridge(appHandle, appCallback));
                mAppHandleToWorkerHandle.put(appHandle, workerHandle);
                wireCancellationSignal(cancel, appHandle);
                return appHandle;
            } catch (RemoteException e) {
                Log.w(TAG, "submitEmbed failed app=" + appHandle, e);
                safeAppVectorError(appCallback, OIRError.WORKER_UNAVAILABLE, "submitEmbed: " + e.getMessage());
                return 0L;
            }
        }

        @Override
        public long submitSynthesize(String capability, String text,
                IOIRAudioStreamCallback appCallback, ICancellationSignal cancel) {
            if (capability == null || capability.isEmpty()) capability = "audio.synthesize";
            Capability c = mCapabilityRegistry.get(capability);
            if (c == null) {
                safeAppAudioError(appCallback, OIRError.INVALID_INPUT, "unknown capability: " + capability);
                return 0L;
            }
            try {
                enforceOirPermission(capability, Binder.getCallingUid(), Binder.getCallingPid());
            } catch (SecurityException se) {
                safeAppAudioError(appCallback, OIRError.PERMISSION_DENIED, se.getMessage());
                return 0L;
            }
            // v0.5 V6: per-UID rate limit check.
            if (!mRateLimiter.tryAcquire(Binder.getCallingUid())) {
                safeAppAudioError(appCallback, OIRError.CAPABILITY_THROTTLED,
                        "rate limit exceeded for capability " + capability);
                return 0L;
            }
            if (text == null || text.isEmpty()) {
                safeAppAudioError(appCallback, OIRError.INVALID_INPUT, "text is empty");
                return 0L;
            }
            final long modelHandle = ensureOnnxModelFor(capability, /*isDetection=*/false);
            if (modelHandle == 0L) {
                // v0.6.1 audit: was MODEL_ERROR. The typed NO_MODEL error
                // is the whole point of the "declared but unbacked"
                // capability pattern — apps feature-detect on this code.
                safeAppAudioError(appCallback, OIRError.CAPABILITY_UNAVAILABLE_NO_MODEL,
                        "no synth model for " + capability + " (OEM must bake-in)");
                return 0L;
            }
            final IOirWorker worker;
            synchronized (mLifecycle.getLock()) { worker = mLifecycle.getWorkerLocked(); }
            if (worker == null) {
                safeAppAudioError(appCallback, OIRError.WORKER_UNAVAILABLE, "worker not attached");
                return 0L;
            }
            final long appHandle = mNextRequestHandle.getAndIncrement();
            try {
                long workerHandle = worker.submitSynthesize(modelHandle, text,
                        new WorkerAudioCallbackBridge(appHandle, appCallback));
                mAppHandleToWorkerHandle.put(appHandle, workerHandle);
                wireCancellationSignal(cancel, appHandle);
                return appHandle;
            } catch (RemoteException e) {
                Log.w(TAG, "submitSynthesize failed app=" + appHandle, e);
                safeAppAudioError(appCallback, OIRError.WORKER_UNAVAILABLE, "submitSynthesize: " + e.getMessage());
                return 0L;
            }
        }

        @Override
        public long submitDetect(String capability, String imagePath,
                IOIRBoundingBoxCallback appCallback, ICancellationSignal cancel) {
            if (capability == null || capability.isEmpty()) capability = "vision.detect";
            Capability c = mCapabilityRegistry.get(capability);
            if (c == null) {
                safeAppBboxError(appCallback, OIRError.INVALID_INPUT, "unknown capability: " + capability);
                return 0L;
            }
            try {
                enforceOirPermission(capability, Binder.getCallingUid(), Binder.getCallingPid());
            } catch (SecurityException se) {
                safeAppBboxError(appCallback, OIRError.PERMISSION_DENIED, se.getMessage());
                return 0L;
            }
            // v0.5 V6: per-UID rate limit check.
            if (!mRateLimiter.tryAcquire(Binder.getCallingUid())) {
                safeAppBboxError(appCallback, OIRError.CAPABILITY_THROTTLED,
                        "rate limit exceeded for capability " + capability);
                return 0L;
            }
            if (imagePath == null || imagePath.isEmpty()) {
                safeAppBboxError(appCallback, OIRError.INVALID_INPUT, "imagePath is empty");
                return 0L;
            }

            // v0.6 Phase B: vision.ocr uses the same BoundingBoxes shape
            // as vision.detect (label field carries OCR'd text instead
            // of class name). Worker dispatches to submitOcr which runs
            // a det+rec ONNX pair. No default model ships — declared
            // unbacked so apps see NO_MODEL cleanly until OEM bakes.
            final boolean isOcr = capability.equals("vision.ocr")
                    || capability.startsWith("vision.ocr:");
            final long modelHandle = ensureOnnxModelFor(capability, /*isDetection=*/true);
            if (modelHandle == 0L) {
                safeAppBboxError(appCallback,
                        isOcr ? OIRError.CAPABILITY_UNAVAILABLE_NO_MODEL
                              : OIRError.MODEL_ERROR,
                        (isOcr ? "no OCR model for " : "no detect model for ")
                                + capability + " (OEM must bake-in)");
                return 0L;
            }
            final IOirWorker worker;
            synchronized (mLifecycle.getLock()) { worker = mLifecycle.getWorkerLocked(); }
            if (worker == null) {
                safeAppBboxError(appCallback, OIRError.WORKER_UNAVAILABLE, "worker not attached");
                return 0L;
            }
            final long appHandle = mNextRequestHandle.getAndIncrement();
            try {
                long workerHandle;
                if (isOcr) {
                    workerHandle = worker.submitOcr(modelHandle, imagePath,
                            new WorkerBboxCallbackBridge(appHandle, appCallback));
                } else {
                    workerHandle = worker.submitDetect(modelHandle, imagePath,
                            new WorkerBboxCallbackBridge(appHandle, appCallback));
                }
                mAppHandleToWorkerHandle.put(appHandle, workerHandle);
                wireCancellationSignal(cancel, appHandle);
                return appHandle;
            } catch (RemoteException e) {
                Log.w(TAG, (isOcr ? "submitOcr" : "submitDetect")
                        + " failed app=" + appHandle, e);
                safeAppBboxError(appCallback, OIRError.WORKER_UNAVAILABLE,
                        (isOcr ? "submitOcr: " : "submitDetect: ") + e.getMessage());
                return 0L;
            }
        }

        @Override
        public long submitVad(String capability, String pcmPath,
                IOIRRealtimeBooleanCallback appCallback, ICancellationSignal cancel) {
            if (capability == null || capability.isEmpty()) capability = "audio.vad";
            Capability c = mCapabilityRegistry.get(capability);
            if (c == null) {
                safeAppBooleanError(appCallback, OIRError.INVALID_INPUT, "unknown capability: " + capability);
                return 0L;
            }
            try {
                enforceOirPermission(capability, Binder.getCallingUid(), Binder.getCallingPid());
            } catch (SecurityException se) {
                safeAppBooleanError(appCallback, OIRError.PERMISSION_DENIED, se.getMessage());
                return 0L;
            }
            if (!mRateLimiter.tryAcquire(Binder.getCallingUid())) {
                safeAppBooleanError(appCallback, OIRError.CAPABILITY_THROTTLED,
                        "rate limit exceeded for capability " + capability);
                return 0L;
            }
            if (pcmPath == null || pcmPath.isEmpty()) {
                safeAppBooleanError(appCallback, OIRError.INVALID_INPUT, "pcmPath is empty");
                return 0L;
            }
            final long modelHandle = ensureVadModelFor(capability);
            if (modelHandle == 0L) {
                safeAppBooleanError(appCallback, OIRError.CAPABILITY_UNAVAILABLE_NO_MODEL,
                        "no vad model resolvable for " + capability);
                return 0L;
            }
            final IOirWorker worker;
            synchronized (mLifecycle.getLock()) { worker = mLifecycle.getWorkerLocked(); }
            if (worker == null) {
                safeAppBooleanError(appCallback, OIRError.WORKER_UNAVAILABLE, "worker not attached");
                return 0L;
            }
            final long appHandle = mNextRequestHandle.getAndIncrement();
            try {
                long workerHandle = worker.submitVad(modelHandle, pcmPath,
                        new WorkerRealtimeBooleanCallbackBridge(appHandle, appCallback));
                mAppHandleToWorkerHandle.put(appHandle, workerHandle);
                wireCancellationSignal(cancel, appHandle);
            } catch (RemoteException e) {
                Log.w(TAG, "submitVad failed app=" + appHandle, e);
                safeAppBooleanError(appCallback, OIRError.WORKER_UNAVAILABLE, "submitVad: " + e.getMessage());
                return 0L;
            }
            Log.i(TAG, "submitVad app=" + appHandle + " uid=" + Binder.getCallingUid()
                    + " capability=" + capability);
            return appHandle;
        }

        @Override
        public long submitRerank(String capability, String query,
                String[] candidates, IOIRVectorCallback appCallback,
                ICancellationSignal cancel) {
            if (capability == null || capability.isEmpty()) capability = "text.rerank";
            Capability c = mCapabilityRegistry.get(capability);
            if (c == null) {
                safeAppVectorError(appCallback, OIRError.INVALID_INPUT, "unknown capability: " + capability);
                return 0L;
            }
            try {
                enforceOirPermission(capability, Binder.getCallingUid(), Binder.getCallingPid());
            } catch (SecurityException se) {
                safeAppVectorError(appCallback, OIRError.PERMISSION_DENIED, se.getMessage());
                return 0L;
            }
            if (!mRateLimiter.tryAcquire(Binder.getCallingUid())) {
                safeAppVectorError(appCallback, OIRError.CAPABILITY_THROTTLED,
                        "rate limit exceeded for capability " + capability);
                return 0L;
            }
            if (query == null || query.isEmpty()) {
                safeAppVectorError(appCallback, OIRError.INVALID_INPUT, "query is empty");
                return 0L;
            }
            if (candidates == null || candidates.length == 0) {
                safeAppVectorError(appCallback, OIRError.INVALID_INPUT, "candidates is empty");
                return 0L;
            }
            final long modelHandle = ensureOnnxModelFor(capability, /*isDetection=*/false);
            if (modelHandle == 0L) {
                safeAppVectorError(appCallback, OIRError.CAPABILITY_UNAVAILABLE_NO_MODEL,
                        "no reranker model for " + capability + " (OEM may bake-in)");
                return 0L;
            }
            final IOirWorker worker;
            synchronized (mLifecycle.getLock()) { worker = mLifecycle.getWorkerLocked(); }
            if (worker == null) {
                safeAppVectorError(appCallback, OIRError.WORKER_UNAVAILABLE, "worker not attached");
                return 0L;
            }
            final long appHandle = mNextRequestHandle.getAndIncrement();
            try {
                long workerHandle = worker.submitRerank(modelHandle, query, candidates,
                        new WorkerVectorCallbackBridge(appHandle, appCallback));
                mAppHandleToWorkerHandle.put(appHandle, workerHandle);
                wireCancellationSignal(cancel, appHandle);
                return appHandle;
            } catch (RemoteException e) {
                Log.w(TAG, "submitRerank failed app=" + appHandle, e);
                safeAppVectorError(appCallback, OIRError.WORKER_UNAVAILABLE, "submitRerank: " + e.getMessage());
                return 0L;
            }
        }

        @Override
        public int isCapabilityRunnable(String capability) {
            if (capability == null || capability.isEmpty()) return 0;
            Capability c = mCapabilityRegistry.get(capability);
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
            Capability c = mCapabilityRegistry.get(capability);
            if (c == null) throw new IllegalArgumentException("unknown capability: " + capability);
            try {
                enforceOirPermission(capability, Binder.getCallingUid(), Binder.getCallingPid());
            } catch (SecurityException se) {
                throw se;
            }
            // v0.6.1 audit: warm was hardcoded to worker.load(), which is the
            // llama path only. Non-llama capabilities load via separate
            // worker entry points (loadEmbed / loadWhisper / loadOnnx /
            // loadVad / loadVlm / loadVisionEmbed) and were silently
            // warming the wrong handle (or wrong model type, causing a
            // spurious first-submit load). Dispatch on capability name +
            // declared backend so warm() matches the ensure*ModelFor()
            // chosen by the submit path.
            // v0.6.9: do NOT hold mWorkerLock across the ensure*ModelFor
            // calls — those methods themselves release mWorkerLock to issue
            // the binder RPC, but Java's synchronized is reentrant for the
            // same thread, so an outer synchronized(mWorkerLock) here would
            // keep the lock held on this thread through the entire load,
            // serializing every other app's submit behind one warm.
            // Snapshot mWorker for the final warm() call; ensure* methods
            // each handle their own mWorker==null check and short-circuit.
            IOirWorker workerSnapshot;
            synchronized (mLifecycle.getLock()) {
                workerSnapshot = mLifecycle.getWorkerLocked();
                if (workerSnapshot == null) return;
            }
            try {
                long h;
                String backend;
                if (capability.startsWith("vision.describe")) {
                    // VLM pair — ensureVlmModelFor parses the pipe-delimited
                    // "mmproj|llm" default-model and calls loadVlm.
                    h = ensureVlmModelFor(capability, /*cb=*/ null);
                    backend = "vlm";
                } else if (capability.startsWith("audio.transcribe")) {
                    h = ensureWhisperModelFor(capability, /*cb=*/ null);
                    backend = "whisper";
                } else if (capability.startsWith("audio.vad")) {
                    h = ensureVadModelFor(capability);
                    backend = "vad";
                } else if (capability.startsWith("vision.embed")) {
                    h = ensureVisionEmbedModelFor(capability, /*cb=*/ null);
                    backend = "vision_embed";
                } else if (capability.startsWith("text.embed")) {
                    h = ensureEmbedModelFor(capability, /*cb=*/ null);
                    backend = "embed";
                } else if (capability.startsWith("vision.detect")
                        || capability.startsWith("vision.ocr")) {
                    // ORT "detection" session-build flag — larger tensors.
                    h = ensureOnnxModelFor(capability, /*isDetection=*/ true);
                    backend = "ort_det";
                } else if (capability.startsWith("text.classify")
                        || capability.startsWith("text.rerank")
                        || capability.startsWith("audio.synthesize")) {
                    h = ensureOnnxModelFor(capability, /*isDetection=*/ false);
                    backend = "ort";
                } else {
                    // Default: text.complete / text.translate / any llama-backed
                    // capability we haven't special-cased. Goes through
                    // ensureModelFor so per-capability pool + tuning knobs
                    // match the submit() path.
                    h = ensureModelFor(capability, /*cb=*/ null);
                    backend = "llama";
                }
                if (h > 0) {
                    // Re-snapshot mWorker in case binderDied since the first
                    // read; ensure*ModelFor returning h>0 is no guarantee
                    // that mWorker is still attached at this instant.
                    IOirWorker w;
                    synchronized (mLifecycle.getLock()) { w = mLifecycle.getWorkerLocked(); }
                    if (w != null) w.warm(h);
                }
                Log.i(TAG, "warm capability=" + capability + " backend=" + backend + " handle=" + h);
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

    private final class WorkerVectorCallbackBridge extends IOirWorkerVectorCallback.Stub {
        private final long mAppHandle;
        private final IOIRVectorCallback mAppCallback;

        WorkerVectorCallbackBridge(long appHandle, IOIRVectorCallback appCallback) {
            mAppHandle = appHandle;
            mAppCallback = appCallback;
        }

        @Override
        public void onVector(float[] vec) {
            mAppHandleToWorkerHandle.remove(mAppHandle);
            try { mAppCallback.onVector(vec); }
            catch (RemoteException e) { Log.w(TAG, "onVector app=" + mAppHandle + " relay failed", e); }
        }

        @Override
        public void onError(int workerCode, String message) {
            mAppHandleToWorkerHandle.remove(mAppHandle);
            try { mAppCallback.onError(workerCode, message); }
            catch (RemoteException e) { Log.w(TAG, "onError(vec) app=" + mAppHandle + " relay failed", e); }
        }
    }

    /** v0.4 H2: internal→public audio stream relay. */
    private final class WorkerAudioCallbackBridge extends IOirWorkerAudioCallback.Stub {
        private final long mAppHandle;
        private final IOIRAudioStreamCallback mAppCallback;

        WorkerAudioCallbackBridge(long appHandle, IOIRAudioStreamCallback appCallback) {
            mAppHandle = appHandle;
            mAppCallback = appCallback;
        }

        @Override
        public void onChunk(byte[] pcm, int sampleRateHz, int channelCount, int encoding, boolean last) {
            try { mAppCallback.onChunk(pcm, sampleRateHz, channelCount, encoding, last); }
            catch (RemoteException e) { Log.w(TAG, "onChunk app=" + mAppHandle + " relay failed", e); }
        }

        @Override
        public void onComplete(long totalMs) {
            mAppHandleToWorkerHandle.remove(mAppHandle);
            try { mAppCallback.onComplete(totalMs); }
            catch (RemoteException e) { Log.w(TAG, "onComplete(audio) app=" + mAppHandle + " relay failed", e); }
        }

        @Override
        public void onError(int workerCode, String message) {
            mAppHandleToWorkerHandle.remove(mAppHandle);
            try { mAppCallback.onError(workerCode, message); }
            catch (RemoteException e) { Log.w(TAG, "onError(audio) app=" + mAppHandle + " relay failed", e); }
        }
    }

    /**
     * v0.5 V5: internal→public RealtimeBoolean relay. Trivial pass-through —
     * both shapes are identical (bool + timestampMs + terminal onComplete /
     * onError). Keeping the bridge here follows the per-callback-shape
     * convention so the public IOIRRealtimeBooleanCallback can evolve
     * independently if needed without rewriting worker callers.
     */
    private final class WorkerRealtimeBooleanCallbackBridge
            extends IOirWorkerRealtimeBooleanCallback.Stub {
        private final long mAppHandle;
        private final IOIRRealtimeBooleanCallback mAppCallback;

        WorkerRealtimeBooleanCallbackBridge(long appHandle, IOIRRealtimeBooleanCallback appCallback) {
            mAppHandle = appHandle;
            mAppCallback = appCallback;
        }

        @Override
        public void onState(boolean isTrue, long timestampMs) {
            try { mAppCallback.onState(isTrue, timestampMs); }
            catch (RemoteException e) { Log.w(TAG, "onState app=" + mAppHandle + " relay failed", e); }
        }

        @Override
        public void onComplete() {
            mAppHandleToWorkerHandle.remove(mAppHandle);
            try { mAppCallback.onComplete(); }
            catch (RemoteException e) { Log.w(TAG, "onComplete(bool) app=" + mAppHandle + " relay failed", e); }
        }

        @Override
        public void onError(int workerCode, String message) {
            mAppHandleToWorkerHandle.remove(mAppHandle);
            try { mAppCallback.onError(workerCode, message); }
            catch (RemoteException e) { Log.w(TAG, "onError(bool) app=" + mAppHandle + " relay failed", e); }
        }
    }

    /**
     * v0.4 H3: internal→public bounding-box relay. Worker sends flattened
     * parallel arrays (NDK AIDL limitation); repack into List&lt;BoundingBox&gt;
     * for the public callback.
     */
    private final class WorkerBboxCallbackBridge extends IOirWorkerBboxCallback.Stub {
        private final long mAppHandle;
        private final IOIRBoundingBoxCallback mAppCallback;

        WorkerBboxCallbackBridge(long appHandle, IOIRBoundingBoxCallback appCallback) {
            mAppHandle = appHandle;
            mAppCallback = appCallback;
        }

        @Override
        public void onBoundingBoxes(int[] xs, int[] ys, int[] widths, int[] heights,
                int[] labelsPerBox, String[] labelsFlat, float[] scoresFlat) {
            mAppHandleToWorkerHandle.remove(mAppHandle);
            java.util.List<BoundingBox> boxes = new java.util.ArrayList<>(xs.length);
            int labelOffset = 0;
            for (int i = 0; i < xs.length; i++) {
                BoundingBox bb = new BoundingBox();
                bb.x = xs[i];
                bb.y = ys[i];
                bb.width = widths[i];
                bb.height = heights[i];
                int n = labelsPerBox[i];
                bb.labels = new String[n];
                bb.scores = new float[n];
                for (int j = 0; j < n; j++) {
                    bb.labels[j] = labelsFlat[labelOffset + j];
                    bb.scores[j] = scoresFlat[labelOffset + j];
                }
                labelOffset += n;
                boxes.add(bb);
            }
            try { mAppCallback.onBoundingBoxes(boxes); }
            catch (RemoteException e) { Log.w(TAG, "onBoundingBoxes app=" + mAppHandle + " relay failed", e); }
        }

        @Override
        public void onError(int workerCode, String message) {
            mAppHandleToWorkerHandle.remove(mAppHandle);
            try { mAppCallback.onError(workerCode, message); }
            catch (RemoteException e) { Log.w(TAG, "onError(bbox) app=" + mAppHandle + " relay failed", e); }
        }
    }

    private final class WorkerCallbackBridge extends IOirWorkerCallback.Stub {
        private final long mAppHandle;
        private final IOIRTokenCallback mAppCallback;

        WorkerCallbackBridge(long appHandle, IOIRTokenCallback appCallback) {
            mAppHandle = appHandle;
            mAppCallback = appCallback;
        }

        @Override public void onToken(String token, int outputIndex) {
            try { mAppCallback.onToken(token, outputIndex); }
            catch (RemoteException e) { Log.w(TAG, "onToken failed app=" + mAppHandle, e); }
        }

        @Override public void onComplete(int totalTokens, long firstTokenMs, long totalMs) {
            mAppHandleToWorkerHandle.remove(mAppHandle);
            Bundle appStats = new Bundle();
            appStats.putInt("totalTokens", totalTokens);
            appStats.putLong("firstTokenMs", firstTokenMs);
            appStats.putLong("totalMs", totalMs);
            try { mAppCallback.onComplete(appStats); }
            catch (RemoteException e) { Log.w(TAG, "onComplete failed app=" + mAppHandle, e); }
        }

        @Override public void onError(int workerErrorCode, String message) {
            mAppHandleToWorkerHandle.remove(mAppHandle);
            safeAppError(mAppCallback, workerErrorCode, message);
        }
    }

    /**
     * Permission enforcement used by {@link IOIRService.Stub#submit} and the shell path.
     * effectiveUid lets the shell command simulate a non-system caller via --as-uid
     * (userdebug/eng builds only; \see #submitAs).
     */
    void enforceOirPermission(String capability, int effectiveUid, int callingPid) {
        String perm = mCapabilityRegistry.getRequiredPermission(capability);
        if (perm == null) {
            throw new IllegalArgumentException("unknown capability: " + capability);
        }
        // Shell UID bypass for developer ergonomics (unless --as-uid was set).
        if (effectiveUid == Process.SHELL_UID) return;
        int result = mContext.checkPermission(perm, callingPid, effectiveUid);
        if (result != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Permission Denial: " + perm
                    + " required for capability " + capability
                    + " (uid=" + effectiveUid + ")");
        }
    }

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
            safeAppError(cb, OIRError.INVALID_INPUT, "unknown capability: " + capability);
            return 0L;
        }
        try {
            enforceOirPermission(capability == null ? "text.complete" : capability,
                    effectiveUid, Binder.getCallingPid());
        } catch (SecurityException se) {
            safeAppError(cb, OIRError.PERMISSION_DENIED, se.getMessage());
            return 0L;
        }
        // v0.5 V6: rate-limit against the simulated UID so `cmd oir --as-uid <N>`
        // exercises the same throttling path an app would hit. mBinder.submit()
        // below sees the real (shell) UID and bypasses.
        if (!mRateLimiter.tryAcquire(effectiveUid)) {
            safeAppError(cb, OIRError.CAPABILITY_THROTTLED,
                    "rate limit exceeded for capability " + capability + " (as-uid=" + effectiveUid + ")");
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

    /**
     * v0.6.3: package-private helper for the runtime-pool columns in
     * `cmd oir memory`. TSV-per-loaded-model from the worker. Empty or
     * null when worker isn't attached — the caller prints model-level
     * stats anyway.
     */
    String getInternalRuntimeStats() {
        synchronized (mLifecycle.getLock()) {
            IOirWorker w = mLifecycle.getWorkerLocked();
            if (w == null) return null;
            try {
                return w.dumpRuntimeStats();
            } catch (RemoteException e) {
                Log.w(TAG, "dumpRuntimeStats failed", e);
                return null;
            }
        }
    }

    CapabilityRegistry getCapabilityRegistry() { return mCapabilityRegistry; }

    /**
     * v0.6.1 audit: `context_window` in submit()'s onStart meta had been
     * hardcoded (2048 for text, 4096 for describe). v0.5 V7 added
     * `<capability_tuning><capability.n_ctx>` as the runtime-configurable
     * knob — read it back here so AgentKit's prompt-compaction math uses
     * the actual ctx the worker allocates. Falls back to the [fallback]
     * argument when no override is configured.
     *
     * Cast to int is safe: n_ctx values parsed as floats (via
     * OirConfig.getCapabilityTuning) are always integer-valued in practice;
     * Math.max enforces a sensible floor in case of config corruption.
     */
    private int getCapabilityCtxSize(String capability, int fallback) {
        if (capability == null) return fallback;
        java.util.Map<String, Float> t = mConfig.getCapabilityTuning();
        Float v = t.get(capability + ".n_ctx");
        if (v != null) return Math.max(1, v.intValue());
        // v0.6.1: text.translate reuses the text.complete model by default
        // (v0.6 prompt-template routing). Inherit text.complete.n_ctx when
        // text.translate has no explicit tuning so AgentKit's compaction
        // math doesn't drift if OEM set only text.complete.n_ctx.
        if (capability.startsWith("text.translate")) {
            v = t.get("text.complete.n_ctx");
            if (v != null) return Math.max(1, v.intValue());
        }
        return fallback;
    }

    /**
     * v0.6.1 audit: cancellation had been wired only in submit() (text.complete
     * path). Every other submit* method accepted the ICancellationSignal arg
     * and dropped it — so apps could cancel text.complete but not text.embed,
     * text.rerank, audio.synthesize, vision.detect, vision.ocr, or audio.vad.
     * Single helper keeps the five submit* methods consistent and means a
     * future change (different cancel semantics, backpressure, etc.) lands
     * in one place.
     */
    private void wireCancellationSignal(ICancellationSignal cancel, long appHandle) {
        if (cancel == null) return;
        try {
            CancellationSignal.fromTransport(cancel)
                    .setOnCancelListener(() -> {
                        try { mBinder.cancel(appHandle); }
                        catch (RemoteException re) {
                            Log.w(TAG, "cancel forward failed app=" + appHandle, re);
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "setOnCancelListener failed app=" + appHandle, e);
        }
    }

    private static void safeAppAudioError(IOIRAudioStreamCallback cb, int code, String msg) {
        if (cb == null) return;
        try { cb.onError(code, msg); } catch (RemoteException ignored) {}
    }

    private static void safeAppBboxError(IOIRBoundingBoxCallback cb, int code, String msg) {
        if (cb == null) return;
        try { cb.onError(code, msg); } catch (RemoteException ignored) {}
    }

    private static void safeAppVectorError(IOIRVectorCallback cb, int code, String msg) {
        if (cb == null) return;
        try { cb.onError(code, msg); } catch (RemoteException ignored) {}
    }

    private static void safeAppError(IOIRTokenCallback cb, int code, String msg) {
        if (cb == null) return;
        try { cb.onError(code, msg); } catch (RemoteException ignored) {}
    }

    private static void safeAppBooleanError(IOIRRealtimeBooleanCallback cb, int code, String msg) {
        if (cb == null) return;
        try { cb.onError(code, msg); } catch (RemoteException ignored) {}
    }

}
