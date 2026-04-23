/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

/**
 * Immutable description of one OIR capability as declared in capabilities.xml.
 * See notes/CAPABILITY_TAXONOMY.md for the shape taxonomy.
 */
public final class Capability {
    public final String name;                // e.g. "text.complete"
    public final String shape;               // TokenStream, Vector, Labels, ...
    public final String requiredPermission;  // e.g. "oir.permission.USE_TEXT"
    public final String defaultModelPath;    // /product/etc/oir/...gguf (nullable)
    public final String source;              // path to XML that declared it
    public final String backend;             // v0.5: llama / mtmd / whisper / ort
                                             // (default-filled by registry from shape
                                             // when the XML attribute is absent)

    public Capability(String name, String shape, String requiredPermission,
                      String defaultModelPath, String source, String backend) {
        this.name = name;
        this.shape = shape;
        this.requiredPermission = requiredPermission;
        this.defaultModelPath = defaultModelPath;
        this.source = source;
        this.backend = backend;
    }

    @Override public String toString() {
        return name + " shape=" + shape + " perm=" + requiredPermission
                + " model=" + defaultModelPath + " backend=" + backend
                + " source=" + source;
    }
}
