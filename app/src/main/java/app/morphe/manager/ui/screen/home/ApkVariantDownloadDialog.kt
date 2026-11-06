/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import android.annotation.SuppressLint
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.morphe.manager.R
import app.morphe.manager.domain.apk.ApkFit
import app.morphe.manager.domain.apk.ApkPhoneProfile
import app.morphe.manager.domain.apk.ApkPhoneProfiles
import app.morphe.manager.domain.apk.isDirectApkUrl
import app.morphe.manager.domain.apk.parseMirrorSnapshot
import app.morphe.manager.ui.screen.shared.AppDialog
import app.morphe.manager.ui.screen.shared.AppDialogButton
import app.morphe.manager.ui.screen.shared.AppDialogDropdownTextField
import app.morphe.manager.ui.screen.shared.AppDialogOutlinedButton
import app.morphe.manager.ui.screen.shared.DialogHostScope
import app.morphe.manager.ui.screen.shared.rememberAppColor
import app.morphe.manager.ui.viewmodel.OriginalDownloadPhase
import app.morphe.manager.ui.viewmodel.OriginalDownloadState
import app.morphe.manager.util.androidVersionName
import app.morphe.manager.util.htmlAnnotatedString
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.Download
import com.composables.icons.materialsymbols.rounded.Public
import java.io.ByteArrayInputStream
import java.util.Locale

private const val VALUE_TOKEN = "\u0000"

private class MirrorWebView(context: android.content.Context) : WebView(context) {
    var loadedUrl: String? = null
    var clickedUrl: String? = null
}

private fun String.withValue(value: String) = replace(VALUE_TOKEN, value)

private const val MIRROR_SNAPSHOT_JS = """
(function(){
  try {
    var rows = [];
    var nodes = document.querySelectorAll('.table-row');
    for (var i = 0; i < nodes.length; i++) {
      var row = nodes[i];
      if ((' ' + row.className + ' ').indexOf(' headerFont ') >= 0) continue;
      var cells = [];
      var cellNodes = row.querySelectorAll('.table-cell');
      for (var c = 0; c < cellNodes.length; c++) {
        cells.push((cellNodes[c].innerText || '').replace(/\s+/g, ' ').trim());
      }
      var link = row.querySelector('a[href]');
      rows.push({ href: link ? link.href : '', cells: cells });
    }
    var download = '';
    var button = document.querySelector('a.downloadButton, a[href*="download.php"]');
    if (button && button.href) download = button.href;
    var upto = document.querySelector('#detail-download-button');
    return JSON.stringify({ title: document.title || '', rows: rows, download: download, clickUptodown: !!upto });
  } catch (e) {
    return JSON.stringify({ title: '', rows: [], download: '', clickUptodown: false });
  }
})()
"""

/**
 * Confirms the phone profile, downloads the matching original APK, then hands it back to the
 * patch flow. The page stays on screen while a site asks the user to prove they are human,
 * because that check does not complete for a hidden page.
 */
@Composable
fun DialogHostScope.ApkVariantDownloadDialog(
    download: OriginalDownloadState,
    onProfileChange: (ApkPhoneProfile) -> Unit,
    onReset: () -> Unit,
    onStart: () -> Unit,
    onPage: (url: String, snapshot: app.morphe.manager.domain.apk.MirrorPageSnapshot) -> Unit,
    onFileUrl: (url: String, userAgent: String?) -> Unit,
    onAcceptMismatch: () -> Unit,
    onHaveApk: () -> Unit,
    onUseWebsite: () -> Unit,
    onDismiss: () -> Unit
) {
    val phase = download.phase
    val busy = phase is OriginalDownloadPhase.Resolving || phase is OriginalDownloadPhase.Saving ||
        phase is OriginalDownloadPhase.Browsing
    val profile = download.profile
    val description = if (download.version != null) {
        htmlAnnotatedString(
            stringResource(R.string.home_apk_download_description, download.appName, download.version)
        )
    } else {
        htmlAnnotatedString(stringResource(R.string.home_apk_download_description_any, download.appName))
    }

    AppDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.home_apk_download_title),
        description = description,
        accentColor = rememberAppColor(download.packageName),
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                when (phase) {
                    is OriginalDownloadPhase.Mismatch -> {
                        AppDialogButton(
                            text = stringResource(R.string.home_apk_download_use_anyway),
                            onClick = onAcceptMismatch,
                            modifier = Modifier.fillMaxWidth()
                        )
                        // The profile can be corrected above, and this fetches that upload instead
                        AppDialogOutlinedButton(
                            text = stringResource(R.string.home_apk_download_action),
                            icon = MaterialSymbols.Rounded.Download,
                            onClick = onStart,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    is OriginalDownloadPhase.Saving, is OriginalDownloadPhase.Resolving -> Unit
                    else -> AppDialogButton(
                        text = stringResource(R.string.home_apk_download_action),
                        icon = MaterialSymbols.Rounded.Download,
                        onClick = onStart,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (phase !is OriginalDownloadPhase.Saving && phase !is OriginalDownloadPhase.Resolving) {
                    AppDialogOutlinedButton(
                        text = stringResource(R.string.home_apk_availability_have),
                        onClick = onHaveApk,
                        modifier = Modifier.fillMaxWidth()
                    )
                    AppDialogOutlinedButton(
                        text = stringResource(R.string.home_apk_download_website),
                        icon = MaterialSymbols.Rounded.Public,
                        onClick = onUseWebsite,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    ) {
        ProfileFields(
            profile = profile,
            detected = download.detected,
            enabled = !busy,
            onProfileChange = onProfileChange,
            onReset = onReset
        )
        download.densityFallback?.let { (wanted, used) ->
            Text(
                text = stringResource(R.string.home_apk_download_adjusted_dpi, dpiLabel(wanted), dpiLabel(used)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        when (phase) {
            is OriginalDownloadPhase.Mismatch -> Text(
                text = mismatchText(phase.fit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
            OriginalDownloadPhase.Failed -> Text(
                text = stringResource(R.string.home_apk_download_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
            OriginalDownloadPhase.NoMatch -> Text(
                text = stringResource(
                    R.string.home_apk_download_no_match,
                    profile.abi,
                    dpiLabel(profile.dpi),
                    profile.androidSdk.androidVersionName()
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
            is OriginalDownloadPhase.Resolving -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
            is OriginalDownloadPhase.Saving -> {
                val total = phase.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (phase.read.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    )
                    Text(
                        text = stringResource(R.string.home_apk_download_saving) +
                            " ${(phase.read * 100 / total).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    Text(
                        text = stringResource(R.string.home_apk_download_saving),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            is OriginalDownloadPhase.Browsing -> MirrorPage(
                url = phase.url,
                onPage = onPage,
                onFileUrl = onFileUrl
            )
            OriginalDownloadPhase.Editing -> Unit
        }
    }
}

@Composable
private fun ProfileFields(
    profile: ApkPhoneProfile,
    detected: ApkPhoneProfile,
    enabled: Boolean,
    onProfileChange: (ApkPhoneProfile) -> Unit,
    onReset: () -> Unit
) {
    val customized = profile != detected
    Text(
        text = stringResource(
            if (customized) R.string.home_apk_download_custom else R.string.home_apk_download_detected
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
    if (customized) {
        AppDialogOutlinedButton(
            text = stringResource(R.string.home_apk_download_reset),
            onClick = onReset,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)
        )
    }
    AppDialogDropdownTextField(
        value = profile.abi,
        onValueChange = { onProfileChange(profile.copy(abi = it)) },
        dropdownItems = ApkPhoneProfiles.ABIS.associateWith { it },
        allowCustomValue = false,
        enabled = enabled,
        label = { Text(stringResource(R.string.home_apk_download_cpu)) },
        modifier = Modifier.padding(top = 8.dp)
    )
    val dpiAll = stringResource(R.string.home_apk_download_dpi_all)
    val dpiPattern = stringResource(R.string.home_apk_download_dpi_value, VALUE_TOKEN)
    AppDialogDropdownTextField(
        value = profile.dpi,
        onValueChange = { onProfileChange(profile.copy(dpi = it)) },
        dropdownItems = ApkPhoneProfiles.DPI_BUCKETS.associate { dpi ->
            (if (dpi == "nodpi") dpiAll else dpiPattern.withValue(dpi.removeSuffix("dpi"))) to dpi
        },
        allowCustomValue = false,
        enabled = enabled,
        label = { Text(stringResource(R.string.home_apk_download_screen)) },
        modifier = Modifier.padding(top = 8.dp)
    )
    val sdkChoices = remember(profile.androidSdk) {
        (ApkPhoneProfiles.ANDROID_SDKS + profile.androidSdk).distinct().sorted()
    }
    val androidPattern = stringResource(R.string.home_apk_download_android_value, VALUE_TOKEN)
    AppDialogDropdownTextField(
        value = profile.androidSdk.toString(),
        onValueChange = { onProfileChange(profile.copy(androidSdk = it.toInt())) },
        dropdownItems = sdkChoices.associate { sdk ->
            androidPattern.withValue(sdk.androidVersionName()) to sdk.toString()
        },
        allowCustomValue = false,
        enabled = enabled,
        label = { Text(stringResource(R.string.home_apk_download_android)) },
        modifier = Modifier.padding(top = 8.dp)
    )
    val languages = remember(profile.language, detected.language) {
        (listOfNotNull(profile.language, detected.language) + ApkPhoneProfiles.LANGUAGES).distinct()
    }
    val allLanguages = stringResource(R.string.home_apk_download_all_languages)
    val languageItems = remember(allLanguages, languages) {
        buildMap {
            put(allLanguages, "")
            languages.forEach { code -> put(languageLabel(code), code) }
        }
    }
    AppDialogDropdownTextField(
        value = profile.language.orEmpty(),
        onValueChange = { onProfileChange(profile.copy(language = it.ifBlank { null })) },
        dropdownItems = languageItems,
        allowCustomValue = false,
        enabled = enabled,
        label = { Text(stringResource(R.string.home_apk_download_language)) },
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun dpiLabel(dpi: String): String = if (dpi == "nodpi") {
    stringResource(R.string.home_apk_download_dpi_all)
} else {
    stringResource(R.string.home_apk_download_dpi_value, dpi.removeSuffix("dpi"))
}

@Composable
private fun mismatchText(fit: ApkFit): String = when (fit) {
    is ApkFit.WrongAbi -> stringResource(
        R.string.home_apk_download_wrong_abi,
        fit.found.joinToString(),
        fit.wanted
    )
    is ApkFit.WrongDensity -> stringResource(
        R.string.home_apk_download_wrong_dpi,
        fit.found.joinToString(),
        dpiLabel(fit.wanted)
    )
    is ApkFit.WrongLanguage -> stringResource(R.string.home_apk_download_wrong_language, languageLabel(fit.wanted))
    ApkFit.Ok -> ""
}

private fun languageLabel(code: String): String {
    val locale = Locale.forLanguageTag(code)
    val name = locale.getDisplayLanguage(locale)
    return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        .ifBlank { code }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MirrorPage(
    url: String,
    onPage: (url: String, snapshot: app.morphe.manager.domain.apk.MirrorPageSnapshot) -> Unit,
    onFileUrl: (url: String, userAgent: String?) -> Unit
) {
    Text(
        text = stringResource(R.string.home_apk_download_challenge),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(240.dp).padding(top = 8.dp),
        factory = { context ->
            MirrorWebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = browserUserAgent()
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                setDownloadListener { downloadUrl, agent, _, _, _ ->
                    onFileUrl(downloadUrl, agent)
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, finishedUrl: String?) {
                        val page = finishedUrl ?: return
                        val mirror = view as? MirrorWebView ?: return
                        if (view.url != null && view.url != page) return
                        view.evaluateJavascript(MIRROR_SNAPSHOT_JS) { raw ->
                            val snapshot = parseMirrorSnapshot(raw) ?: return@evaluateJavascript
                            if (snapshot.clickUptodown && snapshot.download.isBlank() &&
                                mirror.clickedUrl != page
                            ) {
                                mirror.clickedUrl = page
                                view.evaluateJavascript(
                                    "var b=document.querySelector('#detail-download-button'); if(b) b.click();",
                                    null
                                )
                            }
                            onPage(page, snapshot)
                        }
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val target = request.url?.toString().orEmpty()
                        if (request.isForMainFrame && isDirectApkUrl(target)) {
                            view.post { onFileUrl(target, view.settings.userAgentString) }
                            return WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
            }
        },
        update = { webView ->
            if (webView.loadedUrl != url) {
                webView.loadedUrl = url
                webView.loadUrl(url)
            }
        }
    )
}

private fun browserUserAgent(): String =
    "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
