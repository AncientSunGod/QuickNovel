package com.lagradost.quicknovel.providers

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeWebNovelProviderTest {
    @Test
    fun acceptsCurrentAjaxResponseWithoutLegacyCodeField() {
        val response = parseFreeWebNovelTocAjaxResponse(
            """{"html":"<ul id='idData'><li><a href='/novel/sample/chapter-1'>Chapter 1</a></li></ul>","totalPage":4}"""
        )

        assertEquals(4, response.totalPages)
        assertTrue(response.html.contains("Chapter 1"))
    }

    @Test
    fun parsesCurrentIdDataChapterLinksAsAbsoluteUrls() {
        val document = Jsoup.parse(
            """
            <ul id="idData">
              <li><a href="/novel/sample/chapter-1" title="Chapter 1">Chapter One</a></li>
              <li><a href="chapter-2">Chapter Two</a></li>
              <li><a href="">Empty link</a></li>
              <li><a href="/novel/sample/chapter-3">Chapter Three</a></li>
            </ul>
            """.trimIndent(),
            "https://freewebnovel.com/novel/sample",
        )

        val chapters = parseFreeWebNovelTocLinks(document)

        assertEquals(3, chapters.size)
        assertEquals(listOf("Chapter 1", "Chapter Two", "Chapter Three"), chapters.map { it.name })
        assertEquals(
            listOf(
                "https://freewebnovel.com/novel/sample/chapter-1",
                "https://freewebnovel.com/novel/sample/chapter-2",
                "https://freewebnovel.com/novel/sample/chapter-3",
            ),
            chapters.map { it.url },
        )
    }

    @Test
    fun supportsRawHtmlAjaxFragmentsFromOlderMirrors() {
        val html = "<ul id='idData'><li><a href='/chapter-1'>Chapter One</a></li></ul>"

        val response = parseFreeWebNovelTocAjaxResponse(html)

        assertEquals(1, response.totalPages)
        assertEquals(html, response.html)
        assertEquals(
            listOf("Chapter One"),
            parseFreeWebNovelTocLinks(Jsoup.parse(response.html, "https://freewebnovel.com/novel/sample"))
                .map { it.name },
        )
    }

    @Test
    fun emptyAjaxResponseDoesNotCreateFakeChapters() {
        val response = parseFreeWebNovelTocAjaxResponse("""{"html":"","totalPage":1}""")

        assertEquals("", response.html)
        assertEquals(1, response.totalPages)
        assertTrue(
            parseFreeWebNovelTocLinks(Jsoup.parse(response.html, "https://freewebnovel.com/novel/sample"))
                .isEmpty()
        )
    }
}
