/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.manager

import android.os.Build
import android.util.Log
import app.morphe.manager.domain.bundles.PatchSourceHeaders
import app.morphe.manager.network.api.ReleaseAPI
import app.morphe.manager.util.KnownApps
import app.morphe.manager.util.MORPHE_API_URL
import app.morphe.manager.util.tag
import io.ktor.http.encodeURLPath
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

/**
 * Works out where a specific version of an app can be downloaded from.
 */
class DownloadUrlResolver(private val releaseAPI: ReleaseAPI) {

    /**
     * Resolves the download page for [packageName] at [version], falling back to a plain web
     * search when the API cannot point anywhere useful.
     *
     * Every redirect along the way is followed: the API sometimes points at itself once more,
     * and the download sites redirect for reasons of their own, so only the end of the chain
     * says what the user will actually get.
     *
     * @param patchSources The sources the app is patched from, see
     *   [app.morphe.manager.domain.repository.PatchBundleRepository.patchSourceHeaders]. The API
     *   counts requests by them.
     */
    suspend fun resolve(
        packageName: String,
        version: String?,
        patchSources: PatchSourceHeaders? = null,
        abi: String? = null,
        dpi: String? = null,
        sdk: Int? = null
    ): String =
        // The instructions wait on this, so a host that never answers has to end the wait itself
        withTimeoutOrNull(RESOLVE_TIMEOUT) { follow(packageName, version, patchSources, abi, dpi, sdk) } ?: run {
            Log.w(tag, "Timed out resolving the download page")
            webSearchUrl(packageName, version, abi, dpi)
        }

    private suspend fun follow(
        packageName: String,
        version: String?,
        patchSources: PatchSourceHeaders?,
        abi: String?,
        dpi: String?,
        sdk: Int?
    ): String {
        val searchUrl = apiSearchUrl(packageName, version, abi, dpi, sdk)
        Log.d(tag, "Using search url: $searchUrl")

        var resolved = releaseAPI.resolveRedirect(searchUrl, patchSources) ?: run {
            Log.w(tag, "No redirect location for: $searchUrl")
            return webSearchUrl(packageName, version, abi, dpi)
        }

        repeat(MAX_REDIRECTS) {
            // A URL that redirects nowhere is the end of the chain
            val next = releaseAPI.resolveRedirect(resolved, patchSources) ?: return finalUrl(resolved, packageName, version, abi, dpi)
            Log.i(tag, "Following redirect to: $next")
            resolved = next
        }

        Log.w(tag, "Gave up after $MAX_REDIRECTS redirects")
        return webSearchUrl(packageName, version, abi, dpi)
    }

    /**
     * The unfollowed API URL, standing in for the destination until [resolve] has one.
     *
     * [dpi] and [sdk] are sent only when the caller has a profile in mind. The lookup already
     * keys on the ABI, and extra segments the server does not read yet are ignored.
     */
    fun apiSearchUrl(
        packageName: String,
        version: String?,
        abi: String? = null,
        dpi: String? = null,
        sdk: Int? = null
    ): String {
        val parts = buildList {
            add(packageName)
            add(version ?: "any")
            add(abi ?: Build.SUPPORTED_ABIS.first())
            if (dpi != null) add(dpi)
            if (sdk != null) add(sdk.toString())
        }
        return "$MORPHE_API_URL/v2/web-search/${parts.joinToString("~").encodeURLPath()}"
    }

    /** Used when the API is unreachable, so the user still lands on something useful. */
    fun webSearchUrl(
        packageName: String,
        version: String?,
        abi: String? = null,
        dpi: String? = null
    ): String {
        val architecture = when {
            abi != null || dpi != null -> listOfNotNull(abi, dpi).joinToString(" ")
            packageName == KnownApps.YOUTUBE_MUSIC -> " (${Build.SUPPORTED_ABIS.first()})"
            else -> "nodpi"
        }
        val versionPart = version?.let { "\"$it\"" } ?: ""
        val query = "\"$packageName\" $versionPart $architecture $SEARCH_SITES"
        Log.d(tag, "Using search query: $query")
        return "https://google.com/search?q=${URLEncoder.encode(query, "UTF-8")}&udm=14"
    }

    /** Hands back [url], or a web search when it cannot lead to the version that was asked for. */
    private fun finalUrl(
        url: String,
        packageName: String,
        version: String?,
        abi: String? = null,
        dpi: String? = null
    ): String {
        // Uptodown answers a retired download id with its "latest version" page, which is not
        // the build the patches need. Only its per-build pages end in "-x"
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val onUptodown = uri.host?.lowercase()?.endsWith("uptodown.com") == true
        if (onUptodown && !uri.path.orEmpty().trimEnd('/').endsWith("-x")) {
            Log.w(tag, "Uptodown link lost its build, searching instead: $url")
            return webSearchUrl(packageName, version, abi, dpi)
        }
        return url
    }

    private companion object {
        // Enough for the API pointing at itself and a site or two moving the page, while still
        // ending a redirect loop
        const val MAX_REDIRECTS = 5

        val RESOLVE_TIMEOUT = 8.seconds

        // Kept in step with the mirrors the API falls back to, so a search started here and one
        // started by the API lead to the same set of sites
        const val SEARCH_SITES =
            "(site:apkmirror.com OR site:uptodown.com OR site:apkpure.com OR site:apkcube.com OR site:apkcombo.com)"
    }
}
