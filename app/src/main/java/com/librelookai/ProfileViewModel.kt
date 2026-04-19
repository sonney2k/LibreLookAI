package com.librelookai

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
    val preferences: UserPreferences = UserPreferences(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val savedSuccessfully: Boolean = false,
    val error: String? = null,
)

class ProfileViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gson = Gson()
    private var folderId: String? = null
    private val langPrefs = app.getSharedPreferences(LANG_PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        ProfileUiState(
            preferences = UserPreferences(language = cachedLanguage())
        )
    )
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init { loadPreferences() }

    private fun cachedLanguage(): String =
        langPrefs.getString(KEY_LANGUAGE, null) ?: AppLanguage.ENGLISH

    private fun cacheLanguage(language: String) {
        langPrefs.edit().putString(KEY_LANGUAGE, language).apply()
    }

    companion object {
        private const val LANG_PREFS = "librelookai_lang"
        private const val KEY_LANGUAGE = "language"
    }

    fun loadPreferences() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                val json = drive.loadPreferencesJson(id)
                if (json != null) gson.fromJson(json, UserPreferences::class.java) ?: UserPreferences(language = cachedLanguage())
                else UserPreferences(language = cachedLanguage())
            }.onSuccess { prefs ->
                cacheLanguage(prefs.language)
                _state.update { it.copy(preferences = prefs, isLoading = false) }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun savePreferences(prefs: UserPreferences) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, savedSuccessfully = false, error = null) }
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                drive.savePreferencesJson(id, gson.toJson(prefs))
            }.onSuccess {
                cacheLanguage(prefs.language)
                _state.update { it.copy(preferences = prefs, isSaving = false, savedSuccessfully = true) }
            }.onFailure { e ->
                _state.update { it.copy(isSaving = false, error = e.message) }
            }
        }
    }

    fun clearSavedFlag() = _state.update { it.copy(savedSuccessfully = false) }
    fun clearError() = _state.update { it.copy(error = null) }
}
