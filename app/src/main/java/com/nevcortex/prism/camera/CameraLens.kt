package com.nevcortex.prism.camera

/**
 * Pure front/rear lens logic, kept free of androidx/camera classes so it is unit-testable on the
 * JVM. The int values intentionally match androidx.camera.core.CameraSelector AND the underlying
 * android.hardware.camera2.CameraCharacteristics / CameraMetadata constants — verified against
 * camera-core 1.4.1 (`javap -v`): LENS_FACING_FRONT = 0, LENS_FACING_BACK = 1, EXTERNAL = 2.
 *
 * Historically this file assumed BACK = 0 / FRONT = 1, which bound the OPPOSITE camera of the
 * selector's label. Do not reintroduce that mapping.
 */
object CameraLens {

    /** LENS_FACING_FRONT (androidx.camera.core.CameraSelector.LENS_FACING_FRONT) */
    const val FRONT = 0

    /** LENS_FACING_BACK (androidx.camera.core.CameraSelector.LENS_FACING_BACK) */
    const val BACK = 1

    /** Map any stored value onto a real lens facing; unknown values fall back to the rear. */
    fun normalize(facing: Int): Int = if (facing == FRONT) FRONT else BACK

    /** The device-reported lens list wins over anything the app assumes; BACK always first. */
    fun reported(availableLenses: List<Int>): List<Int> {
        val known = availableLenses.distinct().filter { it == BACK || it == FRONT }
        return buildList {
            if (BACK in known) add(BACK)
            if (FRONT in known) add(FRONT)
        }
    }

    /**
     * Whether a lens may be offered as a choice right now. Never hard-codes that both lenses
     * exist: single-lens devices (and cameras that fail to appear) are simply not offered.
     */
    fun selectable(facing: Int, availableLenses: List<Int>): Boolean = facing in reported(availableLenses)

    /**
     * The lens a bind should actually use: the stored preference when the device reports it,
     * otherwise the first lens it does report (BACK first), otherwise the rear default. This
     * keeps persisted settings from binding a lens the device does not have.
     */
    fun resolve(facing: Int, availableLenses: List<Int>): Int {
        val known = reported(availableLenses)
        return when {
            facing in known -> facing
            known.isNotEmpty() -> known.first()
            else -> BACK
        }
    }
}
