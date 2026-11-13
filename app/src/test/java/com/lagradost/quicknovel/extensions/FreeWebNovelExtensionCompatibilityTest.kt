package com.lagradost.quicknovel.extensions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeWebNovelExtensionCompatibilityTest {
    @Test
    fun parsesFreeWebNovelAjaxChapterListWithObjectFormUrlSearchParams() {
        val source = javaClass.getResourceAsStream("/lnreader/freewebnovel-readnovelfull.js")!!
            .bufferedReader()
            .use { it.readText() }
        val bundle = java.io.File.createTempFile("fwn-extension-", ".js")
        bundle.writeText(source)
        try {
            val extension = InstalledExtension(
                id = "fwn-com",
                name = "Free Web Novel",
                site = "https://freewebnovel.com/",
                version = "test",
                language = "English",
                url = "https://example.com/fwn.js",
                repositoryUrl = "https://example.com/plugins.min.json",
                bundlePath = bundle.absolutePath,
            )
            val api = ExtensionMainAPI(
                extension = extension,
                testRequestOverride = { url, method, headers, body ->
                    when {
                        url == "https://freewebnovel.com/novel/sample.html" -> {
                            assertEquals("GET", method)
                            ExtensionHttpClient.TextResponse(
                                url, 200, "text/html",
                                """
                                <html><body>
                                  <div class="books"><img src="/cover.jpg" title="Sample FWN Novel"></div>
                                  <div id="indexListPage" data-novel-id="123" data-total-chapters="2"></div>
                                  <span class="disqus" data-disqus-identifier="sample-acode"></span>
                                </body></html>
                                """.trimIndent(),
                            )
                        }
                        url == "https://freewebnovel.com/api/chapterlist.php" -> {
                            assertEquals("POST", method)
                            assertTrue(headers["Content-Type"].orEmpty().contains("application/x-www-form-urlencoded"))
                            assertTrue(body.orEmpty().contains("aid=123"))
                            assertTrue(body.orEmpty().contains("acode=sample-acode"))
                            assertTrue(Regex("""cid=\d+""").containsMatchIn(body.orEmpty()))
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """{"html":"<a href='/novel/sample/chapter-1' title='Chapter One'>Chapter One</a><a href='/novel/sample/chapter-2' title='Chapter Two'>Chapter Two</a>","totalPage":1}""",
                            )
                        }
                        url == "https://freewebnovel.com/novel/sample/chapter-1" -> {
                            assertEquals("GET", method)
                            ExtensionHttpClient.TextResponse(
                                url, 200, "text/html",
                                """<div class="txt"><p>FreeWebNovel chapter body opens</p></div>""",
                            )
                        }
                        else -> throw AssertionError("Unexpected FreeWebNovel request: $method $url")
                    }
                },
            )
            val novel = runBlocking {
                api.load("https://freewebnovel.com/novel/sample.html#qn_ext_path=novel%2Fsample.html")
            }
            assertEquals("Sample FWN Novel", novel?.name)
            assertEquals(2, novel?.data?.size)
            assertEquals("Chapter One", novel?.data?.firstOrNull()?.name)
            assertTrue(novel?.data?.firstOrNull()?.url.orEmpty().contains("#qn_ext_path="))
            val chapterHtml = runBlocking { api.loadHtml(novel!!.data.first().url) }
            assertTrue(chapterHtml.orEmpty().contains("FreeWebNovel chapter body opens"))
        } finally {
            bundle.delete()
        }
    }
}
