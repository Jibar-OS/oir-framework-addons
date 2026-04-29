/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bundles the cross-cutting services every {@link TextDispatcher} /
 * {@link AudioDispatcher} / {@link VisionDispatcher} method needs:
 * worker lifecycle, model loading, rate-limit + permission + capability
 * registry, the request-handle counter, the app→worker handle map (for
 * cancel routing), and the cancellation forwarder.
 *
 * Mirrors the {@code Runtime} struct extracted on the oird side in
 * Phase 1 — same role: collect the small set of services that every
 * dispatcher needs into one passable bundle so dispatcher constructors
 * stay narrow.
 *
 * All fields are immutable references to long-lived singletons owned by
 * OIRService. Dispatchers never mutate the bundle itself; they call
 * methods on the referenced services.
 */
final class DispatcherDeps {

    final WorkerLifecycle lifecycle;
    final ModelEnsurer ensurer;
    final RateLimiter rateLimiter;
    final PermissionEnforcer enforcer;
    final CapabilityRegistry registry;
    final OirConfig config;
    final AtomicLong nextRequestHandle;
    final ConcurrentHashMap<Long, Long> appHandleToWorkerHandle;
    final CallbackBridges.Canceler canceler;

    DispatcherDeps(WorkerLifecycle lifecycle,
            ModelEnsurer ensurer,
            RateLimiter rateLimiter,
            PermissionEnforcer enforcer,
            CapabilityRegistry registry,
            OirConfig config,
            AtomicLong nextRequestHandle,
            ConcurrentHashMap<Long, Long> appHandleToWorkerHandle,
            CallbackBridges.Canceler canceler) {
        this.lifecycle = lifecycle;
        this.ensurer = ensurer;
        this.rateLimiter = rateLimiter;
        this.enforcer = enforcer;
        this.registry = registry;
        this.config = config;
        this.nextRequestHandle = nextRequestHandle;
        this.appHandleToWorkerHandle = appHandleToWorkerHandle;
        this.canceler = canceler;
    }
}
