package com.librelookai.weather
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
    /** 7-day local forecast (today inclusive) — fetched lazily on demand. */
    val localForecast: List<com.librelookai.data.model.DayForecast> = emptyList(),
    val isLocalForecastLoading: Boolean = false,
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

    /**
     * Fetches a 7-day forecast for the device's current location. Cached in-memory for the
     * lifetime of the ViewModel; subsequent calls are no-ops while the previous load is in
     * flight or already succeeded.
     */
    fun refreshLocalForecast() {
        val cur = _state.value
        if (cur.isLocalForecastLoading || cur.localForecast.isNotEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isLocalForecastLoading = true) }
            val forecast = repo.fetchLocalForecast(days = 7)
            _state.update { it.copy(localForecast = forecast.orEmpty(), isLocalForecastLoading = false) }
        }
    }
}
