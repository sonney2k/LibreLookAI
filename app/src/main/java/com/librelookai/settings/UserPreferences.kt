package com.librelookai.settings

import com.librelookai.util.ImageQuality

data class UserPreferences(
    val gender: String = "",
    val yearOfBirth: Int? = null,
    /** Free-text description of style preferences / recommendation context. */
    val preferences: String = "",
    /** Display language for UI and AI responses. One of [AppLanguage.options]. */
    val language: String = AppLanguage.ENGLISH,
    /** Drive file ID of the user's front-facing photo used by the Try-on feature. */
    val tryOnFrontDriveId: String = "",
    /** Drive file ID of the user's side-facing photo used by the Try-on feature. */
    val tryOnSideDriveId: String = "",
    /** Drive file ID of the user's back-facing photo used by the Try-on feature. */
    val tryOnBackDriveId: String = "",
    /** Which context sections to feed into AI style suggestions. */
    val aiConsiderations: AiConsiderations = AiConsiderations(),
    /** Run on-device similarity check on imports/captures so users can spot duplicates before committing. */
    val dedupeOnImport: Boolean = true,
    /** Cosine-similarity threshold (0..1) above which two items are treated as duplicates. */
    val dedupeThreshold: Float = 0.88f,
    /** Foreground-confidence cutoff (0..1) used by the on-device background-removal segmenter.
     *  Lower values keep more pixels (looser cutout); higher values reject borderline pixels
     *  (tighter cutout). Default 0.3 matches the original hardcoded threshold. */
    val bgRemovalThreshold: Float = 0.3f,
    /** When true, camera and gallery imports route through the on-device Magic Touch segmenter
     *  with an interactive seed-point review before saving, skipping the (paid) Gemini cutout.
     *  URL imports always show the review regardless of this flag. */
    val preferLocalBgRemoval: Boolean = false,
    /** When true, the Similarity Finder match preview shows the per-channel debug breakdown
     *  (segmented thumbnails, per-engine cosine scores, hue histograms) instead of the default
     *  zoom-only view. */
    val debugSimilarityPreview: Boolean = false,
    /** Selected wardrobe-design palette id (see WardrobePalettes). Drives MaterialTheme colors
     *  app-wide as well as the extended `LocalWardrobePalette` tokens. */
    val wardrobeTheme: String = "green-light",
    /** Selected app font id. One of [AppFont.options]. */
    val appFont: String = AppFont.CAVEAT,
    /** Storage-vs-fidelity trade-off for wardrobe images saved to Drive (WebP). Gson back-fills
     *  the field for prefs saved before it existed → defaults to [ImageQuality.BALANCED]. */
    val imageQuality: ImageQuality = ImageQuality.BALANCED,
)

/** Available font choices for the app typography. */
object AppFont {
    const val DEFAULT = "default"
    const val CAVEAT = "caveat"
    val options = listOf(DEFAULT, CAVEAT)
}

// AiConsiderations moved to :core:model (core/model/…/settings/AiConsiderations.kt), same package.
