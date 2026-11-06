/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.apk

import kotlinx.serialization.Serializable

/**
 * The phone a YouTube or YouTube Music APK is chosen for.
 *
 * [language] is a two-letter language, or null when the download should keep every language
 * the file ships. [dpi] is an APKMirror bucket such as `480dpi` or `nodpi`.
 */
data class ApkPhoneProfile(
    val abi: String,
    val dpi: String,
    val androidSdk: Int,
    val language: String?
)

/**
 * Fields the user changed after the phone was read. A null field means "keep what the phone
 * says", so a later language or density change is still picked up for anything left alone.
 */
@Serializable
data class ApkVariantOverride(
    val abi: String? = null,
    val dpi: String? = null,
    val androidSdk: Int? = null,
    val allLanguages: Boolean = false,
    val language: String? = null
) {
    fun applyTo(detected: ApkPhoneProfile) = ApkPhoneProfile(
        abi = abi ?: detected.abi,
        dpi = dpi ?: detected.dpi,
        androidSdk = androidSdk ?: detected.androidSdk,
        language = if (allLanguages) null else language ?: detected.language
    )

    fun isEmpty() = abi == null && dpi == null && androidSdk == null && !allLanguages && language == null
}

/** What of [detected] this profile replaces, or null when the two are the same. */
fun ApkPhoneProfile.overrideOf(detected: ApkPhoneProfile): ApkVariantOverride? {
    val override = ApkVariantOverride(
        abi = abi.takeIf { it != detected.abi },
        dpi = dpi.takeIf { it != detected.dpi },
        androidSdk = androidSdk.takeIf { it != detected.androidSdk },
        allLanguages = language == null && detected.language != null,
        language = language?.takeIf { it != detected.language }
    )
    return override.takeUnless { it.isEmpty() }
}

object ApkPhoneProfiles {
    val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    val DPI_BUCKETS = listOf("nodpi", "120dpi", "160dpi", "240dpi", "320dpi", "480dpi", "640dpi")

    /** SDK levels APKMirror splits uploads on, newest last. */
    val ANDROID_SDKS = listOf(23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36)

    val LANGUAGES = listOf(
        "en", "es", "pt", "de", "fr", "it", "nl", "pl", "ru", "uk",
        "tr", "ar", "hi", "id", "vi", "th", "ja", "ko", "zh"
    )

    /**
     * The profile [supportedAbis], [densityDpi], [sdk] and [localeTags] describe.
     *
     * The first ABI the phone can run that these apps actually ship wins. Density is the
     * bucket the screen falls in, not `nodpi`: a density-specific upload is smaller, and the
     * caller falls back to `nodpi` when that bucket was not published.
     */
    fun detected(
        supportedAbis: List<String>,
        densityDpi: Int,
        sdk: Int,
        localeTags: List<String>
    ): ApkPhoneProfile {
        val abi = ABIS.firstOrNull { it in supportedAbis }
            ?: supportedAbis.firstOrNull { it.isNotBlank() }
            ?: ABIS.first()
        val language = localeTags.firstNotNullOfOrNull { tag ->
            tag.substringBefore('-').substringBefore('_').takeIf { it.length in 2..3 && it.all(Char::isLetter) }
        }
        return ApkPhoneProfile(
            abi = abi,
            dpi = densityBucket(densityDpi),
            androidSdk = sdk,
            language = language?.lowercase()
        )
    }

    /** The APKMirror density bucket for a screen of [densityDpi]. */
    fun densityBucket(densityDpi: Int): String = when {
        densityDpi <= 120 -> "120dpi"
        densityDpi <= 160 -> "160dpi"
        densityDpi <= 240 -> "240dpi"
        densityDpi <= 320 -> "320dpi"
        densityDpi <= 480 -> "480dpi"
        else -> "640dpi"
    }

    /** Resource qualifier a [dpi] bucket corresponds to, or null for `nodpi`. */
    fun densityQualifier(dpi: String): String? = when (dpi.lowercase()) {
        "120dpi" -> "ldpi"
        "160dpi" -> "mdpi"
        "240dpi" -> "hdpi"
        "320dpi" -> "xhdpi"
        "480dpi" -> "xxhdpi"
        "640dpi" -> "xxxhdpi"
        else -> null
    }
}
