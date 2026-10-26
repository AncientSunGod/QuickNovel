package com.lagradost.quicknovel.extensions

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionHtmlParserTest {
    private val html = """
        <html><body>
          <article><a class="chapter" href="/chapter/1">Chapter <b>One</b></a></article>
          <article><a class="chapter" href="/chapter/2">Chapter Two</a></article>
        </body></html>
    """.trimIndent()

    @Test
    fun selectsElementsAndResolvesDocumentBase() {
        val result = ExtensionHtmlParser.selectJson(
            html,
            "a.chapter",
            "https://example.org/novel"
        ).getOrThrow()
        val items = JSONArray(result)
        assertEquals(2, items.length())
        assertEquals("Chapter One", items.getJSONObject(0).getString("text"))
        assertEquals("/chapter/1", items.getJSONObject(0).getJSONObject("attributes").getString("href"))
    }

    @Test
    fun extractsTextAndAttributes() {
        assertEquals("Chapter One Chapter Two", ExtensionHtmlParser.text(html, "a.chapter").getOrThrow())
        assertEquals(
            "/chapter/2",
            ExtensionHtmlParser.attribute(html, "article:nth-of-type(2) a.chapter", "href").getOrThrow()
        )
    }

    @Test
    fun rejectsOversizedInputAndInvalidSelectors() {
        assertTrue(ExtensionHtmlParser.select("<p>x</p>", " ".repeat(501)).isFailure)
        assertTrue(ExtensionHtmlParser.text("x".repeat(2 * 1024 * 1024 + 1)).isFailure)
    }
}
