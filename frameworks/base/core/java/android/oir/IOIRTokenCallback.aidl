/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
 * Licensed under the Apache License, Version 2.0
 */
package android.oir;

import android.os.Bundle;

/** @hide */
oneway interface IOIRTokenCallback {
    /**
     * Called once before the first token.
     * meta: "handle" (long), "backend" (String).
     */
    void onStart(in Bundle meta);

    /**
     * Streaming tokens.
     * outputIndex: 0-based position of this token in the output stream.
     * AgentKit computes tokens_used = prompt_tokens + outputIndex + 1.
     * Apps that do not need token-count bookkeeping can ignore it.
     */
    void onToken(String token, int outputIndex);

    /**
     * Successful completion.
     * stats: "totalTokens" (int), "firstTokenMs" (long), "totalMs" (long).
     */
    void onComplete(in Bundle stats);

    /** Error; no further callbacks. Codes in android.oir.OIRError. */
    void onError(int errorCode, String message);
}
