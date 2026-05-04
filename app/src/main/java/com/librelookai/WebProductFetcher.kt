package com.librelookai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Fetches candidate product images from a pasted shopping URL.
 *
 * Two-stage flow used by the importer:
 *  1. [fetchImageCandidates] — pulls the HTML once and extracts a ranked, deduplicated list of
 *     candidate image URLs (og:image, twitter:image, JSON-LD, all <img> with srcset, and
 *     Amazon-specific `data-a-dynamic-image` / `data-old-hires`). The picker UI shows these in a
 *     grid; if the list is empty (JS-rendered SPA, anti-bot block, etc.), the UI offers a WebView
 *     fallback.
 *  2. [downloadImage] — downloads a chosen absolute URL to [destDir] for the standard import
 *     pipeline.
 */
object WebProductFetcher {

    private const val TAG = "WebProductFetcher"
    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 13_0) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    /** Drop tracking pixels, sprites, and tiny thumbs that are never the hero image. */
    private const val MIN_USEFUL_DIMENSION = 200

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    data class CandidatesResult(
        val pageUrl: String,
        val candidates: List<String>,
    )

    /**
     * Fetches the page HTML and returns ranked candidate image URLs (best-guess hero first).
     * Returns null only on hard network/parse failure of the page itself; an empty candidate list
     * is a valid result and means the caller should offer the WebView fallback.
     */
    suspend fun fetchImageCandidates(pageUrl: String): CandidatesResult? = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(pageUrl) ?: return@withContext null
        val html = fetchHtml(normalized) ?: return@withContext CandidatesResult(normalized, emptyList())
        val urls = extractAllCandidates(html, normalized)
        CandidatesResult(normalized, urls)
    }

    /** Downloads [absoluteImageUrl] to [destDir]; returns the saved file or null on failure. */
    suspend fun downloadImage(
        absoluteImageUrl: String,
        referer: String?,
        destDir: File,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            destDir.mkdirs()
            val out = File(destDir, "url_import_${System.currentTimeMillis()}.jpg")
            val req = Request.Builder()
                .url(absoluteImageUrl)
                .header("User-Agent", USER_AGENT)
                .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Image fetch failed: ${resp.code} for $absoluteImageUrl")
                    return@withContext null
                }
                out.outputStream().use { os -> resp.body!!.byteStream().copyTo(os) }
            }
            out
        }.onFailure { Log.w(TAG, "Image download threw", it) }.getOrNull()
    }

    /**
     * Backwards-compatible "auto" path — returns the top candidate downloaded to [destDir], or
     * null if no candidates can be extracted from the HTML. Equivalent to the legacy single-image
     * fetch but using the unified candidate ranker.
     */
    suspend fun fetchProductImage(pageUrl: String, destDir: File): File? {
        val result = fetchImageCandidates(pageUrl) ?: return null
        val top = result.candidates.firstOrNull() ?: return null
        return downloadImage(top, result.pageUrl, destDir)
    }

    // ---------- HTML fetch ----------

    private fun fetchHtml(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9,de;q=0.8")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Page fetch failed: ${resp.code} for $url")
                return@use null
            }
            resp.body?.string()
        }
    }.onFailure { Log.w(TAG, "Page fetch threw", it) }.getOrNull()

    // ---------- Candidate extraction ----------

    private fun extractAllCandidates(html: String, pageUrl: String): List<String> {
        val out = LinkedHashSet<String>()

        fun add(raw: String?) {
            if (raw.isNullOrBlank()) return
            val abs = resolveUrl(pageUrl, decodeHtmlEntities(raw)) ?: return
            if (!looksLikeImage(abs)) return
            out.add(abs)
        }

        // 1. og:image / twitter:image (highest priority — usually the hero).
        add(extractMetaContent(html, "og:image"))
        add(extractMetaContent(html, "og:image:secure_url"))
        add(extractMetaContent(html, "twitter:image"))

        // 2. JSON-LD Product images (string or array).
        extractJsonLdProductImages(html).forEach(::add)

        // 3. Amazon `data-a-dynamic-image="{ "url": [w,h], ... }"` — pick all keys.
        Regex(
            """data-a-dynamic-image\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).forEach { m ->
            val json = decodeHtmlEntities(m.groupValues[1])
            runCatching {
                val obj = JSONObject(json)
                obj.keys().forEach { add(it) }
            }
        }
        // Amazon high-res hint.
        Regex(
            """data-old-hires\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).forEach { add(it.groupValues[1]) }

        // 4. Generic <img src> + srcset (largest entry).
        Regex(
            """<img\b([^>]*?)/?>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(html).forEach { m ->
            val attrs = m.groupValues[1]
            // Prefer srcset's largest descriptor.
            val srcset = Regex("""srcset\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)
            if (srcset != null) {
                val largest = pickLargestFromSrcset(srcset)
                add(largest)
            }
            // data-src / data-zoom-image / data-large-image (lazy-loaders).
            listOf("data-src", "data-zoom-image", "data-large-image", "data-image").forEach { attr ->
                val v = Regex("""$attr\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.get(1)
                add(v)
            }
            val src = Regex("""(?<!data-)src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.get(1)
            add(src)
        }

        return out.toList()
    }

    private fun pickLargestFromSrcset(srcset: String): String? {
        // "url1 100w, url2 600w, url3 1200w" → url with highest descriptor.
        var bestUrl: String? = null
        var bestScore = -1
        srcset.split(',').forEach { entry ->
            val parts = entry.trim().split(Regex("""\s+"""))
            if (parts.isEmpty()) return@forEach
            val url = parts[0]
            val score = parts.getOrNull(1)?.let { d ->
                Regex("""(\d+)""").find(d)?.groupValues?.get(1)?.toIntOrNull()
            } ?: 0
            if (score > bestScore) {
                bestScore = score
                bestUrl = url
            }
        }
        return bestUrl
    }

    private fun looksLikeImage(url: String): Boolean {
        val lower = url.lowercase()
        // Drop obvious non-image schemes and data URIs (we can't pass them through OkHttp easily).
        if (lower.startsWith("data:")) return false
        // Strip query for extension check.
        val path = lower.substringBefore('?')
        // Most retailer CDNs serve images without an extension; allow if URL contains common
        // image-y hints OR ends in a known extension.
        val knownExt = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif")
        if (knownExt.any { path.endsWith(it) }) return true
        // Allow CDN paths that mention "image", "media", "product", "img".
        return listOf("image", "/media/", "product", "/img/", "photos").any { it in lower }
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
     * decoded `content` value or null if the tag is absent.
     */
    private fun extractMetaContent(html: String, key: String): String? {
        val keyEsc = Regex.escape(key)
        val a = Regex(
            """<meta\s+(?:property|name)\s*=\s*["']$keyEsc["'][^>]*?content\s*=\s*["']([^"']+)["']""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.get(1)
        if (a != null) return decodeHtmlEntities(a)
        val b = Regex(
            """<meta\s+[^>]*?content\s*=\s*["']([^"']+)["'][^>]*?(?:property|name)\s*=\s*["']$keyEsc["']""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.get(1)
        return b?.let { decodeHtmlEntities(it) }
    }

    /** Returns all `image` URLs found in any JSON-LD `Product` block (string or array form). */
    private fun extractJsonLdProductImages(html: String): List<String> {
        val out = mutableListOf<String>()
        val scripts = Regex(
            """<script[^>]*type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).findAll(html).map { it.groupValues[1] }

        for (block in scripts) {
            if (!Regex(""""@type"\s*:\s*"Product"""", RegexOption.IGNORE_CASE).containsMatchIn(block)) continue
            // image: "<url>"
            Regex(""""image"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .findAll(block).forEach { out.add(decodeHtmlEntities(it.groupValues[1])) }
            // image: ["<url>", "<url>", ...]
            Regex(""""image"\s*:\s*\[([^\]]+)\]""", RegexOption.IGNORE_CASE)
                .findAll(block).forEach { arr ->
                    Regex("""\"([^"]+)\"""").findAll(arr.groupValues[1]).forEach {
                        out.add(decodeHtmlEntities(it.groupValues[1]))
                    }
                }
        }
        return out
    }

    /** Resolves [maybeRelative] against [baseUrl]; supports protocol-relative `//cdn/foo`. */
    private fun resolveUrl(baseUrl: String, maybeRelative: String): String? = runCatching {
        URL(URL(baseUrl), maybeRelative).toString()
    }.getOrNull()

    private fun decodeHtmlEntities(s: String): String =
        s.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
}
