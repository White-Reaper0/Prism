package com.nevcortex.prism.mapping

/**
 * Local mapping interface. A real implementation requires the visual-inertial trajectory (for
 * camera poses) and perception outputs (depth/obstacles) — neither of which is produced on-device
 * yet (see [com.nevcortex.prism.vio] and [com.nevcortex.prism.perception]). This is a scaffold
 * so the UI can show honest "not yet available" status rather than a fake map.
 */
interface LocalMap {
    val available: Boolean
    val note: String
    fun reset()
}

class NoOpLocalMap : LocalMap {
    override val available: Boolean = false
    override val note: String =
        "Mapping requires a VIO trajectory + perception depth/obstacles. Neither is produced " +
            "on-device yet; run the PC pipeline and feed poses+perception back, or integrate an " +
            "on-device VIO/depth runtime first."
    override fun reset() {}
}
