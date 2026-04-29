/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import android.os.RemoteException;
import android.util.Log;

/**
 * Single load-orchestration entry point shared by every per-namespace
 * dispatcher. Replaces the 7 {@code ensureXxxModelFor} methods that
 * previously lived on OIRService — one per backend kind (text-gen,
 * text-embed, whisper, ort, ort-detection, ort-vad, ort-vision-embed,
 * vlm). All variants share the same lock + dedup + retry shape; what
 * differs is the {@link IOirWorker} method to call and a couple of
 * shape-specific path tweaks (VLM splits a pipe-delimited path).
 *
 * Behavior preserved bit-for-bit from the previous per-kind methods:
 * <ul>
 *   <li>Cache hit returns immediately, no lock.</li>
 *   <li>Lock-and-claim pattern: only hold the lifecycle lock long
 *       enough to claim a {@link LoadFuture} or look up an existing
 *       in-flight one; release before the slow worker.loadX() RPC.</li>
 *   <li>Concurrent same-capability callers wait on the in-flight
 *       LoadFuture instead of racing a duplicate oird-side load.</li>
 *   <li>On RemoteException whose message starts with the load-method
 *       name (e.g. "load:", "loadEmbed:", ...), error code is
 *       {@link OIRError#WORKER_UNAVAILABLE}; on any other failure it's
 *       {@link OIRError#MODEL_ERROR}.</li>
 * </ul>
 *
 * Variant fallback: ModelEnsurer calls
 * {@link CapabilityRegistry#getOrFallback} so a request for a
 * not-yet-registered variant ({@code text.complete:fast}) silently
 * resolves to the base capability instead of erroring out — dormant
 * today since capabilities.xml has no variants, but ready for v0.7
 * variant work.
 *
 * Caller passes an {@link ErrorReporter} lambda so ModelEnsurer can
 * report errors via the caller's specific callback type (token /
 * vector / audio / bbox / boolean) without knowing about callbacks.
 */
final class ModelEnsurer {

    private static final String TAG = "OIRModelEnsurer";

    /** Reports a load failure back to the caller's specific callback shape. */
    @FunctionalInterface
    interface ErrorReporter {
        void report(int code, String msg);
    }

    /**
     * No-op reporter for legacy "silent" load paths whose callers do
     * their own error mapping after seeing a 0 handle (e.g. submitClassify
     * wants {@code CAPABILITY_UNAVAILABLE_NO_MODEL} specifically rather
     * than the generic INVALID_INPUT/MODEL_ERROR ModelEnsurer would emit).
     * Preserves pre-Phase-2 behavior where ensureOnnxModelFor and
     * ensureVadModelFor returned 0 silently.
     */
    static final ErrorReporter NO_REPORTER = (code, msg) -> {};

    /**
     * Encodes which {@link IOirWorker} load method to call for a given
     * capability. Mirrors the v0.4-era hardwired {@code worker.loadXxx}
     * dispatch in OIRService's per-kind ensure methods.
     */
    private enum LoadKind {
        LLAMA_GEN,         // worker.load(path)            — text.complete, text.translate
        LLAMA_EMBED,       // worker.loadEmbed(path)       — text.embed
        WHISPER,           // worker.loadWhisper(path)     — audio.transcribe
        ORT_GENERIC,       // worker.loadOnnx(path, false) — audio.synthesize, text.classify, text.rerank
        ORT_DETECTION,     // worker.loadOnnx(path, true)  — vision.detect, vision.ocr
        ORT_VAD,           // worker.loadVad(path)         — audio.vad
        ORT_VISION_EMBED,  // worker.loadVisionEmbed(path) — vision.embed
        VLM                // worker.loadVlm(clip, llm)    — vision.describe (pipe-delim path)
    }

    private final WorkerLifecycle mLifecycle;
    private final CapabilityRegistry mRegistry;
    private final HandleRegistry mHandles;
    private final LoadDedup mLoadDedup;

    ModelEnsurer(WorkerLifecycle lifecycle, CapabilityRegistry registry,
                 HandleRegistry handles, LoadDedup loadDedup) {
        mLifecycle = lifecycle;
        mRegistry = registry;
        mHandles = handles;
        mLoadDedup = loadDedup;
    }

    /**
     * Look up or load the worker handle for this capability. Returns the
     * handle on success, 0 on failure (caller's {@code reporter} has been
     * notified with an OIRError code + message).
     */
    long ensure(String capability, ErrorReporter reporter) {
        Capability c = mRegistry.getOrFallback(capability);
        if (c == null) {
            reporter.report(OIRError.INVALID_INPUT, "unknown capability: " + capability);
            return 0L;
        }
        Long existing = mHandles.get(capability);
        if (existing != null) return existing;

        LoadKind kind = kindOf(c);
        // Key includes a kind prefix so a (theoretically misrouted) caller
        // using a different load shape for the same capability name doesn't
        // collide with ours. Kept for forward-compat even though Phase 2
        // routes every load through this single method.
        String loadKey = kind.name() + ":" + capability;

        IOirWorker worker;
        String pathPrimary;       // path or clip-path for VLM
        String pathSecondary;     // null except for VLM (llm-path)
        LoadFuture ours = null;
        LoadFuture waitOn;
        synchronized (mLifecycle.getLock()) {
            existing = mHandles.get(capability);
            if (existing != null) return existing;
            if (mLifecycle.getWorkerLocked() == null) {
                reporter.report(OIRError.WORKER_UNAVAILABLE, "worker not attached");
                return 0L;
            }
            waitOn = mLoadDedup.get(loadKey);
            if (waitOn == null) {
                String declaredPath = c.defaultModelPath;
                if (declaredPath == null || declaredPath.isEmpty()) {
                    reporter.report(OIRError.INVALID_INPUT,
                            "capability " + capability + " has no default-model; OEM must supply");
                    return 0L;
                }
                if (kind == LoadKind.VLM) {
                    if (!declaredPath.contains("|")) {
                        reporter.report(OIRError.INVALID_INPUT,
                                "vision.describe default-model must be pipe-delimited 'clip|llm': got '"
                                        + declaredPath + "'");
                        return 0L;
                    }
                    int bar = declaredPath.indexOf('|');
                    pathPrimary = declaredPath.substring(0, bar);
                    pathSecondary = declaredPath.substring(bar + 1);
                } else {
                    pathPrimary = declaredPath;
                    pathSecondary = null;
                }
                ours = new LoadFuture();
                mLoadDedup.put(loadKey, ours);
                worker = mLifecycle.getWorkerLocked();
            } else {
                worker = null;
                pathPrimary = null;
                pathSecondary = null;
            }
        }

        if (ours == null) {
            // Someone else is loading; wait on their LoadFuture.
            try {
                long h = waitOn.awaitHandle();
                if (h > 0) return h;
                reporter.report(OIRError.MODEL_ERROR,
                        "concurrent load failed: " + waitOn.errMsg());
                return 0L;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                reporter.report(OIRError.WORKER_UNAVAILABLE, "interrupted during load wait");
                return 0L;
            }
        }

        // We own the load. Binder RPC UNLOCKED.
        long h = 0L;
        String err = null;
        String methodTag = methodTagFor(kind);
        try {
            h = invokeLoad(worker, kind, pathPrimary, pathSecondary);
            if (h <= 0) {
                err = "worker." + methodTag + " returned " + h + " for " + pathPrimary;
            }
        } catch (RemoteException e) {
            err = methodTag + ": " + e.getMessage();
            Log.w(TAG, "worker." + methodTag + " failed for " + capability, e);
        }

        synchronized (mLifecycle.getLock()) {
            if (h > 0) mHandles.put(capability, h);
            mLoadDedup.remove(loadKey);
        }
        ours.complete(h, err);

        if (h <= 0) {
            // Same WORKER_UNAVAILABLE-vs-MODEL_ERROR distinction the per-kind
            // ensure methods drew: if the failure is a binder RemoteException
            // (err starts with the method tag like "load:", "loadEmbed:"),
            // the worker is the suspect and the right code is
            // WORKER_UNAVAILABLE. Otherwise the worker returned a 0 handle
            // (model loaded but rejected) so it's a MODEL_ERROR.
            int code = err != null && err.startsWith(methodTag + ":")
                    ? OIRError.WORKER_UNAVAILABLE
                    : OIRError.MODEL_ERROR;
            reporter.report(code, err != null ? err : (methodTag + " failed"));
            return 0L;
        }
        Log.i(TAG, "lazy-loaded capability=" + capability + " kind=" + kind
                + " handle=" + h + " path=" + pathPrimary);
        return h;
    }

    /**
     * Pick the load shape for a capability based on its declared backend
     * (CapabilityRegistry's default-fill ensures every capability has one)
     * plus the capability-name prefix for cases where multiple kinds share
     * a backend (ort serves vad / vision-embed / vision-detect / generic).
     */
    private static LoadKind kindOf(Capability c) {
        final String name = c.name;
        if ("mtmd".equals(c.backend)) return LoadKind.VLM;
        if ("whisper".equals(c.backend)) return LoadKind.WHISPER;
        if ("llama".equals(c.backend)) {
            if (name.startsWith("text.embed")) return LoadKind.LLAMA_EMBED;
            return LoadKind.LLAMA_GEN;
        }
        // backend == "ort" (or unknown — defaults to ort per registry).
        if (name.startsWith("audio.vad")) return LoadKind.ORT_VAD;
        if (name.startsWith("vision.embed")) return LoadKind.ORT_VISION_EMBED;
        if (name.startsWith("vision.detect") || name.startsWith("vision.ocr")) {
            return LoadKind.ORT_DETECTION;
        }
        return LoadKind.ORT_GENERIC;
    }

    private static long invokeLoad(IOirWorker worker, LoadKind kind,
                                   String pathPrimary, String pathSecondary)
            throws RemoteException {
        switch (kind) {
            case LLAMA_GEN:        return worker.load(pathPrimary);
            case LLAMA_EMBED:      return worker.loadEmbed(pathPrimary);
            case WHISPER:          return worker.loadWhisper(pathPrimary);
            case ORT_GENERIC:      return worker.loadOnnx(pathPrimary, /*isDetection=*/ false);
            case ORT_DETECTION:    return worker.loadOnnx(pathPrimary, /*isDetection=*/ true);
            case ORT_VAD:          return worker.loadVad(pathPrimary);
            case ORT_VISION_EMBED: return worker.loadVisionEmbed(pathPrimary);
            case VLM:              return worker.loadVlm(pathPrimary, pathSecondary);
            default: throw new IllegalStateException("unhandled LoadKind: " + kind);
        }
    }

    private static String methodTagFor(LoadKind kind) {
        switch (kind) {
            case LLAMA_GEN:        return "load";
            case LLAMA_EMBED:      return "loadEmbed";
            case WHISPER:          return "loadWhisper";
            case ORT_GENERIC:      return "loadOnnx";
            case ORT_DETECTION:    return "loadOnnx";
            case ORT_VAD:          return "loadVad";
            case ORT_VISION_EMBED: return "loadVisionEmbed";
            case VLM:              return "loadVlm";
            default: throw new IllegalStateException("unhandled LoadKind: " + kind);
        }
    }
}
