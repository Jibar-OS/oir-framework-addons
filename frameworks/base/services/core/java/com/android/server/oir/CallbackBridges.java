/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import android.oir.IOIRAudioStreamCallback;
import android.oir.IOIRBoundingBoxCallback;
import android.oir.IOIRRealtimeBooleanCallback;
import android.oir.IOIRTokenCallback;
import android.oir.IOIRVectorCallback;
import android.os.CancellationSignal;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.util.Log;

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
}
