/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
 * Licensed under the Apache License, Version 2.0
 *
 * Primitive args (not PersistableBundle) — see IOirWorker.aidl note.
 */
package com.android.server.oir;

/** @hide */
oneway interface IOirWorkerCallback {
    /** Streaming token + 0-based output-stream position (v0.4 §3.5). */
    void onToken(String token, int outputIndex);

    /** Successful completion. */
    void onComplete(int totalTokens, long firstTokenMs, long totalMs);

    /** Worker-side error. Codes translated to public OIRError by OIRService. */
    void onError(int workerErrorCode, String message);
}
