package com.nanoai.llm.rag

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

/**
 * WebScraper - Extracts clean text content from web pages.
 *
 * Uses Jsoup for HTML parsing and text extraction.
 */
object WebScraper {
    private const val TAG = "WebScraper"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36"
    private const val TIMEOUT_MS = 30000

    /**
     * Scrape a URL and return clean text content.
     */
    suspend fun scrape(url: String): Result<ScrapedContent> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Scraping: $url")

            val doc = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .followRedirects(true)
                .ignoreHttpErrors(false)
                .get()

            val content = extractContent(doc)

            Log.i(TAG, "Scraped ${content.text.length} characters from $url")
            Result.success(content)

        } catch (e: Exception) {
            Log.e(TAG, "Scrape failed for $url", e)
            Result.failure(e)
        }
    }

    /**
     * Scrape multiple URLs.
     */
    suspend fun scrapeMultiple(urls: List<String>): List<Result<ScrapedContent>> {
        return urls.map { scrape(it) }
    }

    /**
     * Extract main content from HTML document.
     */
    private fun extractContent(doc: Document): ScrapedContent {
        val title = doc.title() ?: ""

        // Remove unwanted elements
        doc.select(
            "script, style, nav, header, footer, aside, .nav, .menu, " +
            ".sidebar, .advertisement, .ad, .ads, .cookie, .popup, " +
            ".modal, .social, .share, .comments, noscript, iframe"
        ).remove()

        // Try to find main content
        val mainContent = findMainContent(doc)

        // Extract text
        val text = if (mainContent != null) {
            cleanText(mainContent.text())
        } else {
            cleanText(doc.body()?.text() ?: "")
        }

        // Extract links
        val links = doc.select("a[href]")
            .mapNotNull { element ->
                val href = element.absUrl("href")
                val linkText = element.text()
                if (href.isNotBlank() && linkText.isNotBlank()) {
                    Link(url = href, text = linkText)
                } else null
            }
            .distinctBy { it.url }
            .take(50) // Limit links

        // Extract metadata
        val description = doc.select("meta[name=description]").attr("content")
            .ifBlank { doc.select("meta[property=og:description]").attr("content") }

        val keywords = doc.select("meta[name=keywords]").attr("content")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return ScrapedContent(
            url = doc.location(),
            title = title,
            text = text,
            description = description,
            keywords = keywords,
            links = links,
            wordCount = text.split(Regex("\\s+")).size
        )
    }

    /**
     * Find the main content element.
     */
    private fun findMainContent(doc: Document): Element? {
        // Priority order for finding main content
        val selectors = listOf(
            "article",
            "main",
            "[role=main]",
            ".post-content",
            ".article-content",
            ".content",
            ".entry-content",
            "#content",
            "#main-content",
            ".main-content"
        )

        for (selector in selectors) {
            val element = doc.selectFirst(selector)
            if (element != null && element.text().length > 200) {
                return element
            }
        }

        // Fallback: find the largest text block
        val children = doc.body()?.children()?.toList() ?: emptyList()
        return children
            .filter { it.text().length > 200 }
            .maxByOrNull { it.text().length }
    }

    /**
     * Clean and normalize text.
     */
    private fun cleanText(text: String): String {
        return text
            .replace(Regex("\\s+"), " ")  // Collapse whitespace
            .replace(Regex("\\n{3,}"), "\n\n")  // Limit newlines
            .trim()
    }

    /**
     * Chunk text into smaller pieces for embedding.
     *
     * @param text Text to chunk
     * @param chunkSize Target chunk size in characters
     * @param overlap Overlap between chunks in characters
     * @return List of text chunks
     */
    fun chunkText(
        text: String,
        chunkSize: Int = 1000,
        overlap: Int = 200
    ): List<String> {
        if (text.length <= chunkSize) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < text.length) {
            var end = minOf(start + chunkSize, text.length)

            // Try to break at sentence boundary
            if (end < text.length) {
                val breakPoints = listOf(". ", "! ", "? ", "\n\n", "\n")
                for (breakPoint in breakPoints) {
                    val lastBreak = text.lastIndexOf(breakPoint, end)
                    if (lastBreak > start + chunkSize / 2) {
                        end = lastBreak + breakPoint.length
                        break
                    }
                }
            }

            val chunk = text.substring(start, end).trim()
            if (chunk.isNotBlank()) {
                chunks.add(chunk)
            }

            // Move start with overlap
            start = end - overlap
            if (start <= chunks.lastOrNull()?.let { text.indexOf(it) + it.length / 2 } ?: 0) {
                start = end
            }
        }

        return chunks
    }

    /**
     * Chunk by approximate token count.
     * Assumes ~4 characters per token (rough estimate for English).
     */
    fun chunkByTokens(
        text: String,
        maxTokens: Int = 300,
        overlapTokens: Int = 50
    ): List<String> {
        val charsPerToken = 4
        return chunkText(
            text = text,
            chunkSize = maxTokens * charsPerToken,
            overlap = overlapTokens * charsPerToken
        )
    }

    /**
     * Extract readable text from HTML string.
     */
    fun htmlToText(html: String): String {
        val clean = Jsoup.clean(html, Safelist.none())
        return cleanText(Jsoup.parse(clean).text())
    }
}

/**
 * Scraped content data class.
 */
data class ScrapedContent(
    val url: String,
    val title: String,
    val text: String,
    val description: String = "",
    val keywords: List<String> = emptyList(),
    val links: List<Link> = emptyList(),
    val wordCount: Int = 0
)

/**
 * Link data class.
 */
data class Link(
    val url: String,
    val text: String
)
