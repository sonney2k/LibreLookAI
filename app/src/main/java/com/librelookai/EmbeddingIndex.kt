package com.librelookai

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
 * Persistent key→vector index for wardrobe cutout embeddings.
 *
 * Keys are cutout Drive IDs. Values are L2-normalized `FloatArray`s produced by
 * [EmbeddingRepository.embedFile]. Because vectors are unit-length, cosine similarity is a plain
 * dot product — [search] returns top-K by that score (range [-1, 1]).
 *
 * On-disk format at `filesDir/wardrobe_embeddings.bin`:
 *   magic u32 ('LLAE' 0x4C4C4145)
 *   version u16 (currently 1)
 *   dim u16
 *   count u32
 *   for each row:
 *     idLen u16 | idBytes utf8 | float32 * dim
 *
 * All access goes through a single [Mutex] — callers can safely share one instance across
 * coroutines. Writes are eagerly flushed; a tiny delta doesn't justify debouncing yet.
 */
class EmbeddingIndex(private val context: Context) {

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, FloatArray>()
    @Volatile private var loaded = false
    @Volatile private var dim: Int = 0

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
                    val count = input.readInt()
                    dim = fileDim
                    entries.clear()
                    repeat(count) {
                        val idLen = input.readUnsignedShort()
                        val idBytes = ByteArray(idLen)
                        input.readFully(idBytes)
                        val vec = FloatArray(fileDim)
                        for (i in 0 until fileDim) vec[i] = input.readFloat()
                        entries[String(idBytes, Charsets.UTF_8)] = vec
                    }
                }
            }.onFailure {
                Log.w(TAG, "Failed to load embeddings index — discarding", it)
                entries.clear()
            }
        }
    }

    suspend fun upsert(id: String, vec: FloatArray) {
        mutex.withLock {
            if (dim == 0) dim = vec.size
            if (vec.size != dim) {
                Log.w(TAG, "upsert: dim mismatch ${vec.size} vs $dim — skipping $id")
                return
            }
            entries[id] = vec
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

    /** Defensive copy of the embedding stored for [id], or null if not indexed. */
    suspend fun vector(id: String): FloatArray? = mutex.withLock { entries[id]?.copyOf() }

    suspend fun save() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = indexFile()
            val tmp = File(file.parentFile, file.name + ".tmp")
            runCatching {
                DataOutputStream(tmp.outputStream().buffered()).use { out ->
                    out.writeInt(MAGIC)
                    out.writeShort(VERSION)
                    out.writeShort(dim)
                    out.writeInt(entries.size)
                    for ((id, vec) in entries) {
                        val idBytes = id.toByteArray(Charsets.UTF_8)
                        out.writeShort(idBytes.size)
                        out.write(idBytes)
                        for (f in vec) out.writeFloat(f)
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
     * Returns the top [topK] matches for [query] ordered by descending similarity. When the index
     * is empty or dimensions mismatch, returns an empty list.
     */
    suspend fun search(query: FloatArray, topK: Int): List<Match> {
        return mutex.withLock {
            if (entries.isEmpty() || query.size != dim) return@withLock emptyList()
            val scored = ArrayList<Match>(entries.size)
            for ((id, vec) in entries) {
                scored.add(Match(id, dot(query, vec)))
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
        // Version bumped to 2 when the embedder swapped from MobileNetV3-Small (1024-d) to
        // EfficientNet Lite0 (1280-d) and the bitmap pre-processing pipeline was changed.
        // Old indexes are discarded on load and rebuilt on first sync.
        private const val VERSION = 2
    }
}
