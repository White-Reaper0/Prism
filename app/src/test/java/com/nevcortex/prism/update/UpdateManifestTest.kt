package com.nevcortex.prism.update

import com.nevcortex.prism.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRISM Update v1 manifest rules: parsing, safe-file-name checks, and the decision of whether a
 * manifest really is an update for the installed build. JVM-side so no device is required.
 */
class UpdateManifestTest {

    private val sha =
        "a".repeat(64) // format-checked only; content hashing is SDK/installer territory

    private fun manifestJson(
        appId: String = "com.nevcortex.prism.debug",
        versionName: String = "0.2.2-live",
        versionCode: Int = 4,
        apk: String = "prism-0.2.2-live-vc4.apk",
        digest: String = sha,
        size: Long = 1024L,
        notes: String = "test build",
        minCode: Int = 0,
        format: String = "prism-update",
        formatVersion: Int = 1
    ): String = """
        {
          "format": "$format",
          "formatVersion": $formatVersion,
          "applicationId": "$appId",
          "versionName": "$versionName",
          "versionCode": $versionCode,
          "apk": "$apk",
          "sha256": "$digest",
          "sizeBytes": $size,
          "notes": "$notes",
          "minSupportedVersionCode": $minCode
        }
    """.trimIndent()

    @Test
    fun `parses a valid manifest`() {
        val parsed = UpdateManifestParser.parse(manifestJson())
        assertTrue(parsed != null)
        parsed ?: return
        assertEquals("com.nevcortex.prism.debug", parsed.applicationId)
        assertEquals("0.2.2-live", parsed.versionName)
        assertEquals(4, parsed.versionCode)
        assertEquals("prism-0.2.2-live-vc4.apk", parsed.apkFileName)
        assertEquals(sha, parsed.sha256)
        assertEquals(1024L, parsed.sizeBytes)
        assertEquals(0, parsed.minSupportedVersionCode)
    }

    @Test
    fun `rejects malformed, empty, and wrong-format manifests`() {
        assertNull(UpdateManifestParser.parse(""))
        assertNull(UpdateManifestParser.parse("not json at all"))
        assertNull(UpdateManifestParser.parse(manifestJson(format = "other-update")))
        assertNull(UpdateManifestParser.parse(manifestJson(formatVersion = 2)))
        // Damage every required field once — each must fail parsing on its own.
        assertNull(UpdateManifestParser.parse(manifestJson(appId = " ")))
        assertNull(UpdateManifestParser.parse(manifestJson(versionName = " ")))
        assertNull(UpdateManifestParser.parse(manifestJson(versionCode = 0)))
        assertNull(UpdateManifestParser.parse(manifestJson(versionCode = -3)))
        assertNull(UpdateManifestParser.parse(manifestJson(apk = "")))
        assertNull(UpdateManifestParser.parse(manifestJson(digest = "")))
        assertNull(UpdateManifestParser.parse(manifestJson(size = 0L)))
        assertNull(UpdateManifestParser.parse(manifestJson(size = -1L)))
        assertNull(UpdateManifestParser.parse(manifestJson(digest = "ABCD0123"))) // not 64 hex
        assertNull(UpdateManifestParser.parse(manifestJson(digest = sha + "e"))) // 65 hex
    }

    @Test
    fun `rejects path traversal and unsafe apk names`() {
        assertFalse(UpdateManifestParser.isSafeFileName("../evil.apk"))
        assertFalse(UpdateManifestParser.isSafeFileName("sub\\dir.apk"))
        assertFalse(UpdateManifestParser.isSafeFileName("sub/dir.apk"))
        assertFalse(UpdateManifestParser.isSafeFileName("plain-app")) // no .apk suffix
        assertFalse(UpdateManifestParser.isSafeFileName("name.bak.apk.txt"))
        assertFalse(UpdateManifestParser.isSafeFileName(" trailing.apk "))
        assertFalse(UpdateManifestParser.isSafeFileName("a".repeat(129)))
        assertFalse(UpdateManifestParser.isSafeFileName(""))
        assertTrue(UpdateManifestParser.isSafeFileName("prism-0.2.2-live-vc4.apk"))
        assertTrue(UpdateManifestParser.isSafeFileName("APP-UPPER.apk"))
        // Parser must reject whatever the file-name rule rejects.
        assertNull(UpdateManifestParser.parse(manifestJson(apk = "../evil.apk")))
    }

    @Test
    fun `manifest with the exact installed code is not newer`() {
        val parsed = UpdateManifestParser.parse(
            manifestJson(versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE)
        )!!
        assertEquals(UpdateFailure.NOT_NEWER, UpdateManifestParser.validateAgainst(parsed, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE))
    }

    @Test
    fun `newer code for the same application id is an update`() {
        val parsed = UpdateManifestParser.parse(
            manifestJson(versionCode = BuildConfig.VERSION_CODE + 1)
        )!!
        assertEquals(UpdateFailure.NONE, UpdateManifestParser.validateAgainst(parsed, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE))
    }

    @Test
    fun `wrong application id is rejected`() {
        val parsed = UpdateManifestParser.parse(manifestJson(appId = "com.other.app"))!!
        assertEquals(
            UpdateFailure.WRONG_APP,
            UpdateManifestParser.validateAgainst(parsed, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE)
        )
    }

    @Test
    fun `older code is not an update`() {
        val parsed = UpdateManifestParser.parse(manifestJson(versionCode = BuildConfig.VERSION_CODE - 1))!!
        assertEquals(
            UpdateFailure.NOT_NEWER,
            UpdateManifestParser.validateAgainst(parsed, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE)
        )
    }

    @Test
    fun `minSupportedVersionCode gates upgrades from too-old builds`() {
        val parsed = UpdateManifestParser.parse(
            manifestJson(versionCode = BuildConfig.VERSION_CODE + 2, minCode = BuildConfig.VERSION_CODE + 1)
        )!!
        // An install at the current code does not meet the manifest's minimum: it must step up
        // via an intermediate release rather than jump.
        assertEquals(
            UpdateFailure.UNSUPPORTED_VERSION,
            UpdateManifestParser.validateAgainst(parsed, BuildConfig.APPLICATION_ID, BuildConfig.VERSION_CODE)
        )
        // The same manifest is a valid update for a phone that already meets the minimum.
        assertEquals(
            UpdateFailure.NONE,
            UpdateManifestParser.validateAgainst(
                parsed,
                BuildConfig.APPLICATION_ID,
                BuildConfig.VERSION_CODE + 1
            )
        )
    }

    @Test
    fun `notes are truncated to a sane length`() {
        val parsed = UpdateManifestParser.parse(manifestJson(notes = "x".repeat(5000)))!!
        assertEquals(4000, parsed.releaseNotes.length)
    }
}
