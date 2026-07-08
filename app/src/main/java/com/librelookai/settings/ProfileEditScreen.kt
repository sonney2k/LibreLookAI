package com.librelookai.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.librelookai.AppScreenHeader
import com.librelookai.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.settings.AiConsiderations
import com.librelookai.settings.AiConsiderationsStrip
import com.librelookai.settings.UserPreferences

/**
 * Profile editor reached from the hero card's "Edit" pill: style preferences,
 * gender/pronouns, year of birth, and the AI considerations chips. Display name
 * comes from the Google account and is not editable here.
 */
@Composable
fun ProfileEditScreen(
    displayName: String,
    preferences: UserPreferences,
    onSave: (UserPreferences) -> Unit,
    onBack: () -> Unit,
) {
    var style by remember(preferences) { mutableStateOf(preferences.preferences) }
    var gender by remember(preferences) { mutableStateOf(preferences.gender) }
    var yearText by remember(preferences) { mutableStateOf(preferences.yearOfBirth?.toString() ?: "") }
    var considerations by remember(preferences) { mutableStateOf(preferences.aiConsiderations) }

    fun commitAndBack() {
        onSave(
            preferences.copy(
                preferences = style.trim(),
                gender = gender.trim(),
                yearOfBirth = yearText.toIntOrNull(),
                aiConsiderations = considerations,
            )
        )
        onBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AppScreenHeader(
            title = stringResource(R.string.settings_profile_edit_title),
            trailingContent = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                value = style,
                onValueChange = { style = it },
                label = { Text(stringResource(DsR.string.settings_style_prefs)) },
                supportingText = { Text(stringResource(R.string.settings_style_prefs_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = gender,
                onValueChange = { gender = it },
                label = { Text(stringResource(DsR.string.settings_profile_gender)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = yearText,
                onValueChange = { v -> yearText = v.filter { it.isDigit() }.take(4) },
                label = { Text(stringResource(DsR.string.settings_profile_year)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            AiConsiderationsStrip(
                considerations = considerations,
                onToggle = { transform -> considerations = transform(considerations) },
                titleRes = R.string.settings_profile_ai_considerations,
            )
            ExpertTagsStrip(
                considerations = considerations,
                onToggleTag = { dim -> considerations = considerations.toggleItemTag(dim) },
            )
            Button(onClick = ::commitAndBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(DsR.string.action_save))
            }
        }
    }
}
