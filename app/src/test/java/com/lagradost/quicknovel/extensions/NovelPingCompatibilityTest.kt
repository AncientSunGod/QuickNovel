package com.lagradost.quicknovel.extensions

import org.json.JSONObject
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
            val result = ExtensionJsRuntime.execute(
                extension = extension,
                operation = "parseNovel",
                argumentsJson = """["novel/sample-novel"]""",
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
                        else -> throw AssertionError("Unexpected NovelPing request: $method $url")
                    }
                },
            ).getOrThrow()
            val novel = JSONObject(result.json)
            assertEquals("Sample Novel", novel.getString("name"))
            assertEquals(2, novel.getJSONArray("chapters").length())
            assertEquals("Chapter One", novel.getJSONArray("chapters").getJSONObject(0).getString("name"))
            assertEquals("novel/sample-novel/chapter-1", novel.getJSONArray("chapters").getJSONObject(0).getString("path"))
        } finally {
            bundle.delete()
        }
    }
}
