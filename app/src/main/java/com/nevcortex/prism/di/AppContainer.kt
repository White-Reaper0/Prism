package com.nevcortex.prism.di

import android.content.Context
import android.os.Build
import com.nevcortex.prism.BuildConfig
import com.nevcortex.prism.camera.CameraRepository
import com.nevcortex.prism.camera.FrameCollector
import com.nevcortex.prism.calibration.CalibrationStore
import com.nevcortex.prism.calibration.CameraCalibration
import com.nevcortex.prism.calibration.ImuCalibration
import com.nevcortex.prism.dataset.DatasetExporter
import com.nevcortex.prism.evidence.FileBeliefLedger
import com.nevcortex.prism.evidence.ScarTissueMemory
import com.nevcortex.prism.link.LinkClient
import com.nevcortex.prism.mapping.NoOpLocalMap
import com.nevcortex.prism.model.SessionConfig
import com.nevcortex.prism.persistence.SettingsStore
import com.nevcortex.prism.playback.SessionPlayer
import com.nevcortex.prism.recording.SessionRecorder
import com.nevcortex.prism.recording.SessionStore
import com.nevcortex.prism.sensors.SensorRepository
import com.nevcortex.prism.service.RecordingState
import com.nevcortex.prism.stream.LiveStreamSession
import com.nevcortex.prism.tracking.ArCoreTrackingEngine
import com.nevcortex.prism.tracking.TrackingRepository
import com.nevcortex.prism.vio.NoOpVisualOdometry
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manual dependency container. One instance lives on [com.nevcortex.prism.PrismApp]. It owns the
 * single [FrameCollector] shared between live preview and recording so frame timestamps stay
 * consistent, and a single [SessionRecorder].
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val sensorRepository = SensorRepository(appContext)
    val cameraRepository = CameraRepository(appContext)
    val frameCollector = FrameCollector()

    val sessionStore = SessionStore(appContext)
    val calibrationStore = CalibrationStore(appContext)
    val settingsStore = SettingsStore(appContext)

    /** Single live link to the PC receiver; one connection at a time. */
    val link: LinkClient = LinkClient(
        deviceManufacturer = Build.MANUFACTURER.orEmpty(),
        deviceModel = Build.MODEL.orEmpty(),
        appVersion = BuildConfig.VERSION_NAME
    ).also { it.configure(settingsStore.pcHost, settingsStore.pcPort) }

    val liveStream: LiveStreamSession = LiveStreamSession(this, link)

    val trackingRepository = TrackingRepository()
    val arCoreTracking = ArCoreTrackingEngine(appContext, trackingRepository)

    /** Live-link app updates over the same trusted LAN, but on their own port. */
    val updateManager: com.nevcortex.prism.update.UpdateManager =
        com.nevcortex.prism.update.UpdateManager(
            appContext,
            endpointProvider = { settingsStore.updateHost to settingsStore.updatePort },
            isRecordingActive = {
                val p = recordingState.value.phase
                p == com.nevcortex.prism.service.RecordingPhase.STARTING ||
                    p == com.nevcortex.prism.service.RecordingPhase.RECORDING ||
                    p == com.nevcortex.prism.service.RecordingPhase.STOPPING
            }
        ).apply {
            seedLastCheckAt(settingsStore.lastUpdateCheckAtMs)
            lastCheckListener = { ok, wallMs ->
                if (ok) settingsStore.lastUpdateCheckAtMs = wallMs
            }
        }

    /** Recording lifecycle is reported explicitly instead of inferred from a timer. */
    val recordingState = MutableStateFlow(RecordingState())

    val imuCalibration = ImuCalibration(appContext)
    val cameraCalibration = CameraCalibration(appContext)

    val datasetExporter = DatasetExporter(appContext)
    val sessionPlayer = SessionPlayer()

    val beliefLedger = FileBeliefLedger(java.io.File(appContext.filesDir, "evidence"))
    val scarTissueMemory = ScarTissueMemory(java.io.File(appContext.filesDir, "evidence"))
    val localMap = NoOpLocalMap()
    val visualOdometry = NoOpVisualOdometry()

    val recorder: SessionRecorder = SessionRecorder(
        sensorRepo = sensorRepository,
        cameraRepo = cameraRepository,
        frameCollector = frameCollector,
        store = sessionStore,
        currentCalibration = { calibrationStore.current() }
    )

    /** Stashed by the UI immediately before starting [com.nevcortex.prism.service.RecordingService]. */
    var pendingSessionConfig: SessionConfig? = null

    /** Populated by [com.nevcortex.prism.service.RecordingService] after a session stops, so the
     *  UI can show validation/export controls. */
    @Volatile
    var lastSessionSummary: com.nevcortex.prism.model.SessionSummary? = null
}
