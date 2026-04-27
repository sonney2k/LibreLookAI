package com.librelookai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Fetches the hero product image of a shopping page from a pasted URL.
 *
 * Resolution order:
 *  1. `<meta property="og:image" content="...">` (or `name="og:image"`)
 *  2. `<meta name="twitter:image" content="...">` (or `property="twitter:image"`)
 *  3. JSON-LD `Product.image` — first array element if multiple.
 *
 * On success, downloads the resolved image to [destDir] as `url_import_{timestamp}.jpg`
 * and returns the [File]. Returns null on any failure (404, no image found, network error).
 *
 * Retailers that render product images client-side (some Shopify themes, certain SPAs) will
 * not expose a usable og:image and will return null. Callers must surface a clear error.
 */
object WebProductFetcher {

    private const val TAG = "WebProductFetcher"
    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 13_0) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun fetchProductImage(pageUrl: String, destDir: File): File? = withContext(Dispatchers.IO) {
        val normalizedPageUrl = normalizeUrl(pageUrl) ?: return@withContext null

        val html = runCatching {
            val req = Request.Builder()
                .url(normalizedPageUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9,de;q=0.8")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Page fetch failed: ${resp.code} for $normalizedPageUrl")
                    return@withContext null
                }
                resp.body?.string()
            }
        }.onFailure { Log.w(TAG, "Page fetch threw", it) }.getOrNull() ?: return@withContext null

        val imageUrl = extractMetaContent(html, "og:image")
            ?: extractMetaContent(html, "twitter:image")
            ?: extractJsonLdProductImage(html)
            ?: run {
                Log.w(TAG, "No og:image / twitter:image / JSON-LD Product image found")
                return@withContext null
            }

        val absoluteImageUrl = resolveUrl(normalizedPageUrl, imageUrl) ?: return@withContext null

        runCatching {
            destDir.mkdirs()
            val out = File(destDir, "url_import_${System.currentTimeMillis()}.jpg")
            val req = Request.Builder()
                .url(absoluteImageUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", normalizedPageUrl)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Image fetch failed: ${resp.code} for $absoluteImageUrl")
                    return@withContext null
                }
                out.outputStream().use { os ->
                    resp.body!!.byteStream().copyTo(os)
                }
            }
            out
        }.onFailure { Log.w(TAG, "Image download threw", it) }.getOrNull()
    }

    /** Adds an https:// scheme if the user pasted a bare host like "amazon.de/foo". */
    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return runCatching { URL(withScheme).toString() }.getOrNull()
    }

    /**
     * Extract `<meta property|name="$key" content="...">` (either attribute order). Returns the
     * decoded `content` value or null if the tag is absent. Tolerant of single-quoted attributes
     * and arbitrary whitespace; case-insensitive on attribute names and the key value.
     */
    private fun extractMetaContent(html: String, key: String): String? {
        val keyEsc = Regex.escape(key)
        // Pattern A: <meta property|name="key" ... content="...">
        val a = Regex(
            """<meta\s+(?:property|name)\s*=\s*["']$keyEsc["'][^>]*?content\s*=\s*["']([^"']+)["']""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.get(1)
        if (a != null) return decodeHtmlEntities(a)
        // Pattern B: <meta content="..." property|name="key">
        val b = Regex(
            """<meta\s+[^>]*?content\s*=\s*["']([^"']+)["'][^>]*?(?:property|name)\s*=\s*["']$keyEsc["']""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.get(1)
        return b?.let { decodeHtmlEntities(it) }
    }

    /**
     * Naive JSON-LD `Product` image extractor — finds a `<script type="application/ld+json">`
     * block, looks for `"@type": "Product"` followed by an `"image"` field. Matches both
     * string ("image": "https://...") and array ("image": ["https://..."]) forms.
     */
    private fun extractJsonLdProductImage(html: String): String? {
        val scripts = Regex(
            """<script[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(html).map { it.groupValues[1] }

        for (block in scripts) {
            // Cheap filter: must mention Product type
            if (!Regex(""""@type"\s*:\s*"Product"""", RegexOption.IGNORE_CASE).containsMatchIn(block)) continue

            // image: "<url>"
            val str = Regex(""""image"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)
            if (!str.isNullOrBlank()) return decodeHtmlEntities(str)

            // image: ["<url>", ...]
            val arr = Regex(""""image"\s*:\s*\[\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)
            if (!arr.isNullOrBlank()) return decodeHtmlEntities(arr)
        }
        return null
    }

    /** Resolves [maybeRelative] against [baseUrl]; supports protocol-relative `//cdn/foo`. */
    private fun resolveUrl(baseUrl: String, maybeRelative: String): String? = runCatching {
        URL(URL(baseUrl), maybeRelative).toString()
    }.getOrNull()

    /** Minimal HTML entity decoder for the handful that show up in og:image URLs. */
    private fun decodeHtmlEntities(s: String): String =
        s.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
}
