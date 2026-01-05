/**
 * NanoAi - JNI Bridge for llama.cpp
 *
 * This file provides the JNI interface between Kotlin/Java and llama.cpp.
 * It handles model loading, text generation, and resource management.
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include <memory>
#include <cstring>
#include <cmath>
#include <unistd.h>
#include <sys/mman.h>

// Llama.cpp headers
#include "llama.h"

// Logging macros
#define LOG_TAG "NanoAi-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Global state
static std::mutex g_mutex;
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static std::atomic<bool> g_is_generating{false};
static std::atomic<bool> g_stop_requested{false};

// Generation parameters
struct GenerationParams {
    int max_tokens = 512;
    float temperature = 0.7f;
    float top_p = 0.9f;
    int top_k = 40;
    float repeat_penalty = 1.1f;
    int n_threads = 4;
    int n_ctx = 2048;
};
static GenerationParams g_params;

// Embedding cache for RAG
struct EmbeddingResult {
    std::vector<float> embedding;
    bool success;
};

// Helper: Convert Java string to C++ string
static std::string jstring_to_string(JNIEnv* env, jstring jstr) {
    if (jstr == nullptr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

// Helper: Convert C++ string to Java string
static jstring string_to_jstring(JNIEnv* env, const std::string& str) {
    return env->NewStringUTF(str.c_str());
}

// Helper: Get available memory
static size_t get_available_memory() {
    FILE* meminfo = fopen("/proc/meminfo", "r");
    if (!meminfo) return 0;

    char line[256];
    size_t available = 0;
    while (fgets(line, sizeof(line), meminfo)) {
        if (strncmp(line, "MemAvailable:", 13) == 0) {
            sscanf(line, "MemAvailable: %zu", &available);
            available *= 1024; // Convert KB to bytes
            break;
        }
    }
    fclose(meminfo);
    return available;
}

// Helper: Tokenize text
static std::vector<llama_token> tokenize(const std::string& text, bool add_bos) {
    if (!g_model) return {};

    int n_tokens = text.length() + (add_bos ? 1 : 0);
    std::vector<llama_token> tokens(n_tokens);

    n_tokens = llama_tokenize(g_model, text.c_str(), text.length(),
                              tokens.data(), tokens.size(), add_bos, false);

    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(g_model, text.c_str(), text.length(),
                                  tokens.data(), tokens.size(), add_bos, false);
    }

    tokens.resize(n_tokens);
    return tokens;
}

// Helper: Detokenize
static std::string detokenize(const std::vector<llama_token>& tokens) {
    if (!g_model) return "";

    std::string result;
    for (llama_token token : tokens) {
        char buf[256];
        int n = llama_token_to_piece(g_model, token, buf, sizeof(buf), false);
        if (n > 0) {
            result.append(buf, n);
        }
    }
    return result;
}

extern "C" {

// ============================================================================
// Model Management
// ============================================================================

JNIEXPORT jboolean JNICALL
Java_com_nanoai_llm_LlamaBridge_loadModel(
    JNIEnv* env,
    jobject /* this */,
    jstring modelPath,
    jint nCtx,
    jint nThreads
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    // Unload existing model first
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_free_model(g_model);
        g_model = nullptr;
    }

    std::string path = jstring_to_string(env, modelPath);
    LOGI("Loading model from: %s", path.c_str());

    // Check available memory
    size_t available = get_available_memory();
    LOGI("Available memory: %zu MB", available / (1024 * 1024));

    // Initialize llama backend (only once)
    static bool backend_initialized = false;
    if (!backend_initialized) {
        llama_backend_init();
        backend_initialized = true;
    }

    // Model parameters
    llama_model_params model_params = llama_model_default_params();
    model_params.use_mmap = true;  // Memory-mapped for efficiency
    model_params.use_mlock = false; // Don't lock in RAM (save memory)

    // Load model
    g_model = llama_load_model_from_file(path.c_str(), model_params);
    if (!g_model) {
        LOGE("Failed to load model from: %s", path.c_str());
        return JNI_FALSE;
    }

    // Context parameters
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx > 0 ? nCtx : g_params.n_ctx;
    ctx_params.n_threads = nThreads > 0 ? nThreads : g_params.n_threads;
    ctx_params.n_threads_batch = ctx_params.n_threads;
    ctx_params.seed = (uint32_t)time(nullptr);

    // Create context
    g_ctx = llama_new_context_with_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_free_model(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    // Update params
    g_params.n_ctx = ctx_params.n_ctx;
    g_params.n_threads = ctx_params.n_threads;

    LOGI("Model loaded successfully. Context size: %d, Threads: %d",
         ctx_params.n_ctx, ctx_params.n_threads);

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_nanoai_llm_LlamaBridge_unloadModel(
    JNIEnv* env,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    LOGI("Unloading model");

    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_free_model(g_model);
        g_model = nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_nanoai_llm_LlamaBridge_isModelLoaded(
    JNIEnv* env,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_model != nullptr && g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}

// ============================================================================
// Text Generation
// ============================================================================

JNIEXPORT jstring JNICALL
Java_com_nanoai_llm_LlamaBridge_generate(
    JNIEnv* env,
    jobject /* this */,
    jstring prompt,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jfloat repeatPenalty
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_ctx) {
        LOGE("Model not loaded");
        return string_to_jstring(env, "[Error: Model not loaded]");
    }

    g_is_generating = true;
    g_stop_requested = false;

    std::string prompt_str = jstring_to_string(env, prompt);
    LOGD("Generating with prompt length: %zu", prompt_str.length());

    // Tokenize prompt
    std::vector<llama_token> tokens = tokenize(prompt_str, true);
    if (tokens.empty()) {
        g_is_generating = false;
        return string_to_jstring(env, "[Error: Failed to tokenize]");
    }

    int n_ctx = llama_n_ctx(g_ctx);
    if ((int)tokens.size() > n_ctx - 4) {
        g_is_generating = false;
        LOGW("Prompt too long: %zu tokens > context %d", tokens.size(), n_ctx);
        return string_to_jstring(env, "[Error: Prompt too long]");
    }

    // Clear KV cache
    llama_kv_cache_clear(g_ctx);

    // Process prompt in batch
    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    llama_seq_id seq_id = 0;
    for (size_t i = 0; i < tokens.size(); i++) {
        batch.token[batch.n_tokens] = tokens[i];
        batch.pos[batch.n_tokens] = i;
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id[batch.n_tokens] = &seq_id;
        batch.logits[batch.n_tokens] = (i == tokens.size() - 1);
        batch.n_tokens++;
    }

    if (llama_decode(g_ctx, batch) != 0) {
        llama_batch_free(batch);
        g_is_generating = false;
        LOGE("Failed to decode prompt");
        return string_to_jstring(env, "[Error: Decode failed]");
    }

    llama_batch_free(batch);

    // Generation parameters
    int max_gen = maxTokens > 0 ? maxTokens : g_params.max_tokens;
    float temp = temperature >= 0 ? temperature : g_params.temperature;
    float top_p_val = topP > 0 ? topP : g_params.top_p;
    int top_k_val = topK > 0 ? topK : g_params.top_k;
    float rep_pen = repeatPenalty > 0 ? repeatPenalty : g_params.repeat_penalty;

    // Generate tokens
    std::vector<llama_token> generated;
    int n_cur = tokens.size();
    int n_vocab = llama_n_vocab(g_model);

    for (int i = 0; i < max_gen && !g_stop_requested; i++) {
        // Get logits
        float* logits = llama_get_logits(g_ctx);

        // Create candidates
        std::vector<llama_token_data> candidates;
        candidates.reserve(n_vocab);
        for (llama_token token_id = 0; token_id < n_vocab; token_id++) {
            candidates.emplace_back(llama_token_data{token_id, logits[token_id], 0.0f});
        }
        llama_token_data_array candidates_p = {candidates.data(), candidates.size(), false};

        // Apply sampling
        llama_sample_top_k(g_ctx, &candidates_p, top_k_val, 1);
        llama_sample_top_p(g_ctx, &candidates_p, top_p_val, 1);
        llama_sample_temp(g_ctx, &candidates_p, temp);
        llama_token new_token = llama_sample_token(g_ctx, &candidates_p);

        // Check for EOS
        if (new_token == llama_token_eos(g_model)) {
            LOGD("EOS token reached at position %d", i);
            break;
        }

        generated.push_back(new_token);

        // Prepare next batch
        llama_batch next_batch = llama_batch_init(1, 0, 1);
        next_batch.token[0] = new_token;
        next_batch.pos[0] = n_cur;
        next_batch.n_seq_id[0] = 1;
        next_batch.seq_id[0] = &seq_id;
        next_batch.logits[0] = true;
        next_batch.n_tokens = 1;

        if (llama_decode(g_ctx, next_batch) != 0) {
            llama_batch_free(next_batch);
            LOGE("Decode failed at token %d", i);
            break;
        }

        llama_batch_free(next_batch);
        n_cur++;
    }
    g_is_generating = false;

    // Convert tokens to text
    std::string result = detokenize(generated);
    LOGD("Generated %zu tokens: %s", generated.size(),
         result.substr(0, 50).c_str());

    return string_to_jstring(env, result);
}

JNIEXPORT void JNICALL
Java_com_nanoai_llm_LlamaBridge_stopGeneration(
    JNIEnv* env,
    jobject /* this */
) {
    LOGI("Stop generation requested");
    g_stop_requested = true;
}

JNIEXPORT jboolean JNICALL
Java_com_nanoai_llm_LlamaBridge_isGenerating(
    JNIEnv* env,
    jobject /* this */
) {
    return g_is_generating ? JNI_TRUE : JNI_FALSE;
}

// ============================================================================
// Embeddings (for RAG)
// ============================================================================

JNIEXPORT jfloatArray JNICALL
Java_com_nanoai_llm_LlamaBridge_getEmbedding(
    JNIEnv* env,
    jobject /* this */,
    jstring text
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_ctx) {
        LOGE("Model not loaded for embedding");
        return nullptr;
    }

    std::string text_str = jstring_to_string(env, text);

    // Tokenize
    std::vector<llama_token> tokens = tokenize(text_str, true);
    if (tokens.empty()) {
        LOGE("Failed to tokenize for embedding");
        return nullptr;
    }

    // Clear cache and process
    llama_kv_cache_clear(g_ctx);

    llama_batch batch = llama_batch_init(tokens.size(), 0, 1);
    llama_seq_id seq_id = 0;
    for (size_t i = 0; i < tokens.size(); i++) {
        batch.token[batch.n_tokens] = tokens[i];
        batch.pos[batch.n_tokens] = i;
        batch.n_seq_id[batch.n_tokens] = 1;
        batch.seq_id[batch.n_tokens] = &seq_id;
        batch.logits[batch.n_tokens] = (i == tokens.size() - 1);
        batch.n_tokens++;
    }

    if (llama_decode(g_ctx, batch) != 0) {
        llama_batch_free(batch);
        LOGE("Failed to decode for embedding");
        return nullptr;
    }

    llama_batch_free(batch);

    // Get embedding dimension
    int n_embd = llama_n_embd(g_model);
    if (n_embd <= 0) {
        LOGW("Model doesn't support embeddings, using logits mean");
        return nullptr;
    }

    // Get embeddings from last token
    float* embd = llama_get_embeddings(g_ctx);
    if (!embd) {
        LOGE("Failed to get embeddings");
        return nullptr;
    }

    // Normalize embedding
    float norm = 0.0f;
    for (int i = 0; i < n_embd; i++) {
        norm += embd[i] * embd[i];
    }
    norm = sqrtf(norm);

    // Create Java float array
    jfloatArray result = env->NewFloatArray(n_embd);
    if (result == nullptr) {
        return nullptr;
    }

    // Copy normalized embedding
    std::vector<float> normalized(n_embd);
    for (int i = 0; i < n_embd; i++) {
        normalized[i] = embd[i] / norm;
    }
    env->SetFloatArrayRegion(result, 0, n_embd, normalized.data());

    return result;
}

// ============================================================================
// Configuration
// ============================================================================

JNIEXPORT void JNICALL
Java_com_nanoai_llm_LlamaBridge_setThreads(
    JNIEnv* env,
    jobject /* this */,
    jint nThreads
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (nThreads > 0) {
        g_params.n_threads = nThreads;
        if (g_ctx) {
            llama_set_n_threads(g_ctx, nThreads, nThreads);
        }
        LOGI("Threads set to: %d", nThreads);
    }
}

JNIEXPORT void JNICALL
Java_com_nanoai_llm_LlamaBridge_setDefaultParams(
    JNIEnv* env,
    jobject /* this */,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jfloat repeatPenalty
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (maxTokens > 0) g_params.max_tokens = maxTokens;
    if (temperature >= 0) g_params.temperature = temperature;
    if (topP > 0) g_params.top_p = topP;
    if (topK > 0) g_params.top_k = topK;
    if (repeatPenalty > 0) g_params.repeat_penalty = repeatPenalty;

    LOGI("Default params updated: max_tokens=%d, temp=%.2f, top_p=%.2f, top_k=%d, rep_pen=%.2f",
         g_params.max_tokens, g_params.temperature, g_params.top_p,
         g_params.top_k, g_params.repeat_penalty);
}

// ============================================================================
// Model Info
// ============================================================================

JNIEXPORT jint JNICALL
Java_com_nanoai_llm_LlamaBridge_getContextSize(
    JNIEnv* env,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_ctx ? llama_n_ctx(g_ctx) : 0;
}

JNIEXPORT jint JNICALL
Java_com_nanoai_llm_LlamaBridge_getVocabSize(
    JNIEnv* env,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_model ? llama_n_vocab(g_model) : 0;
}

JNIEXPORT jint JNICALL
Java_com_nanoai_llm_LlamaBridge_getEmbeddingSize(
    JNIEnv* env,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_model ? llama_n_embd(g_model) : 0;
}

JNIEXPORT jstring JNICALL
Java_com_nanoai_llm_LlamaBridge_getModelDescription(
    JNIEnv* env,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model) {
        return string_to_jstring(env, "No model loaded");
    }

    char desc[256];
    llama_model_desc(g_model, desc, sizeof(desc));
    return string_to_jstring(env, desc);
}

// ============================================================================
// Memory Management
// ============================================================================

JNIEXPORT jlong JNICALL
Java_com_nanoai_llm_LlamaBridge_getAvailableMemory(
    JNIEnv* env,
    jobject /* this */
) {
    return (jlong)get_available_memory();
}

JNIEXPORT void JNICALL
Java_com_nanoai_llm_LlamaBridge_freeBackend(
    JNIEnv* env,
    jobject /* this */
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_free_model(g_model);
        g_model = nullptr;
    }

    llama_backend_free();
    LOGI("Backend freed");
}

// ============================================================================
// Tokenization
// ============================================================================

JNIEXPORT jint JNICALL
Java_com_nanoai_llm_LlamaBridge_tokenize(
    JNIEnv* env,
    jobject /* this */,
    jstring text,
    jintArray outputTokens,
    jboolean addBos
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model) {
        return -1;
    }

    std::string text_str = jstring_to_string(env, text);
    std::vector<llama_token> tokens = tokenize(text_str, addBos);

    int output_len = env->GetArrayLength(outputTokens);
    int copy_len = std::min(output_len, (int)tokens.size());

    env->SetIntArrayRegion(outputTokens, 0, copy_len,
                           reinterpret_cast<jint*>(tokens.data()));

    return (jint)tokens.size();
}

JNIEXPORT jstring JNICALL
Java_com_nanoai_llm_LlamaBridge_detokenize(
    JNIEnv* env,
    jobject /* this */,
    jintArray tokens
) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model) {
        return string_to_jstring(env, "");
    }

    int len = env->GetArrayLength(tokens);
    std::vector<llama_token> token_vec(len);
    env->GetIntArrayRegion(tokens, 0, len, reinterpret_cast<jint*>(token_vec.data()));

    std::string result = detokenize(token_vec);
    return string_to_jstring(env, result);
}

} // extern "C"
