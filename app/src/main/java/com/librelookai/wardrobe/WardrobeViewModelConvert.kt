package com.librelookai.wardrobe

/**
 * Settings ▸ Advanced ▸ "Convert images to WebP" entry point. The op itself lives in the
 * [WebpConvertUseCase] (@Singleton, own process-long scope, refactor § 5 slice 6); this thin
 * delegate just hands it the currently-displayed items so it can stamp their renamed filenames
 * onto the store rows. Progress mirrors back into [WardrobeUiState] via the collector in
 * `WardrobeViewModel.init`.
 */
internal fun WardrobeViewModel.convertImagesToWebp(closetFolderIds: List<String>) =
    webpConvertUseCase.start(closetFolderIds, _state.value.images)
