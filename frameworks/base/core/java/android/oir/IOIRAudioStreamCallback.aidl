/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package android.oir;

/**
 * Callback for capabilities that produce streaming audio chunks:
 * audio.synthesize (Piper TTS), etc. Chunk size is worker-controlled
 * (Piper typically emits 0.5-2s chunks). Caller is expected to push
 * chunks into an AudioTrack.play() or write to a file sink.
 *
 * Exactly one of onComplete() or onError() terminates the stream.
 *
 * @hide
 */
oneway interface IOIRAudioStreamCallback {
    /**
     * One audio chunk.
     *
     * pcm             raw PCM bytes
     * sampleRateHz    e.g. 22050 for Piper voices
     * channelCount    1 = mono, 2 = stereo
     * encoding        AudioFormat.ENCODING_PCM_16BIT = 2 (most common)
     * last            true on the final chunk
     */
    void onChunk(in byte[] pcm, int sampleRateHz, int channelCount, int encoding, boolean last);

    /** Terminal success. May fire with no chunks (rare — empty output). */
    void onComplete(long totalMs);

    /** Terminal failure. */
    void onError(int errorCode, String message);
}
