package com.librelookai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WeatherUiState(
    val data: WeatherData? = null,
    val isLoading: Boolean = false,
)

class WeatherViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = WeatherRepository(app)

    private val _state = MutableStateFlow(WeatherUiState())
    val state: StateFlow<WeatherUiState> = _state.asStateFlow()

    init {
        // Show cached data immediately, then refresh if stale
        val cached = repo.loadCached()
        _state.update { it.copy(data = cached) }
        if (cached == null || !cached.isFresh) refresh()
    }

    /** Fetches fresh weather data. Safe to call multiple times. */
    fun refresh() {
        if (_state.value.isLoading) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val fresh = repo.fetchFresh()
            _state.update { it.copy(data = fresh ?: it.data, isLoading = false) }
        }
    }
}
