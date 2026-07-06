package com.librelookai.wardrobe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.librelookai.util.ImageEncoding
import java.io.File

/** Rotates a cached cutout file 90° clockwise in place (wardrobe + shopping rotate ops). */
fun rotateBitmapFileBy90(file: File) {
    val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return
    val matrix = Matrix().apply { postRotate(90f) }
    val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    // Re-encode as WebP (alpha-preserving) regardless of the cache file's extension — the
    // rotated bytes are re-uploaded via DriveRepository.updateImage, which sends image/webp.
    ImageEncoding.compressCutout(rotated, file)
}
