package com.librelookai.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.librelookai.R
import com.librelookai.core.designsystem.R as DsR
import com.librelookai.data.model.Location

/**
 * Add / rename-and-recity dialog for a closet. [existing] is null when adding.
 * [onDelete] is null when deletion is not allowed (e.g. the last remaining closet).
 */
@Composable
fun ClosetEditDialog(
    existing: Location?,
    onSave: (name: String, city: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var city by remember { mutableStateOf(existing?.geoLocation ?: "") }
    Dialog(onDismissRequest = onDismiss) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.background,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(
                            if (existing == null) R.string.settings_add_closet else R.string.settings_edit_closet,
                        ),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.settings_closet_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = city,
                        onValueChange = { city = it },
                        label = { Text(stringResource(R.string.settings_closet_city_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (onDelete != null) {
                            TextButton(onClick = { onDelete(); onDismiss() }) {
                                Text(
                                    stringResource(R.string.settings_closet_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        } else {
                            androidx.compose.foundation.layout.Spacer(Modifier)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = onDismiss) { Text(stringResource(DsR.string.action_cancel)) }
                            TextButton(
                                enabled = name.isNotBlank(),
                                onClick = { onSave(name.trim(), city.trim()); onDismiss() },
                            ) { Text(stringResource(R.string.action_save)) }
                        }
                    }
                }
            }
        }
    }
}
