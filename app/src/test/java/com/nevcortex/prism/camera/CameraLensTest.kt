package com.nevcortex.prism.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure front/rear lens rules. These run on the JVM and pin the contract the camera binder,
 * the live-stream session, and the Settings/Live Studio UI all rely on. The int values match
 * androidx.camera.core.CameraSelector (BACK = 0, FRONT = 1).
 */
class CameraLensTest {

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
        assertTrue(CameraLens.reported(lenses).containsAll(listOf(0, 1)))
    }

    @Test
    fun `single camera phones disable the missing lens instead of hard-coding both`() {
        val backOnly = listOf(CameraLens.BACK)
        assertTrue(CameraLens.selectable(CameraLens.BACK, backOnly))
        assertFalse(CameraLens.selectable(CameraLens.FRONT, backOnly))
        assertTrue(CameraLens.selectable(CameraLens.FRONT, listOf(CameraLens.FRONT)))
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
        // Matches CameraRepository.availableLenses()'s conservative empty fallback.
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
}
