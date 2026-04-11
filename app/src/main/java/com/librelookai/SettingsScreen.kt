package com.librelookai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    profileViewModel: ProfileViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    creditsViewModel: CreditsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val profileState  by profileViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
    val creditsState  by creditsViewModel.state.collectAsState()

    val context = LocalContext.current
    var currentApiKey by remember { mutableStateOf(ApiKeyStore.get(context)) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> uri?.let { wardrobeViewModel.importFromFolder(it) } }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(R.string.settings_tab_profile)) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(stringResource(R.string.settings_tab_data)) })
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(stringResource(R.string.settings_tab_credits))
                        if (creditsState.isManagedMode && creditsState.balance > 0) {
                            Spacer(Modifier.padding(start = 4.dp))
                            androidx.compose.material3.Badge { Text("${creditsState.balance}") }
                        }
                    }
                },
            )
        }

        when (selectedTab) {
            0 -> ProfileTab(
                state = profileState,
                onSave = profileViewModel::savePreferences,
                onClearSavedFlag = profileViewModel::clearSavedFlag,
                onClearError = profileViewModel::clearError,
                currentApiKey = currentApiKey,
                onSaveApiKey = { key ->
                    ApiKeyStore.set(context, key)
                    currentApiKey = key
                },
            )
            1 -> DataTab(
                wardrobeState = wardrobeState,
                onRetagAll = wardrobeViewModel::retagAll,
                onRemoveAllBackgrounds = wardrobeViewModel::removeAllBackgrounds,
                onImportFromFolder = { importLauncher.launch(null) },
                locationState = locationState,
                onSetActiveLocation = locationViewModel::setActiveLocation,
                onAddLocation = locationViewModel::addLocation,
                onRenameLocation = locationViewModel::renameLocation,
                onDeleteLocation = locationViewModel::deleteLocation,
            )
            2 -> BuyCreditsScreen(
                creditsViewModel = creditsViewModel,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---------- Profile tab ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileTab(
    state: ProfileUiState,
    onSave: (UserPreferences) -> Unit,
    onClearSavedFlag: () -> Unit,
    onClearError: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    currentApiKey: String,
) {
    val genderOptions = listOf(
        stringResource(R.string.settings_gender_prefer_not),
        stringResource(R.string.settings_gender_female),
        stringResource(R.string.settings_gender_male),
        stringResource(R.string.settings_gender_nonbinary),
        stringResource(R.string.settings_gender_other),
    )

    var gender      by remember(state.preferences) { mutableStateOf(state.preferences.gender) }
    var yearOfBirth by remember(state.preferences) { mutableStateOf(state.preferences.yearOfBirth?.toString() ?: "") }
    var preferences by remember(state.preferences) { mutableStateOf(state.preferences.preferences) }
    var language    by remember(state.preferences) { mutableStateOf(state.preferences.language) }
    var apiKey      by remember(currentApiKey) { mutableStateOf(currentApiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            gender      = state.preferences.gender
            yearOfBirth = state.preferences.yearOfBirth?.toString() ?: ""
            preferences = state.preferences.preferences
            language    = state.preferences.language
        }
    }
    LaunchedEffect(currentApiKey) { apiKey = currentApiKey }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // --- Language ---
                var languageExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = it },
                ) {
                    OutlinedTextField(
                        value = language.ifEmpty { stringResource(R.string.settings_gender_select) },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.settings_language)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false },
                    ) {
                        AppLanguage.options.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = { language = option; languageExpanded = false },
                            )
                        }
                    }
                }

                // --- Gender ---
                var genderExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = genderExpanded,
                    onExpandedChange = { genderExpanded = it },
                ) {
                    OutlinedTextField(
                        value = gender.ifEmpty { stringResource(R.string.settings_gender_select) },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.settings_gender)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = genderExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = genderExpanded,
                        onDismissRequest = { genderExpanded = false },
                    ) {
                        genderOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = { gender = option; genderExpanded = false },
                            )
                        }
                    }
                }

                // --- Year of birth ---
                OutlinedTextField(
                    value = yearOfBirth,
                    onValueChange = { v ->
                        if (v.length <= 4 && v.all { it.isDigit() }) yearOfBirth = v
                    },
                    label = { Text(stringResource(R.string.settings_year_of_birth)) },
                    placeholder = { Text(stringResource(R.string.settings_year_placeholder)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // --- Style preferences ---
                OutlinedTextField(
                    value = preferences,
                    onValueChange = { preferences = it },
                    label = { Text(stringResource(R.string.settings_style_prefs)) },
                    placeholder = { Text(stringResource(R.string.settings_style_placeholder)) },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )

                // --- Gemini API Key ---
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.settings_api_key_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.settings_api_key_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.settings_api_key_label)) },
                        placeholder = { Text(stringResource(R.string.settings_api_key_placeholder)) },
                        singleLine = true,
                        visualTransformation = if (apiKeyVisible) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    if (apiKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // --- Save ---
                Button(
                    onClick = {
                        onSaveApiKey(apiKey)
                        onSave(
                            UserPreferences(
                                gender      = gender,
                                yearOfBirth = yearOfBirth.toIntOrNull(),
                                preferences = preferences,
                                language    = language,
                            )
                        )
                    },
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).align(Alignment.CenterVertically),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Text(if (state.isSaving) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
                }
            }
        }

        if (state.savedSuccessfully) {
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = onClearSavedFlag) { Text(stringResource(R.string.action_ok)) } },
            ) { Text(stringResource(R.string.settings_saved)) }
        }

        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = onClearError) { Text(stringResource(R.string.action_dismiss)) } },
            ) { Text(msg) }
        }
    }
}

// ---------- Data tab ----------

@Composable
private fun DataTab(
    wardrobeState: WardrobeUiState,
    onRetagAll: () -> Unit,
    onRemoveAllBackgrounds: () -> Unit,
    onImportFromFolder: () -> Unit,
    locationState: LocationUiState,
    onSetActiveLocation: (String) -> Unit,
    onAddLocation: (String) -> Unit,
    onRenameLocation: (String, String) -> Unit,
    onDeleteLocation: (String) -> Unit,
) {
    var showRetagDialog by remember { mutableStateOf(false) }
    var showRemoveBgDialog by remember { mutableStateOf(false) }
    var showAddLocationDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Location?>(null) }
    var deleteTarget by remember { mutableStateOf<Location?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // ---------- Locations ----------
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_locations_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.settings_locations_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            locationState.locations.forEach { location ->
                val isActive = location.id == locationState.activeLocationId
                Surface(
                    shape = MaterialTheme.shapes.small,
                    tonalElevation = if (isActive) 2.dp else 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isActive) { onSetActiveLocation(location.id) },
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isActive) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.settings_location_active),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(8.dp))
                        } else {
                            Spacer(Modifier.size(26.dp))
                        }
                        Text(
                            location.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { renameTarget = location }) {
                            Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.settings_location_rename_title), modifier = Modifier.size(18.dp))
                        }
                        IconButton(
                            onClick = { deleteTarget = location },
                            enabled = locationState.locations.size > 1,
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.settings_location_delete_title),
                                modifier = Modifier.size(18.dp),
                                tint = if (locationState.locations.size > 1) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            )
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { showAddLocationDialog = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.padding(start = 8.dp))
                Text(stringResource(R.string.settings_location_add))
            }
        }

        HorizontalDivider()

        // ---------- Tags ----------
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_data_tags_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.settings_data_tags_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (wardrobeState.isRetagging) {
                val progress = if (wardrobeState.retagTotal > 0)
                    wardrobeState.retagDone.toFloat() / wardrobeState.retagTotal
                else 0f
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.settings_rescanning, wardrobeState.retagDone + 1, wardrobeState.retagTotal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                OutlinedButton(
                    onClick = { showRetagDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(stringResource(R.string.settings_rescan_button))
                }
            }
        }

        HorizontalDivider()

        // ---------- Background Removal ----------
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_rebg_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.settings_rebg_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (wardrobeState.isRemovingAllBg) {
                val progress = if (wardrobeState.removeBgTotal > 0)
                    wardrobeState.removeBgDone.toFloat() / wardrobeState.removeBgTotal
                else 0f
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.settings_rebging, wardrobeState.removeBgDone + 1, wardrobeState.removeBgTotal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                OutlinedButton(
                    onClick = { showRemoveBgDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(stringResource(R.string.settings_rebg_button))
                }
            }
        }

        HorizontalDivider()

        // ---------- Import ----------
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_import_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.settings_import_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (wardrobeState.isImporting) {
                val progress = if (wardrobeState.importTotal > 0)
                    wardrobeState.importDone.toFloat() / wardrobeState.importTotal
                else 0f
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.settings_importing, wardrobeState.importDone + 1, wardrobeState.importTotal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onImportFromFolder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(stringResource(R.string.settings_import_button))
                }
            }
        }

        HorizontalDivider()
    }

    // ---------- Dialogs ----------

    if (showRetagDialog) {
        AlertDialog(
            onDismissRequest = { showRetagDialog = false },
            title = { Text(stringResource(R.string.settings_rescan_dialog_title)) },
            text = { Text(stringResource(R.string.settings_rescan_dialog_text)) },
            confirmButton = {
                TextButton(onClick = { onRetagAll(); showRetagDialog = false }) {
                    Text(stringResource(R.string.settings_rescan_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRetagDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showRemoveBgDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveBgDialog = false },
            title = { Text(stringResource(R.string.settings_rebg_dialog_title)) },
            text = { Text(stringResource(R.string.settings_rebg_dialog_text)) },
            confirmButton = {
                TextButton(onClick = { onRemoveAllBackgrounds(); showRemoveBgDialog = false }) {
                    Text(stringResource(R.string.settings_rebg_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveBgDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (showAddLocationDialog) {
        var nameInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddLocationDialog = false; nameInput = "" },
            title = { Text(stringResource(R.string.settings_location_add_title)) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = { Text(stringResource(R.string.settings_location_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onAddLocation(nameInput); showAddLocationDialog = false; nameInput = "" },
                    enabled = nameInput.isNotBlank(),
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddLocationDialog = false; nameInput = "" }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    renameTarget?.let { target ->
        var nameInput by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.settings_location_rename_title)) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onRenameLocation(target.id, nameInput); renameTarget = null },
                    enabled = nameInput.isNotBlank(),
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.settings_location_delete_title)) },
            text = { Text(stringResource(R.string.settings_location_delete_text, target.name)) },
            confirmButton = {
                TextButton(onClick = { onDeleteLocation(target.id); deleteTarget = null }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
