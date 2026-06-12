package com.librelookai.data.session

import com.librelookai.settings.UserPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide read path for the user's preferences (refactor § 5 slice 2).
 *
 * [com.librelookai.settings.ProfileViewModel] remains the only writer — it owns the Drive
 * persistence and the SharedPrefs language/theme/font caches — and mirrors every loaded or
 * just-saved snapshot here. Consumers collect [preferences] instead of being pushed by the
 * old AppContent `LaunchedEffect` mirrors. The DataStore conversion stays § 7; this is the
 * seam it will slot into.
 */
@Singleton
class UserPreferencesRepository @Inject constructor() {
    private val _preferences = MutableStateFlow(UserPreferences())
    val preferences: StateFlow<UserPreferences> = _preferences.asStateFlow()

    /** Publisher for ProfileViewModel — the loaded or just-saved preferences snapshot. */
    fun publish(prefs: UserPreferences) {
        _preferences.value = prefs
    }
}
