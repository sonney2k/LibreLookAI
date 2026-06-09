package com.librelookai.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.librelookai.AppScreenHeader
import com.librelookai.BuildConfig
import com.librelookai.R
import com.librelookai.billing.CostTierLegend

/** About page reached from the "More ▸ About" row. Mirrors the legacy About tab copy. */
@Composable
fun AboutScreen(onOpenUsage: () -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        AppScreenHeader(
            title = stringResource(R.string.settings_more_about),
            trailingContent = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
        )
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.about_description), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_open_source), style = MaterialTheme.typography.bodyMedium)
            AboutLinkRow(label = stringResource(R.string.about_website_label), url = "https://librelook.ai")
            AboutLinkRow(label = stringResource(R.string.about_github_label), url = "https://github.com/sonney2k/LibreLookAI")
            HorizontalDivider()
            Text(
                stringResource(R.string.about_ai_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.about_ai_desc), style = MaterialTheme.typography.bodyMedium)
            CostTierLegend()
            Text(
                text = stringResource(R.string.about_view_cost_stats),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable(onClick = onOpenUsage),
            )
        }
    }
}

/** A "Label: url" row whose URL opens in the browser when tapped. */
@Composable
private fun AboutLinkRow(label: String, url: String) {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline,
        ),
    )
    Text(
        text = buildAnnotatedString {
            append(label)
            append(": ")
            withLink(LinkAnnotation.Url(url, styles = linkStyles)) {
                append(url.removePrefix("https://"))
            }
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}
