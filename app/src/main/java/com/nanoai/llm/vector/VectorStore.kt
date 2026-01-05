package com.nanoai.llm.vector

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * VectorStore - Simple in-memory vector store with disk persistence.
 *
 * Uses cosine similarity for retrieval.
 * Stores vectors as normalized float arrays.
 */
class VectorStore(
    private val context: Context,
    private val storeName: String = "default"
) {
    companion object {
        private const val TAG = "VectorStore"
        private const val STORE_FILE = "vectors.json"
        private const val META_FILE = "metadata.json"
    }

    private val storeDir: File = File(context.filesDir, "rag_data/$storeName").apply { mkdirs() }
    private val gson = Gson()
    private val mutex = Mutex()

    // In-memory storage
    private val documents = mutableListOf<DocumentChunk>()
    private val embeddings = mutableListOf<FloatArray>()

    // Statistics
    var totalChunks: Int = 0
        private set
    var embeddingDimension: Int = 0
        private set

    init {
        // Load existing data on initialization
        loadFromDisk()
    }

    /**
     * Add a document chunk with its embedding.
     */
    suspend fun addChunk(
        text: String,
        embedding: FloatArray,
        metadata: ChunkMetadata
    ): Int = mutex.withLock {
        // Normalize embedding
        val normalized = normalize(embedding)

        // Set embedding dimension if first chunk
        if (embeddingDimension == 0) {
            embeddingDimension = normalized.size
        }

        val chunk = DocumentChunk(
            id = documents.size,
            text = text,
            metadata = metadata,
            embeddingSize = normalized.size
        )

        documents.add(chunk)
        embeddings.add(normalized)
        totalChunks = documents.size

        Log.d(TAG, "Added chunk ${chunk.id}: ${text.take(50)}...")
        chunk.id
    }

    /**
     * Add multiple chunks at once (more efficient).
     */
    suspend fun addChunks(
        chunks: List<Pair<String, FloatArray>>,
        source: String
    ): List<Int> = mutex.withLock {
        val ids = mutableListOf<Int>()

        for ((index, pair) in chunks.withIndex()) {
            val (text, embedding) = pair
            val normalized = normalize(embedding)

            if (embeddingDimension == 0) {
                embeddingDimension = normalized.size
            }

            val metadata = ChunkMetadata(
                source = source,
                chunkIndex = index,
                totalChunks = chunks.size,
                timestamp = System.currentTimeMillis()
            )

            val chunk = DocumentChunk(
                id = documents.size,
                text = text,
                metadata = metadata,
                embeddingSize = normalized.size
            )

            documents.add(chunk)
            embeddings.add(normalized)
            ids.add(chunk.id)
        }

        totalChunks = documents.size
        Log.i(TAG, "Added ${chunks.size} chunks from $source")
        ids
    }

    /**
     * Search for similar chunks using cosine similarity.
     *
     * @param queryEmbedding Query vector (will be normalized)
     * @param topK Number of results to return
     * @param minSimilarity Minimum similarity threshold (0-1)
     * @return List of matching chunks with scores
     */
    suspend fun search(
        queryEmbedding: FloatArray,
        topK: Int = 5,
        minSimilarity: Float = 0.0f
    ): List<SearchResult> = mutex.withLock {
        if (embeddings.isEmpty()) {
            return@withLock emptyList()
        }

        val normalizedQuery = normalize(queryEmbedding)

        // Calculate similarities
        val similarities = embeddings.mapIndexed { index, embedding ->
            val similarity = cosineSimilarity(normalizedQuery, embedding)
            index to similarity
        }

        // Filter and sort
        similarities
            .filter { it.second >= minSimilarity }
            .sortedByDescending { it.second }
            .take(topK)
            .map { (index, score) ->
                SearchResult(
                    chunk = documents[index],
                    score = score
                )
            }
    }

    /**
     * Search and return just the text (convenience method).
     */
    suspend fun searchText(
        queryEmbedding: FloatArray,
        topK: Int = 5,
        minSimilarity: Float = 0.0f
    ): List<String> {
        return search(queryEmbedding, topK, minSimilarity).map { it.chunk.text }
    }

    /**
     * Get chunk by ID.
     */
    suspend fun getChunk(id: Int): DocumentChunk? = mutex.withLock {
        documents.getOrNull(id)
    }

    /**
     * Get all chunks from a source.
     */
    suspend fun getChunksBySource(source: String): List<DocumentChunk> = mutex.withLock {
        documents.filter { it.metadata.source == source }
    }

    /**
     * Delete chunks by source.
     */
    suspend fun deleteBySource(source: String): Int = mutex.withLock {
        val toRemove = documents.indices
            .filter { documents[it].metadata.source == source }
            .toSet()

        if (toRemove.isEmpty()) return@withLock 0

        // Rebuild without removed items
        val newDocs = mutableListOf<DocumentChunk>()
        val newEmbeddings = mutableListOf<FloatArray>()

        documents.forEachIndexed { index, chunk ->
            if (index !in toRemove) {
                newDocs.add(chunk.copy(id = newDocs.size))
                newEmbeddings.add(embeddings[index])
            }
        }

        documents.clear()
        documents.addAll(newDocs)
        embeddings.clear()
        embeddings.addAll(newEmbeddings)
        totalChunks = documents.size

        Log.i(TAG, "Deleted ${toRemove.size} chunks from $source")
        toRemove.size
    }

    /**
     * Clear all data.
     */
    suspend fun clear() = mutex.withLock {
        documents.clear()
        embeddings.clear()
        totalChunks = 0
        embeddingDimension = 0

        // Delete files
        File(storeDir, STORE_FILE).delete()
        File(storeDir, META_FILE).delete()

        Log.i(TAG, "Cleared all data")
    }

    /**
     * Get all unique sources.
     */
    suspend fun getSources(): List<String> = mutex.withLock {
        documents.map { it.metadata.source }.distinct()
    }

    /**
     * Get statistics.
     */
    fun getStats(): VectorStoreStats {
        return VectorStoreStats(
            totalChunks = totalChunks,
            embeddingDimension = embeddingDimension,
            sources = documents.map { it.metadata.source }.distinct().size,
            estimatedMemoryMB = (totalChunks * embeddingDimension * 4) / (1024.0 * 1024.0)
        )
    }

    /**
     * Save to disk.
     */
    suspend fun saveToDisk() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                // Save metadata
                val metaFile = File(storeDir, META_FILE)
                val meta = StoreMeta(
                    totalChunks = totalChunks,
                    embeddingDimension = embeddingDimension,
                    version = 1
                )
                metaFile.writeText(gson.toJson(meta))

                // Save documents (without embeddings in JSON for size)
                val storeFile = File(storeDir, STORE_FILE)
                val storeData = StoreData(
                    documents = documents.toList(),
                    embeddings = embeddings.map { it.toList() }
                )
                storeFile.writeText(gson.toJson(storeData))

                Log.i(TAG, "Saved $totalChunks chunks to disk")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save to disk", e)
            }
        }
    }

    /**
     * Load from disk.
     */
    private fun loadFromDisk() {
        try {
            val metaFile = File(storeDir, META_FILE)
            val storeFile = File(storeDir, STORE_FILE)

            if (!metaFile.exists() || !storeFile.exists()) {
                Log.d(TAG, "No existing data to load")
                return
            }

            // Load metadata
            val meta = gson.fromJson(metaFile.readText(), StoreMeta::class.java)

            // Load store data
            val type = object : TypeToken<StoreData>() {}.type
            val storeData: StoreData = gson.fromJson(storeFile.readText(), type)

            documents.clear()
            documents.addAll(storeData.documents)

            embeddings.clear()
            storeData.embeddings.forEach { list ->
                embeddings.add(list.toFloatArray())
            }

            totalChunks = meta.totalChunks
            embeddingDimension = meta.embeddingDimension

            Log.i(TAG, "Loaded $totalChunks chunks from disk")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load from disk", e)
        }
    }

    // Math utilities
    private fun normalize(vector: FloatArray): FloatArray {
        val norm = sqrt(vector.map { it * it }.sum())
        return if (norm > 0) {
            vector.map { it / norm }.toFloatArray()
        } else {
            vector.copyOf()
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
        }
        return dot // Already normalized, so dot product = cosine
    }
}

/**
 * Document chunk data class.
 */
data class DocumentChunk(
    val id: Int,
    val text: String,
    val metadata: ChunkMetadata,
    val embeddingSize: Int
)

/**
 * Chunk metadata.
 */
data class ChunkMetadata(
    val source: String,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val title: String? = null,
    val url: String? = null
)

/**
 * Search result.
 */
data class SearchResult(
    val chunk: DocumentChunk,
    val score: Float
)

/**
 * Vector store statistics.
 */
data class VectorStoreStats(
    val totalChunks: Int,
    val embeddingDimension: Int,
    val sources: Int,
    val estimatedMemoryMB: Double
)

// Internal persistence classes
private data class StoreMeta(
    val totalChunks: Int,
    val embeddingDimension: Int,
    val version: Int
)

private data class StoreData(
    val documents: List<DocumentChunk>,
    val embeddings: List<List<Float>>
)
