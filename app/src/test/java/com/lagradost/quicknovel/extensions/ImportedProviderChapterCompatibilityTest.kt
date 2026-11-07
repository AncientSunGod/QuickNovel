package com.lagradost.quicknovel.extensions

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedProviderChapterCompatibilityTest {
    private fun fixture(resource: String): String =
        javaClass.getResourceAsStream(resource)!!.bufferedReader().use { it.readText() }

    private fun installed(id: String, name: String, site: String, source: String): Pair<InstalledExtension, java.io.File> {
        val file = java.io.File.createTempFile("lnreader-$id-", ".js")
        file.writeText(source)
        return InstalledExtension(
            id = id,
            name = name,
            site = site,
            version = "test",
            language = "English",
            url = "$site/plugin.js",
            repositoryUrl = "https://example.com/plugins.min.json",
            bundlePath = file.absolutePath,
        ) to file
    }

    @Test
    fun fenrirRealmImportedBundleBuildsChapterListFromCurrentApi() {
        val (extension, file) = installed(
            "fenrirrealm", "Fenrir Realm", "https://fenrirealm.com",
            fixture("/lnreader/fenrirrealm.js"),
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension, "parseNovel", """["sample"]""",
                testRequestOverride = { url, method, _, _ ->
                    assertEquals("GET", method)
                    when (url) {
                        "https://fenrirealm.com/api/new/v2/series/sample/chapters" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """[{"id":101,"slug":"chapter-one","number":1,"title":"Chapter 1","locked":{"price":0}}]""",
                            )
                        "https://fenrirealm.com/api/new/v2/series/sample" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """{"title":"Sample Novel","description":"<p>Summary</p>","user":{"name":"Writer"},"cover":"https://fenrirealm.com/cover.jpg","genres":[{"name":"Fantasy"}],"status":"Ongoing"}""",
                            )
                        else -> throw AssertionError("Unexpected Fenrir request: $url")
                    }
                },
            ).getOrThrow()
            val novel = JSONObject(result.json)
            assertEquals("Sample Novel", novel.getString("name"))
            assertEquals(1, novel.getJSONArray("chapters").length())
            assertEquals("Chapter 1", novel.getJSONArray("chapters").getJSONObject(0).getString("name"))
            assertTrue(novel.getJSONArray("chapters").getJSONObject(0).getString("path").contains("chapter-one~~101"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun wtrLabImportedBundleBuildsChapterListFromCurrentApi() {
        val (extension, file) = installed(
            "wtrlab", "WTR-Lab", "https://wtr-lab.com",
            fixture("/lnreader/wtrlab.js"),
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension, "parseNovel", """["serie-123/sample"]""",
                testRequestOverride = { url, method, _, _ ->
                    assertEquals("GET", method)
                    when (url) {
                        "https://wtr-lab.com/serie-123/sample" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "text/html",
                                """
                                <html><head>
                                  <meta name="baggage" content="sample-baggage">
                                  <meta name="sentry-trace" content="sample-trace">
                                  <script id="__NEXT_DATA__" type="application/json">
                                    {"props":{"pageProps":{"serie":{"serie_data":{"data":{"title":"Sample WTR Novel","image":"/cover.jpg","description":"Summary","author":"Writer"},"raw_id":123,"slug":"sample","genres":[],"chapter_count":1,"status":0}}}}}
                                  </script>
                                </head><body><h1 class="text-uppercase">Sample WTR Novel</h1></body></html>
                                """.trimIndent(),
                            )
                        "https://wtr-lab.com/api/chapters/123?start=1&end=500" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """{"chapters":[{"title":"Chapter One","order":1,"updated_at":"2026-10-01"}]}""",
                            )
                        else -> throw AssertionError("Unexpected WTR-Lab request: $url")
                    }
                },
            ).getOrThrow()
            val novel = JSONObject(result.json)
            assertEquals("Sample WTR Novel", novel.getString("name"))
            assertEquals(1, novel.getJSONArray("chapters").length())
            assertEquals("Chapter One", novel.getJSONArray("chapters").getJSONObject(0).getString("name"))
        } finally {
            file.delete()
        }
    }
}
