/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
 * Licensed under the Apache License, Version 2.0
 *
 * Internal AIDL — system_server ↔ oird worker.
 * Not exposed to apps; apps use android.oir.IOIRService.
 *
 * Uses primitive parameters (not PersistableBundle) to avoid needing a
 * parcelable declaration / include path for PersistableBundle in the
 * C++ AIDL backend. v0.2 can introduce an AIDL parcelable for richer
 * options once the capability-registry lands.
 */
package com.android.server.oir;

import com.android.server.oir.IOirWorkerCallback;
import com.android.server.oir.IOirWorkerVectorCallback;
import com.android.server.oir.IOirWorkerAudioCallback;
import com.android.server.oir.IOirWorkerBboxCallback;
import com.android.server.oir.IOirWorkerRealtimeBooleanCallback;
import com.android.server.oir.MemoryStats;

/** @hide */
interface IOirWorker {
    /** Load a GGUF via mmap into the worker's address space. Returns handle. */
    long load(String modelPath);

    /** Unload a previously loaded model. Idempotent. */
    void unload(long modelHandle);

    /**
     * Submit inference. Tokens stream via callback. Returns request handle.
     *
     * v0.1 exposes the two knobs OIRService needs; everything else (top-p,
     * top-k, stop-sequences) uses worker-side defaults until v0.2 wires up
     * a richer options type.
     */
    long submit(long modelHandle,
                String prompt,
                int maxTokens,
                float temperature,
                IOirWorkerCallback callback);

    /** Request cancellation. No-op if request already complete. */
    void cancel(long requestHandle);

    /** v0.4 S2: snapshot of worker memory state (multi-model map + sizes). */
    MemoryStats getMemoryStats();

    /** v0.4 S2-B: configure budget + warm TTL. Pushed once from OIRService.attachWorker. */
    void setConfig(int memoryBudgetMb, int warmTtlSeconds);

    /**
     * v0.5 V7: push per-capability tuning knobs. OIRService calls this once
     * per capability at attachWorker time (or on config reload), with values
     * from <capability_tuning> in oir_config.xml.
     *
     * Generic (String key, float value) shape so new knobs land additively
     * without AIDL churn. Unknown keys are logged and ignored by oird.
     *
     * v0.5 keys:
     *   "vision.detect.score_threshold" (float, default 0.25)
     *   "vision.detect.iou_threshold"   (float, default 0.45)
     * Additional knobs (n_ctx, n_batch, max_tokens, whisper language) land
     * in subsequent v0.5 slices via the same entry point.
     */
    void setCapabilityFloat(String key, float value);

    /**
     * v0.5 V7 full: string-valued counterpart to setCapabilityFloat.
     * Same routing — unknown keys logged and ignored. Examples:
     *   "audio.transcribe.whisper_language" -> "es"
     *   "vision.detect.family"              -> "yolov8" / "detr" / "rtdetr"
     *   "vision.detect.normalize"           -> "0_to_1" / "mean_std"
     */
    void setCapabilityString(String key, String value);

    /** v0.4 S3: mark a model as warm — unevictable for warm_ttl_seconds past this call. */
    void warm(long modelHandle);

    /** v0.4 H4-A: load a model in embedding mode (llama_context with embeddings=true, pooling=MEAN). */
    long loadEmbed(String modelPath);

    /** v0.4 H4-A: run text through an embedding model; callback receives one pooled vector. */
    long submitEmbed(long modelHandle, String text, IOirWorkerVectorCallback callback);

    /** v0.4 H1: load a whisper.cpp model (separate code path from load/loadEmbed). */
    long loadWhisper(String modelPath);

    /** v0.4 H1: run audio file through whisper; segment text streams via existing IOirWorkerCallback.onToken. */
    long submitTranscribe(long modelHandle, String audioPath, IOirWorkerCallback callback);

    /**
     * v0.4 H2/H3: load an ONNX model via ONNX Runtime.
     * isDetection=true  → session built for vision.detect (larger tensors)
     * isDetection=false → session built for audio.synthesize / other
     * The flag hints at whether to enable ORT's graph optimizations aggressively
     * (detection benefits; synthesize keeps latency low with fewer passes).
     */
    long loadOnnx(String modelPath, boolean isDetection);

    /** v0.4 H2: audio.synthesize — text in, streaming PCM chunks via IOirWorkerAudioCallback. */
    long submitSynthesize(long modelHandle, String text, IOirWorkerAudioCallback callback);

    /** v0.4 H3: vision.detect — image file path in, a single bboxes result via IOirWorkerBboxCallback. */
    long submitDetect(long modelHandle, String imagePath, IOirWorkerBboxCallback callback);

    /** v0.4 H4-B: load a vision encoder (SigLIP / CLIP ONNX) in embedding mode. */
    long loadVisionEmbed(String modelPath);

    /** v0.4 H4-B: run image file through vision encoder; single pooled vector via IOirWorkerVectorCallback. */
    long submitVisionEmbed(long modelHandle, String imagePath, IOirWorkerVectorCallback callback);

    /** v0.4 H6: load a VLM (CLIP mmproj + LLaMA text model pair) for vision.describe. */
    long loadVlm(String clipModelPath, String llmModelPath);

    /** v0.4 H6: run image + text prompt through VLM; tokens stream via existing IOirWorkerCallback.onToken. */
    long submitDescribeImage(long modelHandle, String imagePath, String prompt, IOirWorkerCallback callback);

    /**
     * v0.5 V5: load a RealtimeBoolean-shape model (Silero VAD today).
     * Worker keeps per-handle LSTM state across submitVad() calls so
     * streaming windows share continuity.
     */
    long loadVad(String modelPath);

    /**
     * v0.5 V5: run a PCM16 LE 16kHz mono file through the VAD model.
     * Worker chunks it into 32 ms windows (512 samples) and emits
     * onState per window via the callback.
     */
    long submitVad(long modelHandle, String pcmPath, IOirWorkerRealtimeBooleanCallback callback);

    /* ===================================================================
     * v0.6 Phase B — new capability entry points on the worker side.
     * Loaders reuse loadOnnx/load — only the submit-side needs new verbs
     * because each has a distinct input/output shape contract.
     * =================================================================== */

    /**
     * v0.6 — text.classify. Single text in; Vector of per-label scores out.
     * Worker interprets `modelHandle` as an ONNX classifier (logits head).
     * Caller has already loaded the model via loadOnnx(path, false).
     */
    long submitClassify(long modelHandle, String text, IOirWorkerVectorCallback callback);

    /**
     * v0.6 — text.rerank. (query, candidates[]) in; Vector of one score
     * per candidate out (aligned to candidates[i]). Worker interprets
     * modelHandle as an ONNX cross-encoder reranker.
     */
    long submitRerank(long modelHandle,
                      String query,
                      in String[] candidates,
                      IOirWorkerVectorCallback callback);

    /**
     * v0.6 — text.translate. Reuses llama.cpp backend. The prompt
     * passed here has ALREADY been rewritten into a translation
     * instruction by OIRService using the Options{sourceLang,targetLang};
     * worker just runs generation with the translation-leaning sampler
     * defaults (lower temperature, no top-k). onToken stream via the
     * existing IOirWorkerCallback.
     */
    long submitTranslate(long modelHandle,
                         String prompt,
                         int maxTokens,
                         IOirWorkerCallback callback);

    /**
     * v0.6 — vision.ocr. Image path in; BoundingBox results out where
     * BoundingBox.label carries the OCR'd text for each region and
     * BoundingBox.score is the recognizer confidence.
     *
     * Worker expects modelHandle to reference an ONNX OCR detector
     * (e.g. PaddleOCR det + rec pair via sidecar — parallels the
     * vision.detect classes.json sidecar pattern).
     */
    long submitOcr(long modelHandle, String imagePath, IOirWorkerBboxCallback callback);

    /**
     * v0.6.3 — per-handle runtime snapshot for `cmd oir memory`.
     *
     * Returns a TSV dump, one line per loaded model, columns:
     *   handle \t backend \t pool_size \t busy \t waiting \t size_mb \t path
     *
     * `getMemoryStats()` gives model counts + RSS totals; this method
     * adds the concurrency view (pool depth, busy slots, waiting
     * leasers) that v0.6's ContextPool + WhisperPool introduced.
     * Separate method so we don't churn the MemoryStats parcelable
     * shape (AIDL parcelable adds are sticky — apps compiled against
     * the old parcelable keep working by ignoring new fields only if
     * layout is preserved, which is fragile).
     *
     * Backend values:
     *   "llama"   — llama-backed text/translate
     *   "whisper" — audio.transcribe
     *   "mtmd"    — vision.describe (VLM, pooled)
     *   "ort"     — everything ONNX (no pool; pool_size=0)
     *
     * Newline-separated (`\n`); empty string when no models loaded.
     */
    String dumpRuntimeStats();

    /**
     * v0.6.3 — worker-side file stat fallback.
     *
     * Why this lives on the worker AIDL: `system_server` runs in a SELinux
     * domain that may not have `getattr` on every `oir_model_*_file`
     * label, especially on non-cuttlefish products that haven't picked up
     * the v0.6.1 policy rule. `oird` runs in `u:r:oird:s0` which has
     * read access to every model file by platform policy, so delegating
     * the stat here works regardless of the caller's SELinux scope.
     *
     * Returns true when `path` is a regular file that oird can open for
     * read. Symlinks resolved; permission-denied returns false.
     * Called from OIRService.isCapabilityRunnable() as a fallback after
     * `java.io.File.isFile()` returns false.
     */
    boolean fileIsReadable(String path);
}
