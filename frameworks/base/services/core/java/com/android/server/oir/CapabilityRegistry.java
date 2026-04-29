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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads capabilities.xml from /system_ext/etc/oir/ (platform, OIR-owned)
 * and merges OEM fragments from /vendor/etc/oir/*.xml (lexicographic order).
 *
 * Governance:
 *   - Reserved namespace prefixes (text./audio./vision./oir./android./os.)
 *     only the platform XML may declare. OEM fragments declaring entries under
 *     reserved prefixes are rejected with a logged warning.
 *   - OEMs extend via reverse-DNS (com.oem.xxx) and must declare their own
 *     required-permission in the fragment.
 */
public final class CapabilityRegistry {
    private static final String TAG = "CapabilityRegistry";

    static final String PLATFORM_XML   = "/system_ext/etc/oir/capabilities.xml";
    static final String OEM_FRAGMENTS  = "/vendor/etc/oir";

    private static final String[] RESERVED_PREFIXES = {
        "text.", "audio.", "vision.", "oir.", "android.", "os."
    };

    // LinkedHashMap preserves declaration order — useful for dumpsys output.
    private final Map<String, Capability> mCapabilities = new LinkedHashMap<>();

    public void load() {
        mCapabilities.clear();
        loadFile(new File(PLATFORM_XML), /*platformAuthoritative=*/true);

        File oemDir = new File(OEM_FRAGMENTS);
        if (oemDir.isDirectory()) {
            File[] fragments = oemDir.listFiles((d, n) -> n.endsWith(".xml"));
            if (fragments != null) {
                Arrays.sort(fragments);
                for (File f : fragments) loadFile(f, /*platformAuthoritative=*/false);
            }
        }

        Log.i(TAG, "loaded " + mCapabilities.size() + " capabilities");
    }

    public Capability get(String name)         { return mCapabilities.get(name); }
    public Collection<Capability> all()        { return Collections.unmodifiableCollection(mCapabilities.values()); }

    /**
     * Variant-aware lookup. Tries the exact name first; if not found and
     * the name carries a {@code :variant} suffix (e.g. {@code text.complete:fast}),
     * strips the suffix and retries against the base capability.
     *
     * Today (pre-v0.7-variants) there are no {@code :variant} entries in
     * capabilities.xml, so this method behaves identically to {@link #get}
     * for every name a caller hands it. The fallback path is dormant
     * infrastructure for the v0.7 variant work (CAPABILITIES.md §
     * "Variants"): apps that ask for a tier the OEM didn't declare
     * receive the base capability instead of {@code null}.
     *
     * Logged on cache miss so OEM operators see when an app is asking
     * for a variant they didn't bake. Returns null only when neither the
     * variant nor its base exists.
     */
    public Capability getOrFallback(String name) {
        if (name == null) return null;
        Capability c = mCapabilities.get(name);
        if (c != null) return c;
        int colon = name.indexOf(':');
        if (colon <= 0) return null;
        String base = name.substring(0, colon);
        Capability fallback = mCapabilities.get(base);
        if (fallback != null) {
            Log.i(TAG, "capability variant '" + name + "' not declared; falling back to base '"
                    + base + "'");
        }
        return fallback;
    }

    /**
     * v0.6.9: merge default-model overrides from {@link OirConfig}. The
     * platform {@code capabilities.xml} declares some capabilities
     * (notably {@code vision.describe}) with NO default-model because
     * no permissive default exists at platform tier. OEMs previously
     * had to patch capabilities.xml itself to supply one, because the
     * reserved-namespace check blocks OEM fragments from overriding
     * platform entries (that guard is a feature — prevents OEM
     * extensions from shadowing the spec).
     *
     * This method is the escape hatch for the narrow case. OIRService
     * calls it after {@link #load()} + {@link OirConfig#load()} to
     * apply any {@code <capability_tuning><capability name="X">
     * <default_model>/path</default_model></capability>} overrides.
     * ONLY capabilities whose {@code defaultModelPath} is empty are
     * eligible — an OEM override cannot shadow a platform-bundled
     * default, preserving the v0.5 invariant. For composite-path
     * capabilities (vision.describe → {@code "mmproj|llm"}), the raw
     * string is passed through; OIRService splits downstream.
     *
     * Typical override in {@code /vendor/etc/oir/oir_config.xml}:
     * <pre>
     *   &lt;capability_tuning&gt;
     *     &lt;capability name="vision.describe"&gt;
     *       &lt;default_model&gt;/product/etc/oir/mmproj.gguf|/product/etc/oir/llm.gguf&lt;/default_model&gt;
     *     &lt;/capability&gt;
     *   &lt;/capability_tuning&gt;
     * </pre>
     */
    public void applyConfigOverrides(OirConfig config) {
        Map<String, String> strings = config.getCapabilityTuningStrings();
        if (strings == null || strings.isEmpty()) return;
        int applied = 0;
        for (Map.Entry<String, Capability> e
                : new java.util.ArrayList<>(mCapabilities.entrySet())) {
            Capability c = e.getValue();
            if (c.defaultModelPath != null && !c.defaultModelPath.isEmpty()) continue;
            String override = strings.get(c.name + ".default_model");
            if (override == null || override.isEmpty()) continue;
            Capability replaced = new Capability(c.name, c.shape, c.requiredPermission,
                    override, c.source + " (overridden by oir_config.xml)", c.backend);
            mCapabilities.put(c.name, replaced);
            Log.i(TAG, "capability " + c.name + " default-model overridden from oir_config.xml → "
                    + override);
            applied++;
        }
        if (applied > 0) {
            Log.i(TAG, "applied " + applied + " default-model override(s) from oir_config.xml");
        }
    }

    /**
     * Returns the required permission for a capability, deriving from namespace
     * prefix if the XML did not declare one. Returns null for unknown capability
     * (caller should reject with CAPABILITY_NOT_FOUND).
     *
     * Variant fallback: uses {@link #getOrFallback} so a request for a
     * not-declared variant ({@code text.complete:fast}) reads the base
     * capability's required permission. Pre-this-fix, a permission lookup
     * for a variant that fell back at every other call site still failed
     * here with exact-match-null, so the variant was un-callable.
     */
    public String getRequiredPermission(String capabilityName) {
        Capability c = getOrFallback(capabilityName);
        if (c == null) return null;
        if (c.requiredPermission != null) return c.requiredPermission;
        return defaultPermissionForNamespace(c.name);
    }

    private void loadFile(File f, boolean platformAuthoritative) {
        if (!f.isFile()) {
            if (platformAuthoritative) Log.w(TAG, "platform XML missing: " + f);
            return;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && "capability".equals(p.getName())) {
                    parseCapability(p, f.getAbsolutePath(), platformAuthoritative);
                }
            }
        } catch (IOException | XmlPullParserException e) {
            Log.w(TAG, "failed to parse " + f, e);
        }
    }

    private void parseCapability(XmlPullParser p, String source, boolean platformAuthoritative) {
        String name    = p.getAttributeValue(null, "name");
        String shape   = p.getAttributeValue(null, "shape");
        String perm    = p.getAttributeValue(null, "required-permission");
        String model   = p.getAttributeValue(null, "default-model");
        String backend = p.getAttributeValue(null, "backend");  // v0.5: optional

        if (name == null || shape == null) {
            Log.w(TAG, "skipping <capability> with missing name/shape in " + source);
            return;
        }

        if (!platformAuthoritative && isReservedNamespace(name)) {
            Log.w(TAG, "OEM fragment " + source + " tried to declare reserved-namespace "
                    + "capability \"" + name + "\"; ignoring. Use reverse-DNS for OEM extensions.");
            return;
        }

        if (mCapabilities.containsKey(name) && !platformAuthoritative) {
            Log.w(TAG, "OEM fragment " + source + " tried to override existing capability \""
                    + name + "\"; ignoring.");
            return;
        }

        if (perm == null || perm.isEmpty()) {
            perm = defaultPermissionForNamespace(name);
            if (perm == null) {
                Log.w(TAG, "capability \"" + name + "\" in " + source
                        + " has no required-permission and unknown namespace; defaulting to deny.");
                perm = "oir.permission.DENY";  // sentinel — no app will have this
            }
        }

        // v0.5: default-fill backend from capability name when the XML attribute is
        // absent. OEMs only set backend= when they swap to a model that needs a
        // different runtime. Older OIR builds ignore the attribute (forward-compat).
        if (backend == null || backend.isEmpty()) {
            backend = defaultBackendForCapability(name);
        }

        mCapabilities.put(name, new Capability(name, shape, perm, model, source, backend));
    }

    /**
     * Default backend per capability when the XML omits {@code backend=}. Mirrors
     * the v0.4-era hardwired routing in OIRService:
     *   text.*                → llama (or mtmd if vision.describe-shaped)
     *   vision.describe       → mtmd
     *   audio.transcribe      → whisper
     *   audio.synthesize / audio.vad / vision.detect / vision.embed → ort
     * Unknown capability prefixes fall back to "ort" (most permissive — ORT loads
     * any ONNX file shape OIR doesn't otherwise know how to dispatch).
     */
    private static String defaultBackendForCapability(String name) {
        if (name.startsWith("text.complete") || name.startsWith("text.embed")
                || name.startsWith("text.classify") || name.startsWith("text.rerank")
                || name.startsWith("text.translate")) {
            return "llama";
        }
        if (name.startsWith("vision.describe")) return "mtmd";
        if (name.startsWith("audio.transcribe")) return "whisper";
        // audio.synthesize, audio.vad, vision.detect, vision.embed, vision.ocr
        // all default to ort under v0.5's hardwired wiring; OEMs flipping to
        // libmtmd's clip.cpp / mtmd-audio paths set backend="mtmd" explicitly.
        return "ort";
    }

    private static boolean isReservedNamespace(String name) {
        for (String pfx : RESERVED_PREFIXES) if (name.startsWith(pfx)) return true;
        return false;
    }

    private static String defaultPermissionForNamespace(String name) {
        if (name.startsWith("text."))   return "oir.permission.USE_TEXT";
        if (name.startsWith("audio."))  return "oir.permission.USE_AUDIO";
        if (name.startsWith("vision.")) return "oir.permission.USE_VISION";
        // oir.*, android.*, os.* are handled explicitly in XML or by the caller.
        return null;
    }
}
