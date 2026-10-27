package com.nevcortex.prism.update

import com.nevcortex.prism.BuildConfig
import org.json.JSONObject

/**
 * Explicit update states. Nothing pretends an update is present, downloaded, or installed before
 * it actually is. Download progress is reported by bytes, and a completed download is only
 * "ready" after its declared size and SHA-256 are verified.
 */
enum class UpdatePhase {
    NOT_CONFIGURED,
    READY,
    RECORDING_ACTIVE,
    CHECKING,
    LATEST,
    AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    INSTALL_READY,
    INSTALL_PROMPTED,
    NEED_INSTALL_PERMISSION,
    CANCELLED,
    FAILED
}

/** Machine-readable update failures so the UI can show plain language and keep raw detail apart. */
enum class UpdateFailure {
    NONE,
    BAD_ADDRESS,
    UNREACHABLE,
    TIMEOUT,
    MALFORMED,
    WRONG_APP,
    NOT_NEWER,
    UNSUPPORTED_VERSION,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    INCOMPLETE,
    STORAGE,
    INSTALL_PERMISSION,
    INSTALL_START,
    RECORDING_ACTIVE,
    UNKNOWN
}

/** One versioned APK on the PC updater, described by the PRISM Update v1 manifest. */
data class UpdateManifest(
    val applicationId: String,
    val versionName: String,
    val versionCode: Int,
    val apkFileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: String,
    val minSupportedVersionCode: Int = 0
)

/** Update service defaults. Kept apart from the streaming port 47777 so the two never collide. */
object UpdateDefaults {
    const val DEFAULT_PORT = 47778
}

/**
 * Full status shown on the Settings "App Updates" card. `installedVersionName/Code` come from
 * this build; `latest*` fields are what the connected server actually offered.
 */
data class UpdateStatus(
    val phase: UpdatePhase = UpdatePhase.NOT_CONFIGURED,
    val host: String = "",
    val port: Int = UpdateDefaults.DEFAULT_PORT,
    val installedVersionName: String = BuildConfig.VERSION_NAME,
    val installedVersionCode: Int = BuildConfig.VERSION_CODE,
    val latest: UpdateManifest? = null,
    val manifestUrl: String = "",
    val failure: UpdateFailure = UpdateFailure.NONE,
    val failureDetail: String? = null,
    val technicalDetail: String? = null,
    /** 0..100 during download; -1 otherwise. */
    val downloadPercent: Int = -1,
    val downloadedBytes: Long = 0L,
    val verifiedAt: Long = 0L,
    val lastCheckAtMs: Long = 0L,
    val lastCheckSucceeded: Boolean = false
)

/** Very small update manifest validator. All logic here is JSON + string work for JVM tests. */
object UpdateManifestParser {

    const val FORMAT_NAME = "prism-update"
    const val FORMAT_VERSION = 1

    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private const val MAX_NOTES_CHARS = 4000

    /** The Android-side filename must be a plain basename; the PC decides where files live. */
    fun isSafeFileName(name: String): Boolean {
        if (name.isEmpty() || name.length > 128 || name != name.trim()) return false
        if (name.contains('/') || name.contains('\\')) return false
        if (name.contains("..")) return false
        return name.endsWith(".apk", ignoreCase = true)
    }

    fun parse(text: String): UpdateManifest? = runCatching {
        val o = JSONObject(text)
        if (o.optString("format") != FORMAT_NAME) return@runCatching null
        if (o.optInt("formatVersion", -1) != FORMAT_VERSION) return@runCatching null
        val appName = o.optString("applicationId").ifBlank { return@runCatching null }
        val versionName = o.optString("versionName").ifBlank { return@runCatching null }
        val versionCode = o.optInt("versionCode", 0)
        val apk = o.optString("apk").ifBlank { return@runCatching null }
        val sha256 = o.optString("sha256").ifBlank { return@runCatching null }
        val sizeBytes = o.optLong("sizeBytes", 0L)
        val notes = o.optString("notes")
        val minSupported = o.optInt("minSupportedVersionCode", 0)
        if (versionCode <= 0) return@runCatching null
        if (sizeBytes <= 0L) return@runCatching null
        if (!SHA256.matches(sha256)) return@runCatching null
        if (!isSafeFileName(apk)) return@runCatching null
        UpdateManifest(
            applicationId = appName,
            versionName = versionName.take(64),
            versionCode = versionCode,
            apkFileName = apk,
            sha256 = sha256.lowercase(),
            sizeBytes = sizeBytes,
            releaseNotes = notes.take(MAX_NOTES_CHARS),
            minSupportedVersionCode = minSupported
        )
    }.getOrNull()

    /**
     * The manifest plus the installed app together decide whether an update truly exists. A
     * manifest for another application, or for this build or earlier, is not an update.
     */
    fun validateAgainst(
        manifest: UpdateManifest,
        installedApplicationId: String,
        installedVersionCode: Int
    ): UpdateFailure {
        if (manifest.applicationId != installedApplicationId) return UpdateFailure.WRONG_APP
        if (manifest.versionCode <= installedVersionCode) return UpdateFailure.NOT_NEWER
        if (manifest.minSupportedVersionCode > 0 && installedVersionCode < manifest.minSupportedVersionCode) {
            return UpdateFailure.UNSUPPORTED_VERSION
        }
        return UpdateFailure.NONE
    }
}
