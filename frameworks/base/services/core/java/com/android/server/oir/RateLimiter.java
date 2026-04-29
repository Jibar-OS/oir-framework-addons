/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import android.os.Process;
import android.os.SystemClock;

import java.util.HashMap;

/**
 * Per-UID token-bucket rate limiter.
 *
 * A call is allowed if the caller's bucket has ≥ 1 token. Tokens refill at
 * (ratePerMinute / 60000) per millisecond up to the burst cap. SHELL_UID
 * and SYSTEM_UID bypass the limiter entirely (so {@code cmd oir} and
 * other system-level callers are never throttled).
 *
 * A ratePerMinute of 0 disables throttling globally. Configured from
 * OirConfig via {@link #configure(int, int)} at OIRService onStart.
 */
final class RateLimiter {
    private static final class Bucket {
        double tokens;
        long lastRefillMs;
    }

    private final Object mLock = new Object();
    private final HashMap<Integer, Bucket> mBuckets = new HashMap<>();
    private int mRatePerMinute;
    private int mBurst;

    void configure(int ratePerMinute, int burst) {
        synchronized (mLock) {
            mRatePerMinute = ratePerMinute;
            mBurst = burst;
            mBuckets.clear();
        }
    }

    /** Returns true if the call is allowed; false if throttled. */
    boolean tryAcquire(int uid) {
        // System-level callers bypass throttling. UID 0 = root.
        if (uid == 0 || uid == Process.SHELL_UID || uid == Process.SYSTEM_UID) {
            return true;
        }
        synchronized (mLock) {
            if (mRatePerMinute <= 0) return true; // throttling disabled
            final long now = SystemClock.uptimeMillis();
            Bucket b = mBuckets.get(uid);
            if (b == null) {
                b = new Bucket();
                b.tokens = mBurst;
                b.lastRefillMs = now;
                mBuckets.put(uid, b);
            }
            final long elapsed = Math.max(0L, now - b.lastRefillMs);
            final double refill = elapsed * (mRatePerMinute / 60000.0);
            b.tokens = Math.min((double) mBurst, b.tokens + refill);
            b.lastRefillMs = now;
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
