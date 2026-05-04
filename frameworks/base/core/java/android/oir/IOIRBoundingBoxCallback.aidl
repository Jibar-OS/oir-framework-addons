/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
 * Licensed under the Apache License, Version 2.0
 */
package android.oir;

import android.oir.BoundingBox;

/**
 * Callback for vision.detect. Single onBoundingBoxes() fires with the
 * full result; no streaming (detection runs once on the full input image).
 *
 * @hide
 */
oneway interface IOIRBoundingBoxCallback {
    /** Full detection result. Empty list = nothing detected. */
    void onBoundingBoxes(in List<BoundingBox> boxes);

    /** Error reported by the worker. */
    void onError(int errorCode, String message);
}
