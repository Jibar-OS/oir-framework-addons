/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

/**
 * Error codes surfaced via public callbacks (IOIRTokenCallback.onError,
 * IOIRVectorCallback.onError, etc.) — stable integer constants so apps
 * can branch on code, not message.
 *
 * Worker-side equivalents in oird (W_MODEL_ERROR etc) are translated to
 * these values by OIRService.
 */
public final class OIRError {
    private OIRError() {}

    /** Something went wrong at the model/backend level. Check message. */
    public static final int MODEL_ERROR              = 1;

    /** Request was cancelled (by ICancellationSignal or client). */
    public static final int CANCELLED                = 2;

    /** Input validation failed: null, empty, wrong type, unknown capability. */
    public static final int INVALID_INPUT            = 3;

    /** Worker ran out of memory mid-inference (e.g. KV cache). Distinct from
     *  CAPABILITY_UNAVAILABLE_NO_MEMORY which is load-time budget failure. */
    public static final int INSUFFICIENT_MEMORY      = 4;

    /** Hit OirConfig.inference_timeout_seconds without completing. */
    public static final int TIMEOUT                  = 5;

    /** Worker process is unavailable (crashed, respawning, or never attached). */
    public static final int WORKER_UNAVAILABLE       = 6;

    /** Caller lacks the required OIR permission for the capability. v0.3+. */
    public static final int PERMISSION_DENIED        = 7;

    /** Memory budget would be exceeded and no evictable model is available. v0.4+. */
    public static final int CAPABILITY_UNAVAILABLE_NO_MEMORY = 8;

    /** Capability declared but no default/OEM model is resolvable. v0.4+. */
    public static final int CAPABILITY_UNAVAILABLE_NO_MODEL  = 9;

    /** Caller UID exceeded its per-minute token bucket. Retry after a short
     *  backoff (bucket refills at rate_limit_per_minute / 60 per second). v0.5+. */
    public static final int CAPABILITY_THROTTLED             = 10;

    /** OEM-supplied model's input/output shape doesn't match the capability's
     *  expected contract. Detected at load time so apps get a clean error
     *  instead of SIGSEGV during inference. v0.6+. SDK surfaces this as
     *  OirModelIncompatibleException with expected/actual shape strings. */
    public static final int MODEL_INCOMPATIBLE               = 11;
}
