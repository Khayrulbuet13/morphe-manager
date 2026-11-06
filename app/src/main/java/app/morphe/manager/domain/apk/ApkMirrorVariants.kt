/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.apk

import app.morphe.manager.patcher.split.SplitApkPreparer
import app.morphe.manager.patcher.util.NativeLibs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.util.zip.ZipFile

/** One upload listed on a release page. */
data class MirrorApkVariant(
    val versionName: String,
    val pageUrl: String,
    val architectures: Set<String>,
    val dpi: String,
    val minSdk: Int,
    val kind: String
)

data class VariantPick(
    val variant: MirrorApkVariant,
    /** True when [variant] is `nodpi` because the phone's own density was not uploaded. */
    val densityFallback: Boolean
)

/** What a loaded page told us, whether it came from a WebView or from saved HTML. */
@Serializable
data class MirrorPageSnapshot(
    val title: String = "",
    val rows: List<MirrorRow> = emptyList(),
    val download: String = "",
    val clickUptodown: Boolean = false
)

@Serializable
data class MirrorRow(
    val href: String = "",
    val cells: List<String> = emptyList()
)

/** Where to go after a page has loaded. */
sealed interface DownloadStep {
    data class Open(val url: String, val densityFallback: Pair<String, String>?) : DownloadStep
    data class Save(val url: String) : DownloadStep
    data object Wait : DownloadStep
    data object NoMatch : DownloadStep
}

/** Why a downloaded file does not match the profile the user confirmed. */
sealed interface ApkFit {
    data object Ok : ApkFit
    data class WrongAbi(val found: List<String>, val wanted: String) : ApkFit
    data class WrongDensity(val found: List<String>, val wanted: String) : ApkFit
    data class WrongLanguage(val wanted: String) : ApkFit
}

private val ABI_TOKEN = Regex("""arm64-v8a|armeabi-v7a|armeabi|x86_64|x86|noarch|universal""", RegexOption.IGNORE_CASE)
private val DPI_TOKEN = Regex("""\b(?:nodpi|\d{2,4}dpi)\b""", RegexOption.IGNORE_CASE)
private val ANDROID_TOKEN = Regex("""Android\s+(\d+(?:\.\d+)?|12L)""", RegexOption.IGNORE_CASE)
private val VERSION_TOKEN = Regex("""\d+(?:\.\d+){1,3}""")
private val CELL = Regex("""<div class="table-cell[^"]*">([\s\S]*?)</div>""", RegexOption.IGNORE_CASE)
private val HREF = Regex("""href="([^"]+)"""", RegexOption.IGNORE_CASE)
private val LOCALE_DIR = Regex("""res/values-([a-z]{2})(?:-r[a-zA-Z0-9]+)?/""")
private val DENSITY_QUALIFIERS = setOf("ldpi", "mdpi", "hdpi", "tvdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi", "anydpi")

private val snapshotJson = Json { ignoreUnknownKeys = true }

/** The release page that lists every variant, for a link that already points at one of them. */
fun apkmirrorReleasePage(url: String): String? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (!host.endsWith("apkmirror.com")) return null
    val path = uri.path.orEmpty()
    val marker = "-release/"
    val at = path.indexOf(marker)
    if (at < 0) return url
    val releasePath = path.substring(0, at + marker.length)
    return "${uri.scheme}://${uri.host}$releasePath"
}

fun samePage(left: String, right: String): Boolean {
    fun key(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url.trimEnd('/')
        return "${uri.host?.lowercase().orEmpty()}${uri.path.orEmpty().trimEnd('/')}"
    }
    return key(left) == key(right)
}

/** True when [url] is the APK itself rather than a page that links to it. */
fun isDirectApkUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull()
    val path = (uri?.path ?: url).lowercase().substringBefore('?')
    val ext = path.substringAfterLast('.', "")
    if (ext in DIRECT_EXTENSIONS) return true
    val host = uri?.host?.lowercase() ?: return false
    return host == "dw.uptodown.net" || host.startsWith("dw.") && host.contains("uptodown")
}

private val DIRECT_EXTENSIONS = setOf("apk", "apks", "apkm", "xapk")

/**
 * Reads the variant table out of an APKMirror release page.
 *
 * Cells are taken up to their first closing div, which is the shape the live table uses:
 * a cell holds a link, not another div.
 */
fun parseApkMirrorListing(html: String): List<MirrorApkVariant> {
    val rows = mutableListOf<MirrorRow>()
    var search = 0
    while (true) {
        val at = html.indexOf("class=\"table-row", search, ignoreCase = true)
        if (at < 0) break
        val next = html.indexOf("class=\"table-row", at + 16, ignoreCase = true).let { if (it < 0) html.length else it }
        val chunk = html.substring(at, next)
        search = next
        if (chunk.startsWith("class=\"table-row headerFont", ignoreCase = true) ||
            "headerFont" in chunk.take(80)
        ) continue
        val href = HREF.find(chunk)?.groupValues?.get(1).orEmpty()
        val cells = CELL.findAll(chunk).map { cell ->
            cell.groupValues[1]
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }.filter { it.isNotEmpty() }.toList()
        if (cells.isNotEmpty() || href.isNotEmpty()) rows += MirrorRow(href, cells)
    }
    return rows.mapNotNull { it.toVariant() }
}

fun MirrorRow.toVariant(): MirrorApkVariant? {
    val href = href.trim()
    if (href.isEmpty()) return null
    val archCell = cells.firstOrNull { ABI_TOKEN.containsMatchIn(it) } ?: return null
    val architectures = ABI_TOKEN.findAll(archCell).map { normalizeAbi(it.value) }.toSet()
    if (architectures.isEmpty()) return null
    val dpi = cells.firstNotNullOfOrNull { DPI_TOKEN.find(it)?.value }?.lowercase() ?: "nodpi"
    val android = cells.firstNotNullOfOrNull { ANDROID_TOKEN.find(it)?.groupValues?.get(1) }
    val version = cells.firstNotNullOfOrNull { cell ->
        VERSION_TOKEN.find(cell)?.value?.takeIf { cell.length < 40 }
    }.orEmpty()
    val kind = if (cells.any { it.contains("bundle", ignoreCase = true) }) "bundle" else "apk"
    return MirrorApkVariant(
        versionName = version,
        pageUrl = href,
        architectures = architectures,
        dpi = dpi,
        minSdk = android?.let(::minSdkOf) ?: 1,
        kind = kind
    )
}

private fun normalizeAbi(raw: String) = when (raw.lowercase()) {
    "armeabi" -> "armeabi-v7a"
    "noarch", "universal" -> "universal"
    else -> raw.lowercase()
}

/** The API level an APKMirror "Android x+" label requires. */
fun minSdkOf(version: String): Int = when (version.trim().removeSuffix("+")) {
    "16" -> 36
    "15" -> 35
    "14" -> 34
    "13" -> 33
    "12L", "12.1" -> 32
    "12" -> 31
    "11" -> 30
    "10" -> 29
    "9" -> 28
    "8.1" -> 27
    "8.0", "8" -> 26
    "7.1" -> 25
    "7.0", "7" -> 24
    "6.0", "6" -> 23
    "5.1" -> 22
    "5.0", "5" -> 21
    else -> version.substringBefore('.').toIntOrNull() ?: 1
}

/**
 * The upload to fetch for [profile].
 *
 * A single-ABI file beats a universal one of the same version. The phone's density wins when
 * it was published, otherwise `nodpi`, which still contains that density. Bundles lose to a
 * plain APK of the same shape, because these apps are patched from a plain APK.
 */
fun selectVariant(
    variants: List<MirrorApkVariant>,
    version: String?,
    profile: ApkPhoneProfile
): VariantPick? {
    val versioned = if (version.isNullOrBlank()) variants else {
        variants.filter { variant ->
            val versionName = variant.versionName
            versionName == version ||
                versionName.startsWith("$version.") ||
                versionName.startsWith("$version-") ||
                versionName.startsWith("$version ")
        }.ifEmpty { variants }
    }
    val installable = versioned.filter { it.minSdk <= profile.androidSdk }
    if (installable.isEmpty()) return null

    val wanted = profile.abi.lowercase()
    val exactAbi = installable.filter { it.architectures == setOf(wanted) }
    val compatibleAbi = installable.filter { wanted in it.architectures || "universal" in it.architectures }
    val abiPool = when {
        exactAbi.isNotEmpty() -> exactAbi
        compatibleAbi.isNotEmpty() -> compatibleAbi
        else -> return null
    }

    val wantedDpi = profile.dpi.lowercase()
    val exactDpi = abiPool.filter { it.dpi == wantedDpi }
    val nodpi = abiPool.filter { it.dpi == "nodpi" }
    val dpiPool = when {
        exactDpi.isNotEmpty() -> exactDpi
        nodpi.isNotEmpty() -> nodpi
        else -> listOf(abiPool.minBy { dpiDistance(it.dpi, wantedDpi) })
    }
    val best = dpiPool.maxWith(
        compareBy<MirrorApkVariant> { it.minSdk }.thenBy { if (it.kind == "apk") 1 else 0 }
    )
    return VariantPick(
        variant = best,
        densityFallback = exactDpi.isEmpty() && wantedDpi != "nodpi" && best.dpi == "nodpi"
    )
}

private fun dpiDistance(dpi: String, wanted: String): Int {
    fun number(value: String) = value.removeSuffix("dpi").toIntOrNull() ?: Int.MAX_VALUE
    if (dpi == "nodpi") return Int.MAX_VALUE - 1
    return kotlin.math.abs(number(dpi) - number(wanted))
}

/**
 * The next step for a page that just finished loading.
 *
 * A release page is followed to the one variant the profile picked. A page that already is
 * that variant, or that only offers a download button, is saved. Anything still behind a
 * check stays put so the user can complete it.
 */
fun nextDownloadStep(
    pageUrl: String,
    snapshot: MirrorPageSnapshot,
    profile: ApkPhoneProfile,
    version: String?
): DownloadStep {
    val variants = snapshot.rows.mapNotNull { it.toVariant() }
    val download = snapshot.download.takeIf { it.isNotBlank() }
    if (variants.isNotEmpty()) {
        val pick = selectVariant(variants, version, profile) ?: return DownloadStep.NoMatch
        val note = if (pick.densityFallback) profile.dpi to pick.variant.dpi else null
        if (!samePage(pageUrl, pick.variant.pageUrl)) return DownloadStep.Open(pick.variant.pageUrl, note)
        if (download != null) return DownloadStep.Save(download)
        return DownloadStep.Wait
    }
    if (download != null) return DownloadStep.Save(download)
    return DownloadStep.Wait
}

/** Decodes the string [evaluateJavascript] returns for a `JSON.stringify` result. */
fun parseMirrorSnapshot(raw: String?): MirrorPageSnapshot? {
    if (raw.isNullOrBlank() || raw == "null") return null
    val text = runCatching { snapshotJson.decodeFromString<String>(raw) }.getOrElse { raw }
    return runCatching { snapshotJson.decodeFromString<MirrorPageSnapshot>(text) }.getOrNull()
}

/**
 * Whether [file] can be the APK [profile] asked for.
 *
 * An empty answer is not a failure: a pure-Java APK has no ABI, and a `nodpi` upload carries
 * every density without naming the phone's bucket. A mismatch is only reported when the file
 * positively says it is something else.
 */
fun inspectDownloadedApk(file: File, profile: ApkPhoneProfile): ApkFit {
    val abis = if (SplitApkPreparer.isSplitArchive(file)) {
        SplitApkPreparer.splitArchiveAbis(file)
    } else {
        NativeLibs.extractAbisFromApk(file)
    }.map { it.lowercase() }
    if (abis.isNotEmpty() && profile.abi.lowercase() !in abis && "universal" !in abis) {
        return ApkFit.WrongAbi(abis, profile.abi)
    }

    val densities = densitiesIn(file)
    val wantedDensity = ApkPhoneProfiles.densityQualifier(profile.dpi)
    if (wantedDensity != null && densities.isNotEmpty() &&
        "nodpi" !in densities && "anydpi" !in densities &&
        wantedDensity !in densities
    ) {
        return ApkFit.WrongDensity(densities.toList(), profile.dpi)
    }

    val language = profile.language?.lowercase()
    if (language != null && language != "en") {
        val languages = languagesIn(file)
        if (languages.isNotEmpty() && language !in languages) return ApkFit.WrongLanguage(language)
    }
    return ApkFit.Ok
}

private fun densitiesIn(file: File): Set<String> = runCatching {
    ZipFile(file).use { zip ->
        zip.entries().asSequence().mapNotNull { entry ->
            val name = entry.name.lowercase()
            if (!name.startsWith("res/")) return@mapNotNull null
            DENSITY_QUALIFIERS.firstOrNull { qualifier ->
                name.contains("-$qualifier/") || name.contains("-$qualifier-")
            }
        }.toSet()
    }
}.getOrDefault(emptySet())

private fun languagesIn(file: File): Set<String> = runCatching {
    ZipFile(file).use { zip ->
        zip.entries().asSequence().mapNotNull { entry ->
            LOCALE_DIR.find(entry.name.lowercase())?.groupValues?.get(1)
        }.toSet()
    }
}.getOrDefault(emptySet())
