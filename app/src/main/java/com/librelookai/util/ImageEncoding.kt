package com.librelookai.util

import android.graphics.Bitmap
import android.os.Build
import java.io.File
import java.io.OutputStream

/**
 * User-selectable trade-off between Drive storage size and image fidelity.
 * Persisted in `UserPreferences.imageQuality`; mirrored into [ImageEncoding.tier].
 */
enum class ImageQuality { BALANCED, HIGH, MAXIMUM }

/**
 * Single owner of how wardrobe images are encoded for storage on the user's Drive.
 *
 * Wardrobe items live on Drive as a triplet: `{id}_cutout.<ext>` (background-removed,
 * needs an alpha channel), `{id}_original.<ext>` (archived source for re-processing),
 * and `{id}.json` (sidecar). Both images are now written as **WebP** — lossy WebP keeps
 * the cutout's transparency while being ~5-10× smaller than the old lossless PNG.
 *
 * Identity is the Drive **filename suffix** + the **bytes**, never a local cache file's
 * extension: Android/Coil decode by magic bytes, so local cache files named `.png`/`.jpg`
 * may physically hold WebP bytes and still decode. Detection therefore accepts both the
 * new `.webp` suffixes and the legacy `.png`/`.jpg` ones so pre-existing items keep
 * working with no Drive-side rewrite.
 */
object ImageEncoding {

    /** Canonical suffixes for NEW uploads (always WebP). */
    const val CUTOUT_SUFFIX = "_cutout.webp"
    const val ORIGINAL_SUFFIX = "_original.webp"

    /** Legacy suffixes, recognised on read for back-compat. */
    const val CUTOUT_SUFFIX_LEGACY = "_cutout.png"
    const val ORIGINAL_SUFFIX_LEGACY = "_original.jpg"

    /** Current quality tier; mirrored from `UserPreferences.imageQuality` in AppContent. */
    @Volatile
    var tier: ImageQuality = ImageQuality.BALANCED

    /** WebP format + quality for the active [tier]. Lossy (<100) preserves alpha. */
    private fun formatAndQuality(): Pair<Bitmap.CompressFormat, Int> {
        val lossless = tier == ImageQuality.MAXIMUM
        val quality = when (tier) {
            ImageQuality.BALANCED -> 80
            ImageQuality.HIGH -> 90
            ImageQuality.MAXIMUM -> 100
        }
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP // quality<100 = lossy (keeps alpha), 100 = lossless
        }
        return format to quality
    }

    /** Encode a background-removed cutout (alpha preserved) at the active tier. */
    fun compressCutout(bmp: Bitmap, out: OutputStream) {
        val (format, quality) = formatAndQuality()
        bmp.compress(format, quality, out)
    }

    fun compressCutout(bmp: Bitmap, file: File) =
        file.outputStream().use { compressCutout(bmp, it) }

    /** Encode an archived original (no alpha needed) at the active tier. */
    fun compressOriginal(bmp: Bitmap, out: OutputStream) {
        val (format, quality) = formatAndQuality()
        bmp.compress(format, quality, out)
    }

    fun compressOriginal(bmp: Bitmap, file: File) =
        file.outputStream().use { compressOriginal(bmp, it) }

    // ---- dual-format detection (new .webp + legacy .png/.jpg) ----

    fun isCutoutName(name: String): Boolean =
        name.endsWith(CUTOUT_SUFFIX) || name.endsWith(CUTOUT_SUFFIX_LEGACY)

    fun isOriginalName(name: String): Boolean =
        name.endsWith(ORIGINAL_SUFFIX) || name.endsWith(ORIGINAL_SUFFIX_LEGACY)

    /** The owning Drive ID encoded in a cutout filename, or null if [name] isn't a cutout. */
    fun cutoutIdFromName(name: String): String? = when {
        name.endsWith(CUTOUT_SUFFIX) -> name.removeSuffix(CUTOUT_SUFFIX)
        name.endsWith(CUTOUT_SUFFIX_LEGACY) -> name.removeSuffix(CUTOUT_SUFFIX_LEGACY)
        else -> null
    }

    fun originalIdFromName(name: String): String? = when {
        name.endsWith(ORIGINAL_SUFFIX) -> name.removeSuffix(ORIGINAL_SUFFIX)
        name.endsWith(ORIGINAL_SUFFIX_LEGACY) -> name.removeSuffix(ORIGINAL_SUFFIX_LEGACY)
        else -> null
    }

    /**
     * Sniffs the actual image MIME type from a file's magic bytes (not its extension) — local
     * cache files may be named `.png`/`.jpg` while physically holding WebP bytes. Returns one of
     * `image/webp`, `image/png`, `image/jpeg` (default). All three are accepted by the Gemini API.
     */
    fun detectMimeType(file: File): String {
        val h = ByteArray(12)
        val n = runCatching { file.inputStream().use { it.read(h) } }.getOrDefault(0)
        fun b(i: Int, c: Char) = i < n && h[i] == c.code.toByte()
        return when {
            b(0, 'R') && b(1, 'I') && b(2, 'F') && b(3, 'F') &&
                b(8, 'W') && b(9, 'E') && b(10, 'B') && b(11, 'P') -> "image/webp"
            n >= 8 && (h[0].toInt() and 0xFF) == 0x89 && b(1, 'P') && b(2, 'N') && b(3, 'G') -> "image/png"
            else -> "image/jpeg"
        }
    }

    /**
     * A stable, **extension-agnostic** key for matching a wardrobe item's CUTOUT filename across the
     * legacy `_cutout.png` ↔ new `_cutout.webp` rename. Outfits and try-ons persist cutout
     * *filenames* (`itemNames`) and match them against the live wardrobe by name; keying **both
     * sides** by this token makes that matching survive the WebP migration — including the window
     * where Drive's eventually-consistent file listing still reports old names, and devices that
     * hold a mix of legacy and converted items. Non-cutout names are returned unchanged.
     */
    fun itemMatchKey(name: String): String = cutoutIdFromName(name)?.let { "${it}_cutout" } ?: name

    // ---- canonical names for NEW uploads ----

    fun cutoutNameFor(id: String): String = "$id$CUTOUT_SUFFIX"

    fun originalNameFor(id: String): String = "$id$ORIGINAL_SUFFIX"
}
