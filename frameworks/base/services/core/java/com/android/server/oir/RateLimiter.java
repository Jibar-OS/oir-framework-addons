/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
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

    /**
     * Estimated milliseconds until this UID will have at least one token
     * available — i.e. the wait the caller should respect before retrying
     * after a {@link #tryAcquire} returned false.
     *
     * Returns 0 when a token is already available (system-level UIDs,
     * throttling disabled, or this UID's bucket already at &gt;=1 token —
     * common when the caller hits this method without first failing
     * tryAcquire). Read-only inspection; does NOT consume a token.
     *
     * Surfaced through the throttle error message so the SDK's
     * {@code OirThrottledException.retryAfterMs} carries the real wait
     * (the SDK parses it from the message via regex). Pre-this method,
     * the SDK silently fell back to 1000ms because the runtime never
     * included a real number.
     */
    long nextTokenWaitMs(int uid) {
        if (uid == 0 || uid == Process.SHELL_UID || uid == Process.SYSTEM_UID) {
            return 0L;
        }
        synchronized (mLock) {
            if (mRatePerMinute <= 0) return 0L;
            Bucket b = mBuckets.get(uid);
            if (b == null) return 0L; // fresh bucket starts at full burst
            final long now = SystemClock.uptimeMillis();
            final long elapsed = Math.max(0L, now - b.lastRefillMs);
            final double tokensNow = Math.min((double) mBurst,
                    b.tokens + elapsed * (mRatePerMinute / 60000.0));
            if (tokensNow >= 1.0) return 0L;
            final double tokensNeeded = 1.0 - tokensNow;
            final double msPerToken = 60000.0 / mRatePerMinute;
            return (long) Math.ceil(tokensNeeded * msPerToken);
        }
    }
}
