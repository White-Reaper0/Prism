package com.nevcortex.prism.sync

import com.nevcortex.prism.model.FrameSample
import com.nevcortex.prism.model.ImuSample

/**
 * Tools to express source-clock timestamps in the alignment clock ([Domain.ELAPSED]) using a
 * MEASURED offset. These produce DERIVED values only; the raw source timestamps are always kept
 * intact in the dataset (see [com.nevcortex.prism.dataset]). Never substitute a derived timestamp
 * for the raw one destructively.
 *
 * The offset used here is the median of (arrival - source) over the session. If [ClockAlignment]
 * says the clocks do not share an origin (large jitter or unverified), alignment results are
 * approximate and must be labeled as such downstream.
 */
object TimeAlignment {

    /** Express a source timestamp in the alignment clock using an estimated offset. */
    fun toAligned(sourceNs: Long, offsetNs: Long): Long = sourceNs + offsetNs

    /** Convenience: align an IMU sample's source timestamp. */
    fun ImuSample.alignedTimestamp(offsetNs: Long): Long = sensorTimestampNs + offsetNs

    /** Convenience: align a frame sample's source timestamp. */
    fun FrameSample.alignedTimestamp(offsetNs: Long): Long = cameraTimestampNs + offsetNs

    /**
     * Find the nearest IMU sample (by aligned timestamp) to a frame. O(n) — fine for offline
     * playback, not for per-frame hot paths.
     */
    fun nearest(imu: List<ImuSample>, frameAlignedNs: Long, offsetNs: Long): ImuSample? {
        if (imu.isEmpty()) return null
        var best = imu[0]
        var bestDist = kotlin.math.abs(best.alignedTimestamp(offsetNs) - frameAlignedNs)
        for (s in imu) {
            val d = kotlin.math.abs(s.alignedTimestamp(offsetNs) - frameAlignedNs)
            if (d < bestDist) { bestDist = d; best = s }
        }
        return best
    }
}
