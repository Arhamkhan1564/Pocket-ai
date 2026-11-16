package com.pocketai

import android.content.Context

/** Small settings only. Knowledge and conversation data live in SQLite. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("pocket_settings", Context.MODE_PRIVATE)
    var setupComplete: Boolean
        get() = prefs.getBoolean("setup", false)
        set(value) { prefs.edit().putBoolean("setup", value).apply() }
    var thirdLanguage: String
        get() = prefs.getString("third_language", "en") ?: "en"
        set(value) { prefs.edit().putString("third_language", value).apply() }
    var punjabiScript: String
        get() = prefs.getString("punjabi_script", "Arab") ?: "Arab"
        set(value) { prefs.edit().putString("punjabi_script", value).apply() }
    var responseLanguage: String
        get() = prefs.getString("response_language", "en") ?: "en"
        set(value) { prefs.edit().putString("response_language", value).apply() }
    var speechEnabled: Boolean
        get() = prefs.getBoolean("speech", true)
        set(value) { prefs.edit().putBoolean("speech", value).apply() }
    var speechRate: Float
        get() = prefs.getFloat("speech_rate", .9f)
        set(value) { prefs.edit().putFloat("speech_rate", value.coerceIn(.5f, 1.5f)).apply() }
    var voiceVolume: Float
        get() = prefs.getFloat("volume", .8f)
        set(value) { prefs.edit().putFloat("volume", value.coerceIn(0f, 1f)).apply() }
    var contextSize: Int
        get() = prefs.getInt("context_size", 512)
        set(value) { prefs.edit().putInt("context_size", value.coerceIn(256, 2048)).apply() }
    var selectedLlmId: String?
        get() = prefs.getString("llm_id", null)
        set(value) { prefs.edit().putString("llm_id", value).apply() }
    var selectedWhisperId: String?
        get() = prefs.getString("whisper_id", null)
        set(value) { prefs.edit().putString("whisper_id", value).apply() }
}
