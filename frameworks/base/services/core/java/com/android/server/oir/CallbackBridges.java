/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import android.oir.BoundingBox;
import android.oir.IOIRAudioStreamCallback;
import android.oir.IOIRBoundingBoxCallback;
import android.oir.IOIRRealtimeBooleanCallback;
import android.oir.IOIRTokenCallback;
import android.oir.IOIRVectorCallback;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared callback-side helpers for OIRService and the per-namespace
 * dispatchers (text / audio / vision):
 *
 * <ul>
 *   <li>Null-safe error forwarding for each public callback shape
 *       (5 variants — Token, Vector, Audio, Bbox, Boolean).</li>
 *   <li>{@link #wireCancellationSignal} — forwards an
 *       {@link ICancellationSignal} from the caller into a per-handle
 *       cancel call on the worker (typically {@code mBinder::cancel}).</li>
 * </ul>
 *
 * Mirrors {@code com.oir.internal.CallbackBridges.kt} in {@code oir-sdk};
 * naming kept consistent so SDK consumers and framework maintainers see
 * the same boundary on both sides of the binder.
 *
 * All methods are static — there is no per-instance state.
 */
final class CallbackBridges {

    private static final String TAG = "OIRCallbackBridges";

    private CallbackBridges() {}

    /** Forwards a cancellation request for one in-flight app handle. */
    @FunctionalInterface
    interface Canceler {
        void cancel(long appHandle) throws RemoteException;
    }

    static void safeAppError(IOIRTokenCallback cb, int code, String msg) {
        if (cb == null) return;
        try { cb.onError(code, msg); } catch (RemoteException ignored) {}
    }

    static void safeAppVectorError(IOIRVectorCallback cb, int code, String msg) {
        if (cb == null) return;
        try { cb.onError(code, msg); } catch (RemoteException ignored) {}
    }

    static void safeAppAudioError(IOIRAudioStreamCallback cb, int code, String msg) {
        if (cb == null) return;
        try { cb.onError(code, msg); } catch (RemoteException ignored) {}
    }

    static void safeAppBboxError(IOIRBoundingBoxCallback cb, int code, String msg) {
        if (cb == null) return;
        try { cb.onError(code, msg); } catch (RemoteException ignored) {}
    }

    static void safeAppBooleanError(IOIRRealtimeBooleanCallback cb, int code, String msg) {
        if (cb == null) return;
        try { cb.onError(code, msg); } catch (RemoteException ignored) {}
    }

    // ---- ModelEnsurer.ErrorReporter factories ---------------------------------
    // Each one returns a reporter that funnels load failures into the matching
    // safeAppXxxError above. Lets dispatchers write
    //   mEnsurer.ensure(capability, CallbackBridges.forToken(cb))
    // instead of repeating the (code, msg) -> safeAppXxxError(cb, code, msg)
    // lambda at every call site.

    static ModelEnsurer.ErrorReporter forToken(IOIRTokenCallback cb) {
        return (code, msg) -> safeAppError(cb, code, msg);
    }

    static ModelEnsurer.ErrorReporter forVector(IOIRVectorCallback cb) {
        return (code, msg) -> safeAppVectorError(cb, code, msg);
    }

    static ModelEnsurer.ErrorReporter forAudio(IOIRAudioStreamCallback cb) {
        return (code, msg) -> safeAppAudioError(cb, code, msg);
    }

    static ModelEnsurer.ErrorReporter forBbox(IOIRBoundingBoxCallback cb) {
        return (code, msg) -> safeAppBboxError(cb, code, msg);
    }

    static ModelEnsurer.ErrorReporter forBoolean(IOIRRealtimeBooleanCallback cb) {
        return (code, msg) -> safeAppBooleanError(cb, code, msg);
    }

    /**
     * Wire a caller-supplied {@link ICancellationSignal} so triggering it
     * cancels the in-flight request identified by {@code appHandle}.
     *
     * v0.6.1 audit: cancellation had been wired only on the text.complete
     * path — every other submit* method accepted ICancellationSignal and
     * dropped it. This helper keeps the wiring uniform across every
     * submit* path.
     */
    static void wireCancellationSignal(ICancellationSignal cancel, long appHandle, Canceler canceler) {
        if (cancel == null) return;
        try {
            CancellationSignal.fromTransport(cancel)
                    .setOnCancelListener(() -> {
                        try {
                            canceler.cancel(appHandle);
                        } catch (RemoteException re) {
                            Log.w(TAG, "cancel forward failed app=" + appHandle, re);
                        }
                    });
        } catch (Exception e) {
            Log.w(TAG, "setOnCancelListener failed app=" + appHandle, e);
        }
    }

    // ---- Worker→app callback bridges -----------------------------------------
    // Each bridge implements one IOirWorkerXxxCallback.Stub (the binder transport
    // from oird) and forwards events to the matching IOIRXxxCallback (the public
    // API the app passed in). Terminal events (onComplete / onError / onVector /
    // onBoundingBoxes / one-shot completions) remove the appHandle entry from the
    // shared cancellation routing map, so cancel() on a completed request is a
    // safe no-op rather than firing against a stale handle.
    //
    // These were inner classes of OIRService until phase 3; promoted to
    // CallbackBridges so the new namespace dispatchers (TextDispatcher /
    // AudioDispatcher / VisionDispatcher) can construct them directly without
    // needing OIRService.this captured.

    /** Token-stream relay: text.complete, text.translate, audio.transcribe, vision.describe. */
    static final class TokenBridge extends IOirWorkerCallback.Stub {
        private final long mAppHandle;
        private final IOIRTokenCallback mAppCallback;
        private final ConcurrentHashMap<Long, Long> mHandleMap;

        TokenBridge(long appHandle, IOIRTokenCallback appCallback,
                ConcurrentHashMap<Long, Long> handleMap) {
            mAppHandle = appHandle;
            mAppCallback = appCallback;
            mHandleMap = handleMap;
        }

        @Override public void onToken(String token, int outputIndex) {
            try { mAppCallback.onToken(token, outputIndex); }
            catch (RemoteException e) { Log.w(TAG, "onToken failed app=" + mAppHandle, e); }
        }

        @Override public void onComplete(int totalTokens, long firstTokenMs, long totalMs) {
            mHandleMap.remove(mAppHandle);
            Bundle appStats = new Bundle();
            appStats.putInt("totalTokens", totalTokens);
            appStats.putLong("firstTokenMs", firstTokenMs);
            appStats.putLong("totalMs", totalMs);
            try { mAppCallback.onComplete(appStats); }
            catch (RemoteException e) { Log.w(TAG, "onComplete failed app=" + mAppHandle, e); }
        }

        @Override public void onError(int workerErrorCode, String message) {
            mHandleMap.remove(mAppHandle);
            safeAppError(mAppCallback, workerErrorCode, message);
        }
    }

    /** Single-vector relay: text.embed, text.classify, vision.embed. */
    static final class VectorBridge extends IOirWorkerVectorCallback.Stub {
        private final long mAppHandle;
        private final IOIRVectorCallback mAppCallback;
        private final ConcurrentHashMap<Long, Long> mHandleMap;

        VectorBridge(long appHandle, IOIRVectorCallback appCallback,
                ConcurrentHashMap<Long, Long> handleMap) {
            mAppHandle = appHandle;
            mAppCallback = appCallback;
            mHandleMap = handleMap;
        }

        @Override public void onVector(float[] vec) {
            mHandleMap.remove(mAppHandle);
            try { mAppCallback.onVector(vec); }
            catch (RemoteException e) { Log.w(TAG, "onVector app=" + mAppHandle + " relay failed", e); }
        }

        @Override public void onError(int workerCode, String message) {
            mHandleMap.remove(mAppHandle);
            try { mAppCallback.onError(workerCode, message); }
            catch (RemoteException e) { Log.w(TAG, "onError(vec) app=" + mAppHandle + " relay failed", e); }
        }
    }

    /** Audio-stream relay: audio.synthesize. */
    static final class AudioBridge extends IOirWorkerAudioCallback.Stub {
        private final long mAppHandle;
        private final IOIRAudioStreamCallback mAppCallback;
        private final ConcurrentHashMap<Long, Long> mHandleMap;

        AudioBridge(long appHandle, IOIRAudioStreamCallback appCallback,
                ConcurrentHashMap<Long, Long> handleMap) {
            mAppHandle = appHandle;
            mAppCallback = appCallback;
            mHandleMap = handleMap;
        }

        @Override public void onChunk(byte[] pcm, int sampleRateHz, int channelCount,
                int encoding, boolean last) {
            try { mAppCallback.onChunk(pcm, sampleRateHz, channelCount, encoding, last); }
            catch (RemoteException e) { Log.w(TAG, "onChunk app=" + mAppHandle + " relay failed", e); }
        }

        @Override public void onComplete(long totalMs) {
            mHandleMap.remove(mAppHandle);
            try { mAppCallback.onComplete(totalMs); }
            catch (RemoteException e) { Log.w(TAG, "onComplete(audio) app=" + mAppHandle + " relay failed", e); }
        }

        @Override public void onError(int workerCode, String message) {
            mHandleMap.remove(mAppHandle);
            try { mAppCallback.onError(workerCode, message); }
            catch (RemoteException e) { Log.w(TAG, "onError(audio) app=" + mAppHandle + " relay failed", e); }
        }
    }

    /**
     * Realtime-boolean relay: audio.vad. Trivial pass-through — both shapes are
     * identical (bool + timestampMs + terminal onComplete / onError). Kept as
     * its own bridge so the public callback can evolve independently of the
     * worker callback if needed.
     */
    static final class BooleanBridge extends IOirWorkerRealtimeBooleanCallback.Stub {
        private final long mAppHandle;
        private final IOIRRealtimeBooleanCallback mAppCallback;
        private final ConcurrentHashMap<Long, Long> mHandleMap;

        BooleanBridge(long appHandle, IOIRRealtimeBooleanCallback appCallback,
                ConcurrentHashMap<Long, Long> handleMap) {
            mAppHandle = appHandle;
            mAppCallback = appCallback;
            mHandleMap = handleMap;
        }

        @Override public void onState(boolean isTrue, long timestampMs) {
            try { mAppCallback.onState(isTrue, timestampMs); }
            catch (RemoteException e) { Log.w(TAG, "onState app=" + mAppHandle + " relay failed", e); }
        }

        @Override public void onComplete() {
            mHandleMap.remove(mAppHandle);
            try { mAppCallback.onComplete(); }
            catch (RemoteException e) { Log.w(TAG, "onComplete(bool) app=" + mAppHandle + " relay failed", e); }
        }

        @Override public void onError(int workerCode, String message) {
            mHandleMap.remove(mAppHandle);
            try { mAppCallback.onError(workerCode, message); }
            catch (RemoteException e) { Log.w(TAG, "onError(bool) app=" + mAppHandle + " relay failed", e); }
        }
    }

    /**
     * Bounding-box relay: vision.detect, vision.ocr. Worker sends flattened
     * parallel arrays (NDK AIDL limitation); this repacks into List&lt;BoundingBox&gt;
     * for the public callback.
     */
    static final class BboxBridge extends IOirWorkerBboxCallback.Stub {
        private final long mAppHandle;
        private final IOIRBoundingBoxCallback mAppCallback;
        private final ConcurrentHashMap<Long, Long> mHandleMap;

        BboxBridge(long appHandle, IOIRBoundingBoxCallback appCallback,
                ConcurrentHashMap<Long, Long> handleMap) {
            mAppHandle = appHandle;
            mAppCallback = appCallback;
            mHandleMap = handleMap;
        }

        @Override public void onBoundingBoxes(int[] xs, int[] ys, int[] widths, int[] heights,
                int[] labelsPerBox, String[] labelsFlat, float[] scoresFlat) {
            mHandleMap.remove(mAppHandle);
            List<BoundingBox> boxes = new ArrayList<>(xs.length);
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

        @Override public void onError(int workerCode, String message) {
            mHandleMap.remove(mAppHandle);
            try { mAppCallback.onError(workerCode, message); }
            catch (RemoteException e) { Log.w(TAG, "onError(bbox) app=" + mAppHandle + " relay failed", e); }
        }
    }
}
