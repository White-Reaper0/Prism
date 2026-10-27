package com.nevcortex.prism.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.nevcortex.prism.BuildConfig
import com.nevcortex.prism.core.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

/**
 * Local-network app updater: check -> download -> verify -> ask Android to install.
 *
 * Trust model: the updater runs on a *trusted development LAN* over plain HTTP. The manifest's
 * SHA-256 protects against a broken transfer, but a hostile PC could still serve anything, so the
 * app also checks the application identifier, version codes, declared size, and digest before it
 * ever offers installation. Nothing installs without Android's own confirmation dialog.
 * docs/APP_UPDATES.md documents the full threat model.
 *
 * The manager refuses to run while a synchronized recording is active: the caller provides
 * [isRecordingActive] and the manager reports [UpdatePhase.RECORDING_ACTIVE] instead of touching
 * the network.
 */
class UpdateManager(
    private val context: Context,
    private val endpointProvider: () -> Pair<String, Int>,
    private val isRecordingActive: () -> Boolean
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(UpdateStatus())
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    @Volatile private var downloadCancelled = false

    /** Optional persistence hook; the app stores the last successful check in SettingsStore. */
    @Volatile var lastCheckListener: ((ok: Boolean, wallClockMs: Long) -> Unit)? = null

    init {
        val (host, port) = endpointProvider()
        configure(host, port)
    }

    /** Seed the UI with the persisted endpoint without touching the network. */
    fun configure(host: String, port: Int) {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        val cleanHost = host.trim()
        val clamped = port.coerceIn(1, 65535)
        _status.update {
            val keep = it.phase == UpdatePhase.INSTALL_READY ||
                it.phase == UpdatePhase.DOWNLOADING ||
                it.phase == UpdatePhase.INSTALL_PROMPTED
            it.copy(
                host = cleanHost,
                port = clamped,
                phase = when {
                    keep -> it.phase
                    cleanHost.isBlank() -> UpdatePhase.NOT_CONFIGURED
                    it.phase == UpdatePhase.NOT_CONFIGURED -> UpdatePhase.READY
                    else -> it.phase
                },
                failure = UpdateFailure.NONE,
                failureDetail = null,
                technicalDetail = null
            )
        }
    }

    /** Seed the UI with a persisted last-check time when the app restarts. */
    fun seedLastCheckAt(wallClockMs: Long) {
        if (wallClockMs <= 0L) return
        _status.update { it.copy(lastCheckAtMs = wallClockMs, lastCheckSucceeded = true) }
    }

    /** Called when the App Updates section becomes visible, so stale phases do not linger. */
    fun onScreenOpened() {
        val (host, port) = endpointProvider()
        configure(host, port)
        // A verified download from an earlier app run is still usable for a *newer* version.
        // Once this running build already is that version (the install finished), the file has
        // served its purpose and is cleared, so the card never offers to "install" the same
        // version that is currently running.
        val code = latestVerifiedFileCode()
        if (code != null && code > BuildConfig.VERSION_CODE) {
            val phase = _status.value.phase
            if (phase == UpdatePhase.READY ||
                phase == UpdatePhase.NOT_CONFIGURED ||
                phase == UpdatePhase.CANCELLED ||
                phase == UpdatePhase.AVAILABLE
            ) {
                _status.update {
                    it.copy(
                        phase = UpdatePhase.INSTALL_READY,
                        technicalDetail = "verified file from an earlier session: $FILE_PREFIX-$code.apk"
                    )
                }
            }
        } else if (code != null) {
            runCatching {
                downDir().listFiles()?.forEach { f -> if (f.name.startsWith(FILE_PREFIX)) f.delete() }
            }
            val phase = _status.value.phase
            if (phase == UpdatePhase.INSTALL_READY ||
                phase == UpdatePhase.INSTALL_PROMPTED ||
                phase == UpdatePhase.VERIFYING
            ) {
                _status.update { it.copy(phase = UpdatePhase.READY, downloadPercent = -1) }
            }
        }
    }

    /** Reads the manifest over HTTP. Never runs while a recording is active. */
    fun checkForUpdates() {
        if (blockIfRecording()) return
        val (host, port) = endpointProvider()
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) {
            _status.update {
                it.copy(
                    host = "",
                    phase = UpdatePhase.FAILED,
                    failure = UpdateFailure.BAD_ADDRESS
                )
            }
            return
        }
        checkJob?.cancel()
        _status.update {
            it.copy(
                host = cleanHost,
                port = port,
                phase = UpdatePhase.CHECKING,
                failure = UpdateFailure.NONE,
                failureDetail = null,
                technicalDetail = null
            )
        }
        checkJob = scope.launch {
            val manifestUrl = "http://$cleanHost:$port/${MANIFEST_PATH}"
            try {
                val body = httpGetText(manifestUrl, MAX_MANIFEST_BYTES)
                val manifest = UpdateManifestParser.parse(body)
                    ?: throw MalformedManifestException("manifest.json is not PRISM Update v1")
                val mismatch = UpdateManifestParser.validateAgainst(
                    manifest,
                    installedApplicationId = BuildConfig.APPLICATION_ID,
                    installedVersionCode = BuildConfig.VERSION_CODE
                )
                when (mismatch) {
                    UpdateFailure.NONE -> Unit
                    // A real PRISM manifest saying "not newer" is the normal case: the phone
                    // is already running the newest build.
                    UpdateFailure.NOT_NEWER -> {
                        markCheckSucceeded(succeeded = true)
                        _status.update {
                            it.copy(
                                phase = UpdatePhase.LATEST,
                                failure = UpdateFailure.NONE,
                                failureDetail = null,
                                technicalDetail = "server offers ${manifest.versionCode}"
                            )
                        }
                        return@launch
                    }
                    else -> {
                        markCheckSucceeded(succeeded = false)
                        _status.update {
                            it.copy(
                                phase = UpdatePhase.FAILED,
                                failure = mismatch,
                                failureDetail = null,
                                technicalDetail =
                                    "declared appId=${manifest.applicationId} code=${manifest.versionCode} min=${manifest.minSupportedVersionCode}"
                            )
                        }
                        return@launch
                    }
                }
                markCheckSucceeded(succeeded = true)
                _status.update {
                    it.copy(
                        phase = UpdatePhase.AVAILABLE,
                        latest = manifest,
                        failure = UpdateFailure.NONE,
                        failureDetail = null,
                        technicalDetail = null
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: MalformedManifestException) {
                _status.update {
                    it.copy(
                        phase = UpdatePhase.FAILED,
                        failure = UpdateFailure.MALFORMED,
                        failureDetail = null,
                        technicalDetail = e.message
                    )
                }
            } catch (t: Throwable) {
                val failure = classify(t)
                _status.update {
                    it.copy(
                        phase = UpdatePhase.FAILED,
                        failure = failure,
                        failureDetail = null,
                        technicalDetail = when (failure) {
                            UpdateFailure.MALFORMED -> t.message
                            else -> t.toString()
                        }
                    )
                }
                Logger.w(TAG, "update check failed: $failure", t)
            }
        }
    }

    /** Streams the APK to app-private storage, verifying size and digest on the fly. */
    fun downloadPendingUpdate() {
        if (blockIfRecording()) return
        val manifest = _status.value.latest
        if (manifest == null) {
            _status.update { it.copy(phase = UpdatePhase.FAILED, failure = UpdateFailure.UNKNOWN) }
            return
        }
        downloadJob?.cancel()
        downloadCancelled = false
        _status.update {
            it.copy(
                phase = UpdatePhase.DOWNLOADING,
                downloadedBytes = 0L,
                downloadPercent = 0,
                failure = UpdateFailure.NONE,
                failureDetail = null,
                technicalDetail = null
            )
        }
        downloadJob = scope.launch {
            try {
                val (host, port) = endpointProvider()
                val apkUrl = "http://${host.trim()}:$port/${manifest.apkFileName}"
                val target = downDir().resolve("$FILE_PREFIX-${manifest.versionCode}.part")
                downDir().mkdirs()
                val (bytesRead, digestHex) = httpDownload(
                    url = apkUrl,
                    expectedBytes = manifest.sizeBytes,
                    target = target
                ) { read ->
                    _status.update {
                        it.copy(
                            downloadedBytes = read,
                            downloadPercent = (((read * 100) / manifest.sizeBytes)
                                .toInt()).coerceIn(0, 100)
                        )
                    }
                }
                if (downloadCancelled) {
                    target.delete()
                    throw UpdateCancelledException()
                }
                if (bytesRead != manifest.sizeBytes) {
                    target.delete()
                    throw IncompleteDownloadException("read $bytesRead of ${manifest.sizeBytes} bytes")
                }
                _status.update { it.copy(phase = UpdatePhase.VERIFYING) }
                if (!digestHex.equals(manifest.sha256, ignoreCase = true)) {
                    target.delete()
                    throw HashMismatchException("declared ${manifest.sha256}, got $digestHex")
                }
                val final = downDir().resolve("$FILE_PREFIX-${manifest.versionCode}.apk")
                if (target.renameTo(final)) {
                    _status.update {
                        it.copy(
                            phase = UpdatePhase.INSTALL_READY,
                            downloadPercent = 100,
                            verifiedAt = SystemClock.elapsedRealtime(),
                            failure = UpdateFailure.NONE
                        )
                    }
                } else {
                    target.delete()
                    throw IOException("could not move the verified file into place")
                }
            } catch (c: CancellationException) {
                throw c
            } catch (cancelled: UpdateCancelledException) {
                _status.update {
                    it.copy(
                        phase = UpdatePhase.CANCELLED,
                        downloadPercent = -1,
                        downloadedBytes = 0L,
                        failure = UpdateFailure.NONE,
                        failureDetail = null
                    )
                }
            } catch (t: Throwable) {
                val failure = classify(t)
                runCatching { downDir().listFiles()?.forEach(File::delete) }
                _status.update {
                    it.copy(
                        phase = UpdatePhase.FAILED,
                        failure = failure,
                        failureDetail = null,
                        technicalDetail = t.toString(),
                        downloadPercent = -1,
                        downloadedBytes = 0L
                    )
                }
                Logger.w(TAG, "update download failed: $failure", t)
            }
        }
    }

    /** Abort an in-flight download; the partial file is removed and nothing is installed. */
    fun cancelDownload() {
        downloadCancelled = true
        downloadJob?.cancel()
    }

    fun canRequestPackageInstalls(): Boolean = try {
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.REQUEST_INSTALL_PACKAGES
        ) == PackageManager.PERMISSION_GRANTED ||
            context.packageManager.canRequestPackageInstalls()
    } catch (t: Throwable) {
        false
    }

    /** Open the Android screen that grants "install unknown apps" to PRISM. */
    fun installPermissionIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Hand the verified APK to Android's package installer. This is never silent: the system
     * installer shows its own confirmation screen.
     *
     * @return the intent to launch, or null when no verified file is ready.
     */
    fun installIntent(): Intent? {
        if (_status.value.phase != UpdatePhase.INSTALL_READY) return null
        val code = _status.value.latest?.versionCode
            ?: latestVerifiedFileCode()
            ?: return null
        if (code <= BuildConfig.VERSION_CODE) return null
        val file = downDir().resolve("$FILE_PREFIX-$code.apk")
        if (!file.isFile) return null
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return null
        _status.update {
            it.copy(
                phase = UpdatePhase.INSTALL_PROMPTED,
                failure = UpdateFailure.NONE,
                failureDetail = null
            )
        }
        return Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Android hands control back when its installer screen closes. A successful install usually
     * restarts this process before any return value is produced, so this cannot prove success;
     * the version line on the same screen is the source of truth.
     */
    fun onInstallScreenClosed(returnedOk: Boolean) {
        if (returnedOk) {
            _status.update {
                it.copy(
                    phase = UpdatePhase.READY,
                    failure = UpdateFailure.NONE,
                    technicalDetail = "the installer handed control back to PRISM"
                )
            }
        } else {
            _status.update {
                it.copy(
                    phase = UpdatePhase.CANCELLED,
                    failure = UpdateFailure.NONE,
                    technicalDetail = "the installer reported cancellation"
                )
            }
        }
    }

    /** Delete any downloaded, verified file without installing it. */
    fun discardDownloadedFile() {
        runCatching {
            downDir().listFiles()?.forEach { f -> if (f.name.startsWith(FILE_PREFIX)) f.delete() }
        }
        if (_status.value.phase == UpdatePhase.INSTALL_READY ||
            _status.value.phase == UpdatePhase.INSTALL_PROMPTED
        ) {
            _status.update { it.copy(phase = UpdatePhase.READY, downloadPercent = -1) }
        }
    }

    private fun blockIfRecording(): Boolean {
        if (isRecordingActive()) {
            _status.update {
                it.copy(
                    phase = UpdatePhase.RECORDING_ACTIVE,
                    failure = UpdateFailure.RECORDING_ACTIVE
                )
            }
            return true
        }
        return false
    }

    /** Newest `prism-update-<code>.apk` in the update folder, or null when the folder is empty. */
    private fun latestVerifiedFileCode(): Int? {
        val pattern = Regex("^$FILE_PREFIX-(\\d+)\\.apk$")
        return downDir().listFiles()
            ?.mapNotNull { f -> pattern.matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull() }
            ?.filter { it > 0 }
            ?.maxOrNull()
    }

    private fun downDir(): File = File(context.filesDir, "updates").apply { if (!exists()) mkdirs() }

    private fun markCheckSucceeded(succeeded: Boolean) {
        val now = System.currentTimeMillis()
        lastCheckListener?.invoke(succeeded, now)
        _status.update {
            it.copy(lastCheckAtMs = now, lastCheckSucceeded = succeeded)
        }
    }

    private fun httpGetText(url: String, limit: Int): String {
        val conn = openHttp(url)
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw BadServerAnswerException("HTTP ${conn.responseCode}")
            }
            val bytes = conn.inputStream.readUpTo(limit)
            if (bytes.isEmpty() || bytes.size >= limit) {
                throw MalformedManifestException("manifest is empty or too large")
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private fun httpDownload(
        url: String,
        expectedBytes: Long,
        target: File,
        onProgress: (Long) -> Unit
    ): Pair<Long, String> {
        val conn = openHttp(url)
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 15_000
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw BadServerAnswerException("HTTP ${conn.responseCode}")
            }
            val servedLength = conn.contentLengthLong
            if (servedLength > 0L && servedLength != expectedBytes) {
                throw SizeMismatchException(
                    "server reports $servedLength bytes, manifest declares $expectedBytes"
                )
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (!downloadCancelled) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        out.write(buffer, 0, n)
                        read += n
                        onProgress(read)
                    }
                }
            }
            if (downloadCancelled) {
                target.delete()
                throw UpdateCancelledException()
            }
            return Pair(read, digest.hex())
        } finally {
            conn.disconnect()
        }
    }

    private fun openHttp(urlString: String): HttpURLConnection {
        val url: URL? = try {
            val u = URI(urlString).toURL()
            if (u.protocol != "http" && u.protocol != "https") null else u
        } catch (e: IllegalArgumentException) {
            throw BadUrlException(urlString)
        }
        if (url == null) throw BadUrlException(urlString)
        return url.openConnection() as HttpURLConnection
    }

    private fun classify(t: Throwable): UpdateFailure = when (t) {
        is MalformedManifestException -> UpdateFailure.MALFORMED
        is HashMismatchException -> UpdateFailure.HASH_MISMATCH
        is SizeMismatchException -> UpdateFailure.SIZE_MISMATCH
        is IncompleteDownloadException -> UpdateFailure.INCOMPLETE
        is BadServerAnswerException -> UpdateFailure.MALFORMED
        is SocketTimeoutException -> UpdateFailure.TIMEOUT
        is UnknownHostException -> UpdateFailure.UNREACHABLE
        is ConnectException -> UpdateFailure.UNREACHABLE
        is NoRouteToHostException -> UpdateFailure.UNREACHABLE
        is BadUrlException -> UpdateFailure.BAD_ADDRESS
        is MalformedURLException -> UpdateFailure.BAD_ADDRESS
        is IOException -> UpdateFailure.UNREACHABLE
        else -> UpdateFailure.UNKNOWN
    }

    companion object {
        private const val TAG = "PrismUpdate"
        const val FILE_PREFIX = "prism-update"
        const val MANIFEST_PATH = "manifest.json"
        private const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}

class MalformedManifestException(message: String) : IOException(message)
class BadUrlException(url: String) : IOException("not a usable update URL")
class BadServerAnswerException(message: String) : IOException(message)
class IncompleteDownloadException(message: String) : IOException(message)
class HashMismatchException(message: String) : IOException(message)
class SizeMismatchException(message: String) : IOException(message)
class UpdateCancelledException : IOException("update cancelled")

private fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (total < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - total))
        if (n < 0) break
        out.write(buffer, 0, n)
        total += n
    }
    return out.toByteArray()
}

private fun MessageDigest.hex(): String =
    digest().joinToString("") { "%02x".format(it) }
