package com.librelookai.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.librelookai.auth.GoogleAuthManager
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.migrateLegacyInto
import com.librelookai.service.JobForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MigrationState {
    data object Idle : MigrationState
    data class Running(val copied: Int, val total: Int) : MigrationState
    data class Done(val rootId: String) : MigrationState
    data object Error : MigrationState
}

/**
 * Runs the one-time legacy→app-folder migration ([DriveRepository.migrateLegacyInto]) off the
 * composable lifecycle. `viewModelScope` survives recomposition / pager moves / Dialog dismissal
 * (the VM is activity-scoped), and [JobForegroundService] keeps the process alive for the duration
 * — copying hundreds of files in a `rememberCoroutineScope` previously got cancelled / timed out.
 */
class DriveMigrationViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = DriveRepository(app, GoogleAuthManager(app))

    private val _state = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val state: StateFlow<MigrationState> = _state.asStateFlow()

    /** Already-migrated root folder ID (persisted), so onboarding shows "done" on re-entry. */
    fun alreadyMigratedRoot(): String? = repo.pickedRootFolderId()

    fun migrate(legacyFolderId: String) {
        if (_state.value is MigrationState.Running) return
        _state.value = MigrationState.Running(0, 0)
        val app = getApplication<Application>()
        JobForegroundService.acquire(app)
        viewModelScope.launch {
            val result = runCatching {
                repo.migrateLegacyInto(legacyFolderId) { copied, total ->
                    _state.value = MigrationState.Running(copied, total)
                }
            }
            JobForegroundService.release(app)
            _state.value = result.fold(
                onSuccess = { MigrationState.Done(it) },
                onFailure = { MigrationState.Error },
            )
        }
    }
}
