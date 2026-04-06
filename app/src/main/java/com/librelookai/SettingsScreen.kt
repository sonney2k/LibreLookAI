package com.librelookai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private val GENDER_OPTIONS = listOf("Prefer not to say", "Female", "Male", "Non-binary", "Other")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    profileViewModel: ProfileViewModel = viewModel(),
    wardrobeViewModel: WardrobeViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val profileState  by profileViewModel.state.collectAsState()
    val wardrobeState by wardrobeViewModel.state.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Profile") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Data") })
        }

        when (selectedTab) {
            0 -> ProfileTab(
                state = profileState,
                onSave = profileViewModel::savePreferences,
                onClearSavedFlag = profileViewModel::clearSavedFlag,
                onClearError = profileViewModel::clearError,
            )
            1 -> DataTab(
                wardrobeState = wardrobeState,
                onRetagAll = wardrobeViewModel::retagAll,
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
) {
    // Local draft — initialized from loaded preferences, editable without saving
    var gender      by remember(state.preferences) { mutableStateOf(state.preferences.gender) }
    var yearOfBirth by remember(state.preferences) { mutableStateOf(state.preferences.yearOfBirth?.toString() ?: "") }
    var preferences by remember(state.preferences) { mutableStateOf(state.preferences.preferences) }

    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            gender      = state.preferences.gender
            yearOfBirth = state.preferences.yearOfBirth?.toString() ?: ""
            preferences = state.preferences.preferences
        }
    }

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
                // --- Gender ---
                var genderExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = genderExpanded,
                    onExpandedChange = { genderExpanded = it },
                ) {
                    OutlinedTextField(
                        value = gender.ifEmpty { "Select…" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Gender") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = genderExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = genderExpanded,
                        onDismissRequest = { genderExpanded = false },
                    ) {
                        GENDER_OPTIONS.forEach { option ->
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
                    label = { Text("Year of Birth") },
                    placeholder = { Text("e.g. 1990") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // --- Style preferences ---
                OutlinedTextField(
                    value = preferences,
                    onValueChange = { preferences = it },
                    label = { Text("Style Preferences") },
                    placeholder = {
                        Text(
                            "Describe what you like to wear, occasions you dress for, " +
                            "favourite colours, brands, or anything else that helps " +
                            "personalise your outfit recommendations…",
                        )
                    },
                    minLines = 6,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth(),
                )

                // --- Save ---
                Button(
                    onClick = {
                        onSave(
                            UserPreferences(
                                gender      = gender,
                                yearOfBirth = yearOfBirth.toIntOrNull(),
                                preferences = preferences,
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
                    Text(if (state.isSaving) "Saving…" else "Save")
                }
            }
        }

        if (state.savedSuccessfully) {
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = onClearSavedFlag) { Text("OK") } },
            ) { Text("Preferences saved") }
        }

        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = onClearError) { Text("Dismiss") } },
            ) { Text(msg) }
        }
    }
}

// ---------- Data tab ----------

@Composable
private fun DataTab(
    wardrobeState: WardrobeUiState,
    onRetagAll: () -> Unit,
) {
    var showRetagDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // ---- Re-scan all tags ----
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Wardrobe Tags", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Re-classify every item in your wardrobe with Gemini. This overwrites any manual tag edits.",
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
                        "Re-scanning ${wardrobeState.retagDone + 1} of ${wardrobeState.retagTotal}…",
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
                    Text("Re-scan All Tags")
                }
            }
        }

        HorizontalDivider()
    }

    if (showRetagDialog) {
        AlertDialog(
            onDismissRequest = { showRetagDialog = false },
            title = { Text("Re-scan all tags?") },
            text = {
                Text("Gemini will re-classify every item in your wardrobe. This will overwrite any manual tag edits and may take a while.")
            },
            confirmButton = {
                TextButton(onClick = { onRetagAll(); showRetagDialog = false }) {
                    Text("Re-scan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRetagDialog = false }) { Text("Cancel") }
            },
        )
    }
}
