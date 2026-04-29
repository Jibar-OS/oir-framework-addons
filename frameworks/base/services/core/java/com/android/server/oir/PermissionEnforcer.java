/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;

/**
 * Resolves and enforces the OIR permission required by a capability.
 *
 * Today's policy is one permission per capability namespace
 * ({@code USE_TEXT}, {@code USE_AUDIO}, {@code USE_VISION}), with a
 * shell-UID bypass for developer ergonomics.
 *
 * The class exists as a real abstraction (not an inline check) so the
 * v0.8 ObserveSession work can layer additional checks on top — the
 * always-listening / always-watching capabilities ({@code audio.observe},
 * {@code vision.observe}, {@code world.observe}) need:
 *
 * <ul>
 *   <li>A composed permission set (e.g. {@code USE_AUDIO} + a new
 *       {@code USE_OBSERVE_AUDIO}), not a single per-namespace lookup.</li>
 *   <li>Foreground-service state checks on phones (so the app shows the
 *       privacy indicator), with a different gating model on embedded /
 *       automotive devices that have no "foreground" concept — see the
 *       device-class branching note.</li>
 * </ul>
 *
 * For v0.7 the API surface is intentionally minimal: a single
 * {@link #enforce} that throws {@link SecurityException} on denial.
 * Composition + device-class branching slot in by extending the policy
 * inside this class without touching call sites.
 */
final class PermissionEnforcer {

    private final Context mContext;
    private final CapabilityRegistry mRegistry;

    PermissionEnforcer(Context context, CapabilityRegistry registry) {
        mContext = context;
        mRegistry = registry;
    }

    /**
     * Throws if the calling identity lacks the required permission for
     * this capability. Capability must be known (caller should validate
     * via {@link CapabilityRegistry#get} first).
     *
     * The shell UID bypass (uid == SHELL_UID) is preserved for the
     * {@code cmd oir} dev surface; userdebug-only permission tests use
     * {@link OIRService#submitAs} with an {@code --as-uid} override that
     * passes the simulated UID as {@code effectiveUid} here.
     *
     * @throws IllegalArgumentException if the capability is unknown.
     * @throws SecurityException if the caller lacks the required permission.
     */
    void enforce(String capability, int effectiveUid, int callingPid) {
        String perm = mRegistry.getRequiredPermission(capability);
        if (perm == null) {
            throw new IllegalArgumentException("unknown capability: " + capability);
        }
        // Shell UID bypass for developer ergonomics (unless --as-uid was set).
        if (effectiveUid == Process.SHELL_UID) return;
        int result = mContext.checkPermission(perm, callingPid, effectiveUid);
        if (result != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Permission Denial: " + perm
                    + " required for capability " + capability
                    + " (uid=" + effectiveUid + ")");
        }
    }
}
