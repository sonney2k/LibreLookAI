package com.librelookai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StylesUiState(
    val styles: List<Style> = emptyList(),
    val isLoading: Boolean = false,
    val isCreating: Boolean = false,
    val draftItemIds: Set<String> = emptySet(),
    val draftStyleName: String = "",
    /** Non-null when editing an existing style; null when creating a new one. */
    val editingStyle: Style? = null,
    val showNameDialog: Boolean = false,
    val error: String? = null,
)

class StylesViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app, GoogleAuthManager(app))
    private val gson = Gson()
    private var folderId: String? = null

    private val _state = MutableStateFlow(StylesUiState())
    val state: StateFlow<StylesUiState> = _state.asStateFlow()

    init { loadStyles() }

    // ---------- Load ----------

    fun loadStyles() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                val json = drive.loadStylesJson(id)
                if (json != null) {
                    val type = object : TypeToken<List<Style>>() {}.type
                    gson.fromJson<List<Style>>(json, type) ?: emptyList()
                } else emptyList()
            }.onSuccess { styles ->
                _state.update { it.copy(styles = styles, isLoading = false) }
            }.onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    // ---------- Create flow ----------

    fun startCreating() = _state.update {
        it.copy(isCreating = true, draftItemIds = emptySet(), draftStyleName = "", editingStyle = null)
    }

    fun startCreatingFromItems(itemIds: Set<String>) = _state.update {
        it.copy(isCreating = true, draftItemIds = itemIds, draftStyleName = "", editingStyle = null)
    }

    fun startEditing(style: Style) = _state.update {
        it.copy(isCreating = true, draftItemIds = style.itemIds.toSet(), draftStyleName = style.name, editingStyle = style)
    }

    fun updateDraftName(name: String) = _state.update { it.copy(draftStyleName = name) }

    fun cancelCreating() = _state.update {
        it.copy(isCreating = false, draftItemIds = emptySet(), draftStyleName = "", editingStyle = null, showNameDialog = false)
    }

    fun toggleDraftItem(driveId: String) = _state.update { s ->
        val next = s.draftItemIds.toMutableSet()
        if (!next.add(driveId)) next.remove(driveId)
        s.copy(draftItemIds = next)
    }

    fun confirmDraft() {
        val s = _state.value
        if (s.draftItemIds.isEmpty()) return
        if (s.editingStyle != null) {
            // Editing: name was set inline — save immediately without the dialog
            saveStyle(s.draftStyleName.ifEmpty { s.editingStyle.name })
        } else {
            _state.update { it.copy(showNameDialog = true) }
        }
    }

    fun saveStyle(name: String) {
        val s = _state.value
        val draftIds = s.draftItemIds
        if (draftIds.isEmpty()) return
        viewModelScope.launch {
            val resolvedName = name.trim().ifEmpty {
                s.editingStyle?.name ?: "Style ${s.styles.size + 1}"
            }
            val updated = if (s.editingStyle != null) {
                val edited = s.editingStyle.copy(name = resolvedName, itemIds = draftIds.toList())
                s.styles.map { if (it.id == edited.id) edited else it }
            } else {
                s.styles + Style(name = resolvedName, itemIds = draftIds.toList())
            }
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                drive.saveStylesJson(id, gson.toJson(updated))
            }.onSuccess {
                _state.update { it.copy(styles = updated, isCreating = false, draftItemIds = emptySet(), editingStyle = null, showNameDialog = false) }
            }.onFailure { e ->
                _state.update { it.copy(showNameDialog = false, error = e.message) }
            }
        }
    }

    // ---------- Delete ----------

    fun deleteStyle(styleId: String) {
        val updated = _state.value.styles.filter { it.id != styleId }
        viewModelScope.launch {
            runCatching {
                val id = folderId ?: drive.getOrCreateFolder().also { folderId = it }
                drive.saveStylesJson(id, gson.toJson(updated))
            }.onSuccess {
                _state.update { it.copy(styles = updated) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }
}
