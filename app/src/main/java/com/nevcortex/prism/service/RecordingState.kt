package com.nevcortex.prism.service

import com.nevcortex.prism.model.SessionSummary

enum class RecordingPhase {
    IDLE,
    STARTING,
    RECORDING,
    STOPPING,
    SAVED,
    FAILED
}

/**
 * Explicit recording state owned by the container and updated by [RecordingService]. The UI never
 * has to guess whether a recording really started or whether the file was saved.
 */
data class RecordingState(
    val phase: RecordingPhase = RecordingPhase.IDLE,
    val summary: SessionSummary? = null,
    val detail: String? = null
)
