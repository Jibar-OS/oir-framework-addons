/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 *
 * v0.4 H4-A: worker-side vector callback for text.embed.
 */
package com.android.server.oir;

/** @hide */
oneway interface IOirWorkerVectorCallback {
    void onVector(in float[] vec);
    void onError(int workerErrorCode, String message);
}
