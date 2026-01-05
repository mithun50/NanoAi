package com.nanoai.llm

import android.util.Log
import com.nanoai.llm.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * NanoAi LlamaBridge - Kotlin wrapper for llama.cpp JNI interface
 *
 * Provides coroutine-based async operations for model loading,
 * text generation, and embeddings.
 */
object LlamaBridge {
    private const val TAG = "LlamaBridge"

    // Load native library
    init {
        try {
            System.loadLibrary("nanoai_jni")
            Log.i(TAG, "Native library loaded successfully")
            AppLogger.i(TAG, "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
            AppLogger.e(TAG, "Failed to load native library: ${e.message}", e)
            throw RuntimeException("Failed to load native library", e)
        }
    }

    // ========================================================================
    // Native method declarations
    // ========================================================================

    // Model management
    private external fun loadModel(modelPath: String, nCtx: Int, nThreads: Int): Boolean
    private external fun unloadModel()
    external fun isModelLoaded(): Boolean

    // Generation
    private external fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float
    ): String

    external fun stopGeneration()
    external fun isGenerating(): Boolean

    // Embeddings
    private external fun getEmbedding(text: String): FloatArray?

    // Configuration
    private external fun setThreads(nThreads: Int)
    private external fun setDefaultParams(
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float
    )

    // Model info
    external fun getContextSize(): Int
    external fun getVocabSize(): Int
    external fun getEmbeddingSize(): Int
    external fun getModelDescription(): String

    // Memory
    external fun getAvailableMemory(): Long
    private external fun freeBackend()

    // Tokenization
    private external fun tokenize(text: String, outputTokens: IntArray, addBos: Boolean): Int
    private external fun detokenize(tokens: IntArray): String

    // ========================================================================
    // Kotlin wrapper methods
    // ========================================================================

    /**
     * Load a GGUF model from the specified path.
     *
     * @param modelPath Absolute path to the GGUF model file
     * @param contextSize Context size (default 2048)
     * @param threads Number of CPU threads (default: auto-detect)
     * @return Result indicating success or failure with error message
     */
    suspend fun loadModelAsync(
        modelPath: String,
        contextSize: Int = 2048,
        threads: Int = getOptimalThreadCount()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val file = File(modelPath)
            if (!file.exists()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Model file not found: $modelPath")
                )
            }

            // Check available memory
            val availableMb = getAvailableMemory() / (1024 * 1024)
            val modelSizeMb = file.length() / (1024 * 1024)
            Log.i(TAG, "Loading model: ${file.name} (${modelSizeMb}MB), Available RAM: ${availableMb}MB")
            AppLogger.i(TAG, "Loading model: ${file.name} (${modelSizeMb}MB), Available RAM: ${availableMb}MB")

            if (modelSizeMb > availableMb * 0.8) {
                Log.w(TAG, "Warning: Model may be too large for available memory")
            }

            val success = loadModel(modelPath, contextSize, threads)
            if (success) {
                Log.i(TAG, "Model loaded: ${getModelDescription()}")
                AppLogger.i(TAG, "Model loaded: ${getModelDescription()}")
                Result.success(Unit)
            } else {
                AppLogger.e(TAG, "Failed to load model")
                Result.failure(RuntimeException("Failed to load model"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            AppLogger.e(TAG, "Error loading model: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Unload the currently loaded model and free resources.
     */
    suspend fun unloadModelAsync() = withContext(Dispatchers.IO) {
        try {
            unloadModel()
            Log.i(TAG, "Model unloaded")
        } catch (e: Exception) {
            Log.e(TAG, "Error unloading model", e)
        }
    }

    /**
     * Generate text from a prompt.
     *
     * @param prompt The input prompt
     * @param params Generation parameters
     * @return Generated text or error
     */
    suspend fun generateAsync(
        prompt: String,
        params: GenerationParams = GenerationParams()
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            if (!isModelLoaded()) {
                return@withContext Result.failure(
                    IllegalStateException("No model loaded")
                )
            }

            Log.d(TAG, "Generating with prompt length: ${prompt.length}")
            AppLogger.d(TAG, "Generating with prompt length: ${prompt.length}")

            val result = generate(
                prompt = prompt,
                maxTokens = params.maxTokens,
                temperature = params.temperature,
                topP = params.topP,
                topK = params.topK,
                repeatPenalty = params.repeatPenalty
            )

            if (result.startsWith("[Error:")) {
                Result.failure(RuntimeException(result))
            } else {
                Result.success(result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Generation error", e)
            Result.failure(e)
        }
    }

    /**
     * Generate text with streaming output.
     *
     * Note: Current implementation returns full result after completion.
     * For true streaming, would need JNI callback mechanism.
     */
    fun generateStream(
        prompt: String,
        params: GenerationParams = GenerationParams()
    ): Flow<String> = flow {
        val result = generateAsync(prompt, params)
        result.onSuccess { text ->
            // Simulate streaming by emitting chunks
            val words = text.split(" ")
            for (word in words) {
                emit("$word ")
                delay(10) // Small delay for UI responsiveness
            }
        }.onFailure { error ->
            throw error
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Get embedding vector for text (used for RAG).
     *
     * @param text Text to embed
     * @return Float array embedding or null if not supported
     */
    suspend fun getEmbeddingAsync(text: String): Result<FloatArray> =
        withContext(Dispatchers.Default) {
            try {
                if (!isModelLoaded()) {
                    return@withContext Result.failure(
                        IllegalStateException("No model loaded")
                    )
                }

                val embedding = getEmbedding(text)
                if (embedding != null) {
                    Result.success(embedding)
                } else {
                    Result.failure(
                        UnsupportedOperationException("Model doesn't support embeddings")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Embedding error", e)
                Result.failure(e)
            }
        }

    /**
     * Tokenize text to token IDs.
     */
    suspend fun tokenizeAsync(text: String, addBos: Boolean = true): Result<IntArray> =
        withContext(Dispatchers.Default) {
            try {
                if (!isModelLoaded()) {
                    return@withContext Result.failure(
                        IllegalStateException("No model loaded")
                    )
                }

                // Allocate buffer for tokens
                val buffer = IntArray(text.length + 1)
                val count = tokenize(text, buffer, addBos)

                if (count < 0) {
                    Result.failure(RuntimeException("Tokenization failed"))
                } else {
                    Result.success(buffer.copyOf(count))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Convert token IDs back to text.
     */
    suspend fun detokenizeAsync(tokens: IntArray): Result<String> =
        withContext(Dispatchers.Default) {
            try {
                if (!isModelLoaded()) {
                    return@withContext Result.failure(
                        IllegalStateException("No model loaded")
                    )
                }
                Result.success(detokenize(tokens))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Update generation parameters.
     */
    fun updateDefaultParams(params: GenerationParams) {
        setDefaultParams(
            maxTokens = params.maxTokens,
            temperature = params.temperature,
            topP = params.topP,
            topK = params.topK,
            repeatPenalty = params.repeatPenalty
        )
    }

    /**
     * Set the number of threads for inference.
     */
    fun setThreadCount(threads: Int) {
        setThreads(threads)
    }

    /**
     * Free all resources and backend.
     */
    fun freeAll() {
        freeBackend()
    }

    /**
     * Get optimal thread count based on device.
     */
    fun getOptimalThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        // Use N-1 cores, minimum 2, maximum 8
        return (cores - 1).coerceIn(2, 8)
    }

    /**
     * Get model info as a map.
     */
    fun getModelInfo(): Map<String, Any> {
        return if (isModelLoaded()) {
            mapOf(
                "loaded" to true,
                "description" to getModelDescription(),
                "contextSize" to getContextSize(),
                "vocabSize" to getVocabSize(),
                "embeddingSize" to getEmbeddingSize()
            )
        } else {
            mapOf("loaded" to false)
        }
    }
}

/**
 * Parameters for text generation.
 */
data class GenerationParams(
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f
) {
    companion object {
        /** Creative settings for storytelling */
        val CREATIVE = GenerationParams(
            maxTokens = 1024,
            temperature = 0.9f,
            topP = 0.95f,
            topK = 50,
            repeatPenalty = 1.05f
        )

        /** Precise settings for factual responses */
        val PRECISE = GenerationParams(
            maxTokens = 512,
            temperature = 0.3f,
            topP = 0.85f,
            topK = 20,
            repeatPenalty = 1.15f
        )

        /** Balanced default settings */
        val BALANCED = GenerationParams()

        /** Fast settings for quick responses */
        val FAST = GenerationParams(
            maxTokens = 256,
            temperature = 0.5f,
            topP = 0.9f,
            topK = 30,
            repeatPenalty = 1.1f
        )
    }
}
