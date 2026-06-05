package com.librelookai.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-JVM tests for the format-agnostic helpers in [ImageEncoding]. The encode writers and
 * [ImageEncoding.formatAndQuality] touch android.graphics/os and are covered by manual verification.
 */
class ImageEncodingTest {

    @Test
    fun isCutoutName_acceptsNewAndLegacy() {
        assertTrue(ImageEncoding.isCutoutName("abc123_cutout.webp"))
        assertTrue(ImageEncoding.isCutoutName("abc123_cutout.png")) // legacy
        assertFalse(ImageEncoding.isCutoutName("abc123_original.webp"))
        assertFalse(ImageEncoding.isCutoutName("abc123.json"))
    }

    @Test
    fun isOriginalName_acceptsNewAndLegacy() {
        assertTrue(ImageEncoding.isOriginalName("abc123_original.webp"))
        assertTrue(ImageEncoding.isOriginalName("abc123_original.jpg")) // legacy
        assertFalse(ImageEncoding.isOriginalName("abc123_cutout.webp"))
    }

    @Test
    fun cutoutIdFromName_stripsEitherSuffix() {
        assertEquals("abc123", ImageEncoding.cutoutIdFromName("abc123_cutout.webp"))
        assertEquals("abc123", ImageEncoding.cutoutIdFromName("abc123_cutout.png"))
        assertNull(ImageEncoding.cutoutIdFromName("abc123_original.webp"))
    }

    @Test
    fun originalIdFromName_stripsEitherSuffix() {
        assertEquals("xyz", ImageEncoding.originalIdFromName("xyz_original.webp"))
        assertEquals("xyz", ImageEncoding.originalIdFromName("xyz_original.jpg"))
        assertNull(ImageEncoding.originalIdFromName("xyz_cutout.png"))
    }

    @Test
    fun nameBuilders_alwaysWebp() {
        assertEquals("id1_cutout.webp", ImageEncoding.cutoutNameFor("id1"))
        assertEquals("id1_original.webp", ImageEncoding.originalNameFor("id1"))
    }

    @Test
    fun itemMatchKey_isExtensionAgnosticForCutouts() {
        // A legacy PNG cutout and its converted WebP must collapse to the same key so outfit/
        // try-on itemNames keep resolving across the WebP migration.
        assertEquals(
            ImageEncoding.itemMatchKey("abc123_cutout.png"),
            ImageEncoding.itemMatchKey("abc123_cutout.webp"),
        )
        assertEquals("abc123_cutout", ImageEncoding.itemMatchKey("abc123_cutout.webp"))
        // Distinct items never collide.
        assertFalse(
            ImageEncoding.itemMatchKey("a_cutout.png") == ImageEncoding.itemMatchKey("b_cutout.png"),
        )
        // Non-cutout names pass through unchanged.
        assertEquals("note.json", ImageEncoding.itemMatchKey("note.json"))
    }

    @Test
    fun detectMimeType_sniffsByMagicBytes() {
        val webp = tempFile("webp", byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            0, 0, 0, 0,
            'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
        ))
        val png = tempFile("png", byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
        ))
        val jpeg = tempFile("jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))

        assertEquals("image/webp", ImageEncoding.detectMimeType(webp))
        assertEquals("image/png", ImageEncoding.detectMimeType(png))
        assertEquals("image/jpeg", ImageEncoding.detectMimeType(jpeg))
    }

    private fun tempFile(ext: String, bytes: ByteArray): File =
        File.createTempFile("imgenc", ".$ext").apply { writeBytes(bytes); deleteOnExit() }
}
