package com.nanoai.llm.model

/**
 * CatalogModel - Represents a model available for download from the catalog.
 */
data class CatalogModel(
    val id: String,
    val name: String,
    val description: String,
    val sizeMB: Int,
    val ramRequired: String,
    val quantization: String,
    val downloadUrl: String,
    val fileName: String
)

/**
 * Pre-defined model catalog with popular small models optimized for mobile.
 */
object ModelCatalog {

    val models = listOf(
        // TinyLlama - Very fast, good for quick tasks
        CatalogModel(
            id = "tinyllama-1.1b-q4",
            name = "TinyLlama 1.1B",
            description = "Ultra-fast, great for simple tasks and quick responses",
            sizeMB = 637,
            ramRequired = "~1 GB",
            quantization = "Q4_K_M",
            downloadUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            fileName = "tinyllama-1.1b-q4km.gguf"
        ),

        // Phi-2 - Microsoft's compact model
        CatalogModel(
            id = "phi-2-q4",
            name = "Phi-2 2.7B",
            description = "Microsoft's efficient model, strong reasoning ability",
            sizeMB = 1600,
            ramRequired = "~2.5 GB",
            quantization = "Q4_K_M",
            downloadUrl = "https://huggingface.co/TheBloke/phi-2-GGUF/resolve/main/phi-2.Q4_K_M.gguf",
            fileName = "phi-2-q4km.gguf"
        ),

        // Qwen2 0.5B - Very small
        CatalogModel(
            id = "qwen2-0.5b-q8",
            name = "Qwen2 0.5B",
            description = "Alibaba's tiny model, extremely lightweight",
            sizeMB = 531,
            ramRequired = "~0.8 GB",
            quantization = "Q8_0",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2-0.5B-Instruct-GGUF/resolve/main/qwen2-0_5b-instruct-q8_0.gguf",
            fileName = "qwen2-0.5b-q8.gguf"
        ),

        // Gemma 2B - Google's compact model
        CatalogModel(
            id = "gemma-2b-q4",
            name = "Gemma 2B",
            description = "Google's efficient model with good quality",
            sizeMB = 1500,
            ramRequired = "~2 GB",
            quantization = "Q4_K_M",
            downloadUrl = "https://huggingface.co/google/gemma-2b-it-GGUF/resolve/main/gemma-2b-it.Q4_K_M.gguf",
            fileName = "gemma-2b-q4km.gguf"
        ),

        // StableLM 2 1.6B
        CatalogModel(
            id = "stablelm-2-1.6b-q4",
            name = "StableLM 2 1.6B",
            description = "Stability AI's compact chat model",
            sizeMB = 987,
            ramRequired = "~1.5 GB",
            quantization = "Q4_K_M",
            downloadUrl = "https://huggingface.co/stabilityai/stablelm-2-zephyr-1_6b-GGUF/resolve/main/stablelm-2-zephyr-1_6b.Q4_K_M.gguf",
            fileName = "stablelm-2-1.6b-q4km.gguf"
        ),

        // SmolLM 360M - Ultra tiny
        CatalogModel(
            id = "smollm-360m-q8",
            name = "SmolLM 360M",
            description = "HuggingFace's ultra-tiny model, very fast",
            sizeMB = 386,
            ramRequired = "~0.5 GB",
            quantization = "Q8_0",
            downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM-360M-Instruct-GGUF/resolve/main/smollm-360m-instruct-q8_0.gguf",
            fileName = "smollm-360m-q8.gguf"
        ),

        // Llama 3.2 1B - Meta's latest small model
        CatalogModel(
            id = "llama-3.2-1b-q4",
            name = "Llama 3.2 1B",
            description = "Meta's latest compact model with great quality",
            sizeMB = 750,
            ramRequired = "~1.2 GB",
            quantization = "Q4_K_M",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            fileName = "llama-3.2-1b-q4km.gguf"
        ),

        // Llama 3.2 3B - Meta's latest small model (larger)
        CatalogModel(
            id = "llama-3.2-3b-q4",
            name = "Llama 3.2 3B",
            description = "Meta's quality compact model, balanced performance",
            sizeMB = 2020,
            ramRequired = "~3 GB",
            quantization = "Q4_K_M",
            downloadUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            fileName = "llama-3.2-3b-q4km.gguf"
        )
    )

    fun getModelById(id: String): CatalogModel? = models.find { it.id == id }
}
