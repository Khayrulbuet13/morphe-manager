/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.apk

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApkVariantSelectionTest {
    private val release = "https://www.apkmirror.com/apk/google-inc/youtube/youtube-21-43-525-release/"
    private val arm64Nodpi = "$release" + "youtube-21-43-525-3-android-apk-download/"
    private val arm64Dense = "$release" + "youtube-21-43-525-4-android-apk-download/"
    private val arm32 = "$release" + "youtube-21-43-525-2-android-apk-download/"

    private val listing = """
        <div class="table-row headerFont">
            <div class="table-cell">Version</div>
            <div class="table-cell">Arch</div>
            <div class="table-cell">Screen</div>
            <div class="table-cell">Min</div>
            <div class="table-cell">Type</div>
        </div>
        <div class="table-row">
            <div class="table-cell"><a href="$arm64Nodpi">21.43.525</a></div>
            <div class="table-cell">arm64-v8a</div>
            <div class="table-cell">nodpi</div>
            <div class="table-cell">Android 8.0+</div>
            <div class="table-cell">APK</div>
        </div>
        <div class="table-row">
            <div class="table-cell"><a href="$arm64Dense">21.43.525</a></div>
            <div class="table-cell">arm64-v8a</div>
            <div class="table-cell">480dpi</div>
            <div class="table-cell">Android 8.0+</div>
            <div class="table-cell">APK</div>
        </div>
        <div class="table-row">
            <div class="table-cell"><a href="$arm32">21.43.525</a></div>
            <div class="table-cell">armeabi-v7a</div>
            <div class="table-cell">nodpi</div>
            <div class="table-cell">Android 8.0+</div>
            <div class="table-cell">APK</div>
        </div>
    """.trimIndent()

    @Test
    fun `a release listing keeps the uploads and drops the header`() {
        val variants = parseApkMirrorListing(listing)
        assertEquals(listOf("arm64-v8a", "arm64-v8a", "armeabi-v7a"), variants.map { it.architectures.single() })
        assertEquals(listOf("nodpi", "480dpi", "nodpi"), variants.map { it.dpi })
        assertEquals(listOf(26, 26, 26), variants.map { it.minSdk })
        assertTrue(variants.all { it.versionName == "21.43.525" && it.kind == "apk" })
    }

    @Test
    fun `the phone density wins and a missing one falls back to nodpi`() {
        val variants = parseApkMirrorListing(listing)
        val exact = selectVariant(variants, "21.43.525", phone(dpi = "480dpi"))
        assertEquals(arm64Dense, exact?.variant?.pageUrl)
        assertEquals(false, exact?.densityFallback)

        val fallback = selectVariant(variants, "21.43.525", phone(dpi = "640dpi"))
        assertEquals(arm64Nodpi, fallback?.variant?.pageUrl)
        assertEquals(true, fallback?.densityFallback)

        assertNull(selectVariant(variants, "21.43.525", phone(abi = "x86")))
    }

    @Test
    fun `a plain apk beats a bundle and a newer android requirement wins`() {
        val variants = listOf(
            variant(url = "bundle", kind = "bundle", minSdk = 26),
            variant(url = "old", minSdk = 23),
            variant(url = "new", minSdk = 26)
        )
        assertEquals("new", selectVariant(variants, "21.43.525", phone())?.variant?.pageUrl)
    }

    @Test
    fun `a release page is followed to the matching upload`() {
        val snapshot = MirrorPageSnapshot(rows = rowsOf(parseApkMirrorListing(listing)))
        val step = nextDownloadStep(release, snapshot, phone(dpi = "480dpi"), "21.43.525")
        val open = assertIs<DownloadStep.Open>(step)
        assertEquals(arm64Dense, open.url)
        assertNull(open.densityFallback)

        val onUpload = nextDownloadStep(
            arm64Dense,
            snapshot.copy(download = "https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=1"),
            phone(dpi = "480dpi"),
            "21.43.525"
        )
        assertEquals(
            "https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=1",
            assertIs<DownloadStep.Save>(onUpload).url
        )

        val blocked = nextDownloadStep(release, MirrorPageSnapshot(), phone(), "21.43.525")
        assertEquals(DownloadStep.Wait, blocked)
    }

    @Test
    fun `the release page is the parent of one variant and other sites stay as they are`() {
        assertEquals(release, apkmirrorReleasePage(arm64Dense))
        assertNull(apkmirrorReleasePage("https://youtube-music.en.uptodown.com/android/download/123"))
        assertTrue(isDirectApkUrl("https://cdn.example/youtube.apk?token=1"))
        assertTrue(isDirectApkUrl("https://dw.uptodown.net/dwn/abc"))
        assertEquals(false, isDirectApkUrl(release))
    }

    @Test
    fun `a javascript snapshot is the encoded page`() {
        val snapshot = MirrorPageSnapshot(title = "YouTube", download = "https://cdn.example/youtube.apk", clickUptodown = true)
        val encoded = Json.encodeToString(Json.encodeToString(snapshot))
        assertEquals(snapshot, parseMirrorSnapshot(encoded))
        assertNull(parseMirrorSnapshot("null"))
    }

    @Test
    fun `an edited profile remembers only what differs from the phone`() {
        val detected = phone(language = "de")
        assertNull(detected.overrideOf(detected))

        val edited = detected.copy(dpi = "nodpi", language = null)
        val override = edited.overrideOf(detected)
        assertEquals("nodpi", override?.dpi)
        assertEquals(true, override?.allLanguages)
        assertEquals(null, override?.abi)
        assertEquals(edited, override?.applyTo(detected))
    }

    @Test
    fun `a downloaded file is kept when it matches and named when it does not`() {
        val matching = apk(
            "lib/arm64-v8a/libx.so",
            "res/drawable-xxhdpi/icon.png",
            "res/values-de/strings.xml"
        )
        assertEquals(ApkFit.Ok, inspectDownloadedApk(matching, phone(language = "de")))
        assertEquals(ApkFit.Ok, inspectDownloadedApk(matching, phone(language = "en")))
        assertIs<ApkFit.WrongLanguage>(inspectDownloadedApk(matching, phone(language = "fr")))

        val dense = apk("lib/arm64-v8a/libx.so", "res/drawable-mdpi/icon.png")
        assertIs<ApkFit.WrongDensity>(inspectDownloadedApk(dense, phone(dpi = "480dpi")))

        val x86 = apk("lib/x86/libx.so", "res/drawable-xxhdpi/icon.png")
        assertIs<ApkFit.WrongAbi>(inspectDownloadedApk(x86, phone()))
    }

    private fun phone(
        abi: String = "arm64-v8a",
        dpi: String = "480dpi",
        sdk: Int = 34,
        language: String? = "en"
    ) = ApkPhoneProfile(abi, dpi, sdk, language)

    private fun variant(
        url: String,
        kind: String = "apk",
        minSdk: Int = 26,
        dpi: String = "480dpi",
        abi: String = "arm64-v8a"
    ) = MirrorApkVariant("21.43.525", url, setOf(abi), dpi, minSdk, kind)

    private fun rowsOf(variants: List<MirrorApkVariant>) = variants.map { variant ->
        MirrorRow(
            href = variant.pageUrl,
            cells = listOf(variant.versionName, variant.architectures.single(), variant.dpi, "Android 8.0+", variant.kind)
        )
    }

    private fun apk(vararg entries: String): File {
        val file = File.createTempFile("variant", ".apk")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(byteArrayOf(0))
                zip.closeEntry()
            }
        }
        return file
    }
}
