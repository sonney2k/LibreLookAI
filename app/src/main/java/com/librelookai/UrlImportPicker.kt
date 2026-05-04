package com.librelookai

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import coil.compose.AsyncImage

/**
 * Fullscreen picker for URL imports. Opens in candidate-grid mode if [candidates] is non-empty,
 * else jumps straight to the WebView fallback. The user can swap modes at any time. Calls
 * [onPick] with the absolute image URL once a choice is made.
 *
 * See CLAUDE.md "Compose Dialog Quirks" — fullscreen Dialogs need parent context/config and bar
 * insets re-provided so localized strings render and content clears system bars.
 */
@Composable
fun UrlImportPicker(
    pageUrl: String,
    candidates: List<String>,
    isDownloading: Boolean,
    onPick: (absoluteImageUrl: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val barInsets = LocalSystemBarsPadding.current
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current

    var mode by remember { mutableStateOf(if (candidates.isEmpty()) Mode.Browser else Mode.Grid) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = barInsets.calculateTopPadding())
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(
                            if (mode == Mode.Grid) R.string.url_import_pick_title
                            else R.string.url_import_browser_title,
                        ),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.weight(1f))
                    if (mode == Mode.Grid) {
                        TextButton(onClick = { mode = Mode.Browser }) {
                            Text(stringResource(R.string.url_import_open_browser))
                        }
                    } else if (candidates.isNotEmpty()) {
                        TextButton(onClick = { mode = Mode.Grid }) {
                            Text(stringResource(R.string.url_import_show_candidates))
                        }
                    }
                }
                if (isDownloading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                when (mode) {
                    Mode.Grid -> CandidateGrid(
                        pageUrl = pageUrl,
                        candidates = candidates,
                        onPick = onPick,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                    )
                    Mode.Browser -> BrowserPicker(
                        pageUrl = pageUrl,
                        onPick = onPick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

private enum class Mode { Grid, Browser }

@Composable
private fun CandidateGrid(
    pageUrl: String,
    candidates: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (candidates.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.url_import_no_candidates),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    Column(modifier = modifier) {
        Text(
            stringResource(R.string.url_import_pick_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(candidates, key = { it }) { url ->
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(LocalContext.current)
                        .data(url)
                        .setHeader("Referer", pageUrl)
                        .setHeader(
                            "User-Agent",
                            "Mozilla/5.0 (Macintosh; Intel Mac OS X 13_0) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
                        )
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .clickable { onPick(url) }
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
    }
}

/**
 * WebView fallback. Loads [pageUrl] in a real browser environment so JS-rendered images
 * materialize. Tapping any `<img>` (or background-image element) sends its src back to Kotlin
 * via the `LibreLookAI.onImage(...)` JS bridge.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserPicker(
    pageUrl: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var loading by remember { mutableStateOf(true) }
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadsImagesAutomatically = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.userAgentString = settings.userAgentString +
                        " LibreLookAI"
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onImage(src: String?) {
                            if (src.isNullOrBlank()) return
                            post { onPick(src) }
                        }
                    }, "LibreLookAI")
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean = false

                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                            view.evaluateJavascript(IMAGE_TAP_BRIDGE_JS, null)
                        }
                    }
                    loadUrl(pageUrl)
                }
            },
        )
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Text(
            stringResource(R.string.url_import_browser_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .padding(8.dp),
        )
    }
}

/**
 * Injected on every page load. Resolves img.src to absolute URLs (via .currentSrc when
 * available, else .src), and also handles elements whose background-image is set in CSS — for
 * those we parse the computed style. Calls back into the LibreLookAI Kotlin bridge.
 */
private const val IMAGE_TAP_BRIDGE_JS = """
(function() {
  if (window.__libreLookAIInjected) return;
  window.__libreLookAIInjected = true;
  function bestSrc(el) {
    if (!el) return null;
    if (el.tagName === 'IMG') {
      return el.currentSrc || el.src || null;
    }
    var bg = window.getComputedStyle(el).backgroundImage;
    if (bg && bg.indexOf('url(') === 0) {
      return bg.slice(4, -1).replace(/^["']|["']${'$'}/g, '');
    }
    return null;
  }
  document.addEventListener('click', function(ev) {
    var el = ev.target;
    // Walk up a few levels to handle <picture>/<a> wrappers around <img>.
    var hops = 0;
    while (el && hops < 4) {
      var s = bestSrc(el);
      if (s) {
        ev.preventDefault();
        ev.stopPropagation();
        try { window.LibreLookAI.onImage(s); } catch (e) {}
        return;
      }
      el = el.parentElement;
      hops++;
    }
  }, true);
})();
"""
