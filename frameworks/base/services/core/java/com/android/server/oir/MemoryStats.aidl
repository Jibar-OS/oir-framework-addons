/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
 * Licensed under the Apache License, Version 2.0
 *
 * Worker memory state — surfaced via cmd oir dumpsys memory (v0.4 S2).
 * Per-model info as parallel arrays (NDK AIDL lists-of-parcelables are
 * fragile in older codegen).
 */
package com.android.server.oir;

/** @hide */
parcelable MemoryStats {
    /** Number of currently loaded models. */
    int modelCount;

    /** Total resident bytes across all loaded models (MB). */
    int residentMb;

    // Per-model parallel arrays. Length == modelCount.
    String[] modelPaths;
    int[]    modelSizesMb;
    long[]   loadTimestampMs;
    long[]   lastAccessMs;

    // Per-model pool telemetry, parallel to the arrays above. ORT-backed
    // models report poolSize=0 (no pool abstraction; ORT::Session::Run
    // is thread-safe by design). VLM models share llama context pools,
    // so poolSize/busy/waiting reflect the underlying llama pool entry.
    /** "llama" / "llama_embed" / "whisper" / "ort" / "mtmd". */
    String[] backendLabels;
    /** Pool slots configured for this handle. 0 = no pool. */
    int[]    poolSizes;
    /** Pool slots currently leased. */
    int[]    busyCounts;
    /** Callers currently queued waiting for a free slot. */
    int[]    waitingCounts;
}
