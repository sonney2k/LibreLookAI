package com.librelookai.ml
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Persistent key→(embedding, color-histogram) index for wardrobe cutout entries.
 *
 * Keys are cutout Drive IDs. Each entry holds a L2-normalized CNN embedding (cosine = dot product)
 * and a L1-normalized HSV color histogram. [search] combines both into a single score:
 *
 *     score = α · cos(emb) + (1 − α) · cos(hist),   α = EMBED_WEIGHT
 *
 * The histogram channel exists because pure CNN embeddings are weak on hue and routinely confuse
 * uniformly-colored garments (e.g. red vs. black). See `ColorHistogram` for the bin layout.
 *
 * On-disk format at `filesDir/wardrobe_embeddings.bin`:
 *   magic u32 ('LLAE' 0x4C4C4145)
 *   version u16 (currently 6)
 *   dim u16
 *   histDim u16
 *   count u32
 *   for each row:
 *     idLen u16 | idBytes utf8 | float32 * dim | float32 * histDim
 *
 * All access goes through a single [Mutex] — callers can safely share one instance across
 * coroutines. Writes are eagerly flushed; a tiny delta doesn't justify debouncing yet.
 */
class EmbeddingIndex(private val context: Context) {

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, Entry>()
    @Volatile private var loaded = false
    @Volatile private var dim: Int = 0

    data class Entry(val vec: FloatArray, val hist: FloatArray, val pHashes: LongArray)
    data class Match(val id: String, val score: Float)

    val size: Int get() = entries.size

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withLock
            loaded = true
            val file = indexFile()
            if (!file.exists()) return@withLock
            runCatching {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    val magic = input.readInt()
                    if (magic != MAGIC) {
                        Log.w(TAG, "Index magic mismatch (got 0x${magic.toString(16)}) — ignoring")
                        return@use
                    }
                    val version = input.readUnsignedShort()
                    if (version != VERSION) {
                        Log.w(TAG, "Index version $version unsupported — ignoring")
                        return@use
                    }
                    val fileDim = input.readUnsignedShort()
                    val fileHistDim = input.readUnsignedShort()
                    val filePHashCount = input.readUnsignedShort()
                    val count = input.readInt()
                    if (fileHistDim != HIST_DIM) {
                        Log.w(TAG, "Index histDim $fileHistDim != $HIST_DIM — ignoring")
                        return@use
                    }
                    if (filePHashCount != PHASH_COUNT) {
                        Log.w(TAG, "Index pHashCount $filePHashCount != $PHASH_COUNT — ignoring")
                        return@use
                    }
                    dim = fileDim
                    entries.clear()
                    repeat(count) {
                        val idLen = input.readUnsignedShort()
                        val idBytes = ByteArray(idLen)
                        input.readFully(idBytes)
                        val vec = FloatArray(fileDim)
                        for (i in 0 until fileDim) vec[i] = input.readFloat()
                        val hist = FloatArray(fileHistDim)
                        for (i in 0 until fileHistDim) hist[i] = input.readFloat()
                        val pHashes = LongArray(filePHashCount)
                        for (i in 0 until filePHashCount) pHashes[i] = input.readLong()
                        entries[String(idBytes, Charsets.UTF_8)] = Entry(vec, hist, pHashes)
                    }
                }
            }.onFailure {
                Log.w(TAG, "Failed to load embeddings index — discarding", it)
                entries.clear()
            }
        }
    }

    suspend fun upsert(id: String, vec: FloatArray, hist: FloatArray, pHashes: LongArray) {
        mutex.withLock {
            if (dim == 0) dim = vec.size
            if (vec.size != dim) {
                Log.w(TAG, "upsert: dim mismatch ${vec.size} vs $dim — skipping $id")
                return
            }
            if (hist.size != HIST_DIM) {
                Log.w(TAG, "upsert: histDim mismatch ${hist.size} vs $HIST_DIM — skipping $id")
                return
            }
            if (pHashes.size != PHASH_COUNT) {
                Log.w(TAG, "upsert: pHashCount mismatch ${pHashes.size} vs $PHASH_COUNT — skipping $id")
                return
            }
            entries[id] = Entry(vec, hist, pHashes)
        }
    }

    suspend fun remove(id: String) {
        mutex.withLock { entries.remove(id) }
    }

    suspend fun retainIds(keep: Set<String>): Int {
        return mutex.withLock {
            val before = entries.size
            entries.keys.retainAll(keep)
            before - entries.size
        }
    }

    suspend fun contains(id: String): Boolean = mutex.withLock { entries.containsKey(id) }

    /** Snapshot of all currently-indexed IDs. */
    suspend fun ids(): Set<String> = mutex.withLock { entries.keys.toSet() }

    /** Defensive copy of the entry stored for [id], or null if not indexed. */
    suspend fun entry(id: String): Entry? = mutex.withLock {
        entries[id]?.let { Entry(it.vec.copyOf(), it.hist.copyOf(), it.pHashes.copyOf()) }
    }

    suspend fun save() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = indexFile()
            val tmp = File(file.parentFile, file.name + ".tmp")
            runCatching {
                DataOutputStream(tmp.outputStream().buffered()).use { out ->
                    out.writeInt(MAGIC)
                    out.writeShort(VERSION)
                    out.writeShort(dim)
                    out.writeShort(HIST_DIM)
                    out.writeShort(PHASH_COUNT)
                    out.writeInt(entries.size)
                    for ((id, e) in entries) {
                        val idBytes = id.toByteArray(Charsets.UTF_8)
                        out.writeShort(idBytes.size)
                        out.write(idBytes)
                        for (f in e.vec) out.writeFloat(f)
                        for (f in e.hist) out.writeFloat(f)
                        for (h in e.pHashes) out.writeLong(h)
                    }
                }
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }.onFailure {
                Log.w(TAG, "Failed to save embeddings index", it)
                tmp.delete()
            }
        }
    }

    /**
     * Returns the top [topK] matches for the [queryVec] / [queryHist] pair, ordered by descending
     * combined score. When the index is empty or vector dimensions mismatch, returns an empty list.
     */
    suspend fun search(queryVec: FloatArray, queryHist: FloatArray, queryPHash: Long, topK: Int): List<Match> {
        return mutex.withLock {
            if (entries.isEmpty() || queryVec.size != dim || queryHist.size != HIST_DIM) {
                return@withLock emptyList()
            }
            val scored = ArrayList<Match>(entries.size)
            for ((id, e) in entries) {
                val embScore = dot(queryVec, e.vec).coerceIn(0f, 1f)
                val histScore = ColorHistogram.intersection(queryHist, e.hist)
                val pHashSim = PHash.bestSimilarity(queryPHash, e.pHashes)
                val finalScore = embScore * 0.40f + histScore * 0.20f + pHashSim * 0.40f
                scored.add(Match(id, finalScore))
            }
            scored.sortByDescending { it.score }
            if (scored.size <= topK) scored else scored.subList(0, topK).toList()
        }
    }

    private fun indexFile(): File = File(context.filesDir, INDEX_FILE)

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    companion object {
        private const val TAG = "EmbeddingIndex"
        private const val INDEX_FILE = "wardrobe_embeddings.bin"
        private const val MAGIC = 0x4C4C4145 // 'LLAE'
        // Version 7: each entry now also stores 24 rotated pHashes (every 15°) consumed by
        // `search` as a third similarity channel. v6 indices lack that block and are discarded
        // on load — `syncIndex` re-embeds every cached cutout on next sync (fast for ~100 items).
        private const val VERSION = 7
        /** Number of rotated pHashes per entry (must match `PHash.computeRotations` step of 15°). */
        private const val PHASH_COUNT = 24

        // Deprecated: previous weighted sum model replaced by multiplicative model.
        // const val EMBED_WEIGHT = 0.65f
    }
}
