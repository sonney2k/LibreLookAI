package com.librelookai.wardrobe

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.librelookai.R
import com.librelookai.gemini.ClothingTags
import com.librelookai.util.AiProcessingOverlay
import com.librelookai.util.Analytics
import com.librelookai.util.LocalIsOffline
import com.librelookai.util.LocalSystemBarsPadding

// ---------- Tag edit sheet ----------

// Predefined suggestion lists matching the Gemini classify prompt
private val PRESET_USES        = listOf("casual", "formal", "business", "sport", "outdoor", "beach", "evening")
private val PRESET_SEASONALITY = listOf("spring", "summer", "fall", "winter")
private val PRESET_AESTHETIC   = listOf("minimalist", "streetwear", "preppy", "bohemian", "classic", "sporty", "romantic", "edgy", "business-casual", "luxury")
private val PRESET_FIT         = listOf("slim", "regular", "relaxed", "oversized", "tailored")
private val PRESET_MATERIAL    = listOf("cotton", "denim", "wool", "leather", "polyester", "linen", "silk", "knit")
private val PRESET_PATTERN     = listOf("solid", "stripes", "plaid", "floral", "geometric", "animal-print", "graphic", "camo", "abstract")

// Color swatch map: tag value → swatch color. Falls back to chipBg for unknown values.
// "multicolor" renders a rainbow gradient (handled at draw time).

private enum class SaveState { Saved, Saving, Edited }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TagEditScreen(
    image: DriveImage,
    allTagCategories: List<TagCategory>,
    onUpdate: (ClothingTags) -> Unit,
    onClassify: () -> Unit,
    isProcessing: Boolean,
    onDismiss: () -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            window.setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        }
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            TagEditScreenContent(
                image = image,
                allTagCategories = allTagCategories,
                onUpdate = onUpdate,
                onClassify = onClassify,
                isProcessing = isProcessing,
                onDismiss = onDismiss,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditScreenContent(
    image: DriveImage,
    allTagCategories: List<TagCategory>,
    onUpdate: (ClothingTags) -> Unit,
    onClassify: () -> Unit,
    isProcessing: Boolean,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val isOffline = LocalIsOffline.current
    val barInsets = LocalSystemBarsPadding.current

    var tags by remember(image.driveId) {
        mutableStateOf(image.tags ?: ClothingTags())
    }
    var saveState by remember { mutableStateOf(SaveState.Saved) }
    var openRow by remember { mutableStateOf<String?>("colors") }
    var pendingChange by remember { mutableStateOf(0) }
    // 0 = immediate (chip toggle), 1 = debounced text (free-text edit)
    var pendingMode by remember { mutableStateOf(0) }

    fun mutate(mode: Int, transform: (ClothingTags) -> ClothingTags) {
        tags = transform(tags)
        saveState = SaveState.Saving
        pendingMode = mode
        pendingChange += 1
    }

    LaunchedEffect(pendingChange) {
        if (pendingChange == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(if (pendingMode == 1) 600L else 300L)
        runCatching { onUpdate(tags) }
            .onSuccess {
                kotlinx.coroutines.delay(400L) // min display so the pill doesn't flicker
                saveState = SaveState.Saved
            }
            .onFailure { saveState = SaveState.Edited }
    }

    fun suggestions(label: String, presets: List<String>): List<String> {
        val fromWardrobe = allTagCategories.find { it.label == label }?.tags.orEmpty()
        return (presets + fromWardrobe).distinct().sorted()
    }

    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = barInsets.calculateTopPadding())
                .padding(bottom = barInsets.calculateBottomPadding())
                .imePadding(),
        ) {
            // App header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = scheme.onSurface,
                    )
                }
                Text(
                    stringResource(R.string.wardrobe_tag_sheet_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                SaveIndicator(state = saveState)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IdentityCard(
                    image = image,
                    tags = tags,
                    isOffline = isOffline,
                    isProcessing = isProcessing,
                    onUpdateText = { newTags -> mutate(1) { newTags } },
                    onClassify = onClassify,
                )

                TagsTableCard(
                    tags = tags,
                    openRow = openRow,
                    onToggleRow = { key -> openRow = if (openRow == key) null else key },
                    onToggleValue = { setter ->
                        mutate(0) { setter(it) }
                    },
                    suggest = ::suggestions,
                )

                Spacer(Modifier.height(8.dp))
            }
        }

        if (isProcessing) {
            AiProcessingOverlay(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SaveIndicator(state: SaveState) {
    val scheme = MaterialTheme.colorScheme
    val icon = when (state) {
        SaveState.Saved  -> androidx.compose.material.icons.Icons.Default.CheckCircle
        SaveState.Saving -> androidx.compose.material.icons.Icons.Default.Refresh
        SaveState.Edited -> androidx.compose.material.icons.Icons.Default.Info
    }
    val labelRes = when (state) {
        SaveState.Saved  -> R.string.wardrobe_tag_saved
        SaveState.Saving -> R.string.wardrobe_tag_saving
        SaveState.Edited -> R.string.wardrobe_tag_unsaved
    }
    val fg = if (state == SaveState.Saved) scheme.primary else scheme.onSurfaceVariant
    val bg = if (state == SaveState.Saved) scheme.primary.copy(alpha = 0.12f) else scheme.surfaceVariant
    Surface(shape = RoundedCornerShape(999.dp), color = bg) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
            Text(stringResource(labelRes), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
        }
    }
}

@Composable
private fun IdentityCard(
    image: DriveImage,
    tags: ClothingTags,
    isOffline: Boolean,
    isProcessing: Boolean,
    onUpdateText: (ClothingTags) -> Unit,
    onClassify: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var editingName by remember { mutableStateOf(false) }
    var editingType by remember { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(image.localPath)
                        .memoryCacheKey("${image.driveId}_${image.version}")
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.surfaceVariant)
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp)),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    EyebrowLabel(stringResource(R.string.tag_label))
                    if (editingName) {
                        OutlinedTextField(
                            value = tags.label,
                            onValueChange = { onUpdateText(tags.copy(label = it)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { editingName = false }),
                        )
                    } else {
                        Text(
                            text = tags.label.ifBlank { stringResource(R.string.wardrobe_tag_not_set) },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (tags.label.isBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { editingName = true },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SubField(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.tag_type),
                            value = tags.type,
                            editing = editingType,
                            onStartEdit = { editingType = true },
                            onChange = { onUpdateText(tags.copy(type = it)) },
                            onDone = { editingType = false },
                        )
                        SubField(
                            modifier = Modifier.weight(1f),
                            label = stringResource(R.string.tag_category),
                            value = tags.category.replaceFirstChar { it.uppercase() },
                            editing = editingCategory,
                            onStartEdit = { editingCategory = true },
                            onChange = { onUpdateText(tags.copy(category = it)) },
                            onDone = { editingCategory = false },
                        )
                    }
                }
            }

            // AI re-tag CTA
            val ctaEnabled = !isOffline && !isProcessing
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.primary.copy(alpha = if (ctaEnabled) 0.10f else 0.05f))
                    .border(1.dp, scheme.primary.copy(alpha = 0.33f), RoundedCornerShape(12.dp))
                    .clickable(enabled = ctaEnabled) {
                        Analytics.action("TagEdit", "redetect_ai")
                        onClassify()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .graphicsLayer { alpha = if (ctaEnabled) 1f else 0.5f },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                com.librelookai.billing.CostBadge(com.librelookai.gemini.GeminiActionId.CLASSIFY_CLOTHING)
                Icon(
                    androidx.compose.material.icons.Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    stringResource(R.string.wardrobe_tag_redetect_ai),
                    color = scheme.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SubField(
    modifier: Modifier,
    label: String,
    value: String,
    editing: Boolean,
    onStartEdit: () -> Unit,
    onChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        EyebrowLabel(label, small = true)
        if (editing) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
            )
        } else {
            Text(
                text = value.ifBlank { stringResource(R.string.wardrobe_tag_not_set) },
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (value.isBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 1.dp)
                    .clickable { onStartEdit() },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EyebrowLabel(text: String, small: Boolean = false) {
    Text(
        text = text.uppercase(),
        fontSize = if (small) 9.sp else 10.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = (if (small) 0.3 else 0.4).sp,
    )
}

internal data class TagRowSpec(
    val key: String,
    val labelRes: Int,
    val sectionLabel: String,
    val presets: List<String>,
    val isColor: Boolean = false,
)

internal val TAG_ROWS = listOf(
    TagRowSpec("colors",      R.string.tag_colors,      "Colors",      emptyList(),         isColor = true),
    TagRowSpec("uses",        R.string.tag_uses,        "Uses",        PRESET_USES),
    TagRowSpec("seasonality", R.string.tag_seasonality, "Seasonality", PRESET_SEASONALITY),
    TagRowSpec("aesthetic",   R.string.tag_aesthetic,   "Aesthetic",   PRESET_AESTHETIC),
    TagRowSpec("fit",         R.string.tag_fit,         "Fit",         PRESET_FIT),
    TagRowSpec("material",    R.string.tag_material,    "Material",    PRESET_MATERIAL),
    TagRowSpec("pattern",     R.string.tag_pattern,     "Pattern",     PRESET_PATTERN),
)

