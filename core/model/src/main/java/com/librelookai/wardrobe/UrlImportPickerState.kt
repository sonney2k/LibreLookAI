package com.librelookai.wardrobe

/** State for the URL-import picker dialog (candidate grid + WebView fallback). */
data class UrlImportPickerState(
    val pageUrl: String,
    val candidates: List<String>,
    val targetFolderId: String? = null,
    val isDownloading: Boolean = false,
)
