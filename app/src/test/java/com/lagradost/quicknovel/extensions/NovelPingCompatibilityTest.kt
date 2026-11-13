package com.lagradost.quicknovel.extensions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelPingCompatibilityTest {
    @Test
    fun parsesNovelPingAjaxChapterArchiveIntoNonEmptyChapterCatalogue() {
        val source = javaClass.getResourceAsStream("/lnreader/novelping-readnovelfull.js")!!
            .bufferedReader()
            .use { it.readText() }
        val bundle = java.io.File.createTempFile("novelping-fixture-", ".js")
        bundle.writeText(source)
        try {
            val extension = InstalledExtension(
                id = "novelping",
                name = "Novel Ping",
                site = "https://novelping.com",
                version = "2.3.4",
                language = "English",
                url = "https://example.com/novelping.js",
                repositoryUrl = "https://example.com/plugins.min.json",
                bundlePath = bundle.absolutePath,
            )
            // Real NovelPing parses the full novel page through htmlparser2. Keep a
            // large but otherwise irrelevant comment to reproduce the >2 MB page that
            // previously failed before the plugin could build its chapter catalogue.
            val largeHtml = "<html><body><!--" +
                "x".repeat(2 * 1024 * 1024 + 2048) +
                "--><div class=\"books\"><img src=\"/cover.jpg\" title=\"Sample Novel\"></div>" +
                "<div id=\"indexListPage\" data-novel-id=\"123\" data-total-chapters=\"2\"></div>" +
                "<span class=\"disqus\" data-disqus-identifier=\"sample-acode\"></span>" +
                "</body></html>"
            val api = ExtensionMainAPI(
                extension = extension,
                testRequestOverride = { url, method, headers, body ->
                    when {
                        url == "https://novelping.com/novel/sample-novel" -> {
                            assertEquals("GET", method)
                            ExtensionHttpClient.TextResponse(
                                url, 200, "text/html",
                                largeHtml,
                            )
                        }
                        url == "https://novelping.com/ajax/chapter-archive" -> {
                            assertEquals("POST", method)
                            assertTrue(headers["Content-Type"].orEmpty().contains("application/x-www-form-urlencoded"))
                            assertTrue(body.orEmpty().contains("novelId=123"))
                            assertTrue(body.orEmpty().contains("acode=sample-acode"))
                            ExtensionHttpClient.TextResponse(
                                url, 200, "text/html",
                                """<a href="/novel/sample-novel/chapter-1" title="Chapter One">Chapter One</a><a href="/novel/sample-novel/chapter-2" title="Chapter Two">Chapter Two</a>""",
                            )
                        }
                        url == "https://novelping.com/novel/sample-novel/chapter-1" -> {
                            assertEquals("GET", method)
                            ExtensionHttpClient.TextResponse(
                                url, 200, "text/html",
                                """<div class="txt"><p>NovelPing chapter body opens</p></div>""",
                            )
                        }
                        else -> throw AssertionError("Unexpected NovelPing request: $method $url")
                    }
                },
            )
            val novel = runBlocking {
                api.load("https://novelping.com/novel/sample-novel#qn_ext_path=novel%2Fsample-novel")
            }
            assertEquals("Sample Novel", novel?.name)
            assertEquals(2, novel?.data?.size)
            assertEquals("Chapter One", novel?.data?.firstOrNull()?.name)
            assertTrue(novel?.data?.firstOrNull()?.url.orEmpty().contains("#qn_ext_path="))
            val chapterHtml = runBlocking { api.loadHtml(novel!!.data.first().url) }
            assertTrue(chapterHtml.orEmpty().contains("NovelPing chapter body opens"))
        } finally {
            bundle.delete()
        }
    }
}
