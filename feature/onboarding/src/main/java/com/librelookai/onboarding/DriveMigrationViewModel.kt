package com.librelookai.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.librelookai.core.sync.BuildConfig
import com.librelookai.data.drive.DriveRepository
import com.librelookai.data.drive.migrateLegacyInto
import com.librelookai.service.JobForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface MigrationState {
    data object Idle : MigrationState
    /** Looking for an existing legacy wardrobe to import (auto-detect, before any copy). */
    data object Detecting : MigrationState
    /** Detection finished with nothing to import (new account, or migration already done). */
    data object NothingToMigrate : MigrationState
    data class Running(val copied: Int, val total: Int) : MigrationState
    data class Done(val rootId: String) : MigrationState
    data object Error : MigrationState
}

/**
 * Runs the one-time legacy→app-folder migration ([DriveRepository.migrateLegacyInto]) off the
 * composable lifecycle. `viewModelScope` survives recomposition / pager moves / Dialog dismissal
 * (the VM is activity-scoped), and [JobForegroundService] keeps the process alive for the duration
 * — copying hundreds of files in a `rememberCoroutineScope` previously got cancelled / timed out.
 *
 * Onboarding calls [detect] the moment Drive sign-in lands: on the full-`drive` migration build it
 * auto-finds the legacy folder ([DriveRepository.findLegacyMigrationSource]) and starts the copy
 * with no manual folder picker. [migrate] stays public for the picker fallback offered on error.
 */
@HiltViewModel
class DriveMigrationViewModel @Inject constructor(
    app: Application,
    private val repo: DriveRepository,
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<MigrationState>(MigrationState.Idle)
    val state: StateFlow<MigrationState> = _state.asStateFlow()

    // Last auto-detected legacy folder, so the on-error "Try again" can re-run without re-detecting.
    private var detectedLegacy: String? = null
    private var detectStarted = false

    /** Already-migrated root folder ID (persisted), so onboarding shows "done" on re-entry. */
    fun alreadyMigratedRoot(): String? = repo.pickedRootFolderId()

    /**
     * Auto-detect-and-migrate. No-op after the first call (per VM, which is activity-scoped). On the
     * production `drive.file` build there's no legacy to read, so it resolves to [NothingToMigrate].
     */
    fun detect() {
        if (detectStarted) return
        detectStarted = true
        if (!BuildConfig.DRIVE_FULL_SCOPE || repo.pickedRootFolderId() != null) {
            _state.value = MigrationState.NothingToMigrate
            return
        }
        _state.value = MigrationState.Detecting
        viewModelScope.launch {
            val legacy = runCatching { repo.findLegacyMigrationSource() }.getOrNull()
            if (legacy == null) {
                _state.value = MigrationState.NothingToMigrate
            } else {
                detectedLegacy = legacy
                migrate(legacy)
            }
        }
    }

    /** Re-run the auto-detected migration after an error (the "Try again" affordance). */
    fun retry() { detectedLegacy?.let { migrate(it) } }

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
