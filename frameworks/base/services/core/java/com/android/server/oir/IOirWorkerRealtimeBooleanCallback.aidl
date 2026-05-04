/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

/**
 * Worker-facing counterpart to android.oir.IOIRRealtimeBooleanCallback.
 * OIRService bridges across the two shapes the way it does for bbox/audio.
 *
 * @hide
 */
oneway interface IOirWorkerRealtimeBooleanCallback {
    void onState(boolean isTrue, long timestampMs);
    void onComplete();
    void onError(int workerErrorCode, String message);
}
