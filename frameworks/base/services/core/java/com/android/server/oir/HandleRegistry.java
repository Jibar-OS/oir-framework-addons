/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-capability worker handle cache. Replaces the seven separate
 * {@code mXxxHandlesByCapability} maps that grew in OIRService — one
 * per backend kind (text-gen, text-embed, whisper, ort, vad, vlm,
 * vision-embed). Capability names are globally unique across kinds
 * (CapabilityRegistry rejects duplicates at load time), so a single
 * flat map covers every kind.
 *
 * The "kind" of a handle is implicit in the capability's metadata
 * (Capability.backend + capability name), not stored here. Callers
 * (ModelEnsurer / dispatchers) use the capability metadata to decide
 * which IOirWorker.loadX() / submitX() method to call.
 *
 * Lifecycle: entries live until either an explicit unload call or the
 * worker dies (binderDied calls clearAll()). After worker respawn, all
 * cached handles are stale and must be re-acquired.
 *
 * Thread safety: backed by ConcurrentHashMap. Callers using the
 * "check, claim via LoadDedup, release lock, do RPC, re-acquire,
 * publish" pattern hold mWorkerLock around the put — see ensureXxxModelFor
 * methods on OIRService for the locking contract.
 */
final class HandleRegistry {

    private final ConcurrentHashMap<String, Long> mHandles = new ConcurrentHashMap<>();

    /** Returns the cached worker handle for this capability, or null if not loaded. */
    Long get(String capability) {
        return mHandles.get(capability);
    }

    /** Cache the worker handle for this capability after a successful load. */
    void put(String capability, long handle) {
        mHandles.put(capability, handle);
    }

    /** Drop the cached entry for this capability — used by unload paths. */
    void remove(String capability) {
        mHandles.remove(capability);
    }

    /**
     * Clear every cached handle. Called from binderDied: after the worker
     * dies and respawns, every cached handle points at a freed context
     * in the old oird process. The next submit on each capability
     * triggers a fresh load against the new worker.
     */
    void clearAll() {
        mHandles.clear();
    }

    /** Diagnostic: current cache size. */
    int size() {
        return mHandles.size();
    }
}
