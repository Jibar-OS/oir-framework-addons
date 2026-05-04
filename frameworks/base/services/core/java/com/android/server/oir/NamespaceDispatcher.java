/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import android.os.Binder;

/**
 * Base class for the per-namespace AIDL dispatchers
 * ({@link TextDispatcher} / {@link AudioDispatcher} / {@link VisionDispatcher},
 * plus future {@code AudioObserveDispatcher} / {@code VisionObserveDispatcher} /
 * {@code WorldDispatcher}). Centralizes the preflight pipeline so a fix to
 * any of its steps (variant fallback, permission, rate-limit, model ensure)
 * lands in exactly one place and can't drift between sibling dispatchers.
 *
 * Why a base class rather than a static helper or a method on
 * {@link DispatcherDeps}: every namespace dispatcher needs the same
 * (deps, preflight, workerOrNull) trio at construction; inheritance is the
 * lightest expression of that. Subclasses still own their shape-specific
 * submit methods — only the cross-cutting plumbing lives here.
 */
abstract class NamespaceDispatcher {

    protected final DispatcherDeps mDeps;

    protected NamespaceDispatcher(DispatcherDeps deps) {
        mDeps = deps;
    }

    /**
     * Capability validate + permission + rate-limit + ensure model. Returns
     * the loaded worker model handle on success, or 0 on any failure (the
     * caller's {@code reporter} has been notified with an OIRError code +
     * message). Subclasses call this from each shape-specific submit method
     * before issuing the worker RPC.
     *
     * Variant fallback behavior:
     * <ul>
     *   <li>The capability registry is consulted via
     *       {@link CapabilityRegistry#getOrFallback}, so {@code text.complete:fast}
     *       resolves to base {@code text.complete} when the variant isn't declared.</li>
     *   <li>Permission enforcement uses the RESOLVED capability's name, so the
     *       base capability's required permission is inherited by the variant.</li>
     *   <li>Rate limiting and {@link ModelEnsurer#ensure} run with the requested
     *       name preserved in error messages (so apps see what they typed in
     *       any error chain) but {@link ModelEnsurer} keys its handle cache by
     *       the resolved name internally so variant + base share one entry.</li>
     * </ul>
     */
    protected long preflight(String capability, ModelEnsurer.ErrorReporter reporter) {
        Capability resolved = mDeps.registry.getOrFallback(capability);
        if (resolved == null) {
            reporter.report(OIRError.INVALID_INPUT, "unknown capability: " + capability);
            return 0L;
        }
        try {
            // Pass resolved.name so the permission lookup follows the same
            // fallback the registry just did. Otherwise a variant with no
            // declared permission would die at PermissionEnforcer's exact
            // lookup before reaching the base's required-permission entry.
            mDeps.enforcer.enforce(resolved.name, Binder.getCallingUid(), Binder.getCallingPid());
        } catch (SecurityException se) {
            reporter.report(OIRError.PERMISSION_DENIED, se.getMessage());
            return 0L;
        }
        final int uid = Binder.getCallingUid();
        if (!mDeps.rateLimiter.tryAcquire(uid)) {
            long waitMs = mDeps.rateLimiter.nextTokenWaitMs(uid);
            reporter.report(OIRError.CAPABILITY_THROTTLED,
                    "rate limit exceeded for capability " + capability
                            + " — retry after " + waitMs + "ms");
            return 0L;
        }
        return mDeps.ensurer.ensure(capability, reporter);
    }

    /**
     * Snapshot the worker reference under the lifecycle lock. Returns null
     * when oird is not attached (binder death + respawn in progress).
     * Subclasses handle the null case with their callback-shape-specific
     * error reporter — this method intentionally stays callback-agnostic.
     */
    protected IOirWorker workerOrNull() {
        synchronized (mDeps.lifecycle.getLock()) {
            return mDeps.lifecycle.getWorkerLocked();
        }
    }
}
