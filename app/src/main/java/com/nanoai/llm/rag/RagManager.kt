package com.nanoai.llm.rag

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.nanoai.llm.LlamaBridge
import com.nanoai.llm.GenerationParams
import com.nanoai.llm.vector.ChunkMetadata
import com.nanoai.llm.vector.SearchResult
import com.nanoai.llm.vector.VectorStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RagManager - Orchestrates RAG pipeline with web scraping, embeddings, and retrieval.
 *
 * Handles:
 * - Web page scraping and indexing
 * - Document chunking and embedding
 * - Context retrieval for LLM prompts
 * - Prompt construction with retrieved context
 */
class RagManager(private val context: Context) {
    companion object {
        private const val TAG = "RagManager"
        private const val PREFS_NAME = "nanoai_rag"
        private const val KEY_RAG_ENABLED = "rag_enabled"
        private const val KEY_TOP_K = "top_k"
        private const val KEY_CHUNK_SIZE = "chunk_size"
        private const val KEY_MIN_SIMILARITY = "min_similarity"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Vector store for embeddings
    val vectorStore: VectorStore = VectorStore(context, "main")

    // Settings
    private val _isEnabled = MutableStateFlow(prefs.getBoolean(KEY_RAG_ENABLED, true))
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _topK = MutableStateFlow(prefs.getInt(KEY_TOP_K, 5))
    val topK: StateFlow<Int> = _topK.asStateFlow()

    private val _chunkSize = MutableStateFlow(prefs.getInt(KEY_CHUNK_SIZE, 300))
    val chunkSize: StateFlow<Int> = _chunkSize.asStateFlow()

    private val _minSimilarity = MutableStateFlow(prefs.getFloat(KEY_MIN_SIMILARITY, 0.3f))
    val minSimilarity: StateFlow<Float> = _minSimilarity.asStateFlow()

    // State
    private val _indexingState = MutableStateFlow<IndexingState>(IndexingState.Idle)
    val indexingState: StateFlow<IndexingState> = _indexingState.asStateFlow()

    private val _sources = MutableStateFlow<List<RagSource>>(emptyList())
    val sources: StateFlow<List<RagSource>> = _sources.asStateFlow()

    init {
        refreshSources()
    }

    /**
     * Enable or disable RAG.
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        prefs.edit().putBoolean(KEY_RAG_ENABLED, enabled).apply()
    }

    /**
     * Set top-K results for retrieval.
     */
    fun setTopK(k: Int) {
        _topK.value = k.coerceIn(1, 20)
        prefs.edit().putInt(KEY_TOP_K, _topK.value).apply()
    }

    /**
     * Set chunk size in tokens.
     */
    fun setChunkSize(tokens: Int) {
        _chunkSize.value = tokens.coerceIn(100, 1000)
        prefs.edit().putInt(KEY_CHUNK_SIZE, _chunkSize.value).apply()
    }

    /**
     * Set minimum similarity threshold.
     */
    fun setMinSimilarity(similarity: Float) {
        _minSimilarity.value = similarity.coerceIn(0f, 0.9f)
        prefs.edit().putFloat(KEY_MIN_SIMILARITY, _minSimilarity.value).apply()
    }

    /**
     * Index a web page.
     */
    suspend fun indexUrl(url: String): Result<RagSource> = withContext(Dispatchers.IO) {
        try {
            _indexingState.value = IndexingState.Scraping(url)

            // Scrape the page
            val scrapeResult = WebScraper.scrape(url)
            val content = scrapeResult.getOrElse {
                _indexingState.value = IndexingState.Error("Failed to scrape: ${it.message}")
                return@withContext Result.failure(it)
            }

            if (content.text.isBlank()) {
                _indexingState.value = IndexingState.Error("No content found")
                return@withContext Result.failure(IllegalStateException("No content found"))
            }

            // Chunk the content
            _indexingState.value = IndexingState.Chunking(content.title)
            val chunks = WebScraper.chunkByTokens(
                text = content.text,
                maxTokens = _chunkSize.value
            )

            if (chunks.isEmpty()) {
                _indexingState.value = IndexingState.Error("No chunks created")
                return@withContext Result.failure(IllegalStateException("No chunks created"))
            }

            Log.i(TAG, "Created ${chunks.size} chunks from $url")

            // Generate embeddings
            _indexingState.value = IndexingState.Embedding(chunks.size)

            if (!LlamaBridge.isModelLoaded()) {
                _indexingState.value = IndexingState.Error("Model not loaded for embeddings")
                return@withContext Result.failure(IllegalStateException("Model not loaded"))
            }

            val chunkEmbeddings = mutableListOf<Pair<String, FloatArray>>()
            for ((index, chunk) in chunks.withIndex()) {
                _indexingState.value = IndexingState.Embedding(chunks.size, index + 1)

                val embeddingResult = LlamaBridge.getEmbeddingAsync(chunk)
                embeddingResult.onSuccess { embedding ->
                    chunkEmbeddings.add(chunk to embedding)
                }.onFailure { e ->
                    Log.w(TAG, "Failed to embed chunk $index: ${e.message}")
                    // Use simple hash-based fallback embedding
                    val fallback = createFallbackEmbedding(chunk)
                    chunkEmbeddings.add(chunk to fallback)
                }
            }

            // Store in vector store
            _indexingState.value = IndexingState.Storing
            val source = url.hashCode().toString()
            vectorStore.addChunks(chunkEmbeddings, source)
            vectorStore.saveToDisk()

            // Create source record
            val ragSource = RagSource(
                id = source,
                url = url,
                title = content.title,
                chunksCount = chunkEmbeddings.size,
                wordCount = content.wordCount,
                addedTimestamp = System.currentTimeMillis()
            )

            refreshSources()
            _indexingState.value = IndexingState.Complete(ragSource)
            Log.i(TAG, "Indexed $url with ${chunkEmbeddings.size} chunks")

            Result.success(ragSource)

        } catch (e: Exception) {
            Log.e(TAG, "Indexing failed", e)
            _indexingState.value = IndexingState.Error(e.message ?: "Indexing failed")
            Result.failure(e)
        }
    }

    /**
     * Index plain text content.
     */
    suspend fun indexText(
        text: String,
        sourceId: String,
        title: String? = null
    ): Result<RagSource> = withContext(Dispatchers.IO) {
        try {
            _indexingState.value = IndexingState.Chunking(title ?: sourceId)

            // Chunk the text
            val chunks = WebScraper.chunkByTokens(
                text = text,
                maxTokens = _chunkSize.value
            )

            if (chunks.isEmpty()) {
                _indexingState.value = IndexingState.Error("No chunks created")
                return@withContext Result.failure(IllegalStateException("No chunks created"))
            }

            // Generate embeddings
            _indexingState.value = IndexingState.Embedding(chunks.size)

            val chunkEmbeddings = mutableListOf<Pair<String, FloatArray>>()
            for ((index, chunk) in chunks.withIndex()) {
                _indexingState.value = IndexingState.Embedding(chunks.size, index + 1)

                val embeddingResult = LlamaBridge.getEmbeddingAsync(chunk)
                embeddingResult.onSuccess { embedding ->
                    chunkEmbeddings.add(chunk to embedding)
                }.onFailure {
                    val fallback = createFallbackEmbedding(chunk)
                    chunkEmbeddings.add(chunk to fallback)
                }
            }

            // Store
            _indexingState.value = IndexingState.Storing
            vectorStore.addChunks(chunkEmbeddings, sourceId)
            vectorStore.saveToDisk()

            val ragSource = RagSource(
                id = sourceId,
                url = null,
                title = title ?: "Text: ${text.take(30)}...",
                chunksCount = chunkEmbeddings.size,
                wordCount = text.split(Regex("\\s+")).size,
                addedTimestamp = System.currentTimeMillis()
            )

            refreshSources()
            _indexingState.value = IndexingState.Complete(ragSource)

            Result.success(ragSource)

        } catch (e: Exception) {
            Log.e(TAG, "Text indexing failed", e)
            _indexingState.value = IndexingState.Error(e.message ?: "Indexing failed")
            Result.failure(e)
        }
    }

    /**
     * Retrieve relevant context for a query.
     */
    suspend fun retrieve(query: String): List<SearchResult> {
        if (!_isEnabled.value) return emptyList()
        if (!LlamaBridge.isModelLoaded()) return emptyList()

        return try {
            val embeddingResult = LlamaBridge.getEmbeddingAsync(query)
            embeddingResult.getOrNull()?.let { embedding ->
                vectorStore.search(
                    queryEmbedding = embedding,
                    topK = _topK.value,
                    minSimilarity = _minSimilarity.value
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Retrieval failed", e)
            emptyList()
        }
    }

    /**
     * Build a prompt with RAG context.
     */
    suspend fun buildRagPrompt(
        userQuery: String,
        systemPrompt: String? = null
    ): String {
        val results = retrieve(userQuery)

        val contextText = if (results.isNotEmpty()) {
            results.joinToString("\n\n") { result ->
                "[Source: ${result.chunk.metadata.source}]\n${result.chunk.text}"
            }
        } else {
            ""
        }

        return buildPrompt(
            userQuery = userQuery,
            context = contextText,
            systemPrompt = systemPrompt
        )
    }

    /**
     * Build prompt with given context.
     */
    fun buildPrompt(
        userQuery: String,
        context: String = "",
        systemPrompt: String? = null
    ): String {
        val system = systemPrompt ?: DEFAULT_SYSTEM_PROMPT

        return if (context.isNotBlank()) {
            """
SYSTEM:
$system

CONTEXT:
$context

USER:
$userQuery

ASSISTANT:
""".trim()
        } else {
            """
SYSTEM:
$system

USER:
$userQuery

ASSISTANT:
""".trim()
        }
    }

    /**
     * Generate a response with RAG.
     */
    suspend fun generateWithRag(
        userQuery: String,
        params: GenerationParams = GenerationParams.BALANCED,
        systemPrompt: String? = null
    ): Result<RagResponse> {
        val results = retrieve(userQuery)
        val prompt = buildRagPrompt(userQuery, systemPrompt)

        val generationResult = LlamaBridge.generateAsync(prompt, params)

        return generationResult.map { response ->
            RagResponse(
                response = response,
                sources = results.map { it.chunk.metadata.source }.distinct(),
                chunksUsed = results.size
            )
        }
    }

    /**
     * Delete a source and its chunks.
     */
    suspend fun deleteSource(sourceId: String): Result<Unit> {
        return try {
            vectorStore.deleteBySource(sourceId)
            vectorStore.saveToDisk()
            refreshSources()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Clear all RAG data.
     */
    suspend fun clearAll() {
        vectorStore.clear()
        refreshSources()
    }

    /**
     * Refresh sources list.
     */
    private fun refreshSources() {
        kotlinx.coroutines.GlobalScope.launch {
            val sourceIds = vectorStore.getSources()
            val sourceList = sourceIds.map { id ->
                val chunks = vectorStore.getChunksBySource(id)
                RagSource(
                    id = id,
                    url = chunks.firstOrNull()?.metadata?.url,
                    title = chunks.firstOrNull()?.metadata?.title ?: id,
                    chunksCount = chunks.size,
                    wordCount = chunks.sumOf { it.text.split(Regex("\\s+")).size },
                    addedTimestamp = chunks.minOfOrNull { it.metadata.timestamp }
                        ?: System.currentTimeMillis()
                )
            }
            _sources.value = sourceList
        }
    }

    /**
     * Create a fallback embedding when model embedding fails.
     * Uses simple TF-IDF-like hashing.
     */
    private fun createFallbackEmbedding(text: String, dimension: Int = 384): FloatArray {
        val embedding = FloatArray(dimension)
        val words = text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }

        for (word in words) {
            val hash = word.hashCode()
            val index = Math.abs(hash) % dimension
            embedding[index] += 1f / words.size
        }

        // Normalize
        val norm = kotlin.math.sqrt(embedding.map { it * it }.sum())
        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }

        return embedding
    }

    /**
     * Get statistics.
     */
    fun getStats(): RagStats {
        val storeStats = vectorStore.getStats()
        return RagStats(
            enabled = _isEnabled.value,
            sources = _sources.value.size,
            totalChunks = storeStats.totalChunks,
            embeddingDimension = storeStats.embeddingDimension,
            estimatedMemoryMB = storeStats.estimatedMemoryMB,
            topK = _topK.value,
            chunkSize = _chunkSize.value,
            minSimilarity = _minSimilarity.value
        )
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """You are a helpful offline AI assistant. Answer questions accurately and concisely based on the provided context. If the context doesn't contain relevant information, say so and provide your best general knowledge answer."""
    }
}

/**
 * RAG source data class.
 */
data class RagSource(
    val id: String,
    val url: String?,
    val title: String,
    val chunksCount: Int,
    val wordCount: Int,
    val addedTimestamp: Long
)

/**
 * RAG response with metadata.
 */
data class RagResponse(
    val response: String,
    val sources: List<String>,
    val chunksUsed: Int
)

/**
 * RAG statistics.
 */
data class RagStats(
    val enabled: Boolean,
    val sources: Int,
    val totalChunks: Int,
    val embeddingDimension: Int,
    val estimatedMemoryMB: Double,
    val topK: Int,
    val chunkSize: Int,
    val minSimilarity: Float
)

/**
 * Indexing state.
 */
sealed class IndexingState {
    object Idle : IndexingState()
    data class Scraping(val url: String) : IndexingState()
    data class Chunking(val title: String) : IndexingState()
    data class Embedding(val total: Int, val current: Int = 0) : IndexingState()
    object Storing : IndexingState()
    data class Complete(val source: RagSource) : IndexingState()
    data class Error(val message: String) : IndexingState()
}
