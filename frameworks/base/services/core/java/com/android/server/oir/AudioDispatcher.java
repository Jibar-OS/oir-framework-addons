/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import android.oir.IOIRAudioStreamCallback;
import android.oir.IOIRRealtimeBooleanCallback;
import android.oir.IOIRTokenCallback;
import android.os.Binder;
import android.os.Bundle;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.util.Log;

/**
 * AIDL dispatcher for the {@code audio.*} namespace:
 * {@code audio.transcribe}, {@code audio.synthesize}, {@code audio.vad}.
 *
 * Reserved slot for v0.8 {@code audio.observe} (continuous mic stream
 * with VAD/ASR cascade) — same shape as the one-shot methods here, plus
 * a SessionRegistry handle (deferred design).
 */
final class AudioDispatcher {

    private static final String TAG = "OIRAudioDispatcher";

    private final DispatcherDeps mDeps;

    AudioDispatcher(DispatcherDeps deps) {
        mDeps = deps;
    }

    /**
     * Whisper transcription: caller passes an audio file path in the
     * {@code prompt} arg of the token-shape submit AIDL method.
     */
    long submitTranscribe(String capability, String audioPath,
            IOIRTokenCallback cb, ICancellationSignal cancel) {
        if (audioPath == null || audioPath.isEmpty()) {
            CallbackBridges.safeAppError(cb, OIRError.INVALID_INPUT, "audio path is empty");
            return 0L;
        }
        long modelHandle = preflight(capability, CallbackBridges.forToken(cb));
        if (modelHandle <= 0L) return 0L;

        IOirWorker worker = workerOrError(cb);
        if (worker == null) return 0L;

        long appHandle = mDeps.nextRequestHandle.getAndIncrement();
        try {
            Bundle meta = new Bundle();
            meta.putLong("handle", appHandle);
            meta.putString("backend", "oird-whisper");
            // v0.4 §3.5: audio has no text prompt; context_window is the
            // whisper-internal 30s audio window expressed in seconds * 50
            // frames — approximate to 0 (not meaningful for AgentKit use).
            meta.putInt("prompt_tokens", 0);
            meta.putInt("context_window", 0);
            cb.onStart(meta);
            long workerHandle = worker.submitTranscribe(modelHandle, audioPath,
                    new CallbackBridges.TokenBridge(appHandle, cb, mDeps.appHandleToWorkerHandle));
            mDeps.appHandleToWorkerHandle.put(appHandle, workerHandle);
            CallbackBridges.wireCancellationSignal(cancel, appHandle, mDeps.canceler);
            return appHandle;
        } catch (RemoteException e) {
            Log.w(TAG, "submitTranscribe failed app=" + appHandle, e);
            CallbackBridges.safeAppError(cb, OIRError.WORKER_UNAVAILABLE,
                    "submitTranscribe: " + e.getMessage());
            return 0L;
        }
    }

    /**
     * Piper TTS: graphemes → phonemes (sidecar) → ONNX VITS → PCM stream.
     * Worker emits PCM chunks via {@link CallbackBridges.AudioBridge}.
     */
    long submitSynthesize(String capability, String text,
            IOIRAudioStreamCallback cb, ICancellationSignal cancel) {
        if (text == null || text.isEmpty()) {
            CallbackBridges.safeAppAudioError(cb, OIRError.INVALID_INPUT, "text is empty");
            return 0L;
        }
        long modelHandle = preflight(capability, CallbackBridges.forAudio(cb));
        if (modelHandle <= 0L) {
            // ensureOnnx was historically silent on this path; the legacy
            // caller wanted CAPABILITY_UNAVAILABLE_NO_MODEL specifically
            // when the OEM hadn't baked a Piper voice + phonemes sidecar.
            if (modelHandle == 0L) {
                CallbackBridges.safeAppAudioError(cb, OIRError.CAPABILITY_UNAVAILABLE_NO_MODEL,
                        "no default voice for " + capability
                                + " — OEM must bake-in a Piper voice + phonemes.json");
            }
            return 0L;
        }

        IOirWorker worker = workerOrAudioError(cb);
        if (worker == null) return 0L;

        long appHandle = mDeps.nextRequestHandle.getAndIncrement();
        try {
            long workerHandle = worker.submitSynthesize(modelHandle, text,
                    new CallbackBridges.AudioBridge(appHandle, cb, mDeps.appHandleToWorkerHandle));
            mDeps.appHandleToWorkerHandle.put(appHandle, workerHandle);
            CallbackBridges.wireCancellationSignal(cancel, appHandle, mDeps.canceler);
            return appHandle;
        } catch (RemoteException e) {
            Log.w(TAG, "submitSynthesize failed app=" + appHandle, e);
            CallbackBridges.safeAppAudioError(cb, OIRError.WORKER_UNAVAILABLE,
                    "submitSynthesize: " + e.getMessage());
            return 0L;
        }
    }

    /**
     * Silero VAD: PCM file → boolean speech-on/off transitions per 32ms
     * window. App-side composition for live mic — the worker just
     * processes whatever PCM it's handed.
     */
    long submitVad(String capability, String pcmPath,
            IOIRRealtimeBooleanCallback cb, ICancellationSignal cancel) {
        if (pcmPath == null || pcmPath.isEmpty()) {
            CallbackBridges.safeAppBooleanError(cb, OIRError.INVALID_INPUT, "pcm path is empty");
            return 0L;
        }
        long modelHandle = preflight(capability, CallbackBridges.forBoolean(cb));
        if (modelHandle <= 0L) {
            if (modelHandle == 0L) {
                CallbackBridges.safeAppBooleanError(cb, OIRError.CAPABILITY_UNAVAILABLE_NO_MODEL,
                        "no default VAD model for " + capability
                                + " — OEM must bake-in via /product/etc/oir/");
            }
            return 0L;
        }

        IOirWorker worker = workerOrBooleanError(cb);
        if (worker == null) return 0L;

        long appHandle = mDeps.nextRequestHandle.getAndIncrement();
        try {
            long workerHandle = worker.submitVad(modelHandle, pcmPath,
                    new CallbackBridges.BooleanBridge(appHandle, cb, mDeps.appHandleToWorkerHandle));
            mDeps.appHandleToWorkerHandle.put(appHandle, workerHandle);
            CallbackBridges.wireCancellationSignal(cancel, appHandle, mDeps.canceler);
            return appHandle;
        } catch (RemoteException e) {
            Log.w(TAG, "submitVad failed app=" + appHandle, e);
            CallbackBridges.safeAppBooleanError(cb, OIRError.WORKER_UNAVAILABLE,
                    "submitVad: " + e.getMessage());
            return 0L;
        }
    }

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
        final int uid = Binder.getCallingUid();
        if (!mDeps.rateLimiter.tryAcquire(uid)) {
            long waitMs = mDeps.rateLimiter.nextTokenWaitMs(uid);
            reporter.report(OIRError.CAPABILITY_THROTTLED,
                    "rate limit exceeded for capability " + capability
                            + " — retry after " + waitMs + "ms");
            return 0L;
        }
        return mDeps.ensurer.ensure(capability, reporter);
    }

    private IOirWorker workerOrError(IOIRTokenCallback cb) {
        IOirWorker w;
        synchronized (mDeps.lifecycle.getLock()) { w = mDeps.lifecycle.getWorkerLocked(); }
        if (w == null) CallbackBridges.safeAppError(cb, OIRError.WORKER_UNAVAILABLE, "worker not attached");
        return w;
    }

    private IOirWorker workerOrAudioError(IOIRAudioStreamCallback cb) {
        IOirWorker w;
        synchronized (mDeps.lifecycle.getLock()) { w = mDeps.lifecycle.getWorkerLocked(); }
        if (w == null) CallbackBridges.safeAppAudioError(cb, OIRError.WORKER_UNAVAILABLE, "worker not attached");
        return w;
    }

    private IOirWorker workerOrBooleanError(IOIRRealtimeBooleanCallback cb) {
        IOirWorker w;
        synchronized (mDeps.lifecycle.getLock()) { w = mDeps.lifecycle.getWorkerLocked(); }
        if (w == null) CallbackBridges.safeAppBooleanError(cb, OIRError.WORKER_UNAVAILABLE, "worker not attached");
        return w;
    }
}
