package com.lagradost.quicknovel.providers

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeWebNovelProviderTest {
    @Test
    fun buildsTheOfficialPaginatedAjaxChapterUrl() {
        assertEquals(
            "https://freewebnovel.com/novel/sample.html?ajax=chapters&page=2&pageSize=40",
            buildFreeWebNovelAjaxChapterPageUrl(
                "https://freewebnovel.com/novel/sample.html?tracking=ignored",
                page = 2,
            ),
        )
    }


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
              <li><a href="/novel/sample/chapter-2">Chapter Two</a></li>
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

    @Test
    fun extractsArticleIdFromCurrentIndexListPageMetadata() {
        val document = Jsoup.parse(
            """
            <div id="indexListPage" data-novel-id="2572" data-total-chapters="1682"></div>
            """.trimIndent(),
            "https://freewebnovel.com/novel/sample",
        )

        assertEquals("2572", extractFreeWebNovelArticleId(document))
    }

    @Test
    fun extractsArticleIdFromLegacySourceIdScript() {
        val document = Jsoup.parse(
            """<script>var sourceid = "31415";</script>""",
            "https://freewebnovel.com/novel/sample",
        )

        assertEquals("31415", extractFreeWebNovelArticleId(document))
    }


    @Test
    fun parsesArchiveOptionsReturnedByChapterListApi() {
        val document = Jsoup.parse(
            """
            <select>
              <option value="/novel/sample/chapter-1">Chapter One</option>
              <option value="novel/sample/chapter-2">Chapter Two</option>
            </select>
            """.trimIndent(),
            "https://freewebnovel.com/api/chapterlist.php",
        )

        val chapters = parseFreeWebNovelTocLinks(
            document,
            siteRoot = "https://freewebnovel.com",
            includeOptions = true,
        )

        assertEquals(2, chapters.size)
        assertEquals(
            listOf(
                "https://freewebnovel.com/novel/sample/chapter-1",
                "https://freewebnovel.com/novel/sample/chapter-2",
            ),
            chapters.map { it.url },
        )
    }


    @Test
    fun prefersDisqusIdentifierForArchiveAcode() {
        val document = Jsoup.parse(
            """<span class="disqus" data-disqus-identifier="novel-article-2572"></span>"""
        )

        assertEquals(
            "novel-article-2572",
            extractFreeWebNovelAcode(document, "url-slug"),
        )
    }

    @Test
    fun fallsBackToNovelSlugWhenDisqusIdentifierIsMissing() {
        val document = Jsoup.parse("<html><body></body></html>")

        assertEquals("sample-novel", extractFreeWebNovelAcode(document, "sample-novel"))
    }


    @Test
    fun resolvesInitialChapterLinksAgainstLibReadHostWhenRequested() {
        val document = Jsoup.parse(
            """<ul id="idData"><li><a href="/novel/sample/chapter-1">Chapter One</a></li></ul>"""
        )

        val chapters = parseFreeWebNovelTocLinks(
            document,
            siteRoot = "https://libread.com",
        )

        assertEquals("https://libread.com/novel/sample/chapter-1", chapters.single().url)
    }


    @Test
    fun extractsTotalChapterCountFromTheOfficialPaginationScript() {
        val document = Jsoup.parse(
            """<script>window.chapterPagination = { totalChapters: 1682 };</script>""",
            "https://freewebnovel.com/novel/sample",
        )

        assertEquals(1682, extractFreeWebNovelTotalChapters(document))
    }

    @Test
    fun extractsTotalChapterCountFromIndexMetadata() {
        val document = Jsoup.parse(
            """<div id="indexListPage" data-total-chapters="987"></div>""",
            "https://freewebnovel.com/novel/sample",
        )

        assertEquals(987, extractFreeWebNovelTotalChapters(document))
    }

    @Test
    fun extractsArticleIdFromCoverImageFilename() {
        assertEquals(
            "2572",
            extractFreeWebNovelImageArticleId("https://freewebnovel.com/uploads/2572s.jpg"),
        )
        assertEquals(
            "31415",
            extractFreeWebNovelImageArticleId("https://freewebnovel.com/uploads/31415s.webp"),
        )
    }


    @Test
    fun parsesNestedAjaxDataEnvelope() {
        val response = parseFreeWebNovelTocAjaxResponse(
            """{"data":{"html":"<select><option value=\"/novel/sample/chapter-1\">Chapter 1</option></select>","totalPages":3}}"""
        )

        assertEquals(3, response.totalPages)
        assertTrue(response.html.contains("Chapter 1"))
        assertEquals(
            listOf("Chapter 1"),
            parseFreeWebNovelTocLinks(Jsoup.parse(response.html), includeOptions = true).map { it.name },
        )
    }
}
