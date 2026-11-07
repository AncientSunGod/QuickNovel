package com.lagradost.quicknovel.extensions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelPingExtensionCompatibilityTest {
    @Test
    fun parsesNovelPingChapterArchiveUsingObjectFormUrlSearchParams() {
        val source = javaClass.getResourceAsStream("/lnreader/novelping-readnovelfull.js")!!
            .bufferedReader()
            .use { it.readText() }
        val bundle = java.io.File.createTempFile("novelping-extension-", ".js")
        bundle.writeText(source)
        try {
            val extension = InstalledExtension(
                id = "novelping",
                name = "Novel Ping",
                site = "https://novelping.com/",
                version = "test",
                language = "English",
                url = "https://example.com/novelping.js",
                repositoryUrl = "https://example.com/plugins.min.json",
                bundlePath = bundle.absolutePath,
            )
            var archiveWasRequested = false
            val result = ExtensionJsRuntime.execute(
                extension = extension,
                operation = "parseNovel",
                argumentsJson = """["novel/sample.html"]""",
                testRequestOverride = { url, method, headers, body ->
                    when (url) {
                        "https://novelping.com/novel/sample.html" -> {
                            assertEquals("GET", method)
                            ExtensionHttpClient.TextResponse(
                                url, 200, "text/html",
                                """
                                <html><body>
                                  <div class="books">
                                    <img src="/cover.jpg" title="Sample Novel">
                                    <span class="disqus" data-disqus-identifier="sample-acode"></span>
                                  </div>
                                  <div id="indexListPage" data-novel-id="123" data-total-chapters="20"></div>
                                </body></html>
                                """.trimIndent(),
                            )
                        }
                        "https://novelping.com/ajax/chapter-archive" -> {
                            archiveWasRequested = true
                            assertEquals("POST", method)
                            assertTrue(headers["Content-Type"].orEmpty().contains("application/x-www-form-urlencoded"))
                            val form = body.orEmpty()
                            assertTrue("novelId missing from chapter request: $form", form.contains("novelId=123"))
                            assertTrue("acode missing from chapter request: $form", form.contains("acode=sample-acode"))
                            assertTrue("chapter cursor missing from chapter request: $form", Regex("""cid=\d+""").containsMatchIn(form))
                            ExtensionHttpClient.TextResponse(
                                url, 200, "text/html",
                                """<a href="/novel/sample/chapter-1" title="Chapter One">Chapter One</a><a href="/novel/sample/chapter-2" title="Chapter Two">Chapter Two</a>""",
                            )
                        }
                        else -> throw AssertionError("Unexpected NovelPing request: $method $url")
                    }
                },
            ).getOrThrow()
            val novel = JSONObject(result.json)
            assertTrue("NovelPing chapter archive endpoint was never requested", archiveWasRequested)
            assertEquals("Sample Novel", novel.getString("name"))
            assertEquals(2, novel.getJSONArray("chapters").length())
            assertEquals("Chapter One", novel.getJSONArray("chapters").getJSONObject(0).getString("name"))
        } finally {
            bundle.delete()
        }
    }
}
