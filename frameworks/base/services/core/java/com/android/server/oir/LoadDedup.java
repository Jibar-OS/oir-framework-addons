/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Concurrent-load dedup registry. When a capability hits its first submit,
 * the first caller claims a slot via {@link #put}, drops {@code mWorkerLock},
 * issues the slow {@code mWorker.loadX()} binder RPC unlocked, then
 * re-acquires {@code mWorkerLock} to insert into the per-kind handle map +
 * notify waiters via {@link #remove}. Concurrent callers for the same
 * capability find the existing {@link LoadFuture} via {@link #get} and wait
 * on it instead of racing a duplicate oird-side load.
 *
 * Thread safety: callers coordinate access via {@code mWorkerLock} on
 * OIRService — this class wraps a {@link ConcurrentHashMap} but does not
 * synchronize internally beyond the map's own concurrency contract. The
 * one operation that doesn't need an external lock is {@link #drainAndClear},
 * called from {@code binderDied} to wake waiters whose owning load thread
 * will never publish.
 *
 * Key convention: {@code "<kind>:<capability>"} (e.g. {@code "load:text.complete"}
 * or {@code "loadEmbed:text.embed"}) so a misrouted caller using a different
 * ensure*ModelFor() for the same capability name doesn't collide.
 */
final class LoadDedup {

    private final ConcurrentHashMap<String, LoadFuture> mInFlight = new ConcurrentHashMap<>();

    /** Returns the in-flight future for this load key, or null if none. */
    LoadFuture get(String loadKey) {
        return mInFlight.get(loadKey);
    }

    /** Register a new in-flight future as the owning load for this key. */
    void put(String loadKey, LoadFuture future) {
        mInFlight.put(loadKey, future);
    }

    /** Clear the in-flight entry once the owning load has resolved. */
    void remove(String loadKey) {
        mInFlight.remove(loadKey);
    }

    /**
     * Drain all in-flight entries and return their futures so the caller can
     * complete them with a failure outside any lock. Used from
     * {@code binderDied}: any waiter blocked on awaitHandle() needs to wake
     * because the owning load thread will never publish (oird died).
     */
    List<LoadFuture> drainAndClear() {
        List<LoadFuture> orphaned = new ArrayList<>(mInFlight.values());
        mInFlight.clear();
        return orphaned;
    }
}
