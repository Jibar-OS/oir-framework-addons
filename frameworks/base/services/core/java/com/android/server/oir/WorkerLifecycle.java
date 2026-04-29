/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * Owns OIRService's binder connection to the {@code oir_worker} service:
 * the {@link IOirWorker} reference, the lock that protects compound
 * worker-state operations, the attach retry loop, and the death recipient.
 *
 * Why not just call ServiceManager.getService() everywhere?
 * - oird may not be registered yet at OIRService onStart (race with init).
 *   Lifecycle retries with a fast-then-slow backoff.
 * - oird may die at any point; lifecycle clears state + reattaches when
 *   init respawns it.
 * - Every load/submit path needs a coordinated "get worker, check non-null,
 *   do something" critical section. Centralizing the lock here makes the
 *   contract explicit instead of having mWorkerLock floating in OIRService.
 *
 * Locking contract:
 * - {@link #getLock} returns the lock guarding mWorker. Callers needing
 *   compound atomic operations (e.g. "check worker non-null AND claim a
 *   LoadFuture in the same critical section") synchronize on it.
 * - {@link #getWorkerLocked} returns mWorker assuming the caller already
 *   holds the lock. Used inside callers' own {@code synchronized} blocks.
 * - {@link #getWorker} acquires the lock briefly and returns mWorker.
 *   Used for one-off snapshots outside any critical section.
 *
 * Listener contract:
 * - {@link Listener#onAttached} fires once per successful attach, OUTSIDE
 *   the lock. Used to push setConfig + capability tuning to the new worker.
 *   May be called multiple times if oird dies and respawns.
 * - {@link Listener#onDeath} fires from the death recipient, OUTSIDE the
 *   lock. Used to clear cached handles + drain in-flight LoadFutures.
 *   The listener acquires {@link #getLock} as needed.
 */
final class WorkerLifecycle {

    private static final String TAG = "OIRWorkerLifecycle";
    private static final String WORKER_SERVICE_NAME = "oir_worker";

    // v0.6 Phase A: retry forever. v0.5 capped at 60×2s=120s and gave up — if
    // oird was slow to register (overlayfs remount, library load hiccup, long
    // SELinux relabel), the platform never recovered. Fast-attempt cadence
    // for the first half-minute (typical worst case) then slow.
    private static final long ATTACH_RETRY_DELAY_FAST_MS = 2000L;
    private static final long ATTACH_RETRY_DELAY_SLOW_MS = 10000L;
    private static final int  ATTACH_FAST_ATTEMPTS       = 30;

    interface Listener {
        /** Called outside the lock after a successful attach. */
        void onAttached(IOirWorker worker);
        /** Called outside the lock from binderDied. */
        void onDeath();
    }

    private final Listener mListener;
    private final Object mLock = new Object();
    private IOirWorker mWorker;
    private int mAttachAttempt;
    private HandlerThread mThread;
    private Handler mHandler;

    WorkerLifecycle(Listener listener) {
        mListener = listener;
    }

    /** Starts the proxy thread and posts the first attach attempt. */
    void start() {
        mThread = new HandlerThread("oir-proxy", Process.THREAD_PRIORITY_BACKGROUND);
        mThread.start();
        mHandler = new Handler(mThread.getLooper());
        mHandler.post(this::attachWorker);
    }

    /** Lock guarding compound worker-state operations. */
    Object getLock() { return mLock; }

    /** Returns mWorker. Caller MUST hold {@link #getLock}. */
    IOirWorker getWorkerLocked() { return mWorker; }

    /** Snapshots mWorker under the lock — safe outside any critical section. */
    IOirWorker getWorker() {
        synchronized (mLock) { return mWorker; }
    }

    private void attachWorker() {
        IBinder binder = ServiceManager.getService(WORKER_SERVICE_NAME);
        if (binder == null) {
            mAttachAttempt++;
            final long delay = mAttachAttempt <= ATTACH_FAST_ATTEMPTS
                    ? ATTACH_RETRY_DELAY_FAST_MS
                    : ATTACH_RETRY_DELAY_SLOW_MS;
            // Log every fast attempt; on the slow track, log every 6th
            // (~once/min) to keep the signal without logcat spam.
            if (mAttachAttempt <= ATTACH_FAST_ATTEMPTS || mAttachAttempt % 6 == 0) {
                Log.w(TAG, "worker \"" + WORKER_SERVICE_NAME + "\" not registered yet; "
                        + "attempt " + mAttachAttempt + " retry in " + delay + "ms");
            }
            mHandler.postDelayed(this::attachWorker, delay);
            return;
        }
        mAttachAttempt = 0;

        try {
            binder.linkToDeath(mDeath, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "linkToDeath failed", e);
        }

        IOirWorker worker = IOirWorker.Stub.asInterface(binder);
        synchronized (mLock) { mWorker = worker; }
        Log.i(TAG, "attached to oird worker; models will load lazily per capability on first submit");
        mListener.onAttached(worker);
    }

    private final IBinder.DeathRecipient mDeath = new IBinder.DeathRecipient() {
        @Override public void binderDied() {
            Log.w(TAG, "oird died; clearing handles. init will respawn it.");
            synchronized (mLock) { mWorker = null; }
            mListener.onDeath();
            mHandler.postDelayed(WorkerLifecycle.this::attachWorker, 1000L);
        }
    };
}
