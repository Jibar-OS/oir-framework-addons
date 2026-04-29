/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads /system_ext/etc/oir/oir_config.xml (platform defaults) and merges
 * /vendor/etc/oir/oir_config.xml (OEM override). Last-writer-wins per key.
 *
 * See notes/MEMORY_BUDGET.md for per-device-tier recommendations.
 */
public final class OirConfig {
    private static final String TAG = "OirConfig";

    static final String PLATFORM_PATH = "/system_ext/etc/oir/oir_config.xml";
    static final String OEM_PATH      = "/vendor/etc/oir/oir_config.xml";

    // Hard-coded defaults — used when neither file is present or the file
    // is malformed. Sensible mid-tier values per MEMORY_BUDGET.md.
    private static final int DEFAULT_MEMORY_BUDGET_MB        = 4096;
    private static final int DEFAULT_WARM_TTL_SECONDS        = 60;
    private static final int DEFAULT_INFERENCE_TIMEOUT_SECS  = 120;
    // v0.5 V6: per-UID rate limits. 0 disables throttling entirely.
    // v0.6 Phase A: lowered from 100/20 to 60/10. Rationale: v0.5 gate 6
    // expected "25 rapid submits → last 5 throttled" but the shell-loop
    // test takes ~5s end-to-end; 20 burst + 100/min × 5s = 25 total
    // tokens = nothing throttled. New defaults give 10 burst + 60/min ×
    // 5s = 15 tokens → 15 pass / 10 throttle. Tighter safety for a
    // platform service. OEMs override via oir_config.xml.
    private static final int DEFAULT_RATE_LIMIT_PER_MINUTE   = 60;
    private static final int DEFAULT_RATE_LIMIT_BURST        = 10;

    private int mMemoryBudgetMb       = DEFAULT_MEMORY_BUDGET_MB;
    private int mWarmTtlSeconds       = DEFAULT_WARM_TTL_SECONDS;
    private int mInferenceTimeoutSecs = DEFAULT_INFERENCE_TIMEOUT_SECS;
    private int mRateLimitPerMinute   = DEFAULT_RATE_LIMIT_PER_MINUTE;
    private int mRateLimitBurst       = DEFAULT_RATE_LIMIT_BURST;

    // v0.5 V7: per-capability tuning knobs. Keyed "<capability>.<knob>"
    // (e.g. "vision.detect.score_threshold"). Values are floats in AIDL —
    // integer knobs (n_ctx, n_batch) still parse here but oird casts back
    // to int on receive. Empty map = no overrides; oird uses its defaults.
    private final Map<String, Float> mCapabilityTuning = new HashMap<>();
    // v0.5 V7 full: parallel map for string-valued knobs
    // (whisper_language, vision.detect.family, etc.).
    private final Map<String, String> mCapabilityTuningStr = new HashMap<>();

    /** Re-read both files and merge. Call once at OIRService onStart. */
    public void load() {
        // Reset to defaults so repeated load() calls are deterministic.
        mMemoryBudgetMb       = DEFAULT_MEMORY_BUDGET_MB;
        mWarmTtlSeconds       = DEFAULT_WARM_TTL_SECONDS;
        mInferenceTimeoutSecs = DEFAULT_INFERENCE_TIMEOUT_SECS;
        mRateLimitPerMinute   = DEFAULT_RATE_LIMIT_PER_MINUTE;
        mRateLimitBurst       = DEFAULT_RATE_LIMIT_BURST;
        mCapabilityTuning.clear();
        mCapabilityTuningStr.clear();

        loadFile(new File(PLATFORM_PATH));
        loadFile(new File(OEM_PATH));  // OEM overrides platform

        Log.i(TAG, "config loaded: memory_budget_mb=" + mMemoryBudgetMb
                + " warm_ttl_seconds=" + mWarmTtlSeconds
                + " inference_timeout_seconds=" + mInferenceTimeoutSecs
                + " rate_limit_per_minute=" + mRateLimitPerMinute
                + " rate_limit_burst=" + mRateLimitBurst
                + " tuning_knobs=" + mCapabilityTuning.size());
    }

    public int getMemoryBudgetMb()       { return mMemoryBudgetMb; }
    public int getWarmTtlSeconds()       { return mWarmTtlSeconds; }
    public int getInferenceTimeoutSecs() { return mInferenceTimeoutSecs; }
    public int getRateLimitPerMinute()   { return mRateLimitPerMinute; }
    public int getRateLimitBurst()       { return mRateLimitBurst; }
    /** v0.5 V7: snapshot of the parsed <capability_tuning> knobs. */
    public Map<String, Float> getCapabilityTuning() {
        return Collections.unmodifiableMap(mCapabilityTuning);
    }
    /** v0.5 V7 full: snapshot of the parsed string-valued knobs. */
    public Map<String, String> getCapabilityTuningStrings() {
        return Collections.unmodifiableMap(mCapabilityTuningStr);
    }

    /**
     * Resolve the {@code <capability>.n_ctx} tuning override, with a
     * sensible fallback. v0.6.1 audit: AgentKit's prompt-compaction math
     * needs the runtime-configured ctx size, not the hardcoded value
     * that earlier callers were passing into the onStart Bundle.
     *
     * text.translate inherits text.complete.n_ctx when it has no explicit
     * override, since v0.6 routes translate through the text.complete
     * llama pool by prompt-template rewriting.
     *
     * Cast to int is safe: n_ctx values parsed as floats are always
     * integer-valued in practice; Math.max enforces a floor against
     * corrupt config.
     */
    public int getCapabilityCtxSize(String capability, int fallback) {
        if (capability == null) return fallback;
        Float v = mCapabilityTuning.get(capability + ".n_ctx");
        if (v != null) return Math.max(1, v.intValue());
        if (capability.startsWith("text.translate")) {
            v = mCapabilityTuning.get("text.complete.n_ctx");
            if (v != null) return Math.max(1, v.intValue());
        }
        return fallback;
    }

    private void loadFile(File f) {
        if (!f.isFile()) return;  // silently skip missing files
        try (FileInputStream in = new FileInputStream(f)) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            int ev;
            String tag = null;
            // v0.5 V7: track two levels of nesting for <capability_tuning>.
            // Outside: normal flat tag handling. Inside <capability_tuning>
            // with current capability name in capabilityScope, the inner tag
            // is a knob written to the tuning map.
            boolean inTuning = false;
            String capabilityScope = null;
            StringBuilder text = new StringBuilder();
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = p.getName();
                    text.setLength(0);
                    if ("capability_tuning".equals(tag)) {
                        inTuning = true;
                    } else if (inTuning && "capability".equals(tag)) {
                        capabilityScope = p.getAttributeValue(null, "name");
                    }
                } else if (ev == XmlPullParser.TEXT && tag != null) {
                    text.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    String endName = p.getName();
                    if ("capability_tuning".equals(endName)) {
                        inTuning = false;
                    } else if (inTuning && "capability".equals(endName)) {
                        capabilityScope = null;
                    } else if (inTuning && capabilityScope != null) {
                        applyTuningKnob(capabilityScope, endName,
                                text.toString().trim(), f.getAbsolutePath());
                    } else if (!inTuning) {
                        applyTag(endName, text.toString().trim(), f.getAbsolutePath());
                    }
                    tag = null;
                    text.setLength(0);
                }
            }
        } catch (IOException | XmlPullParserException e) {
            Log.w(TAG, "failed to parse " + f, e);
        }
    }

    private void applyTuningKnob(String capability, String knob, String value, String source) {
        if (value.isEmpty()) return;
        final String key = capability + "." + knob;
        try {
            mCapabilityTuning.put(key, Float.parseFloat(value));
        } catch (NumberFormatException e) {
            // Non-numeric knob — store as string (v0.5 V7 full).
            mCapabilityTuningStr.put(key, value);
        }
    }

    private void applyTag(String tag, String value, String source) {
        if (value.isEmpty()) return;
        try {
            switch (tag) {
                case "memory_budget_mb":
                    mMemoryBudgetMb = Integer.parseInt(value);
                    if (mMemoryBudgetMb < 256) {
                        Log.w(TAG, source + ": memory_budget_mb=" + mMemoryBudgetMb
                                + " is very low; clamping to 256");
                        mMemoryBudgetMb = 256;
                    }
                    break;
                case "warm_ttl_seconds":
                    mWarmTtlSeconds = Integer.parseInt(value);
                    if (mWarmTtlSeconds < 0) mWarmTtlSeconds = 0;
                    break;
                case "inference_timeout_seconds":
                    mInferenceTimeoutSecs = Integer.parseInt(value);
                    if (mInferenceTimeoutSecs < 1) mInferenceTimeoutSecs = 1;
                    break;
                case "rate_limit_per_minute":
                    mRateLimitPerMinute = Integer.parseInt(value);
                    if (mRateLimitPerMinute < 0) mRateLimitPerMinute = 0;
                    break;
                case "rate_limit_burst":
                    mRateLimitBurst = Integer.parseInt(value);
                    if (mRateLimitBurst < 0) mRateLimitBurst = 0;
                    break;
                default:
                    // ignore unknown keys (forward-compat)
                    break;
            }
        } catch (NumberFormatException e) {
            Log.w(TAG, source + ": bad int for <" + tag + ">: " + value);
        }
    }
}
