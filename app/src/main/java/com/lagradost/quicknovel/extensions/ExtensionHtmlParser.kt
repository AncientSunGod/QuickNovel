package com.lagradost.quicknovel.extensions

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small, bounded HTML parsing surface for the future LNReader compatibility bridge.
 * This is not a full Cheerio replacement; it deliberately exposes only deterministic
 * selector/text/attribute operations until the bundle's expected API is implemented.
 */
object ExtensionHtmlParser {
    private const val MAX_HTML_CHARS = 2 * 1024 * 1024
    private const val MAX_SELECTOR_CHARS = 500
    private const val MAX_RESULTS = 1_000
    private const val MAX_TEXT_CHARS = 100_000

    data class HtmlElement(
        val tag: String,
        val text: String,
        val html: String,
        val attributes: Map<String, String>,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("tag", tag)
            .put("text", text)
            .put("html", html)
            .put("attributes", JSONObject(attributes))
    }

    fun select(html: String, selector: String, baseUrl: String = ""): Result<List<HtmlElement>> =
        runCatching {
            require(html.length <= MAX_HTML_CHARS) { "HTML input exceeds the 2 MB limit." }
            require(selector.isNotBlank() && selector.length <= MAX_SELECTOR_CHARS) {
                "CSS selector is empty or too long."
            }
            val document = Jsoup.parse(html, baseUrl)
            document.select(selector).take(MAX_RESULTS).map { element -> element.toSafeElement() }
        }

    fun selectJson(html: String, selector: String, baseUrl: String = ""): Result<String> =
        select(html, selector, baseUrl).map { elements ->
            JSONArray().apply { elements.forEach { put(it.toJson()) } }.toString()
        }

    fun text(html: String, selector: String? = null, baseUrl: String = ""): Result<String> =
        runCatching {
            require(html.length <= MAX_HTML_CHARS) { "HTML input exceeds the 2 MB limit." }
            val document = Jsoup.parse(html, baseUrl)
            val text = if (selector.isNullOrBlank()) document.text() else {
                require(selector.length <= MAX_SELECTOR_CHARS) { "CSS selector is too long." }
                document.select(selector).text()
            }
            text.take(MAX_TEXT_CHARS)
        }

    fun attribute(
        html: String,
        selector: String,
        attribute: String,
        baseUrl: String = "",
    ): Result<String?> = runCatching {
        require(html.length <= MAX_HTML_CHARS) { "HTML input exceeds the 2 MB limit." }
        require(selector.isNotBlank() && selector.length <= MAX_SELECTOR_CHARS) {
            "CSS selector is empty or too long."
        }
        require(attribute.matches(Regex("[A-Za-z_:][A-Za-z0-9_:.-]{0,99}"))) {
            "Attribute name is invalid."
        }
        Jsoup.parse(html, baseUrl).selectFirst(selector)?.attr(attribute)?.take(MAX_TEXT_CHARS)
    }

    private fun Element.toSafeElement(): HtmlElement {
        val safeAttributes = attributes().asList()
            .take(100)
            .associate { it.key.take(100) to it.value.take(MAX_TEXT_CHARS) }
        return HtmlElement(
            tag = tagName().take(100),
            text = text().take(MAX_TEXT_CHARS),
            html = html().take(MAX_TEXT_CHARS),
            attributes = safeAttributes,
        )
    }
}
