package com.librelookai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    profileViewModel: ProfileViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    locationViewModel: LocationViewModel = viewModel(),
    creditsViewModel: CreditsViewModel = viewModel(),
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val profileState  by profileViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
    val creditsState  by creditsViewModel.state.collectAsState()

    val context = LocalContext.current
    var currentApiKey by remember { mutableStateOf(ApiKeyStore.get(context)) }

    // Options chosen in the import dialog are captured here so the launcher callback can read them
    var pendingImportRemoveBg    by rememberSaveable { mutableStateOf(false) }
    var pendingImportAutoTag     by rememberSaveable { mutableStateOf(false) }
    var pendingImportReplace     by rememberSaveable { mutableStateOf(false) }
    var pendingImportOverwrite   by rememberSaveable { mutableStateOf(false) }
    var showDriveFolderPicker    by rememberSaveable { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let {
            wardrobeViewModel.importFromFolder(
                treeUri = it,
                removeBackground = pendingImportRemoveBg,
                autoTag = pendingImportAutoTag,
                replaceExisting = pendingImportReplace,
                overwriteDuplicates = pendingImportOverwrite,
            )
        }
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        AppScreenHeader(
            title = stringResource(R.string.nav_settings),
            trailingContent = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
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
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text(stringResource(R.string.settings_tab_ai)) })
            Tab(selected = selectedTab == 4, onClick = { selectedTab = 4 }, text = { Text(stringResource(R.string.settings_tab_about)) })
        }

        when (selectedTab) {
            0 -> ProfileTab(
                state = profileState,
                onSave = profileViewModel::savePreferences,
                onClearSavedFlag = profileViewModel::clearSavedFlag,
                onClearError = profileViewModel::clearError,
                onUploadTryOnPhoto = profileViewModel::uploadTryOnPhoto,
                onRemoveTryOnPhoto = profileViewModel::deleteTryOnPhoto,
            )
            1 -> DataTab(
                wardrobeState = wardrobeState,
                onRetagAll = wardrobeViewModel::retagAll,
                onRemoveAllBackgrounds = wardrobeViewModel::removeAllBackgrounds,
                onStartRepairAndRefresh = {
                    wardrobeViewModel.startRepairAndRefresh(locationState.locations.map { it.folderId })
                },
                onContinueRepair = wardrobeViewModel::continueRepairProcessing,
                onDismissAudit = wardrobeViewModel::dismissAuditResult,
                onImportFromFolder = { removeBg, autoTag, replace, overwrite, useDrive ->
                    pendingImportRemoveBg  = removeBg
                    pendingImportAutoTag   = autoTag
                    pendingImportReplace   = replace
                    pendingImportOverwrite = overwrite
                    if (useDrive) {
                        showDriveFolderPicker = true
                    } else {
                        importLauncher.launch(null)
                    }
                },
                locationState = locationState,
                onSetActiveLocation = locationViewModel::setActiveLocation,
                onAddLocation = locationViewModel::addLocation,
                onRenameLocation = locationViewModel::renameLocation,
                onDeleteLocation = locationViewModel::deleteLocation,
            )
            2 -> BuyCreditsScreen(
                creditsViewModel = creditsViewModel,
                currentApiKey = currentApiKey,
                onSaveApiKey = { key ->
                    ApiKeyStore.set(context, key)
                    currentApiKey = key
                },
                modifier = Modifier.fillMaxSize(),
            )
            3 -> AiTab(
                preferences = profileState.preferences,
                onSaveConsiderations = { c ->
                    profileViewModel.savePreferences(profileState.preferences.copy(aiConsiderations = c))
                },
            )
            4 -> AboutTab()
        }
    }

    if (showDriveFolderPicker) {
        DriveFolderPickerDialog(
            wardrobeViewModel = wardrobeViewModel,
            onFolderSelected = { folderId ->
                showDriveFolderPicker = false
                wardrobeViewModel.importFromDriveFolder(
                    sourceFolderId = folderId,
                    removeBackground = pendingImportRemoveBg,
                    autoTag = pendingImportAutoTag,
                    replaceExisting = pendingImportReplace,
                    overwriteDuplicates = pendingImportOverwrite,
                )
            },
            onDismiss = { showDriveFolderPicker = false },
        )
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
    onUploadTryOnPhoto: (TryOnSlot, android.net.Uri) -> Unit,
    onRemoveTryOnPhoto: (TryOnSlot) -> Unit,
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

    var pendingTryOnSlot by remember { mutableStateOf<TryOnSlot?>(null) }
    val tryOnPickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val slot = pendingTryOnSlot
        pendingTryOnSlot = null
        if (uri != null && slot != null) onUploadTryOnPhoto(slot, uri)
    }

    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            gender      = state.preferences.gender
            yearOfBirth = state.preferences.yearOfBirth?.toString() ?: ""
            preferences = state.preferences.preferences
            language    = state.preferences.language
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
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

                // --- Outfit preferences ---
                OutlinedTextField(
                    value = preferences,
                    onValueChange = { preferences = it },
                    label = { Text(stringResource(R.string.settings_style_prefs)) },
                    placeholder = { Text(stringResource(R.string.settings_style_placeholder)) },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )

                // --- Try-on photos ---
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.tryon_section_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.tryon_section_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TryOnSlot.entries.forEach { slot ->
                            val label = when (slot) {
                                TryOnSlot.FRONT -> stringResource(R.string.tryon_slot_front)
                                TryOnSlot.SIDE  -> stringResource(R.string.tryon_slot_side)
                                TryOnSlot.BACK  -> stringResource(R.string.tryon_slot_back)
                            }
                            TryOnPhotoSlot(
                                label = label,
                                localPath = state.tryOnLocalPaths[slot],
                                isUploading = slot in state.tryOnUploading,
                                onPick = {
                                    pendingTryOnSlot = slot
                                    tryOnPickLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onRemove = { onRemoveTryOnPhoto(slot) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // --- Save ---
                Button(
                    onClick = {
                        onSave(
                            state.preferences.copy(
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

@Composable
private fun TryOnPhotoSlot(
    label: String,
    localPath: String?,
    isUploading: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            tonalElevation = 1.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clickable(enabled = !isUploading) { onPick() },
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    isUploading -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    localPath != null -> {
                        AsyncImage(
                            model = File(localPath),
                            contentDescription = label,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        IconButton(
                            onClick = onRemove,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.tryon_remove_photo, label),
                                tint = Color.White,
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .padding(3.dp),
                            )
                        }
                    }
                    else -> Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.tryon_add_photo, label),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (isUploading) {
            Text(
                stringResource(R.string.tryon_uploading),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------- Data tab ----------

@Composable
private fun DataTab(
    wardrobeState: WardrobeUiState,
    onRetagAll: () -> Unit,
    onRemoveAllBackgrounds: () -> Unit,
    onStartRepairAndRefresh: () -> Unit,
    onContinueRepair: (Boolean) -> Unit,
    onDismissAudit: () -> Unit,
    onImportFromFolder: (removeBackground: Boolean, autoTag: Boolean, replaceExisting: Boolean, overwriteDuplicates: Boolean, useDrivePicker: Boolean) -> Unit,
    locationState: LocationUiState,
    onSetActiveLocation: (String) -> Unit,
    onAddLocation: (String, String) -> Unit,
    onRenameLocation: (String, String, String?) -> Unit,
    onDeleteLocation: (String) -> Unit,
) {
    val isOffline = LocalIsOffline.current
    val audit = wardrobeState.auditProgress
    var showRetagDialog by remember { mutableStateOf(false) }
    var showRemoveBgDialog by remember { mutableStateOf(false) }
    var showImportOptionsDialog by remember { mutableStateOf(false) }
    var showAddLocationDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Location?>(null) }
    var deleteTarget by remember { mutableStateOf<Location?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
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
                val isActive = location.folderId == locationState.activeLocationId
                Surface(
                    shape = MaterialTheme.shapes.small,
                    tonalElevation = if (isActive) 2.dp else 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isActive) { onSetActiveLocation(location.folderId) },
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                location.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (location.geoLocation.isNotBlank()) {
                                Text(
                                    location.geoLocation,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
                    enabled = !isOffline,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
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
                    enabled = !isOffline,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(stringResource(R.string.settings_rebg_button))
                }
            }
        }

        HorizontalDivider()

        // ---------- Repair & Sync ----------
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_clear_cache_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.settings_clear_cache_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                audit?.isScanning == true -> {
                    val progress = if (audit.totalFolders > 0)
                        audit.scannedFolders.toFloat() / audit.totalFolders else 0f
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.settings_repair_scanning, audit.scannedFolders + 1, audit.totalFolders),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                audit?.isProcessing == true -> {
                    val progress = if (audit.processTotal > 0)
                        audit.processDone.toFloat() / audit.processTotal else 0f
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.settings_repair_processing, audit.processDone + 1, audit.processTotal),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                audit?.isDone == true -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.settings_repair_done),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissAudit) { Text(stringResource(R.string.action_ok)) }
                    }
                }
                else -> OutlinedButton(
                    onClick = onStartRepairAndRefresh,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !wardrobeState.isLoading && audit == null && !isOffline,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(stringResource(R.string.settings_clear_cache_button))
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
                    onClick = { showImportOptionsDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isOffline,
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.padding(start = 8.dp))
                    Text(stringResource(R.string.settings_import_button))
                }
                Text(
                    stringResource(R.string.settings_import_later_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()
    }

    // ---------- Dialogs ----------

    if (showRetagDialog) {
        val count = wardrobeState.images.size
        val cost  = count * CreditPacks.COST_CLASSIFY
        AlertDialog(
            onDismissRequest = { showRetagDialog = false },
            title = { Text(stringResource(R.string.settings_rescan_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_rescan_dialog_text))
                    Text(
                        stringResource(R.string.settings_bulk_cost_line, count, CreditPacks.COST_CLASSIFY, cost),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
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
        val count = wardrobeState.images.size
        val cost  = count * CreditPacks.COST_BG_REMOVAL
        AlertDialog(
            onDismissRequest = { showRemoveBgDialog = false },
            title = { Text(stringResource(R.string.settings_rebg_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_rebg_dialog_text))
                    Text(
                        stringResource(R.string.settings_bulk_cost_line, count, CreditPacks.COST_BG_REMOVAL, cost),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
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

    if (audit?.awaitingConfirmation == true) {
        AlertDialog(
            onDismissRequest = { onContinueRepair(false) },
            title = { Text(stringResource(R.string.settings_repair_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val hasFindings = audit.renamedCount > 0 || audit.orphanedOriginals > 0 || audit.rawImages > 0 || audit.sidecarNeeded > 0
                    if (!hasFindings) {
                        Text(stringResource(R.string.settings_repair_confirm_all_ok))
                    } else {
                        if (audit.renamedCount > 0) {
                            Text(stringResource(R.string.settings_repair_confirm_renamed, audit.renamedCount),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (audit.orphanedOriginals > 0) {
                            val cost = audit.orphanedOriginals * (CreditPacks.COST_BG_REMOVAL + CreditPacks.COST_CLASSIFY)
                            Text(stringResource(R.string.settings_repair_confirm_unprocessed, audit.orphanedOriginals),
                                style = MaterialTheme.typography.bodySmall)
                            Text(
                                stringResource(R.string.settings_bulk_cost_line, audit.orphanedOriginals,
                                    CreditPacks.COST_BG_REMOVAL + CreditPacks.COST_CLASSIFY, cost),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (audit.rawImages > 0) {
                            val cost = audit.rawImages * (CreditPacks.COST_BG_REMOVAL + CreditPacks.COST_CLASSIFY)
                            Text(stringResource(R.string.settings_repair_confirm_raw, audit.rawImages),
                                style = MaterialTheme.typography.bodySmall)
                            Text(
                                stringResource(R.string.settings_bulk_cost_line, audit.rawImages,
                                    CreditPacks.COST_BG_REMOVAL + CreditPacks.COST_CLASSIFY, cost),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (audit.sidecarNeeded > 0) {
                            val cost = audit.sidecarNeeded * CreditPacks.COST_CLASSIFY
                            Text(stringResource(R.string.settings_repair_confirm_sidecar, audit.sidecarNeeded),
                                style = MaterialTheme.typography.bodySmall)
                            Text(
                                stringResource(R.string.settings_bulk_cost_line, audit.sidecarNeeded,
                                    CreditPacks.COST_CLASSIFY, cost),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (audit.orphanedOriginals > 0 || audit.rawImages > 0 || audit.sidecarNeeded > 0) {
                    TextButton(onClick = { onContinueRepair(true) }) {
                        Text(stringResource(R.string.settings_repair_process))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { onContinueRepair(false) }) {
                    Text(stringResource(R.string.settings_repair_skip))
                }
            },
        )
    }

    if (showImportOptionsDialog) {
        ImportOptionsDialog(
            existingItemCount = wardrobeState.images.size,
            onConfirm = { removeBg, autoTag, replace, overwrite, useDrive ->
                showImportOptionsDialog = false
                onImportFromFolder(removeBg, autoTag, replace, overwrite, useDrive)
            },
            onDismiss = { showImportOptionsDialog = false },
        )
    }

    if (showAddLocationDialog) {
        var nameInput by remember { mutableStateOf("") }
        var geoInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddLocationDialog = false; nameInput = ""; geoInput = "" },
            title = { Text(stringResource(R.string.settings_location_add_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        placeholder = { Text(stringResource(R.string.settings_location_name_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = geoInput,
                        onValueChange = { geoInput = it },
                        placeholder = { Text(stringResource(R.string.settings_location_geo_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (nameInput.isNotBlank()) { onAddLocation(nameInput, geoInput); showAddLocationDialog = false; nameInput = ""; geoInput = "" }
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onAddLocation(nameInput, geoInput); showAddLocationDialog = false; nameInput = ""; geoInput = "" },
                    enabled = nameInput.isNotBlank(),
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddLocationDialog = false; nameInput = ""; geoInput = "" }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    renameTarget?.let { target ->
        var nameInput by remember(target.id) { mutableStateOf(target.name) }
        var geoInput by remember(target.id) { mutableStateOf(target.geoLocation) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text(stringResource(R.string.settings_location_rename_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        placeholder = { Text(stringResource(R.string.settings_location_name_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = geoInput,
                        onValueChange = { geoInput = it },
                        placeholder = { Text(stringResource(R.string.settings_location_geo_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (nameInput.isNotBlank()) { onRenameLocation(target.folderId, nameInput, geoInput); renameTarget = null }
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onRenameLocation(target.folderId, nameInput, geoInput); renameTarget = null },
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
                TextButton(onClick = { onDeleteLocation(target.folderId); deleteTarget = null }) {
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

// ---------- Import options dialog ----------

@Composable
private fun ImportOptionsDialog(
    existingItemCount: Int,
    onConfirm: (removeBackground: Boolean, autoTag: Boolean, replaceExisting: Boolean, overwriteDuplicates: Boolean, useDrivePicker: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var removeBackground  by remember { mutableStateOf(false) }
    var autoTag           by remember { mutableStateOf(false) }
    var replaceExisting   by remember { mutableStateOf(false) }
    var overwriteDuplicates by remember { mutableStateOf(false) }
    var useDrivePicker    by remember { mutableStateOf(false) }

    val aiCostPerItem = (if (removeBackground) CreditPacks.COST_BG_REMOVAL else 0) +
                        (if (autoTag) CreditPacks.COST_CLASSIFY else 0)
    val showCostWarning = aiCostPerItem > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_import_options_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.settings_import_options_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()

                // ---- Source ----
                Text(stringResource(R.string.settings_import_source_title), style = MaterialTheme.typography.labelMedium)

                OptionRow(
                    label = stringResource(R.string.settings_import_source_local),
                    sublabel = stringResource(R.string.settings_import_source_local_desc),
                    checked = !useDrivePicker,
                    onCheckedChange = { useDrivePicker = false },
                )
                OptionRow(
                    label = stringResource(R.string.settings_import_source_drive),
                    sublabel = stringResource(R.string.settings_import_source_drive_desc),
                    checked = useDrivePicker,
                    onCheckedChange = { useDrivePicker = true },
                )

                HorizontalDivider()

                // ---- Destination mode ----
                Text(stringResource(R.string.settings_import_mode_title), style = MaterialTheme.typography.labelMedium)

                OptionRow(
                    label = stringResource(R.string.settings_import_mode_add),
                    sublabel = stringResource(R.string.settings_import_mode_add_desc),
                    checked = !replaceExisting,
                    onCheckedChange = { replaceExisting = false },
                )

                OptionRow(
                    label = stringResource(R.string.settings_import_mode_replace),
                    sublabel = if (existingItemCount > 0)
                        stringResource(R.string.settings_import_mode_replace_desc, existingItemCount)
                    else
                        stringResource(R.string.settings_import_mode_replace_desc_empty),
                    checked = replaceExisting,
                    onCheckedChange = { replaceExisting = true },
                    dangerColor = existingItemCount > 0,
                )

                // ---- Duplicate handling (only relevant when adding) ----
                if (!replaceExisting) {
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_import_duplicates_title), style = MaterialTheme.typography.labelMedium)

                    OptionRow(
                        label = stringResource(R.string.settings_import_duplicates_skip),
                        sublabel = stringResource(R.string.settings_import_duplicates_skip_desc),
                        checked = !overwriteDuplicates,
                        onCheckedChange = { overwriteDuplicates = false },
                    )
                    OptionRow(
                        label = stringResource(R.string.settings_import_duplicates_overwrite),
                        sublabel = stringResource(R.string.settings_import_duplicates_overwrite_desc),
                        checked = overwriteDuplicates,
                        onCheckedChange = { overwriteDuplicates = true },
                    )
                }

                HorizontalDivider()

                // ---- AI processing (optional) ----
                Text(stringResource(R.string.settings_import_ai_title), style = MaterialTheme.typography.labelMedium)

                SwitchRow(
                    label = stringResource(R.string.settings_import_options_remove_bg),
                    sublabel = stringResource(R.string.settings_import_options_remove_bg_cost),
                    checked = removeBackground,
                    onCheckedChange = { removeBackground = it },
                )
                SwitchRow(
                    label = stringResource(R.string.settings_import_options_auto_tag),
                    sublabel = stringResource(R.string.settings_import_options_auto_tag_cost),
                    checked = autoTag,
                    onCheckedChange = { autoTag = it },
                )

                if (showCostWarning) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            stringResource(R.string.settings_import_options_cost_warning,
                                aiCostPerItem),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(removeBackground, autoTag, replaceExisting, overwriteDuplicates, useDrivePicker) }) {
                Text(stringResource(R.string.settings_import_options_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun OptionRow(
    label: String,
    sublabel: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    dangerColor: Boolean = false,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        tonalElevation = if (checked) 3.dp else 0.dp,
        color = when {
            checked && dangerColor -> MaterialTheme.colorScheme.errorContainer
            checked -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(true) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (checked && dangerColor) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (checked && dangerColor) MaterialTheme.colorScheme.onErrorContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (checked) Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = if (dangerColor) MaterialTheme.colorScheme.onErrorContainer
                       else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    sublabel: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(sublabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---------- Drive folder picker dialog ----------

private data class FolderEntry(val id: String, val name: String)

@Composable
private fun DriveFolderPickerDialog(
    wardrobeViewModel: WardrobeViewModel,
    onFolderSelected: (folderId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val rootLabel = stringResource(R.string.settings_import_drive_root)
    var breadcrumb by remember { mutableStateOf(listOf(FolderEntry("root", rootLabel))) }
    var subfolders by remember { mutableStateOf<List<DriveFileDto>>(emptyList()) }
    var imageCount by remember { mutableStateOf(-1) }
    var isLoading  by remember { mutableStateOf(true) }

    val currentFolder = breadcrumb.last()

    LaunchedEffect(currentFolder.id) {
        isLoading = true
        subfolders = emptyList()
        imageCount = -1
        runCatching {
            subfolders = wardrobeViewModel.listDriveSubfolders(currentFolder.id)
            imageCount = wardrobeViewModel.countDriveImages(currentFolder.id)
        }
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (breadcrumb.size > 1) {
                    IconButton(
                        onClick = { breadcrumb = breadcrumb.dropLast(1) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                }
                Text(currentFolder.name, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (imageCount >= 0) {
                        Text(
                            stringResource(R.string.settings_import_drive_image_count, imageCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (subfolders.isNotEmpty()) Spacer(Modifier.height(4.dp))
                    }

                    if (subfolders.isEmpty() && imageCount == 0) {
                        Text(
                            stringResource(R.string.settings_import_drive_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    subfolders.forEach { folder ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            tonalElevation = 1.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    breadcrumb = breadcrumb + FolderEntry(folder.id, folder.name)
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.size(10.dp))
                                Text(
                                    folder.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onFolderSelected(currentFolder.id) },
                enabled = !isLoading && imageCount > 0,
            ) {
                Text(
                    if (imageCount > 0)
                        stringResource(R.string.settings_import_drive_select, imageCount)
                    else
                        stringResource(R.string.settings_import_drive_no_images),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

// ---------- AI tab ----------

@Composable
private fun AiTab(
    preferences: UserPreferences,
    onSaveConsiderations: (AiConsiderations) -> Unit,
) {
    val context = LocalContext.current
    var considerations by remember(preferences.aiConsiderations) { mutableStateOf(preferences.aiConsiderations) }
    var showResetAllDialog by remember { mutableStateOf(false) }
    // bump to force re-reads from PromptStore after edits or resets
    var promptRefresh by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // ---- Considerations ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.ai_considerations_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.ai_considerations_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(
                label = stringResource(R.string.ai_consider_weather),
                sublabel = stringResource(R.string.ai_consider_weather_desc),
                checked = considerations.weather,
                onCheckedChange = {
                    considerations = considerations.copy(weather = it)
                    onSaveConsiderations(considerations)
                },
            )
            SwitchRow(
                label = stringResource(R.string.ai_consider_location),
                sublabel = stringResource(R.string.ai_consider_location_desc),
                checked = considerations.location,
                onCheckedChange = {
                    considerations = considerations.copy(location = it)
                    onSaveConsiderations(considerations)
                },
            )
            SwitchRow(
                label = stringResource(R.string.ai_consider_trends),
                sublabel = stringResource(R.string.ai_consider_trends_desc),
                checked = considerations.trends,
                onCheckedChange = {
                    considerations = considerations.copy(trends = it)
                    onSaveConsiderations(considerations)
                },
            )
            SwitchRow(
                label = stringResource(R.string.ai_consider_gender),
                sublabel = stringResource(R.string.ai_consider_gender_desc),
                checked = considerations.gender,
                onCheckedChange = {
                    considerations = considerations.copy(gender = it)
                    onSaveConsiderations(considerations)
                },
            )
            SwitchRow(
                label = stringResource(R.string.ai_consider_age),
                sublabel = stringResource(R.string.ai_consider_age_desc),
                checked = considerations.age,
                onCheckedChange = {
                    considerations = considerations.copy(age = it)
                    onSaveConsiderations(considerations)
                },
            )
            SwitchRow(
                label = stringResource(R.string.ai_consider_preferences),
                sublabel = stringResource(R.string.ai_consider_preferences_desc),
                checked = considerations.preferences,
                onCheckedChange = {
                    considerations = considerations.copy(preferences = it)
                    onSaveConsiderations(considerations)
                },
            )
        }

        HorizontalDivider()

        // ---- Prompts ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.ai_prompts_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showResetAllDialog = true }) {
                    Text(stringResource(R.string.ai_prompts_reset_all))
                }
            }
            Text(
                stringResource(R.string.ai_prompts_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PromptKey.entries.forEach { key ->
            key(key, promptRefresh) {
                PromptEditorCard(
                    key = key,
                    initialText = PromptStore.get(context, key),
                    isOverridden = PromptStore.isOverridden(context, key),
                    onSave = { text ->
                        PromptStore.set(context, key, text)
                        promptRefresh++
                    },
                    onReset = {
                        PromptStore.reset(context, key)
                        promptRefresh++
                    },
                )
            }
        }
    }

    if (showResetAllDialog) {
        AlertDialog(
            onDismissRequest = { showResetAllDialog = false },
            title = { Text(stringResource(R.string.ai_prompts_reset_all_title)) },
            text = { Text(stringResource(R.string.ai_prompts_reset_all_text)) },
            confirmButton = {
                TextButton(onClick = {
                    PromptStore.resetAll(context)
                    promptRefresh++
                    showResetAllDialog = false
                }) { Text(stringResource(R.string.ai_prompts_reset_all_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetAllDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun PromptEditorCard(
    key: PromptKey,
    initialText: String,
    isOverridden: Boolean,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var text by remember(initialText) { mutableStateOf(initialText) }
    val dirty = text.trim() != initialText.trim()

    Surface(
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(key.titleRes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (isOverridden) {
                            Spacer(Modifier.size(6.dp))
                            Badge { Text(stringResource(R.string.ai_prompt_overridden_badge)) }
                        }
                    }
                    Text(
                        stringResource(key.descRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }
            if (expanded) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 6,
                    maxLines = 20,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onReset()
                            text = key.default
                        },
                        enabled = isOverridden || dirty,
                    ) { Text(stringResource(R.string.ai_prompt_reset)) }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onSave(text) },
                        enabled = dirty,
                    ) { Text(stringResource(R.string.action_save)) }
                }
            }
        }
    }
}

// ---------- About tab ----------

@Composable
private fun AboutTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Version
        Text(
            stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        // App description
        Text(
            stringResource(R.string.about_description),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(R.string.about_open_source),
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()

        // AI usage
        Text(
            stringResource(R.string.about_ai_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.about_ai_desc),
            style = MaterialTheme.typography.bodyMedium,
        )

        // Cost table
        Text(
            stringResource(R.string.about_cost_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.about_cost_bg_removal), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.about_cost_classify), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.about_cost_text), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.about_cost_travel), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.about_cost_tryon), style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()

        // BYOK
        Text(
            stringResource(R.string.about_byok_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.about_byok_desc),
            style = MaterialTheme.typography.bodyMedium,
        )

        HorizontalDivider()

        // Credits / managed mode
        Text(
            stringResource(R.string.about_credits_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.about_credits_desc),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
