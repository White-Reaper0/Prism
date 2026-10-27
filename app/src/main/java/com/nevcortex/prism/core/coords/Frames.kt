package com.nevcortex.prism.core.coords

/**
 * Coordinate frames for P.R.I.S.M.
 *
 * The Android device axes (X right, Y up, Z out of screen when held in portrait) are NOT the same
 * as a drone body frame or a VIO world frame. We never assume they are. Every recorded sample is
 * tagged with its source frame, and downstream consumers apply an explicit [MountingConfig]
 * transform.
 *
 * Frame naming:
 *  - ANDROID_DEVICE: raw [SensorEvent] / camera axes as reported by the hardware.
 *  - CAMERA: camera optical frame (Z forward, X right, Y down — OpenCV/VIO convention).
 *  - BODY: user-configurable experiment/vehicle body frame.
 *  - WORLD: local navigation / VIO world frame. Origin and gravity alignment are defined by the
 *    selected VIO implementation, not assumed here.
 */
enum class Frame {
    ANDROID_DEVICE,
    CAMERA,
    BODY,
    WORLD
}

/**
 * How the phone is physically mounted for the experiment. The user picks an orientation that
 * describes how the device axes map to the experiment body frame. The transform is applied to
 * raw IMU streams to express them in BODY before being fed to any algorithm.
 *
 * `rotation` is a row-major 3x3 matrix that converts a vector expressed in ANDROID_DEVICE (portrait
 * reference) to BODY. Identity means "device portrait == body frame".
 */
data class MountingConfig(
    val name: String,
    val rotation: FloatArray,      // 9 elements, row-major, orthonormal
    val description: String,
    val cameraToDevice: FloatArray  // 3x3, row-major: camera extrinsic rotation w.r.t. device
) {
    init {
        require(rotation.size == 9) { "rotation must be 9 elements" }
        require(cameraToDevice.size == 9) { "cameraToDevice must be 9 elements" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MountingConfig) return false
        return name == other.name && rotation.contentEquals(other.rotation) &&
            cameraToDevice.contentEquals(other.cameraToDevice)
    }

    override fun hashCode(): Int {
        var h = name.hashCode()
        h = 31 * h + rotation.contentHashCode()
        h = 31 * h + cameraToDevice.contentHashCode()
        return h
    }

    companion object {
        /** Identity: device portrait axes treated as body axes. */
        val IDENTITY = MountingConfig(
            name = "device_as_body",
            rotation = identity3(),
            cameraToDevice = cameraToDevicePortrait(),
            description = "Device portrait axes treated as body frame (no rotation). " +
                "Use only for quick tests; not representative of drone mounting."
        )

        /** Phone laid flat on its back, screen up, top pointing forward (+X body = forward). */
        val FLAT_SCREEN_UP = MountingConfig(
            name = "flat_screen_up",
            // device(x,y,z) -> body(forward=x', right=y', down=z')
            // device portrait: x=right, y=up, z=out(screen)
            // body flat: forward=+x', right=+y', down=+z'
            // forward=+x' == device +X(right)? We want forward = device +Y(up in portrait)? 
            // This is a best-effort default; user must verify for their rig.
            rotation = floatArrayOf(
                0f, 1f, 0f,
                1f, 0f, 0f,
                0f, 0f, -1f
            ),
            cameraToDevice = cameraToDevicePortrait(),
            description = "Phone flat, screen up, top edge pointing forward. " +
                "VERIFY for your rig — axis mapping is a default guess."
        )

        fun identity3(): FloatArray = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )

        /**
         * Camera optical frame (Z forward, X right, Y down) relative to Android device portrait
         * frame (X right, Y up, Z out of screen). For a typical back camera this is approximately
         * identity-ish: camera Z (forward) == device +Z (out of screen), camera X (right) ==
         * device +X (right), camera Y (down) == device -Y. Treated as UNKNOWN until calibrated.
         */
        fun cameraToDevicePortrait(): FloatArray = floatArrayOf(
            1f, 0f, 0f,
            0f, -1f, 0f,
            0f, 0f, 1f
        )
    }
}

/** Apply a row-major 3x3 rotation to a 3-vector. */
fun FloatArray.apply3(v: FloatArray): FloatArray {
    require(size == 9 && v.size == 3)
    return floatArrayOf(
        this[0] * v[0] + this[1] * v[1] + this[2] * v[2],
        this[3] * v[0] + this[4] * v[1] + this[5] * v[2],
        this[6] * v[0] + this[7] * v[1] + this[8] * v[2]
    )
}
