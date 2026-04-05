package com.librelookai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private val GENDER_OPTIONS = listOf("Prefer not to say", "Female", "Male", "Non-binary", "Other")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    // Local draft — initialized once from loaded preferences, editable without saving
    var gender      by remember(state.preferences) { mutableStateOf(state.preferences.gender) }
    var yearOfBirth by remember(state.preferences) { mutableStateOf(state.preferences.yearOfBirth?.toString() ?: "") }
    var preferences by remember(state.preferences) { mutableStateOf(state.preferences.preferences) }

    // Reset draft when remote data loads
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            gender      = state.preferences.gender
            yearOfBirth = state.preferences.yearOfBirth?.toString() ?: ""
            preferences = state.preferences.preferences
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text("Profile", style = MaterialTheme.typography.headlineSmall)

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
                            viewModel.savePreferences(
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
        }

        // Success / error feedback
        if (state.savedSuccessfully) {
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = { TextButton(onClick = viewModel::clearSavedFlag) { Text("OK") } },
            ) { Text("Preferences saved") }
        }

        state.error?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = { TextButton(onClick = viewModel::clearError) { Text("Dismiss") } },
            ) { Text(msg) }
        }
    }
}
