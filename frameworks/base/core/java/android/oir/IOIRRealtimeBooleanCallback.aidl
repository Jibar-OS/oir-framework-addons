/*
 * Copyright (C) 2026 The OpenIntelligenceRuntime Project
 * Licensed under the Apache License, Version 2.0
 */
package android.oir;

/**
 * Callback for RealtimeBoolean-shape capabilities (v0.5+). The canonical
 * consumer is audio.vad (voice activity detection) — the worker streams
 * a boolean state transition per analysis window against a continuous
 * audio source.
 *
 * Contract:
 *   - onState fires exactly once per analysis window the worker processes.
 *     Not debounced — callers who want "only emit on edge transition"
 *     filter client-side.
 *   - timestampMs is the window's start time in the source's timeline
 *     (monotonic, ms since the submit began). Callers can correlate back
 *     to their PCM byte offsets.
 *   - The callback may fire many times per submit. onComplete signals the
 *     source ended (for file-backed submits) or the request was cancelled.
 *
 * Shape frozen in v0.5. Future realtime-boolean capabilities reuse this
 * callback type.
 *
 * @hide
 */
oneway interface IOIRRealtimeBooleanCallback {
    /** Per-window state. isTrue meaning is capability-specific; for
     *  audio.vad: true = voice present, false = silence. */
    void onState(boolean isTrue, long timestampMs);

    /** Source terminated (file end-of-stream, mic stopped, etc.). */
    void onComplete();

    /** Error reported by the worker. */
    void onError(int errorCode, String message);
}
