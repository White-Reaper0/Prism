package com.nevcortex.prism.dataset

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.nevcortex.prism.model.SessionSummary
import com.nevcortex.prism.model.SensorKind
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Dataset export. Raw session data is immutable; exports are DERIVED copies written to the
 * session's export/ dir (and never overwrite the raw per-kind imu CSVs or frames.csv).
 *
 * Exporters are structural converters. They faithfully copy timestamps and values but they do NOT
 * verify synchronization or invent calibration. The export README repeats the sync-not-verified
 * note from the session.
 */
class DatasetExporter(private val context: Context) {

    /** Build a portable copy of the session under [outDir] with combined IMU CSV + metadata. */
    fun exportPortable(summary: SessionSummary, outDir: File): File {
        outDir.mkdirs()
        val sessionDir = File(summary.sessionDir)

        // combined IMU CSV across accel + gyro (the streams VIO tools usually need)
        combineImu(sessionDir, outDir)

        // copy frames manifest
        val framesSrc = File(sessionDir, "frames.csv")
        if (framesSrc.exists()) framesSrc.copyTo(File(outDir, "frames.csv"), overwrite = true)

        // copy session.json
        val sessionSrc = File(sessionDir, "session.json")
        if (sessionSrc.exists()) sessionSrc.copyTo(File(outDir, "session.json"), overwrite = true)

        // human-readable metadata
        val meta = StringBuilder()
        meta.appendLine("P.R.I.S.M. portable export -- session ${summary.id}")
        meta.appendLine("Label: ${summary.label}")
        meta.appendLine("Duration ns: ${summary.durationNs}")
        meta.appendLine("Camera frames: ${summary.sync.cameraFrameCount}")
        meta.appendLine("IMU samples: ${summary.sync.imuSampleCount}")
        meta.appendLine("Synchronization verified: ${summary.sync.synchronizationVerified}")
        meta.appendLine(summary.sync.verificationNote)
        meta.appendLine("Validation notes:")
        summary.validationNotes.forEach { meta.appendLine("  - $it") }
        File(outDir, "README.txt").writeText(meta.toString())

        // zip the export dir
        val zip = File(outDir.parentFile ?: outDir, "${outDir.name}.zip")
        zipDir(outDir, zip)
        return zip
    }

    /**
     * Combined IMU CSV: sensor_timestamp_ns, ax, ay, az, gx, gy, gz, arrival_elapsed_ns.
     * Rows are aligned by index (both streams at similar rates). Raw timestamps preserved.
     */
    private fun combineImu(sessionDir: File, outDir: File) {
        val accel = readImu(File(sessionDir, "imu/${SensorKind.ACCELEROMETER.name.lowercase()}.csv"))
        val gyro = readImu(File(sessionDir, "imu/${SensorKind.GYROSCOPE.name.lowercase()}.csv"))
        val out = File(outDir, "imu.csv")
        out.printWriter().use { w ->
            w.println("# P.R.I.S.M. combined IMU. Raw values. Camera/IMU sync NOT verified.")
            w.println("sensor_timestamp_ns,ax,ay,az,gx,gy,gz,arrival_elapsed_ns")
            val aIter = accel.iterator()
            val gIter = gyro.iterator()
            while (aIter.hasNext() && gIter.hasNext()) {
                val a = aIter.next()
                val g = gIter.next()
                // columns: 0=seq, 1=sensor_ts, 2=arrival, 3=acc, 4=x, 5=y, 6=z
                if (a.size >= 7 && g.size >= 7) {
                    w.println("${a[1]},${a[4]},${a[5]},${a[6]},${g[4]},${g[5]},${g[6]},${a[2]}")
                }
            }
        }
    }

    private fun readImu(file: File): List<List<String>> {
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

    fun shareIntent(zip: File): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", zip
            )
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun zipDir(src: File, zip: File) {
        zip.outputStream().use { fos ->
            java.util.zip.ZipOutputStream(fos).use { zos ->
                src.walkTopDown().forEach { f ->
                    if (f.isFile) {
                        val name = f.relativeTo(src).path.replace(File.separatorChar, '/')
                        zos.putNextEntry(java.util.zip.ZipEntry(name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
    }
}
