package com.nuvio.app.features.player

import android.content.Context

/**
 * Android-local persistence for [ttsNarrationEnabled].
 *
 * Kept out of [PlayerSettingsStorage]/sync on purpose: TTS voice availability is
 * per-device (many TV boxes lack a Vietnamese voice), so syncing this toggle
 * across devices would enable narration where it cannot speak.
 */
internal object TtsNarrationPrefs {
    private const val FILE = "nuvio_tts_narration"
    private const val KEY_ENABLED = "tts_narration_enabled"
    private const val KEY_VOLUME = "tts_narration_volume"
    private const val KEY_VOICE = "tts_narration_voice"
    private const val KEY_DUCK = "tts_duck_volume"
    private const val KEY_SAVED_AT = "tts_saved_at_ms"

    /** Updates the last-save timestamp. Call after a batch of saves. */
    fun stamp(context: Context) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                .commit()
        }
    }

    fun load(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_ENABLED)) prefs.getBoolean(KEY_ENABLED, false) else null
    }

    fun save(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .commit()
    }

    fun loadVolume(context: Context): Int? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_VOLUME)) prefs.getInt(KEY_VOLUME, 100) else null
    }

    fun saveVolume(context: Context, volumePercent: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_VOLUME, volumePercent.coerceIn(0, 100))
            .commit()
    }

    fun loadVoice(context: Context): TtsNarrationVoice? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_VOICE, null) ?: return null
        return runCatching { TtsNarrationVoice.valueOf(name) }.getOrNull()
    }

    fun saveVoice(context: Context, voice: TtsNarrationVoice) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VOICE, voice.name)
            .commit()
    }

    fun loadDuckVolume(context: Context): Int? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_DUCK)) prefs.getInt(KEY_DUCK, 50) else null
    }

    /** Raw dump of the whole prefs file for diagnostics (key=value;). */
    fun rawSnapshot(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            prefs.all.entries.sortedBy { it.key }.joinToString(";") { (k, v) -> "$k=$v" }
        } catch (t: Throwable) {
            "err=${t.message?.take(40)}"
        }
    }

    fun saveDuckVolume(context: Context, volumePercent: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DUCK, volumePercent.coerceIn(5, 100))
            .commit()
    }
}
