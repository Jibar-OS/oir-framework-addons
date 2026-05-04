/*
 * Copyright (C) 2026 The Open Intelligence Runtime Project, a JibarOS project
 * Licensed under the Apache License, Version 2.0
 */
package android.oir;

/**
 * One detected object from vision.detect. Coordinates are pixel values in
 * the input image's coordinate space (not normalized). Labels and scores
 * are parallel arrays sorted by descending confidence; length >= 1.
 *
 * See notes/CAPABILITY_TAXONOMY.md §1.1 for shape rationale (multi-label
 * support so models that output k-top classes per box can surface them
 * all, not just argmax).
 *
 * @hide
 */
parcelable BoundingBox {
    int x;               // top-left x, pixel
    int y;               // top-left y, pixel
    int width;           // pixel
    int height;          // pixel
    String[] labels;     // parallel with scores, descending
    float[] scores;      // 0.0-1.0
}
