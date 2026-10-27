package com.nevcortex.prism.sensors

import android.content.Context
import com.nevcortex.prism.model.SensorKind
import com.nevcortex.prism.model.SensorStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Facade over [SensorSpecs] + [ImuCollector]. The app holds a single instance (see
 * [com.nevcortex.prism.di.AppContainer]) and starts/stops it as needed.
 */
class SensorRepository(context: Context) {

    private val specs = SensorSpecs(context)
    private val collector = ImuCollector(specs.manager(), specs)

    fun enumerate(kinds: List<SensorKind> = SensorKind.values().toList()): List<SensorStatus> =
        specs.enumerate(kinds)

    fun maxRateHz(kind: SensorKind): Int = specs.maxRateHz(kind)

    val stats: StateFlow<Map<SensorKind, com.nevcortex.prism.model.ImuStreamStats>> = collector.stats

    fun plotBuffer(kind: SensorKind) = collector.plotBuffers[kind]

    fun collector(): ImuCollector = collector

    /** Start listening for [kinds] at the requested rate. */
    fun start(kinds: List<SensorKind>, rateHz: Int) = collector.start(kinds, rateHz)

    fun setRecording(on: Boolean) = collector.setRecording(on)

    fun stop() = collector.stop()
}
