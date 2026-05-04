/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import android.oir.IOIRBoundingBoxCallback;
import android.oir.IOIRTokenCallback;
import android.oir.IOIRVectorCallback;
import android.os.Bundle;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.util.Log;

/**
 * AIDL dispatcher for the {@code vision.*} namespace:
 * {@code vision.detect}, {@code vision.embed}, {@code vision.ocr},
 * {@code vision.describe}.
 *
 * Reserved slot for v0.8 {@code vision.observe} (continuous camera-frame
 * stream with motion + detect cascade) and v1.0 {@code world.observe}
 * (multimodal mic+camera) — same shape, plus a SessionRegistry handle
 * (deferred design).
 */
final class VisionDispatcher extends NamespaceDispatcher {

    private static final String TAG = "OIRVisionDispatcher";

    VisionDispatcher(DispatcherDeps deps) {
        super(deps);
    }

    /**
     * VLM (CLIP+LLM) image description. {@code prompt} carries the image
     * path with optional {@code " | <user-prompt>"} suffix for custom
     * prompt text — preserves the v0.4 H6 wire convention.
     */
    long submitDescribe(String capability, String prompt,
            IOIRTokenCallback cb, ICancellationSignal cancel) {
        if (prompt == null || prompt.isEmpty()) {
            CallbackBridges.safeAppError(cb, OIRError.INVALID_INPUT, "image path is empty");
            return 0L;
        }
        long modelHandle = preflight(capability, CallbackBridges.forToken(cb));
        if (modelHandle <= 0L) return 0L;

        // Split image path from optional user prompt.
        String imagePath = prompt;
        String userPrompt = "";
        int bar = prompt.indexOf(" | ");
        if (bar > 0) {
            imagePath = prompt.substring(0, bar);
            userPrompt = prompt.substring(bar + 3);
        }

        IOirWorker worker = workerOrError(cb);
        if (worker == null) return 0L;

        long appHandle = mDeps.nextRequestHandle.getAndIncrement();
        try {
            Bundle meta = new Bundle();
            meta.putLong("handle", appHandle);
            meta.putString("backend", "oird-vlm");
            meta.putInt("prompt_tokens", 0);
            // v0.6.1 audit: honor OEM's <vision.describe.n_ctx> override
            // so AgentKit's compaction math uses the actual ctx allocated.
            // Falls back to the long-standing vision.describe default 4096.
            meta.putInt("context_window", mDeps.config.getCapabilityCtxSize(capability, 4096));
            cb.onStart(meta);
            long workerHandle = worker.submitDescribeImage(modelHandle, imagePath, userPrompt,
                    new CallbackBridges.TokenBridge(appHandle, cb, mDeps.appHandleToWorkerHandle));
            mDeps.appHandleToWorkerHandle.put(appHandle, workerHandle);
            CallbackBridges.wireCancellationSignal(cancel, appHandle, mDeps.canceler);
            return appHandle;
        } catch (RemoteException e) {
            Log.w(TAG, "submitDescribeImage failed app=" + appHandle, e);
            CallbackBridges.safeAppError(cb, OIRError.WORKER_UNAVAILABLE,
                    "submitDescribeImage: " + e.getMessage());
            return 0L;
        }
    }

    /**
     * Detection / OCR: ONNX session with {@code isDetection=true} sized
     * tensors. Capability prefix selects the worker submit shape:
     * {@code vision.detect} → bboxes only; {@code vision.ocr} → bboxes +
     * recognized text per box (worker decodes with a sidecar rec model).
     */
    long submitDetect(String capability, String imagePath,
            IOIRBoundingBoxCallback cb, ICancellationSignal cancel) {
        if (imagePath == null || imagePath.isEmpty()) {
            CallbackBridges.safeAppBboxError(cb, OIRError.INVALID_INPUT, "image path is empty");
            return 0L;
        }
        long modelHandle = preflight(capability, CallbackBridges.forBbox(cb));
        if (modelHandle <= 0L) return 0L;

        IOirWorker worker = workerOrBboxError(cb);
        if (worker == null) return 0L;

        long appHandle = mDeps.nextRequestHandle.getAndIncrement();
        try {
            long workerHandle = capability.startsWith("vision.ocr")
                    ? worker.submitOcr(modelHandle, imagePath,
                            new CallbackBridges.BboxBridge(appHandle, cb, mDeps.appHandleToWorkerHandle))
                    : worker.submitDetect(modelHandle, imagePath,
                            new CallbackBridges.BboxBridge(appHandle, cb, mDeps.appHandleToWorkerHandle));
            mDeps.appHandleToWorkerHandle.put(appHandle, workerHandle);
            CallbackBridges.wireCancellationSignal(cancel, appHandle, mDeps.canceler);
            return appHandle;
        } catch (RemoteException e) {
            Log.w(TAG, "submitDetect/Ocr failed app=" + appHandle + " cap=" + capability, e);
            CallbackBridges.safeAppBboxError(cb, OIRError.WORKER_UNAVAILABLE,
                    "submitDetect: " + e.getMessage());
            return 0L;
        }
    }

    /**
     * Vision encoder pooled embedding (SigLIP / CLIP family).
     */
    long submitVisionEmbed(String capability, String imagePath,
            IOIRVectorCallback cb, ICancellationSignal cancel) {
        if (imagePath == null || imagePath.isEmpty()) {
            CallbackBridges.safeAppVectorError(cb, OIRError.INVALID_INPUT, "image path is empty");
            return 0L;
        }
        long modelHandle = preflight(capability, CallbackBridges.forVector(cb));
        if (modelHandle <= 0L) return 0L;

        IOirWorker worker = workerOrVectorError(cb);
        if (worker == null) return 0L;

        long appHandle = mDeps.nextRequestHandle.getAndIncrement();
        try {
            long workerHandle = worker.submitVisionEmbed(modelHandle, imagePath,
                    new CallbackBridges.VectorBridge(appHandle, cb, mDeps.appHandleToWorkerHandle));
            mDeps.appHandleToWorkerHandle.put(appHandle, workerHandle);
            CallbackBridges.wireCancellationSignal(cancel, appHandle, mDeps.canceler);
            return appHandle;
        } catch (RemoteException e) {
            Log.w(TAG, "submitVisionEmbed failed app=" + appHandle, e);
            CallbackBridges.safeAppVectorError(cb, OIRError.WORKER_UNAVAILABLE,
                    "submitVisionEmbed: " + e.getMessage());
            return 0L;
        }
    }

    private IOirWorker workerOrError(IOIRTokenCallback cb) {
        IOirWorker w = workerOrNull();
        if (w == null) CallbackBridges.safeAppError(cb, OIRError.WORKER_UNAVAILABLE, "worker not attached");
        return w;
    }

    private IOirWorker workerOrBboxError(IOIRBoundingBoxCallback cb) {
        IOirWorker w = workerOrNull();
        if (w == null) CallbackBridges.safeAppBboxError(cb, OIRError.WORKER_UNAVAILABLE, "worker not attached");
        return w;
    }

    private IOirWorker workerOrVectorError(IOIRVectorCallback cb) {
        IOirWorker w = workerOrNull();
        if (w == null) CallbackBridges.safeAppVectorError(cb, OIRError.WORKER_UNAVAILABLE, "worker not attached");
        return w;
    }
}
