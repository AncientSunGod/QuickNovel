package com.lagradost.quicknovel.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtensionMainAPITest {
    @Test
    fun routesSearchNovelAndChapterThroughOpaqueLnReaderPaths() {
        val bundle = File.createTempFile("quicknovel-adapter-", ".js")
        bundle.writeText("module.exports = {};")
        try {
            val extension = InstalledExtension(
                id = "chikari",
                name = "Chikari test",
                site = "https://chikari.moe",
                version = "1",
                language = "unknown",
                url = "https://example.com/chikari.js",
                repositoryUrl = "https://example.com/plugins.min.json",
                bundlePath = bundle.absolutePath,
            )
            val api = ExtensionMainAPI(extension) { operation, args ->
                when (operation) {
                    "searchNovels" -> """[{"name":"Sample Novel","path":"sample-novel","cover":"https://chikari.moe/cover.jpg"}]"""
                    "resolveUrl" -> {
                        val path = args.getString(0)
                        if (args.optBoolean(1)) {
                            org.json.JSONObject.quote("https://chikari.moe/novels/$path")
                        } else {
                            org.json.JSONObject.quote("https://chikari.moe/$path")
                        }
                    }
                    "parseNovel" -> """{"name":"Sample Novel","path":"sample-novel","chapters":[{"name":"Chapter One","path":"api/novels/sample-novel/chapters/1/read"}]}"""
                    "parseChapter" -> org.json.JSONObject.quote("<p>Chapter text</p>")
                    else -> null
                }
            }

            assertEquals("en", api.lang)
            val search = kotlinx.coroutines.runBlocking { api.search("sample") }
            assertNotNull(search)
            assertEquals(1, search!!.size)
            assertEquals("Sample Novel", search[0].name)
            assertTrue(search[0].url.contains("#qn_ext_path=sample-novel"))

            val novel = kotlinx.coroutines.runBlocking { api.load(search[0].url) }
            assertNotNull(novel)
            assertEquals("Sample Novel", novel!!.name)
            assertEquals(1, (novel as com.lagradost.quicknovel.StreamResponse).data.size)
            val chapterUrl = (novel as com.lagradost.quicknovel.StreamResponse).data[0].url
            assertTrue(chapterUrl.contains("#qn_ext_path=api%2Fnovels%2Fsample-novel%2Fchapters%2F1%2Fread"))

            val html = kotlinx.coroutines.runBlocking { api.loadHtml(chapterUrl) }
            assertEquals("<p>Chapter text</p>", html)
        } finally {
            bundle.delete()
        }
    }

}
