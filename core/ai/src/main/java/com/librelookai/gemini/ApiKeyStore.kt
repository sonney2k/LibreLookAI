package com.librelookai.gemini
import android.content.Context

/**
 * Local storage for the user-supplied Gemini API key.
 * Stored in SharedPreferences (device-local, never synced to Drive).
 */
object ApiKeyStore {
    private const val PREFS_NAME = "librelookai_local"
    private const val KEY_GEMINI  = "gemini_api_key"

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_GEMINI, "") ?: ""

    fun set(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_GEMINI, key.trim()).apply()
    }
}
