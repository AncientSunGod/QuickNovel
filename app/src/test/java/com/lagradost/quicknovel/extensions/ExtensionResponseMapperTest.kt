package com.lagradost.quicknovel.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtensionResponseMapperTest {
    @Test
    fun searchResultsResolveRelativeUrlsAndSkipInvalidEntries() {
        val json = """
            [
              {
                "title": "Novel One",
                "url": "/series/one",
                "posterUrl": "/images/one.jpg",
                "latestChapter": "Chapter 12"
              },
              {
                "title": "Bad URL",
                "url": "javascript:alert(1)"
              },
              {
                "title": "Missing URL"
              },
              {
                "title": "Novel Two",
                "url": "https://reader.example/series/two"
              }
            ]
        """.trimIndent()

        val results = ExtensionResponseMapper.parseSearchResults(
            json = json,
            apiName = "Test provider",
            baseUrl = "https://reader.example/catalog/",
        )

        assertEquals(2, results.size)
        assertEquals("Novel One", results[0].name)
        assertEquals("https://reader.example/series/one", results[0].url)
        assertEquals("https://reader.example/images/one.jpg", results[0].posterUrl)
        assertEquals("Chapter 12", results[0].latestChapter)
        assertEquals("Test provider", results[0].apiName)
        assertEquals("https://reader.example/series/two", results[1].url)
        assertNull(results[1].posterUrl)
    }

    @Test
    fun novelDetailsMapMetadataAndSkipInvalidChapters() {
        val json = """
            {
              "title": "Example Novel",
              "url": "/series/example",
              "author": "A. Writer",
              "posterUrl": "/cover.jpg",
              "synopsis": "A short summary.",
              "tags": ["Fantasy", "Adventure"],
              "chapters": [
                {"title": "Chapter 1", "url": "/chapter/1", "dateOfRelease": "2026-01-01"},
                {"title": "Invalid", "url": "file:///etc/passwd"},
                {"title": "Chapter 2", "url": "https://reader.example/chapter/2"}
              ]
            }
        """.trimIndent()

        val result = ExtensionResponseMapper.parseNovel(
            json = json,
            apiName = "Test provider",
            baseUrl = "https://reader.example/",
            requestedUrl = "https://reader.example/series/example",
        )

        assertEquals("Example Novel", result.name)
        assertEquals("https://reader.example/series/example", result.url)
        assertEquals("A. Writer", result.author)
        assertEquals("https://reader.example/cover.jpg", result.posterUrl)
        assertEquals("A short summary.", result.synopsis)
        assertEquals(listOf("Fantasy", "Adventure"), result.tags)
        assertEquals(2, result.data.size)
        assertEquals("Chapter 1", result.data[0].name)
        assertEquals("https://reader.example/chapter/1", result.data[0].url)
        assertEquals("2026-01-01", result.data[0].dateOfRelease)
        assertEquals("https://reader.example/chapter/2", result.data[1].url)
    }

    @Test(expected = IllegalArgumentException::class)
    fun novelDetailsRejectMissingChapterList() {
        ExtensionResponseMapper.parseNovel(
            json = """{"title":"Incomplete","url":"/series/incomplete"}""",
            apiName = "Test provider",
            baseUrl = "https://reader.example/",
            requestedUrl = "https://reader.example/series/incomplete",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun searchResultsRejectMoreThanTheConfiguredLimit() {
        val entries = (0..100).joinToString(
            prefix = "[",
            postfix = "]",
        ) { index ->
            """{"title":"Novel $index","url":"/novel/$index"}"""
        }

        ExtensionResponseMapper.parseSearchResults(
            json = entries,
            apiName = "Test provider",
            baseUrl = "https://reader.example/",
        )
    }
}
