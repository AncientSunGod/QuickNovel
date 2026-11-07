package com.lagradost.quicknovel.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WtrLabProviderTest {
    @Test
    fun rendersCurrentParagraphArrayAndGlossaryMarkers() {
        val html = buildWtrLabChapterHtml(
            body = listOf("<b>Hello</b> ※0⛬", "Second paragraph"),
            images = emptyList(),
            glossaryTerms = listOf("World"),
        )

        assertTrue(html.contains("<p>Hello World</p>"))
        assertTrue(html.contains("<p>Second paragraph</p>"))
    }

    @Test
    fun rendersImagesInTheirOriginalPositions() {
        val html = buildWtrLabChapterHtml(
            body = listOf("Before image", "[image]", "After image"),
            images = listOf("https://img.example/cover.jpg"),
            glossaryTerms = emptyList(),
        )

        assertTrue(html.indexOf("Before image") < html.indexOf("<img"))
        assertTrue(html.contains("src=\"https://img.example/cover.jpg\""))
        assertTrue(html.indexOf("<img") < html.indexOf("After image"))
    }

    @Test
    fun skipsMissingImagesAndBlankParagraphs() {
        val html = buildWtrLabChapterHtml(
            body = listOf("", "[image]", "Visible paragraph"),
            images = emptyList(),
            glossaryTerms = emptyList(),
        )

        assertEquals("<p>Visible paragraph</p>", html)
    }
}
