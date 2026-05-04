/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import java.util.concurrent.CountDownLatch;

/**
 * Single-use future produced by the {@link LoadDedup} registry. The owning
 * thread calls {@link #complete} once the load resolves; waiters call
 * {@link #awaitHandle} to block until then. Failure is signalled by
 * {@code handle == 0} with non-null {@link #errMsg}.
 *
 * Lifecycle:
 *   1. Owner creates a LoadFuture and registers it via LoadDedup.put().
 *   2. Owner runs the slow worker.loadX() RPC unlocked.
 *   3. Owner calls LoadDedup.remove() then this.complete(handle, err).
 *   4. Concurrent waiters block on awaitHandle() until step 3 fires.
 *
 * On worker death: LoadDedup.drainAndClear() returns all unresolved
 * futures so the caller can complete them with a failure message.
 */
final class LoadFuture {
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile long handle = 0L;
    private volatile String errMsg;

    /**
     * Blocks until the owning thread publishes a result, then returns the
     * loaded handle (0 on failure; inspect {@link #errMsg} for why).
     */
    long awaitHandle() throws InterruptedException {
        latch.await();
        return handle;
    }

    void complete(long h, String err) {
        this.handle = h;
        this.errMsg = err;
        latch.countDown();
    }

    String errMsg() { return errMsg; }
}
