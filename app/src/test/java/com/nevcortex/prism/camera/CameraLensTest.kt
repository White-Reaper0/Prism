package com.nevcortex.prism.camera

import com.nevcortex.prism.persistence.LensCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Front/rear lens rules. These run on the JVM and pin the contract the camera binder, the
 * live-stream session, and the Settings/Live Studio UI rely on.
 *
 * The int values MUST match androidx.camera.core.CameraSelector — verified against
 * camera-core-1.4.1 with `javap -v`: LENS_FACING_FRONT = 0, LENS_FACING_BACK = 1. A previous
 * revision of this file encoded BACK = 0 / FRONT = 1, which bound the opposite camera.
 */
class CameraLensTest {

    @Test
    fun `lens constants match CameraX - BACK is 1, FRONT is 0`() {
        assertEquals(1, CameraLens.BACK)
        assertEquals(0, CameraLens.FRONT)
    }

    @Test
    fun `normalize maps unknown values onto the rear default`() {
        assertEquals(CameraLens.BACK, CameraLens.normalize(CameraLens.BACK))
        assertEquals(CameraLens.FRONT, CameraLens.normalize(CameraLens.FRONT))
        assertEquals(CameraLens.BACK, CameraLens.normalize(9))    // unknown value
        assertEquals(CameraLens.BACK, CameraLens.normalize(-1))   // unknown value
    }

    @Test
    fun `dual camera phones offer both lenses`() {
        val lenses = listOf(CameraLens.BACK, CameraLens.FRONT)
        assertTrue(CameraLens.selectable(CameraLens.BACK, lenses))
        assertTrue(CameraLens.selectable(CameraLens.FRONT, lenses))
        assertEquals(listOf(1, 0), CameraLens.reported(lenses))    // BACK first
    }

    @Test
    fun `single camera phones disable the missing lens instead of hard-coding both`() {
        val backOnly = listOf(CameraLens.BACK)
        assertTrue(CameraLens.selectable(CameraLens.BACK, backOnly))
        assertFalse(CameraLens.selectable(CameraLens.FRONT, backOnly))
        assertEquals(CameraLens.BACK, CameraLens.resolve(CameraLens.FRONT, backOnly))
    }

    @Test
    fun `reported keeps BACK first even when FRONT is reported first`() {
        assertEquals(
            listOf(CameraLens.BACK, CameraLens.FRONT),
            CameraLens.reported(listOf(CameraLens.FRONT, CameraLens.BACK))
        )
    }

    @Test
    fun `resolve keeps the stored preference when the device reports it`() {
        val lenses = listOf(CameraLens.BACK, CameraLens.FRONT)
        assertEquals(CameraLens.BACK, CameraLens.resolve(CameraLens.BACK, lenses))
        assertEquals(CameraLens.FRONT, CameraLens.resolve(CameraLens.FRONT, lenses))
    }

    @Test
    fun `resolve degrades to a reported lens when the stored preference is missing`() {
        // A phone with a broken front camera must not try to bind "front".
        assertEquals(
            CameraLens.BACK,
            CameraLens.resolve(CameraLens.FRONT, listOf(CameraLens.BACK))
        )
        assertEquals(
            CameraLens.FRONT,
            CameraLens.resolve(CameraLens.BACK, listOf(CameraLens.FRONT))
        )
    }

    @Test
    fun `resolve falls back to rear when nothing is reported`() {
        assertEquals(CameraLens.BACK, CameraLens.resolve(CameraLens.FRONT, emptyList()))
        assertEquals(CameraLens.BACK, CameraLens.resolve(CameraLens.BACK, emptyList()))
    }

    @Test
    fun `reported filters values that are not real lens facings`() {
        assertEquals(
            listOf(CameraLens.BACK, CameraLens.FRONT),
            CameraLens.reported(listOf(CameraLens.FRONT, 7, CameraLens.BACK, CameraLens.FRONT))
        )
    }

    @Test
    fun `legacy stored lensFacing values migrate to the CameraX mapping`() {
        // Old encoding: 0 = back, 1 = front.  New encoding: FRONT = 0, BACK = 1.
        assertEquals(CameraLens.BACK, LensCompat.migrateStoredLensFacing(0))
        assertEquals(CameraLens.FRONT, LensCompat.migrateStoredLensFacing(1))
        // Any other legacy garbage resolves to rear.
        assertEquals(CameraLens.BACK, LensCompat.migrateStoredLensFacing(9))
    }
}
