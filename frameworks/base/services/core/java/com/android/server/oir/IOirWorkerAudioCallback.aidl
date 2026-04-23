/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

/** @hide */
oneway interface IOirWorkerAudioCallback {
    /**
     * One PCM chunk from the synthesizer. last=true on final chunk.
     * Bridges to IOIRAudioStreamCallback at the OIRService layer.
     */
    void onChunk(in byte[] pcm, int sampleRateHz, int channelCount, int encoding, boolean last);

    /** Terminal success. */
    void onComplete(long totalMs);

    /** Terminal failure. */
    void onError(int workerErrorCode, String message);
}
