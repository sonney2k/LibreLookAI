package com.librelookai.outfit
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.librelookai.settings.ProfileViewModel
import com.librelookai.wardrobe.LocationViewModel
import com.librelookai.wardrobe.WardrobeViewModel
import com.librelookai.weather.WeatherViewModel
import com.librelookai.R

/**
 * Setup dialog launched by the "Find with AI" button on the Outfits tab. Mirrors the upper
 * part of the outfit composer (goal text + weather/closet/vibe chips) and submits to the
 * existing prediction flow when the user taps "Find".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PredictionSetupDialog(
    outfitsViewModel: OutfitsViewModel,
    profileViewModel: ProfileViewModel,
    weatherViewModel: WeatherViewModel,
    locationViewModel: LocationViewModel,
    wardrobeViewModel: WardrobeViewModel,
) {
    val s by outfitsViewModel.state.collectAsState()
    val profile by profileViewModel.state.collectAsState()
    val weather by weatherViewModel.state.collectAsState()
    val locationState by locationViewModel.state.collectAsState()
    val wardrobe by wardrobeViewModel.state.collectAsState()
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current

    if (!s.isPredictionSetupOpen) return

    var showWeatherSheet by remember { mutableStateOf(false) }
    var showClosetSheet by remember { mutableStateOf(false) }

    val sourceFolders = s.composerSourceFolderIds
    val closetNames = remember(sourceFolders, locationState.locations) {
        locationState.locations.filter { it.folderId in sourceFolders }.map { it.name }
    }
    val crossClosetImages = wardrobe.allLocationImages.ifEmpty { wardrobe.images }

    Dialog(
        onDismissRequest = { outfitsViewModel.closePredictionSetup() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = true),
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.prediction_setup_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { outfitsViewModel.closePredictionSetup() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                    }

                    GoalInput(
                        goal = s.composerFeedback,
                        onGoalChange = outfitsViewModel::updateComposerFeedback,
                    )

                    ContextStrip(
                        weatherMode = s.composerWeatherMode,
                        autoWeather = weather.data,
                        manualTempC = s.composerManualTempC,
                        closetNames = closetNames,
                        closetPickerAvailable = locationState.locations.size >= 2,
                        selectedVibes = s.composerVibes,
                        onToggleVibe = outfitsViewModel::toggleComposerVibe,
                        onClickWeather = { showWeatherSheet = true },
                        onClickCloset = { showClosetSheet = true },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { outfitsViewModel.closePredictionSetup() }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                outfitsViewModel.submitPredictionSetup(
                                    prefs = profile.preferences,
                                    weather = weather.data,
                                    images = crossClosetImages,
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.prediction_setup_find))
                        }
                    }
                }
            }
        }
    }

    if (showWeatherSheet) {
        WeatherPickerSheet(
            mode = s.composerWeatherMode,
            onModeChange = outfitsViewModel::setComposerWeatherMode,
            autoWeather = weather.data,
            season = s.composerManualSeason,
            onSeason = outfitsViewModel::setComposerManualSeason,
            tempC = s.composerManualTempC,
            onTempC = outfitsViewModel::setComposerManualTempC,
            precip = s.composerManualPrecip,
            onPrecip = outfitsViewModel::setComposerManualPrecip,
            onDismiss = { showWeatherSheet = false },
        )
    }
    if (showClosetSheet) {
        ClosetPickerSheet(
            locations = locationState.locations,
            selected = s.composerSourceFolderIds,
            onToggle = outfitsViewModel::toggleComposerSourceFolder,
            onDismiss = { showClosetSheet = false },
        )
    }
}

@Composable
private fun GoalInput(goal: String, onGoalChange: (String) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val gradient = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
        ),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(gradient, RoundedCornerShape(16.dp))
            .border(1.dp, primary.copy(alpha = 0.33f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        OutlinedTextField(
            value = goal,
            onValueChange = onGoalChange,
            placeholder = {
                Text(
                    stringResource(R.string.composer_goal_placeholder),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                )
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
    }
}
