package com.nanoai.llm.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nanoai.llm.LlamaBridge
import com.nanoai.llm.nanoAiApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * ModelManager - Handles model lifecycle, installation, and switching.
 */
class ModelManager(private val context: Context) {
    companion object {
        private const val TAG = "ModelManager"
        private const val PREFS_NAME = "nanoai_models"
        private const val KEY_MODELS = "installed_models"
        private const val KEY_ACTIVE_MODEL = "active_model"
        private const val KEY_LAST_CONTEXT_SIZE = "last_context_size"
        private const val KEY_LAST_THREADS = "last_threads"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val modelsDir: File = context.nanoAiApp.modelsDir

    // State flows
    private val _installedModels = MutableStateFlow<List<ModelInfo>>(emptyList())
    val installedModels: StateFlow<List<ModelInfo>> = _installedModels.asStateFlow()

    private val _activeModel = MutableStateFlow<ModelInfo?>(null)
    val activeModel: StateFlow<ModelInfo?> = _activeModel.asStateFlow()

    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Idle)
    val loadingState: StateFlow<LoadingState> = _loadingState.asStateFlow()

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    init {
        loadModelsFromStorage()
    }

    /**
     * Scan models directory and load metadata.
     */
    private fun loadModelsFromStorage() {
        val savedModels = loadSavedModelList()
        val files = modelsDir.listFiles { file ->
            file.isFile && file.extension.lowercase() == "gguf"
        } ?: emptyArray()

        val models = files.map { file ->
            savedModels.find { it.fileName == file.name }
                ?: createModelInfoFromFile(file)
        }

        _installedModels.value = models.sortedBy { it.name }
        saveModelList(models)

        // Restore active model reference
        val activeId = prefs.getString(KEY_ACTIVE_MODEL, null)
        _activeModel.value = models.find { it.id == activeId }

        Log.i(TAG, "Loaded ${models.size} models from storage")
    }

    /**
     * Create ModelInfo from a GGUF file.
     */
    private fun createModelInfoFromFile(file: File): ModelInfo {
        val name = file.nameWithoutExtension
            .replace("_", " ")
            .replace("-", " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        // Try to detect quantization from filename
        val quantization = detectQuantization(file.name)

        return ModelInfo(
            id = file.name.hashCode().toString(),
            name = name,
            fileName = file.name,
            filePath = file.absolutePath,
            sizeBytes = file.length(),
            quantization = quantization,
            addedTimestamp = file.lastModified(),
            source = ModelSource.LOCAL
        )
    }

    /**
     * Detect quantization type from filename.
     */
    private fun detectQuantization(fileName: String): String {
        val lowerName = fileName.lowercase()
        return when {
            "q2_k" in lowerName -> "Q2_K"
            "q3_k_s" in lowerName -> "Q3_K_S"
            "q3_k_m" in lowerName -> "Q3_K_M"
            "q3_k_l" in lowerName -> "Q3_K_L"
            "q4_0" in lowerName -> "Q4_0"
            "q4_1" in lowerName -> "Q4_1"
            "q4_k_s" in lowerName -> "Q4_K_S"
            "q4_k_m" in lowerName -> "Q4_K_M"
            "q5_0" in lowerName -> "Q5_0"
            "q5_1" in lowerName -> "Q5_1"
            "q5_k_s" in lowerName -> "Q5_K_S"
            "q5_k_m" in lowerName -> "Q5_K_M"
            "q6_k" in lowerName -> "Q6_K"
            "q8_0" in lowerName -> "Q8_0"
            "f16" in lowerName -> "F16"
            "f32" in lowerName -> "F32"
            else -> "Unknown"
        }
    }

    /**
     * Import a model from external storage.
     */
    suspend fun importModel(sourceUri: android.net.Uri): Result<ModelInfo> =
        withContext(Dispatchers.IO) {
            try {
                _loadingState.value = LoadingState.Importing

                val resolver = context.contentResolver
                val fileName = getFileName(sourceUri) ?: "imported_model.gguf"

                // Ensure .gguf extension
                val finalFileName = if (!fileName.endsWith(".gguf", true)) {
                    "$fileName.gguf"
                } else {
                    fileName
                }

                val destFile = File(modelsDir, finalFileName)
                if (destFile.exists()) {
                    return@withContext Result.failure(
                        IllegalStateException("Model already exists: $finalFileName")
                    )
                }

                // Copy file
                resolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                } ?: return@withContext Result.failure(
                    IllegalStateException("Cannot open source file")
                )

                // Create model info
                val modelInfo = createModelInfoFromFile(destFile)

                // Update list
                val currentModels = _installedModels.value.toMutableList()
                currentModels.add(modelInfo)
                _installedModels.value = currentModels.sortedBy { it.name }
                saveModelList(currentModels)

                _loadingState.value = LoadingState.Idle
                Log.i(TAG, "Imported model: ${modelInfo.name}")
                Result.success(modelInfo)

            } catch (e: Exception) {
                _loadingState.value = LoadingState.Error(e.message ?: "Import failed")
                Log.e(TAG, "Import failed", e)
                Result.failure(e)
            }
        }

    /**
     * Download a model from URL.
     */
    suspend fun downloadModel(
        url: String,
        fileName: String,
        name: String? = null
    ): Result<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            _loadingState.value = LoadingState.Downloading

            val finalFileName = if (!fileName.endsWith(".gguf", true)) {
                "$fileName.gguf"
            } else {
                fileName
            }

            val destFile = File(modelsDir, finalFileName)
            if (destFile.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Model already exists: $finalFileName")
                )
            }

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.connect()

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        _downloadProgress.value = DownloadProgress(
                            fileName = finalFileName,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            percent = if (totalBytes > 0) {
                                (downloadedBytes * 100 / totalBytes).toInt()
                            } else 0
                        )
                    }
                }
            }

            // Create model info
            val modelInfo = createModelInfoFromFile(destFile).copy(
                name = name ?: destFile.nameWithoutExtension,
                source = ModelSource.DOWNLOADED,
                downloadUrl = url
            )

            // Update list
            val currentModels = _installedModels.value.toMutableList()
            currentModels.add(modelInfo)
            _installedModels.value = currentModels.sortedBy { it.name }
            saveModelList(currentModels)

            _loadingState.value = LoadingState.Idle
            _downloadProgress.value = null
            Log.i(TAG, "Downloaded model: ${modelInfo.name}")
            Result.success(modelInfo)

        } catch (e: Exception) {
            _loadingState.value = LoadingState.Error(e.message ?: "Download failed")
            _downloadProgress.value = null
            Log.e(TAG, "Download failed", e)
            Result.failure(e)
        }
    }

    /**
     * Activate (load) a model for inference.
     */
    suspend fun activateModel(
        modelInfo: ModelInfo,
        contextSize: Int = 2048,
        threads: Int = LlamaBridge.getOptimalThreadCount()
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _loadingState.value = LoadingState.Loading(modelInfo.name)

            // Unload current model if any
            if (LlamaBridge.isModelLoaded()) {
                LlamaBridge.unloadModelAsync()
            }

            // Load new model
            val result = LlamaBridge.loadModelAsync(
                modelPath = modelInfo.filePath,
                contextSize = contextSize,
                threads = threads
            )

            result.onSuccess {
                _activeModel.value = modelInfo
                prefs.edit()
                    .putString(KEY_ACTIVE_MODEL, modelInfo.id)
                    .putInt(KEY_LAST_CONTEXT_SIZE, contextSize)
                    .putInt(KEY_LAST_THREADS, threads)
                    .apply()

                _loadingState.value = LoadingState.Loaded(modelInfo.name)
                Log.i(TAG, "Activated model: ${modelInfo.name}")
            }.onFailure { e ->
                _loadingState.value = LoadingState.Error(e.message ?: "Load failed")
            }

            result

        } catch (e: Exception) {
            _loadingState.value = LoadingState.Error(e.message ?: "Activation failed")
            Log.e(TAG, "Activation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Deactivate (unload) the current model.
     */
    suspend fun deactivateModel() {
        withContext(Dispatchers.IO) {
            if (LlamaBridge.isModelLoaded()) {
                LlamaBridge.unloadModelAsync()
            }
            _activeModel.value = null
            _loadingState.value = LoadingState.Idle
            prefs.edit().remove(KEY_ACTIVE_MODEL).apply()
            Log.i(TAG, "Model deactivated")
        }
    }

    /**
     * Delete a model from storage.
     */
    suspend fun deleteModel(modelInfo: ModelInfo): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                // Unload if active
                if (_activeModel.value?.id == modelInfo.id) {
                    deactivateModel()
                }

                // Delete file
                val file = File(modelInfo.filePath)
                if (file.exists()) {
                    if (!file.delete()) {
                        return@withContext Result.failure(
                            RuntimeException("Failed to delete file")
                        )
                    }
                }

                // Update list
                val currentModels = _installedModels.value.toMutableList()
                currentModels.removeAll { it.id == modelInfo.id }
                _installedModels.value = currentModels
                saveModelList(currentModels)

                Log.i(TAG, "Deleted model: ${modelInfo.name}")
                Result.success(Unit)

            } catch (e: Exception) {
                Log.e(TAG, "Delete failed", e)
                Result.failure(e)
            }
        }

    /**
     * Rename a model.
     */
    fun renameModel(modelInfo: ModelInfo, newName: String): Result<ModelInfo> {
        return try {
            val updatedModel = modelInfo.copy(name = newName)
            val currentModels = _installedModels.value.toMutableList()
            val index = currentModels.indexOfFirst { it.id == modelInfo.id }

            if (index >= 0) {
                currentModels[index] = updatedModel
                _installedModels.value = currentModels.sortedBy { it.name }
                saveModelList(currentModels)

                if (_activeModel.value?.id == modelInfo.id) {
                    _activeModel.value = updatedModel
                }
            }

            Result.success(updatedModel)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Load the last active model on app start.
     */
    suspend fun loadLastActiveModel(): Result<Unit> {
        val activeId = prefs.getString(KEY_ACTIVE_MODEL, null) ?: return Result.success(Unit)
        val model = _installedModels.value.find { it.id == activeId }
            ?: return Result.success(Unit)

        val contextSize = prefs.getInt(KEY_LAST_CONTEXT_SIZE, 2048)
        val threads = prefs.getInt(KEY_LAST_THREADS, LlamaBridge.getOptimalThreadCount())

        return activateModel(model, contextSize, threads)
    }

    /**
     * Refresh models list from disk.
     */
    fun refreshModels() {
        loadModelsFromStorage()
    }

    /**
     * Get model by ID.
     */
    fun getModelById(id: String): ModelInfo? {
        return _installedModels.value.find { it.id == id }
    }

    /**
     * Check if a model file exists.
     */
    fun modelExists(fileName: String): Boolean {
        return File(modelsDir, fileName).exists()
    }

    // Persistence helpers
    private fun loadSavedModelList(): List<ModelInfo> {
        return try {
            val json = prefs.getString(KEY_MODELS, null) ?: return emptyList()
            val type = object : TypeToken<List<ModelInfo>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model list", e)
            emptyList()
        }
    }

    private fun saveModelList(models: List<ModelInfo>) {
        try {
            val json = gson.toJson(models)
            prefs.edit().putString(KEY_MODELS, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save model list", e)
        }
    }

    private fun getFileName(uri: android.net.Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                cursor.getString(nameIndex)
            } else null
        }
    }
}

/**
 * Model information data class.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val quantization: String = "Unknown",
    val addedTimestamp: Long = System.currentTimeMillis(),
    val source: ModelSource = ModelSource.LOCAL,
    val downloadUrl: String? = null,
    val description: String? = null
) {
    val sizeMB: Double
        get() = sizeBytes / (1024.0 * 1024.0)

    val sizeFormatted: String
        get() = when {
            sizeBytes >= 1024 * 1024 * 1024 -> "%.2f GB".format(sizeBytes / (1024.0 * 1024.0 * 1024.0))
            sizeBytes >= 1024 * 1024 -> "%.1f MB".format(sizeMB)
            else -> "%.1f KB".format(sizeBytes / 1024.0)
        }
}

/**
 * Model source type.
 */
enum class ModelSource {
    LOCAL,      // Imported from local storage
    DOWNLOADED, // Downloaded from URL
    BUNDLED     // Bundled with app (if any)
}

/**
 * Model loading state.
 */
sealed class LoadingState {
    object Idle : LoadingState()
    data class Loading(val modelName: String) : LoadingState()
    data class Loaded(val modelName: String) : LoadingState()
    object Importing : LoadingState()
    object Downloading : LoadingState()
    data class Error(val message: String) : LoadingState()
}

/**
 * Download progress data.
 */
data class DownloadProgress(
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val percent: Int
) {
    val downloadedMB: Double
        get() = downloadedBytes / (1024.0 * 1024.0)

    val totalMB: Double
        get() = totalBytes / (1024.0 * 1024.0)
}
