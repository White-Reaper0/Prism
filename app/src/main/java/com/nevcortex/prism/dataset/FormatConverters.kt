package com.nevcortex.prism.dataset

import com.nevcortex.prism.model.CalibrationBundle
import com.nevcortex.prism.model.SensorKind
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Structural converters from a P.R.I.S.M. session into dataset formats used by common VIO/SLAM
 * tools (EuRoC-style). These convert timestamps and values faithfully; they do NOT verify
 * synchronization and they do NOT invent calibration. Missing calibration is written as zeros
 * with a note so downstream tools fail loudly rather than silently using wrong numbers.
 */
object FormatConverters {

    /**
     * Produce a EuRoC-structured dataset under [outDir]/mav0/.
     *   mav0/cam0/timestamps.txt   — one camera frame timestamp (ns) per line
     *   mav0/imu0/data.csv         — ns,gx,gy,gz,ax,ay,az (rad/s, m/s^2)
     *   mav0/cam0/sensor.yaml      — intrinsics (zeros + note if not calibrated)
     *   mav0/imu0/sensor.yaml      — IMU noise (zeros + note if unknown)
     *   mav0/CONVERSION_NOTES.txt  — what was and was not verified
     */
    fun toEuRoCLike(sessionDir: File, outDir: File, cal: CalibrationBundle?) {
        val mav = File(outDir, "mav0").also { it.mkdirs() }
        val cam0 = File(mav, "cam0").also { it.mkdirs() }
        val imu0 = File(mav, "imu0").also { it.mkdirs() }

        // camera timestamps
        File(cam0, "timestamps.txt").printWriter().use { w ->
            readColumn(File(sessionDir, "frames.csv"), 1) { ts -> w.println(ts) }
        }

        // imu data: ns, gx,gy,gz, ax,ay,az
        val accel = readRows(File(sessionDir, "imu/${SensorKind.ACCELEROMETER.name.lowercase()}.csv"))
        val gyro = readRows(File(sessionDir, "imu/${SensorKind.GYROSCOPE.name.lowercase()}.csv"))
        File(imu0, "data.csv").printWriter().use { w ->
            w.println("#timestamp [ns],w_RS_S_x [rad s^-1],w_RS_S_y,w_RS_S_z,a_RS_S_x [m s^-2],a_RS_S_y,a_RS_S_z")
            val n = minOf(accel.size, gyro.size)
            for (i in 0 until n) {
                val a = accel[i]; val g = gyro[i]
                // columns: 1=timestamp_ns, 4..6 = x,y,z
                w.println("${g[1]},${g[4]},${g[5]},${g[6]},${a[4]},${a[5]},${a[6]}")
            }
        }

        // cam0 sensor.yaml
        File(cam0, "sensor.yaml").writeText(buildSensorYaml(cal))

        // imu0 sensor.yaml — noise unknown; zeros + note
        File(imu0, "sensor.yaml").writeText(
            """
            % YAML
            sensor_type: imu
            comment: P.R.I.S.M. export. IMU noise parameters UNKNOWN (not estimated here).
                     Replace with measured/characterized values before tuning a VIO pipeline.
            gyroscope_noise_density: 0.0
            gyroscope_random_walk: 0.0
            accelerometer_noise_density: 0.0
            accelerometer_random_walk: 0.0
            T_BS: !!opencv-matrix
              rows: 4
              cols: 4
              dt: d
              data: [1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]
            """.trimIndent()
        )

        File(mav, "CONVERSION_NOTES.txt").writeText(buildString {
            appendLine("P.R.I.S.M. -> EuRoC-style conversion")
            appendLine("-----------------------------------")
            appendLine("This is a STRUCTURAL conversion only.")
            appendLine("- Timestamps are sensor timestamps in nanoseconds (camera HAL clock for cam,")
            appendLine("  sensor clock for imu). Camera/IMU synchronization was NOT verified. Apply a")
            appendLine("  measured time offset if your tool requires a single clock.")
            appendLine("- IMU noise parameters are UNKNOWN and set to zero; replace with characterized")
            appendLine("  values from a P.R.I.S.M. IMU calibration session.")
            appendLine("- Camera intrinsics are included only if a calibration was entered; otherwise")
            appendLine("  they are zeros with a note. Do not run metric VIO without real intrinsics.")
            appendLine("- No extrinsics calibration is provided; T_BS is identity placeholder.")
        })
    }

    private fun buildSensorYaml(cal: CalibrationBundle?): String {
        val cam = cal?.camera
        val has = cam != null && !cam!!.fx.isNaN()
        val fx = cam?.fx ?: 0f
        val fy = cam?.fy ?: 0f
        val cx = cam?.cx ?: 0f
        val cy = cam?.cy ?: 0f
        return buildString {
            appendLine("% YAML")
            appendLine("sensor_type: camera")
            appendLine("comment: ${if (has) "intrinsics from ${cam!!.provenance.name}/${cam!!.method}" else "NO intrinsics available — zeros placeholder; calibrate before metric use."}")
            appendLine("resolution: [${cam?.imageWidth ?: 0}, ${cam?.imageHeight ?: 0}]")
            appendLine("camera_model: pinhole")
            appendLine("intrinsics: [$fx, $fy, $cx, $cy]")
            appendLine("distortion_model: radial-tangential")
            appendLine("distortion_coefficients: ${(cam?.distortionCoeffs ?: FloatArray(0)).joinToString(",")}")
        }
    }

    private fun readColumn(file: File, col: Int, emit: (String) -> Unit) {
        if (!file.exists()) return
        BufferedReader(InputStreamReader(file.inputStream())).use { r ->
            var line = r.readLine()
            while (line != null) {
                if (!line.startsWith("#") && line.isNotBlank() && !line.startsWith("frame_index")) {
                    val parts = line.split(",")
                    if (parts.size > col) emit(parts[col])
                }
                line = r.readLine()
            }
        }
    }

    private fun readRows(file: File): List<List<String>> {
        if (!file.exists()) return emptyList()
        val rows = ArrayList<List<String>>()
        BufferedReader(InputStreamReader(file.inputStream())).use { r ->
            var line = r.readLine()
            while (line != null) {
                if (!line.startsWith("#") && line.isNotBlank() && !line.startsWith("sequence")) {
                    rows.add(line.split(","))
                }
                line = r.readLine()
            }
        }
        return rows
    }
}
