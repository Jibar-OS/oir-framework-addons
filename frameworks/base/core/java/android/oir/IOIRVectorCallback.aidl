/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
 * Licensed under the Apache License, Version 2.0
 */
package android.oir;

/**
 * Public callback for capabilities returning a single fixed-dim vector.
 * v0.4 H4-A: text.embed (MiniLM 384-dim). Future: vision.embed, audio.embed.
 *
 * @hide
 */
oneway interface IOIRVectorCallback {
    void onVector(in float[] vec);
    void onError(int errorCode, String message);
}
