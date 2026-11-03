package com.nevcortex.prism.persistence

import com.nevcortex.prism.camera.CameraLens

/**
 * One-time rewrite of lensFacing preferences written by the old broken mapping
 * (stored 0 = "back", 1 = "front"). CameraX actually defines LENS_FACING_FRONT = 0 and
 * LENS_FACING_BACK = 1, so every old value must swap. Kept pure for JVM unit tests.
 */
internal object LensCompat {
    fun migrateStoredLensFacing(old: Int): Int =
        if (old == 1) CameraLens.FRONT else CameraLens.BACK
}
