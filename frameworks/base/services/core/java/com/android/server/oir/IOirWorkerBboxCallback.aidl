/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
 * Licensed under the Apache License, Version 2.0
 */
package com.android.server.oir;

/**
 * Bounding box output for vision.detect. Primitive parallel arrays
 * instead of a List<BoundingBox> parcelable to match the IOirWorker
 * simple-types convention (NDK AIDL backend limitations on nested
 * parcelable lists in some older codegen paths).
 *
 * OIRService repacks these into List<android.oir.BoundingBox> for the
 * public-facing IOIRBoundingBoxCallback.
 *
 * @hide
 */
oneway interface IOirWorkerBboxCallback {
    /**
     * Detection result. Five parallel arrays of length N (number of boxes).
     * labelsFlat + scoresFlat hold multi-label/multi-score per box, flattened.
     * labelsPerBox tells how many label entries belong to each box.
     *
     * Example for 2 boxes where box 0 has {cat 0.9, dog 0.1} and box 1 has {car 0.95}:
     *   xs        = [100, 200]
     *   ys        = [50, 80]
     *   widths    = [80, 200]
     *   heights   = [60, 150]
     *   labelsPerBox = [2, 1]           // length matches boxes
     *   labelsFlat   = ["cat","dog","car"]  // length = sum(labelsPerBox)
     *   scoresFlat   = [0.9, 0.1, 0.95]
     */
    void onBoundingBoxes(in int[] xs,
                         in int[] ys,
                         in int[] widths,
                         in int[] heights,
                         in int[] labelsPerBox,
                         in String[] labelsFlat,
                         in float[] scoresFlat);

    /** Terminal failure. */
    void onError(int workerErrorCode, String message);
}
