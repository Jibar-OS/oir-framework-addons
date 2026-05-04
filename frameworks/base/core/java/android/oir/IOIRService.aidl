/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
 * Licensed under the Apache License, Version 2.0
 */
package android.oir;

import android.oir.IOIRTokenCallback;
import android.oir.IOIRVectorCallback;
import android.oir.IOIRAudioStreamCallback;
import android.oir.IOIRBoundingBoxCallback;
import android.oir.IOIRRealtimeBooleanCallback;
import android.os.Bundle;
import android.os.ICancellationSignal;

/** @hide */
interface IOIRService {
    /**
     * Submit a text.complete request. Tokens stream via the callback.
     * Returns an opaque request handle usable with {@link #cancel(long)}.
     *
     * options Bundle:
     *   - "maxTokens"     int
     *   - "temperature"   float
     *   - "topP"          float
     *   - "topK"          int
     *   - "stopSequences" String[]
     *
     * Unknown keys ignored; defaults implementation-defined. OIRService
     * copies primitive values from this Bundle into a PersistableBundle
     * when crossing into the native worker.
     *
     * v0.1: only capability. Canned stub in Phase 2, real llama.cpp in Phase 4.
     */
    long submit(String capability, String prompt, in Bundle options, IOIRTokenCallback callback, in ICancellationSignal cancel);

    /**
     * Request cancellation of an in-flight request.
     * No-op if the request has already completed.
     */
    void cancel(long requestHandle);

    /**
     * v0.4 H4-A: text.embed — submit text, receive single fixed-dim vector.
     * No streaming; onVector or onError fires exactly once.
     */
    long submitEmbedText(String capability,
                         String text,
                         IOIRVectorCallback callback,
                         in ICancellationSignal cancel);

    /**
     * v0.4 H2: audio.synthesize — text in, PCM chunks out via streaming callback.
     * OEM-bundled Piper-family ONNX voice + phonemizer on the worker side.
     * onChunk fires 1..N times; exactly one of onComplete/onError terminates.
     */
    long submitSynthesize(String capability,
                          String text,
                          IOIRAudioStreamCallback callback,
                          in ICancellationSignal cancel);

    /**
     * v0.4 H3: vision.detect — image file path in, bounding boxes out.
     * No streaming; detection is a single forward pass on the full image.
     * OEM-bundled YOLO-family ONNX detector + sidecar class labels JSON.
     */
    long submitDetect(String capability,
                      String imagePath,
                      IOIRBoundingBoxCallback callback,
                      in ICancellationSignal cancel);

    /**
     * Hint that a capability will be called soon. Worker loads/mmaps the
     * model without running inference. No-op if already warm. Requires
     * the same permission as actually calling the capability.
     */
    void warm(String capability);

    /**
     * v0.5 V5: audio.vad — submit a PCM16 LE 16 kHz mono source; receive
     * streaming boolean transitions per analysis window via the callback.
     *
     * Shape intentionally simple: a file path today (same pattern as
     * audio.transcribe). Live-mic dictation composes on top — app streams
     * PCM buffer, feeds file-like chunks, observes state edges client-side.
     * See V0.5_PLAN.md §2.4 for the dictation composition pattern.
     *
     * The IOIRRealtimeBooleanCallback shape is frozen in v0.5. Future
     * RealtimeBoolean capabilities reuse this type.
     */
    long submitVad(String capability,
                   String pcmPath,
                   IOIRRealtimeBooleanCallback callback,
                   in ICancellationSignal cancel);

    /**
     * v0.6 Phase B — text.rerank. Query + candidate documents in, one
     * relevance score per candidate out via Vector callback. Scores are
     * implementation-defined (higher = more relevant); apps sort
     * descending. onVector fires exactly once with scores[i] aligned to
     * candidates[i], or onError.
     *
     * Note: text.classify, text.translate, and vision.ocr do NOT need new
     * methods — they reuse existing AIDL surface:
     *   - text.classify  → submitEmbedText(capability="text.classify", ...)
     *                      onVector returns per-label scores.
     *   - text.translate → submit(capability="text.translate", prompt=text, ...)
     *                      Options may include {"sourceLang","targetLang"}.
     *   - vision.ocr     → submitDetect(capability="vision.ocr", ...)
     *                      BoundingBox.label carries OCR'd text.
     */
    long submitRerank(String capability,
                      String query,
                      in String[] candidates,
                      IOIRVectorCallback callback,
                      in ICancellationSignal cancel);

    /**
     * v0.6 Phase B — capability pre-flight. Returns a status code without
     * loading the model or running inference, so apps and test harnesses
     * can feature-detect before submitting and get a clean fallback path.
     *
     *   0 — UNKNOWN_CAPABILITY  (name not in the platform registry)
     *   1 — RUNNABLE            (default-model resolvable on disk)
     *   2 — NO_DEFAULT_MODEL    (declared but OEM has not baked a model)
     *   3 — MODEL_MISSING       (default-model path set but file absent)
     *
     * Matches the bracketed statuses emitted by `cmd oir dumpsys capabilities`.
     * Does not require any OIR permission — capability names are already
     * public via capabilities.xml; this just queries the parsed registry.
     */
    int isCapabilityRunnable(String capability);
}
