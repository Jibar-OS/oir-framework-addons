/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import android.oir.IOIRTokenCallback;
import android.oir.IOIRVectorCallback;
import android.os.Binder;
import android.os.Bundle;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.util.Log;

/**
 * AIDL dispatcher for the {@code text.*} namespace:
 * {@code text.complete}, {@code text.translate}, {@code text.embed},
 * {@code text.classify}, {@code text.rerank}.
 *
 * Each public method here is called by exactly one {@link IOIRService.Stub}
 * forwarder method on OIRService. The pre-flight (capability validate +
 * permission + rate limit + ensure model + worker snapshot) and
 * cancellation/error-bridge wiring are uniform across every submit*; the
 * per-capability bit is just the {@code IOirWorker.submitX} call shape.
 *
 * v0.8 / v1.0 namespace siblings (audio.observe / vision.observe /
 * world.observe) get their own dispatcher classes alongside this one;
 * the AIDL stub router stays the same shape: pick a dispatcher by
 * capability prefix, forward.
 */
final class TextDispatcher {

    private static final String TAG = "OIRTextDispatcher";

    private final DispatcherDeps mDeps;

    TextDispatcher(DispatcherDeps deps) {
        mDeps = deps;
    }

    /**
     * Token-stream entry point for {@code text.complete} and
     * {@code text.translate}. Translate's prompt-template rewriting
     * happens here (capability + caller's options carry source/target
     * languages), preserving the v0.6 Phase B routing where translate
     * reuses the text.complete llama pool with a different prompt envelope.
     */
    long submitToken(String capability, String prompt, Bundle options,
            IOIRTokenCallback cb, ICancellationSignal cancel) {
        if (prompt == null || prompt.isEmpty()) {
            CallbackBridges.safeAppError(cb, OIRError.INVALID_INPUT, "prompt is empty");
            return 0L;
        }
        long modelHandle = preflight(capability, CallbackBridges.forToken(cb));
        if (modelHandle <= 0L) return 0L;

        // text.translate: rewrite the prompt with the OIR translation envelope.
        // Apps pass Options{"sourceLang","targetLang"}; defaults are auto/en.
        // Zero marginal memory cost — same llama pool as text.complete.
        if (capability.equals("text.translate") || capability.startsWith("text.translate:")) {
            String src = options != null ? options.getString("sourceLang", "auto") : "auto";
            String tgt = options != null ? options.getString("targetLang", "en") : "en";
            prompt = "Translate the following text from " + src
                    + " to " + tgt
                    + ". Return only the translation, with no commentary or"
                    + " preamble.\n\nText: " + prompt + "\n\nTranslation:";
        }

        IOirWorker worker = workerOrError(cb);
        if (worker == null) return 0L;

        long appHandle = mDeps.nextRequestHandle.getAndIncrement();
        try {
            Bundle meta = new Bundle();
            meta.putLong("handle", appHandle);
            meta.putString("backend", "oird");
            // AgentKit compaction hints. prompt_tokens=0 until worker exposes
            // tokenize+count sync AIDL (see IOirWorker.aidl).
            meta.putInt("prompt_tokens", 0);
            meta.putInt("context_window", mDeps.config.getCapabilityCtxSize(capability, 2048));
            cb.onStart(meta);
        } catch (RemoteException e) {
            Log.w(TAG, "onStart failed app=" + appHandle, e);
            return 0L;
        }

        int maxTokens = options != null ? options.getInt("maxTokens", 256) : 256;
        float temperature = options != null ? options.getFloat("temperature", 0.7f) : 0.7f;
        try {
            long workerHandle = worker.submit(modelHandle, prompt, maxTokens, temperature,
                    new CallbackBridges.TokenBridge(appHandle, cb, mDeps.appHandleToWorkerHandle));
            mDeps.appHandleToWorkerHandle.put(appHandle, workerHandle);
            CallbackBridges.wireCancellationSignal(cancel, appHandle, mDeps.canceler);
        } catch (RemoteException e) {
            Log.w(TAG, "worker.submit failed app=" + appHandle, e);
            CallbackBridges.safeAppError(cb, OIRError.MODEL_ERROR,
                    "worker.submit failed: " + e.getMessage());
            return 0L;
        }
        Log.i(TAG, "submit app=" + appHandle + " uid=" + Binder.getCallingUid()
                + " prompt.len=" + prompt.length());
        return appHandle;
    }

    /**
     * Vector-shape entry for {@code text.embed} and {@code text.classify}.
     * Both produce a single pooled vector; classify also reads a
     * {@code <model>.tokenizer.json} sidecar which the worker resolves
     * lazily — that's invisible to this side.
     */
    long submitVector(String capability, String text,
            IOIRVectorCallback cb, ICancellationSignal cancel) {
        if (text == null) {
            CallbackBridges.safeAppVectorError(cb, OIRError.INVALID_INPUT, "text is null");
            return 0L;
        }
        long modelHandle = preflight(capability, CallbackBridges.forVector(cb));
        if (modelHandle <= 0L) {
            // ensureOnnxModelFor(text.classify) was historically silent; the
            // caller wants CAPABILITY_UNAVAILABLE_NO_MODEL specifically when
            // the OEM didn't bake a classifier. Preserve the legacy code by
            // remapping ModelEnsurer's silent 0 here.
            if (modelHandle == 0L && capability.startsWith("text.classify")) {
                CallbackBridges.safeAppVectorError(cb, OIRError.CAPABILITY_UNAVAILABLE_NO_MODEL,
                        "no default classifier for " + capability
                                + " — OEM must bake-in via /product/etc/oir/");
            }
            return 0L;
        }

        IOirWorker worker = workerOrVectorError(cb);
        if (worker == null) return 0L;

        long appHandle = mDeps.nextRequestHandle.getAndIncrement();
        try {
            long workerHandle = capability.startsWith("text.classify")
                    ? worker.submitClassify(modelHandle, text,
                            new CallbackBridges.VectorBridge(appHandle, cb, mDeps.appHandleToWorkerHandle))
                    : worker.submitEmbed(modelHandle, text,
                            new CallbackBridges.VectorBridge(appHandle, cb, mDeps.appHandleToWorkerHandle));
            mDeps.appHandleToWorkerHandle.put(appHandle, workerHandle);
            CallbackBridges.wireCancellationSignal(cancel, appHandle, mDeps.canceler);
        } catch (RemoteException e) {
            Log.w(TAG, "submitVector failed app=" + appHandle + " cap=" + capability, e);
            CallbackBridges.safeAppVectorError(cb, OIRError.WORKER_UNAVAILABLE,
                    "submitVector: " + e.getMessage());
            return 0L;
        }
        return appHandle;
    }

    /**
     * Cross-encoder rerank: scores each candidate against the query as
     * {@code (q, c)} pairs. Returns one float per candidate.
     */
    long submitRerank(String capability, String query, String[] candidates,
            IOIRVectorCallback cb, ICancellationSignal cancel) {
        if (query == null || query.isEmpty()) {
            CallbackBridges.safeAppVectorError(cb, OIRError.INVALID_INPUT, "query is empty");
            return 0L;
        }
        if (candidates == null || candidates.length == 0) {
            CallbackBridges.safeAppVectorError(cb, OIRError.INVALID_INPUT, "candidates is empty");
            return 0L;
        }
        long modelHandle = preflight(capability, CallbackBridges.forVector(cb));
        if (modelHandle <= 0L) {
            // Same OEM-bake-required surface as text.classify.
            if (modelHandle == 0L) {
                CallbackBridges.safeAppVectorError(cb, OIRError.CAPABILITY_UNAVAILABLE_NO_MODEL,
                        "no default reranker for " + capability
                                + " — OEM must bake-in via /product/etc/oir/");
            }
            return 0L;
        }

        IOirWorker worker = workerOrVectorError(cb);
        if (worker == null) return 0L;

        long appHandle = mDeps.nextRequestHandle.getAndIncrement();
        try {
            long workerHandle = worker.submitRerank(modelHandle, query, candidates,
                    new CallbackBridges.VectorBridge(appHandle, cb, mDeps.appHandleToWorkerHandle));
            mDeps.appHandleToWorkerHandle.put(appHandle, workerHandle);
            CallbackBridges.wireCancellationSignal(cancel, appHandle, mDeps.canceler);
        } catch (RemoteException e) {
            Log.w(TAG, "submitRerank failed app=" + appHandle, e);
            CallbackBridges.safeAppVectorError(cb, OIRError.WORKER_UNAVAILABLE,
                    "submitRerank: " + e.getMessage());
            return 0L;
        }
        return appHandle;
    }

    /**
     * Capability validate + permission + rate-limit + model-ensure pipeline
     * shared by every submit* on this dispatcher. Returns the loaded model
     * handle, or 0 on any failure (caller's reporter has been notified).
     *
     * Returning 0 means "ensurer reported a load failure"; the outer caller
     * may want to remap the error code (e.g. text.classify wants
     * CAPABILITY_UNAVAILABLE_NO_MODEL specifically). Preflight failures
     * that come from this method directly (registry / permission / rate
     * limit) leave the message accurate to the cause.
     */
    private long preflight(String capability, ModelEnsurer.ErrorReporter reporter) {
        if (mDeps.registry.get(capability) == null) {
            reporter.report(OIRError.INVALID_INPUT, "unknown capability: " + capability);
            return 0L;
        }
        try {
            mDeps.enforcer.enforce(capability, Binder.getCallingUid(), Binder.getCallingPid());
        } catch (SecurityException se) {
            reporter.report(OIRError.PERMISSION_DENIED, se.getMessage());
            return 0L;
        }
        if (!mDeps.rateLimiter.tryAcquire(Binder.getCallingUid())) {
            reporter.report(OIRError.CAPABILITY_THROTTLED,
                    "rate limit exceeded for capability " + capability);
            return 0L;
        }
        return mDeps.ensurer.ensure(capability, reporter);
    }

    private IOirWorker workerOrError(IOIRTokenCallback cb) {
        IOirWorker w;
        synchronized (mDeps.lifecycle.getLock()) { w = mDeps.lifecycle.getWorkerLocked(); }
        if (w == null) {
            CallbackBridges.safeAppError(cb, OIRError.WORKER_UNAVAILABLE,
                    "worker not attached; respawn in progress");
        }
        return w;
    }

    private IOirWorker workerOrVectorError(IOIRVectorCallback cb) {
        IOirWorker w;
        synchronized (mDeps.lifecycle.getLock()) { w = mDeps.lifecycle.getWorkerLocked(); }
        if (w == null) {
            CallbackBridges.safeAppVectorError(cb, OIRError.WORKER_UNAVAILABLE,
                    "worker not attached; respawn in progress");
        }
        return w;
    }
}
