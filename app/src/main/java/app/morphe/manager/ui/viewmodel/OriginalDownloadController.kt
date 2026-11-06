/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.viewmodel

import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.morphe.manager.domain.apk.ApkFit
import app.morphe.manager.domain.apk.ApkPhoneProfile
import app.morphe.manager.domain.apk.ApkVariantStore
import app.morphe.manager.domain.apk.DownloadStep
import app.morphe.manager.domain.apk.MirrorPageSnapshot
import app.morphe.manager.domain.apk.inspectDownloadedApk
import app.morphe.manager.domain.apk.nextDownloadStep
import app.morphe.manager.domain.apk.overrideOf
import app.morphe.manager.domain.apk.apkmirrorReleasePage
import app.morphe.manager.domain.apk.samePage
import app.morphe.manager.domain.bundles.PatchSourceHeaders
import app.morphe.manager.domain.manager.ApkDownloadSource
import app.morphe.manager.domain.manager.DownloadUrlResolver
import app.morphe.manager.network.service.HttpService
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the download dialog is doing. */
sealed interface OriginalDownloadPhase {
    data object Editing : OriginalDownloadPhase
    data object Resolving : OriginalDownloadPhase
    data class Browsing(val url: String) : OriginalDownloadPhase
    data class Saving(val read: Long, val total: Long?) : OriginalDownloadPhase
    data class Mismatch(val fit: ApkFit) : OriginalDownloadPhase
    data object Failed : OriginalDownloadPhase
    data object NoMatch : OriginalDownloadPhase
}

data class OriginalDownloadState(
    val packageName: String,
    val appName: String,
    val version: String?,
    val detected: ApkPhoneProfile,
    val profile: ApkPhoneProfile,
    val phase: OriginalDownloadPhase,
    /** Set when the upload is `nodpi` because the phone's density was not published. */
    val densityFallback: Pair<String, String>? = null
)

/**
 * Downloads the original APK for one YouTube or YouTube Music update.
 *
 * The mirror page is loaded by a WebView, which is what gets past the check those sites put
 * in front of a plain HTTP client. This class decides which variant that page should open
 * and then saves the file the page hands over. [onReady] receives the file once it matches
 * the profile, or once the user has accepted that it does not.
 */
class OriginalDownloadController(
    private val scope: CoroutineScope,
    private val http: HttpService,
    private val urls: DownloadUrlResolver,
    private val store: ApkVariantStore,
    private val tempDir: File,
    private val patchSources: suspend (packageName: String) -> PatchSourceHeaders?,
    private val onReady: suspend (File) -> Unit
) {
    var state by mutableStateOf<OriginalDownloadState?>(null)
        private set

    /** The queued app this download fills, when it was started from the batch screen. */
    var batchItemId: String? = null
        private set

    private var job: Job? = null
    private var handingOff = false
    private var pending: File? = null
    private var ignoreFit = false
    private var referer: String? = null

    /** Opens this same download again after a file was turned down. */
    private var again: (() -> Unit)? = null

    fun open(
        packageName: String,
        appName: String,
        version: String?,
        detected: ApkPhoneProfile,
        batchItemId: String? = null
    ) {
        again = { open(packageName, appName, version, detected, batchItemId) }
        close()
        this.batchItemId = batchItemId
        state = OriginalDownloadState(
            packageName = packageName,
            appName = appName,
            version = version,
            detected = detected,
            profile = detected,
            phase = OriginalDownloadPhase.Editing
        )
        scope.launch {
            val stored = store.read(packageName)
            val current = state?.takeIf { it.packageName == packageName } ?: return@launch
            if (current.phase != OriginalDownloadPhase.Editing) return@launch
            state = current.copy(profile = stored?.applyTo(detected) ?: detected)
        }
    }

    fun updateProfile(profile: ApkPhoneProfile) {
        val current = state ?: return
        state = current.copy(profile = profile)
        scope.launch {
            store.write(current.packageName, profile.overrideOf(current.detected))
        }
    }

    fun resetProfile() {
        val current = state ?: return
        updateProfile(current.detected)
    }

    /**
     * Puts the form back after the file was not accepted, so it can be downloaded again.
     * Safe to call from [onReady], which runs while this download is still handing the file off.
     */
    fun reopen() {
        val retry = again ?: return
        val current = state
        if (current != null) {
            state = current.copy(phase = OriginalDownloadPhase.Editing, densityFallback = null)
            return
        }
        retry()
    }

    /** Looks up the release and opens it so the variant matching the profile can be chosen. */
    fun start() {
        val current = state ?: return
        if (current.phase is OriginalDownloadPhase.Saving || current.phase is OriginalDownloadPhase.Resolving) return
        ignoreFit = false
        pending?.delete()
        pending = null
        job?.cancel()
        job = scope.launch {
            state = current.copy(phase = OriginalDownloadPhase.Resolving, densityFallback = null)
            val profile = current.profile
            val resolved = withContext(Dispatchers.IO) {
                urls.resolve(
                    packageName = current.packageName,
                    version = current.version,
                    patchSources = patchSources(current.packageName),
                    abi = profile.abi,
                    dpi = profile.dpi,
                    sdk = profile.androidSdk
                )
            }
            if (state?.packageName != current.packageName) return@launch
            referer = resolved
            when (ApkDownloadSource.from(resolved)) {
                is ApkDownloadSource.DirectFile -> save(resolved, userAgent = null, cookie = null)
                is ApkDownloadSource.WebSearch, ApkDownloadSource.Unresolved ->
                    state = state?.copy(phase = OriginalDownloadPhase.Failed)
                else -> {
                    val listing = apkmirrorReleasePage(resolved) ?: resolved
                    state = state?.copy(phase = OriginalDownloadPhase.Browsing(listing))
                }
            }
        }
    }

    /** A page finished loading. [raw] is the string `evaluateJavascript` returned. */
    fun onPage(url: String, snapshot: MirrorPageSnapshot) {
        val current = state ?: return
        val browsing = current.phase as? OriginalDownloadPhase.Browsing ?: return
        // A check page and the real page share one address, so a page that is not ready yet
        // has to be allowed to report again once the check has gone
        if (!samePage(url, browsing.url)) return
        when (val step = nextDownloadStep(url, snapshot, current.profile, current.version)) {
            is DownloadStep.Open -> {
                if (samePage(step.url, browsing.url)) return
                state = current.copy(
                    phase = OriginalDownloadPhase.Browsing(step.url),
                    densityFallback = step.densityFallback ?: current.densityFallback
                )
            }
            is DownloadStep.Save -> onFileUrl(step.url, userAgent = null)
            DownloadStep.NoMatch -> state = current.copy(phase = OriginalDownloadPhase.NoMatch)
            DownloadStep.Wait -> Unit
        }
    }

    /**
     * The page offered [url] as the file. [userAgent] is the WebView's, so the file request
     * looks like the page that earned it.
     */
    fun onFileUrl(url: String, userAgent: String?) {
        val current = state ?: return
        // Only the page on screen can hand over a file. A callback from a page that has
        // already been left, or a second report of the same file, must not start another fetch
        if (current.phase !is OriginalDownloadPhase.Browsing) return
        // Claim the download before the coroutine starts, so a second report of the same
        // file does not fetch it twice
        state = current.copy(phase = OriginalDownloadPhase.Saving(0, null))
        val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
        job?.cancel()
        job = scope.launch { save(url, userAgent, cookie) }
    }

    fun acceptMismatch() {
        val file = pending ?: return
        pending = null
        ignoreFit = true
        job = scope.launch { handOff(file) }
    }

    /** Stops the page load without leaving the dialog, for the website instructions to take over. */
    fun pause() {
        if (handingOff) return
        job?.cancel()
        val current = state ?: return
        if (current.phase !is OriginalDownloadPhase.Editing) {
            state = current.copy(phase = OriginalDownloadPhase.Editing)
        }
    }

    fun close() {
        if (!handingOff) job?.cancel()
        if (!handingOff) pending?.delete()
        pending = null
        batchItemId = null
        state = null
    }

    private suspend fun save(url: String, userAgent: String?, cookie: String?) {
        val current = state ?: return
        val dest = File(tempDir, "original-${current.packageName.hashCode()}-${System.currentTimeMillis()}.apk")
        state = current.copy(phase = OriginalDownloadPhase.Saving(0, null))
        try {
            http.downloadToFile(dest, builder = {
                url(url)
                userAgent?.let { header(HttpHeaders.UserAgent, it) }
                if (!cookie.isNullOrBlank()) header(HttpHeaders.Cookie, cookie)
                referer?.let { header(HttpHeaders.Referrer, it) }
            }, onProgress = { read, total ->
                scope.launch(Dispatchers.Main.immediate) {
                    val saving = state?.phase as? OriginalDownloadPhase.Saving ?: return@launch
                    if (saving.read == read && saving.total == total) return@launch
                    state = state?.copy(phase = OriginalDownloadPhase.Saving(read, total))
                }
            })
        } catch (cancelled: CancellationException) {
            if (!handingOff) dest.delete()
            throw cancelled
        } catch (_: Exception) {
            dest.delete()
            state = state?.copy(phase = OriginalDownloadPhase.Failed)
            return
        }

        if (!ignoreFit) {
            val fit = withContext(Dispatchers.IO) { inspectDownloadedApk(dest, current.profile) }
            if (fit !is ApkFit.Ok) {
                pending = dest
                state = state?.copy(phase = OriginalDownloadPhase.Mismatch(fit))
                return
            }
        }
        handOff(dest)
    }

    private suspend fun handOff(file: File) {
        handingOff = true
        state = null
        try {
            onReady(file)
        } finally {
            handingOff = false
        }
    }
}
