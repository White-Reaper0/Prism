package com.nevcortex.prism.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.vector.ImageVector
import com.nevcortex.prism.R

/** The three primary destinations. Everything technical is nested under Diagnostics. */
enum class Dest(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    HOME("home", R.string.nav_home, Icons.Filled.Home),
    STUDIO("studio", R.string.nav_live_studio, Icons.Filled.Videocam),
    DIAGNOSTICS("diagnostics", R.string.nav_diagnostics, Icons.Filled.Speed);

    companion object {
        val ordered: List<Dest> = entries.toList()
    }
}

/** Nested routes reached from Diagnostics or from Live Studio. */
object Routes {
    const val HOME = "home"
    const val STUDIO = "studio"
    const val DIAGNOSTICS = "diagnostics"

    const val CONNECTION = "diagnostics/connection"
    const val MOTION = "diagnostics/motion"
    const val CAMERA = "diagnostics/camera"
    const val TRACKING = "diagnostics/tracking"
    const val RECORDING = "diagnostics/recording"
    const val RECORDINGS = "diagnostics/recordings"
    const val PIPELINE = "diagnostics/pipeline"
    const val SETTINGS = "diagnostics/settings"
    const val MAPPING = "diagnostics/mapping"
    const val MODELS = "diagnostics/models"
    const val EVIDENCE = "diagnostics/evidence"

    @StringRes
    fun title(route: String): Int = when (route) {
        CONNECTION -> R.string.diag_connection_title
        MOTION -> R.string.diag_motion_title
        CAMERA -> R.string.diag_camera_title
        TRACKING -> R.string.diag_tracking_title
        RECORDING -> R.string.diag_recording_title
        RECORDINGS -> R.string.diag_recordings_title
        PIPELINE -> R.string.diag_pipeline_title
        SETTINGS -> R.string.diag_settings_title
        MAPPING -> R.string.diag_mapping_title
        MODELS -> R.string.diag_models_title
        EVIDENCE -> R.string.diag_evidence_title
        else -> R.string.app_name
    }
}
