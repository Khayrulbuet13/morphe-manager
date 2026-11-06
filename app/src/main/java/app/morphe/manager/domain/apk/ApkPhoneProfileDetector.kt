/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.apk

import android.content.res.Resources
import android.os.Build
import java.util.Locale

/**
 * The profile of the phone itself, ignoring any language or display size Morphe set for its
 * own screens. The download has to match the system the patched app will run in.
 */
fun detectApkPhoneProfile(): ApkPhoneProfile {
    val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val list = Resources.getSystem().configuration.locales
        (0 until list.size()).map { list[it].toLanguageTag() }
    } else {
        @Suppress("DEPRECATION")
        listOf(Locale.getDefault().toLanguageTag())
    }
    return ApkPhoneProfiles.detected(
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
        densityDpi = Resources.getSystem().displayMetrics.densityDpi,
        sdk = Build.VERSION.SDK_INT,
        localeTags = locales
    )
}
