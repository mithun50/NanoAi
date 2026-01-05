package com.nanoai.llm.tools

import android.util.Log
import com.nanoai.llm.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import java.net.URL

/**
 * WebFetcher - Fetches web pages and extracts text content.
 *
 * Enables the LLM to "browse" the web by fetching URL content.
 */
object WebFetcher {
    private const val TAG = "WebFetcher"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    private const val TIMEOUT_MS = 15000
    private const val MAX_CONTENT_LENGTH = 8000 // Limit content to fit in context

    /**
     * Fetch a URL and return its text content.
     */
    suspend fun fetchUrl(url: String): Result<WebContent> = withContext(Dispatchers.IO) {
        try {
            AppLogger.i(TAG, "Fetching URL: $url")

            val validUrl = normalizeUrl(url)

            val doc: Document = Jsoup.connect(validUrl)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .followRedirects(true)
                .get()

            val title = doc.title() ?: "Untitled"
            val content = extractContent(doc)
            val truncatedContent = truncateContent(content)

            AppLogger.i(TAG, "Fetched: $title (${truncatedContent.length} chars)")

            Result.success(WebContent(
                url = validUrl,
                title = title,
                content = truncatedContent,
                fetchedAt = System.currentTimeMillis()
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch URL: $url", e)
            AppLogger.e(TAG, "Fetch failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Extract main text content from HTML document.
     */
    private fun extractContent(doc: Document): String {
        // Remove script, style, nav, footer, ads
        doc.select("script, style, nav, footer, header, aside, .ad, .ads, .advertisement, #comments, .comments").remove()

        // Try to find main content area
        val mainContent = doc.select("article, main, .content, .post, .entry, #content, #main").firstOrNull()

        val textSource = mainContent ?: doc.body()

        // Get text with some structure preserved
        val text = textSource?.let { element ->
            // Clean HTML and get text
            Jsoup.clean(element.html(), Safelist.none())
                .replace(Regex("\\s+"), " ")
                .trim()
        } ?: ""

        return text
    }

    /**
     * Truncate content to fit within context limits.
     */
    private fun truncateContent(content: String): String {
        if (content.length <= MAX_CONTENT_LENGTH) return content

        // Try to truncate at sentence boundary
        val truncated = content.take(MAX_CONTENT_LENGTH)
        val lastPeriod = truncated.lastIndexOf('.')

        return if (lastPeriod > MAX_CONTENT_LENGTH * 0.7) {
            truncated.take(lastPeriod + 1) + "\n\n[Content truncated...]"
        } else {
            truncated + "...\n\n[Content truncated...]"
        }
    }

    /**
     * Normalize and validate URL.
     */
    private fun normalizeUrl(url: String): String {
        var normalized = url.trim()

        // Add https if no protocol
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }

        // Validate URL
        URL(normalized) // Throws if invalid

        return normalized
    }

    /**
     * Check if a string looks like a URL.
     */
    fun isUrl(text: String): Boolean {
        val urlPattern = Regex(
            """^(https?://)?[\w\-]+(\.[\w\-]+)+[/\w\-._~:/?#\[\]@!$&'()*+,;=]*$""",
            RegexOption.IGNORE_CASE
        )
        return urlPattern.matches(text.trim())
    }

    /**
     * Extract URLs from text.
     */
    fun extractUrls(text: String): List<String> {
        val urlPattern = Regex(
            """https?://[\w\-]+(\.[\w\-]+)+[/\w\-._~:/?#\[\]@!$&'()*+,;=%]*""",
            RegexOption.IGNORE_CASE
        )
        return urlPattern.findAll(text).map { it.value }.toList()
    }
}

/**
 * Data class for fetched web content.
 */
data class WebContent(
    val url: String,
    val title: String,
    val content: String,
    val fetchedAt: Long
) {
    /**
     * Format content for LLM context.
     */
    fun toContext(): String {
        return """
            |--- Web Page: $title ---
            |URL: $url
            |
            |$content
            |--- End of Web Page ---
        """.trimMargin()
    }
}
