package com.nanoai.llm.tools

import com.nanoai.llm.GenerationParams
import com.nanoai.llm.LlamaBridge
import com.nanoai.llm.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ToolManager - Manages tool calls for the LLM.
 *
 * Enables the model to use tools like web browsing, calculations, etc.
 * Uses a simple XML-like syntax for tool calls that small models can learn.
 */
object ToolManager {
    private const val TAG = "ToolManager"

    // Tool call patterns
    private val FETCH_URL_PATTERN = Regex(
        """<fetch_url>\s*(https?://[^\s<]+)\s*</fetch_url>""",
        RegexOption.IGNORE_CASE
    )

    private val SEARCH_PATTERN = Regex(
        """<search>\s*([^<]+)\s*</search>""",
        RegexOption.IGNORE_CASE
    )

    /**
     * System prompt that teaches the model about available tools.
     */
    val toolSystemPrompt = """
You are a helpful AI assistant with access to tools.

Available tools:
1. Web Browsing: To fetch content from a URL, use: <fetch_url>https://example.com</fetch_url>

Guidelines:
- Only use tools when the user asks for current information from the web
- If the user provides a URL, fetch it to answer their question
- After using a tool, explain the information you found
- If you cannot access a URL, apologize and suggest alternatives

Example:
User: What's on the homepage of example.com?
Assistant: Let me fetch that page for you.
<fetch_url>https://example.com</fetch_url>
""".trim()

    /**
     * Process a response and execute any tool calls found.
     * Returns the final response with tool results injected.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun processToolCalls(
        response: String,
        originalPrompt: String // Reserved for future use
    ): ToolResult = withContext(Dispatchers.Default) {
        var processedResponse = response
        var toolsUsed = mutableListOf<String>()
        var toolResults = mutableListOf<String>()

        // Check for URL fetch requests
        val fetchMatches = FETCH_URL_PATTERN.findAll(response)
        for (match in fetchMatches) {
            val url = match.groupValues[1].trim()
            AppLogger.i(TAG, "Tool call detected: fetch_url($url)")
            toolsUsed.add("fetch_url")

            val fetchResult = WebFetcher.fetchUrl(url)
            fetchResult.onSuccess { webContent ->
                val toolOutput = "\n\n--- Fetched Content from ${webContent.title} ---\n${webContent.content}\n--- End ---\n"
                toolResults.add(toolOutput)

                // Replace the tool call with the result
                processedResponse = processedResponse.replace(
                    match.value,
                    "[Fetched: ${webContent.title}]"
                )
            }.onFailure { error ->
                val errorOutput = "\n[Error fetching URL: ${error.message}]\n"
                toolResults.add(errorOutput)
                processedResponse = processedResponse.replace(
                    match.value,
                    "[Failed to fetch URL: ${error.message}]"
                )
            }
        }

        ToolResult(
            originalResponse = response,
            processedResponse = processedResponse,
            toolsUsed = toolsUsed,
            toolResults = toolResults,
            hasToolCalls = toolsUsed.isNotEmpty()
        )
    }

    /**
     * Generate a response with tool support.
     * This handles the full loop of generation -> tool execution -> follow-up.
     */
    suspend fun generateWithTools(
        prompt: String,
        systemPrompt: String = toolSystemPrompt,
        params: GenerationParams = GenerationParams.BALANCED,
        maxToolIterations: Int = 2
    ): Result<ToolGenerationResult> = withContext(Dispatchers.Default) {
        try {
            val fullPrompt = buildPromptWithSystem(prompt, systemPrompt)
            var currentPrompt = fullPrompt
            var allResponses = mutableListOf<String>()
            var allToolResults = mutableListOf<String>()
            var iterations = 0

            while (iterations < maxToolIterations) {
                iterations++

                // Generate response
                val result = LlamaBridge.generateAsync(currentPrompt, params)
                if (result.isFailure) {
                    return@withContext Result.failure(result.exceptionOrNull()!!)
                }

                val response = result.getOrThrow()
                allResponses.add(response)

                // Process tool calls
                val toolResult = processToolCalls(response, prompt)

                if (!toolResult.hasToolCalls) {
                    // No more tool calls, we're done
                    break
                }

                allToolResults.addAll(toolResult.toolResults)

                // If tools were used, generate a follow-up with the results
                val followUpPrompt = buildFollowUpPrompt(
                    originalPrompt = prompt,
                    previousResponse = response,
                    toolResults = toolResult.toolResults.joinToString("\n")
                )
                currentPrompt = followUpPrompt
            }

            // Combine all responses
            val finalResponse = if (allToolResults.isEmpty()) {
                allResponses.last()
            } else {
                // Generate final summary with tool results
                val summaryPrompt = buildSummaryPrompt(
                    originalPrompt = prompt,
                    toolResults = allToolResults.joinToString("\n")
                )
                val summaryResult = LlamaBridge.generateAsync(summaryPrompt, params)
                summaryResult.getOrElse { allResponses.last() }
            }

            Result.success(ToolGenerationResult(
                response = finalResponse,
                toolsUsed = allToolResults.isNotEmpty(),
                iterations = iterations
            ))

        } catch (e: Exception) {
            AppLogger.e(TAG, "Tool generation failed", e)
            Result.failure(e)
        }
    }

    /**
     * Detect if user message likely needs web browsing.
     */
    fun needsWebBrowsing(userMessage: String): Boolean {
        val webIndicators = listOf(
            "fetch", "browse", "visit", "open", "check",
            "what's on", "go to", "look at", "read from",
            "http://", "https://", "www.", ".com", ".org", ".net"
        )
        val lowerMessage = userMessage.lowercase()
        return webIndicators.any { lowerMessage.contains(it) }
    }

    /**
     * Extract URL from user message if present.
     */
    fun extractUrlFromMessage(message: String): String? {
        val urls = WebFetcher.extractUrls(message)
        return urls.firstOrNull()
    }

    private fun buildPromptWithSystem(userPrompt: String, systemPrompt: String): String {
        return """$systemPrompt

User: $userPrompt
Assistant: """
    }

    @Suppress("UNUSED_PARAMETER")
    private fun buildFollowUpPrompt(
        originalPrompt: String,
        previousResponse: String, // Reserved for context
        toolResults: String
    ): String {
        return """Based on the user's question and the information retrieved:

User's question: $originalPrompt

Tool results:
$toolResults

Now provide a helpful response using this information.
Assistant: """
    }

    private fun buildSummaryPrompt(
        originalPrompt: String,
        toolResults: String
    ): String {
        return """The user asked: $originalPrompt

Here is the information retrieved from the web:
$toolResults

Provide a clear, helpful summary answering the user's question based on this information.
Assistant: """
    }

    /**
     * Simple URL fetch without full tool loop - for direct URL requests.
     */
    suspend fun fetchAndSummarize(
        url: String,
        userQuestion: String,
        params: GenerationParams = GenerationParams.BALANCED
    ): Result<String> = withContext(Dispatchers.Default) {
        try {
            // Fetch the URL
            val fetchResult = WebFetcher.fetchUrl(url)
            if (fetchResult.isFailure) {
                return@withContext Result.failure(fetchResult.exceptionOrNull()!!)
            }

            val webContent = fetchResult.getOrThrow()

            // Generate response based on content
            val prompt = """Here is content from ${webContent.title} (${webContent.url}):

${webContent.content}

User question: $userQuestion

Provide a helpful response based on the content above.
Assistant: """

            LlamaBridge.generateAsync(prompt, params)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Fetch and summarize failed", e)
            Result.failure(e)
        }
    }
}

/**
 * Result of processing tool calls.
 */
data class ToolResult(
    val originalResponse: String,
    val processedResponse: String,
    val toolsUsed: List<String>,
    val toolResults: List<String>,
    val hasToolCalls: Boolean
)

/**
 * Result of tool-enabled generation.
 */
data class ToolGenerationResult(
    val response: String,
    val toolsUsed: Boolean,
    val iterations: Int
)