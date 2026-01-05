# Nano.Ai - Offline LLM with RAG for Android

A complete Android application that runs GGUF-based LLM models locally with RAG (Retrieval Augmented Generation) support. Fully offline capable after initial model download.

## Features

- **Local LLM Inference**: Run quantized GGUF models entirely on-device using llama.cpp
- **Model Management**: Install, delete, switch between multiple models
- **RAG Pipeline**: Web scraping, document chunking, vector embeddings, semantic search
- **Offline First**: Works completely offline once model and data are loaded
- **Low Memory Optimized**: Designed for mobile devices with limited RAM
- **Open Source**: Uses only open-source components

## Requirements

### Development Environment
- Android Studio Hedgehog (2023.1.1) or newer
- Android NDK r25c or newer
- CMake 3.22.1+
- JDK 17
- Kotlin 1.9.20+

### Target Device
- Android 7.0+ (API 24)
- ARM64-v8a or ARMv7a processor
- Minimum 4GB RAM (8GB recommended for larger models)
- 2-10GB storage for models

## Project Structure

```
Nano.Ai/
├── app/
│   ├── src/main/
│   │   ├── java/com/nanoai/llm/
│   │   │   ├── MainActivity.kt           # Main chat UI
│   │   │   ├── ModelManagerActivity.kt   # Model management UI
│   │   │   ├── RagSettingsActivity.kt    # RAG configuration UI
│   │   │   ├── LlamaBridge.kt            # Kotlin wrapper for JNI
│   │   │   ├── NanoAiApplication.kt      # Application class
│   │   │   ├── model/
│   │   │   │   └── ModelManager.kt       # Model lifecycle management
│   │   │   ├── rag/
│   │   │   │   ├── RagManager.kt         # RAG orchestration
│   │   │   │   └── WebScraper.kt         # Web page scraping
│   │   │   ├── vector/
│   │   │   │   └── VectorStore.kt        # Vector database
│   │   │   └── ui/
│   │   │       └── ChatAdapter.kt        # Chat RecyclerView adapter
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt            # Native build config
│   │   │   ├── llama_jni.cpp             # JNI bridge
│   │   │   └── llama.cpp/                # llama.cpp submodule
│   │   ├── res/                          # Android resources
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts                  # App build config
│   └── proguard-rules.pro
├── build.gradle.kts                      # Project build config
├── settings.gradle.kts
└── README.md
```

## Build Instructions

### Step 1: Clone and Setup

```bash
# Clone the repository
git clone https://github.com/mithun50/NanoAi.git
cd NanoAi

# Initialize llama.cpp submodule
git submodule add https://github.com/ggerganov/llama.cpp.git app/src/main/cpp/llama.cpp
git submodule update --init --recursive
```

### Step 2: Configure Android Studio

1. Open Android Studio
2. File → Open → Select Nano.Ai folder
3. Wait for Gradle sync to complete
4. Ensure NDK is installed: Tools → SDK Manager → SDK Tools → NDK

### Step 3: Build the APK

**Using Android Studio:**
1. Build → Build Bundle(s) / APK(s) → Build APK(s)
2. APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

**Using Command Line:**
```bash
# Debug build
./gradlew assembleDebug

# Release build (requires signing config)
./gradlew assembleRelease

# Build split APKs by ABI (smaller size)
./gradlew assembleDebug
# Produces: app-arm64-v8a-debug.apk, app-armeabi-v7a-debug.apk
```

### Step 4: Install on Device

```bash
# Install via ADB
adb install app/build/outputs/apk/debug/app-debug.apk

# Or install specific ABI variant
adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

## Model Preparation

### Recommended Models for Mobile

| Model | Size | RAM Needed | Quality |
|-------|------|------------|---------|
| TinyLlama 1.1B Q4_K_M | ~700MB | 2GB | Basic |
| Phi-2 Q4_K_M | ~1.6GB | 3GB | Good |
| Gemma 2B Q4_K_M | ~1.5GB | 3GB | Good |
| Llama 3.2 3B Q4_K_M | ~2GB | 4GB | Very Good |
| Mistral 7B Q4_K_S | ~4GB | 6GB | Excellent |
| Qwen 2.5 7B Q4_K_M | ~4.5GB | 6GB | Excellent |

### Download Pre-Quantized Models

```bash
# Example: Download from Hugging Face
# TinyLlama (small, fast)
wget https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf

# Phi-2 (good balance)
wget https://huggingface.co/TheBloke/phi-2-GGUF/resolve/main/phi-2.Q4_K_M.gguf

# Qwen 2.5 3B (recommended for mobile)
wget https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf
```

### Convert Your Own Models (HuggingFace → GGUF)

```bash
# 1. Install llama.cpp tools
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp
pip install -r requirements.txt

# 2. Convert HuggingFace model to GGUF
python convert_hf_to_gguf.py /path/to/model --outfile model.gguf

# 3. Quantize to reduce size
./llama-quantize model.gguf model-Q4_K_M.gguf Q4_K_M
```

### Quantization Options

| Quant | Size Reduction | Quality | Recommended For |
|-------|---------------|---------|-----------------|
| Q2_K | ~85% smaller | Low | Very limited RAM |
| Q3_K_S | ~80% smaller | Low-Medium | Limited RAM |
| Q4_0 | ~75% smaller | Medium | Good balance |
| Q4_K_M | ~70% smaller | Medium-High | **Recommended** |
| Q5_K_M | ~65% smaller | High | More RAM available |
| Q6_K | ~55% smaller | Very High | Desktop-like quality |
| Q8_0 | ~50% smaller | Near-Original | Maximum quality |

## Using the App

### Load a Model

1. Copy GGUF file to device storage
2. Open Nano.Ai → Menu → Models
3. Tap "+" → Import from storage
4. Select your GGUF file
5. Tap "Activate" on the model

### Enable RAG

1. Open Nano.Ai → Tap RAG icon (left of input)
2. Enable RAG toggle
3. Tap "Add Source" → Enter URL
4. Wait for indexing to complete
5. Ask questions - context will be retrieved automatically

### Tips for Best Performance

- Use Q4_K_M quantization for best size/quality balance
- Set context size to 2048 for most models (saves RAM)
- Use 4-6 CPU threads (not all cores)
- Close other apps to free RAM
- Smaller models = faster responses

## API Reference

### LlamaBridge (Kotlin)

```kotlin
// Load model
val result = LlamaBridge.loadModelAsync(
    modelPath = "/path/to/model.gguf",
    contextSize = 2048,
    threads = 4
)

// Generate text
val response = LlamaBridge.generateAsync(
    prompt = "Hello, how are you?",
    params = GenerationParams(
        maxTokens = 512,
        temperature = 0.7f,
        topP = 0.9f
    )
)

// Get embeddings (for RAG)
val embedding = LlamaBridge.getEmbeddingAsync("text to embed")

// Stop generation
LlamaBridge.stopGeneration()

// Check status
val isLoaded = LlamaBridge.isModelLoaded()
val isGenerating = LlamaBridge.isGenerating()
```

### RAG Manager

```kotlin
// Index a URL
ragManager.indexUrl("https://example.com/article")

// Search for context
val results = ragManager.retrieve("what is the topic about?")

// Generate with RAG
val response = ragManager.generateWithRag(
    userQuery = "Summarize the main points",
    params = GenerationParams.BALANCED
)
```

## APK Size Considerations

| Component | Size |
|-----------|------|
| Base APK | ~5MB |
| Native libs (ARM64) | ~15MB |
| Native libs (ARMv7) | ~12MB |
| Total (single ABI) | ~20MB |
| Total (universal) | ~35MB |

**Play Store Limits:**
- APK: 150MB max
- AAB: 150MB + 150MB per ABI
- Recommendation: Use split APKs by ABI

## Troubleshooting

### Out of Memory
- Use smaller model (Q4 or Q3 quantization)
- Reduce context size to 1024
- Close other apps
- Try on device with more RAM

### Model Won't Load
- Verify file is valid GGUF format
- Check file isn't corrupted (compare hash)
- Ensure sufficient storage space
- Check logcat for specific errors

### Slow Generation
- Use smaller model
- Reduce max tokens
- Use fewer CPU threads (4 is often optimal)
- Check for thermal throttling

### Build Errors
```bash
# Clean and rebuild
./gradlew clean
./gradlew assembleDebug

# Check NDK version
cat local.properties | grep ndk

# Verify llama.cpp submodule
git submodule update --init --recursive
```

## License

This project uses the following open-source components:

- **llama.cpp**: MIT License
- **Jsoup**: MIT License
- **Kotlin Coroutines**: Apache 2.0
- **AndroidX**: Apache 2.0
- **Material Components**: Apache 2.0

## Contributing

1. Fork the repository
2. Create feature branch
3. Commit changes
4. Push to branch
5. Create Pull Request

## Acknowledgments

- [ggerganov/llama.cpp](https://github.com/ggerganov/llama.cpp) - Core inference engine
- [TheBloke](https://huggingface.co/TheBloke) - Pre-quantized models
- Android NDK team - Native development support
