package com.librelookai.tryon

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.librelookai.data.model.TryOn
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TryOnHistoryUiState(
    /** Past try-ons, newest-first — mirror of the store-derived [TryOnRepository.history]. */
    val history: List<TryOn> = emptyList(),
    /** Pre-formatted error message; prefer [errorRes] (see [TryOnUiState.error] for why). */
    val error: String? = null,
    /** String resource for the error dialog body, resolved in the composable (app locale). */
    val errorRes: Int? = null,
)

/**
 * The **history** half of the try-on VM split (refactor § 5 slice 9): the past-try-ons feed,
 * detail deletes, and the Drive refresh. The composer half stays on [TryOnViewModel]; both go
 * through [TryOnRepository], so the two VMs never call each other — a composer save reaches
 * this VM's [TryOnHistoryUiState.history] via the repo's store-derived flow.
 */
@HiltViewModel
class TryOnHistoryViewModel @Inject constructor(
    app: Application,
    private val repo: TryOnRepository,
) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(TryOnHistoryUiState())
    val state: StateFlow<TryOnHistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repo.history.collect { history -> _state.update { it.copy(history = history) } }
        }
        // Pre-warm on creation (the VM is activity-scoped, created at app start) so the history
        // feed paints instantly — replaces the AppContent pre-warm bridge (§ 5). The derived
        // history paints the store by itself; this runs the Phase-2 Drive refresh.
        refresh()
    }

    /** Phase-2 Drive refresh (the derived history paints the store by itself — Phase 1). */
    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.refreshFromDrive()
            } catch (e: Exception) {
                Log.w("TryOnHistoryVM", "refresh failed: ${e.message}")
            }
        }
    }

    fun deleteTryOn(tryOn: TryOn) = deleteTryOns(listOf(tryOn))

    /**
     * Batch-deletes [tryOns] in a single Drive JSON write. Also used when wardrobe items are
     * deleted and the user opts to cascade the removal to try-ons that wore those items.
     */
    fun deleteTryOns(tryOns: List<TryOn>) {
        if (tryOns.isEmpty()) return
        viewModelScope.launch {
            try {
                repo.deleteTryOns(tryOns)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        error = e.message,
                        errorRes = if (e.message == null) com.librelookai.R.string.error_delete_failed else null,
                    )
                }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null, errorRes = null) }
}
