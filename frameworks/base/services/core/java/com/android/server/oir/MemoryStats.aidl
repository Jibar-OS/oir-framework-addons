/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
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
}
