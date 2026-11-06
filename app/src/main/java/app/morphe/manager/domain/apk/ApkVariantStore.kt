/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.domain.apk

import app.morphe.manager.domain.manager.PreferencesManager
import kotlinx.serialization.json.Json

/**
 * The per-app corrections to [ApkPhoneProfile].
 *
 * Kept off the settings export: a correction belongs to the phone it was made on.
 */
class ApkVariantStore(private val prefs: PreferencesManager) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun read(packageName: String): ApkVariantOverride? = decode()[packageName]

    suspend fun write(packageName: String, override: ApkVariantOverride?) {
        val map = decode().toMutableMap()
        if (override == null || override.isEmpty()) map.remove(packageName) else map[packageName] = override
        prefs.apkVariantOverrides.update(if (map.isEmpty()) "" else json.encodeToString(map))
    }

    private suspend fun decode(): Map<String, ApkVariantOverride> {
        val raw = prefs.apkVariantOverrides.get()
        if (raw.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, ApkVariantOverride>>(raw) }
            .getOrDefault(emptyMap())
    }
}
