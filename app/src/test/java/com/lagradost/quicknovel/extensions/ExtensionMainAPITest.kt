package com.lagradost.quicknovel.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test
import java.io.File

class ExtensionMainAPITest {
    @Test
    fun loadsAllChapterPagesFromLnReaderPaginationMethod() {
        val bundle = File.createTempFile("quicknovel-pagination-", ".js")
        bundle.writeText("module.exports = {};")
        try {
            val extension = InstalledExtension(
                id = "pagination-test",
                name = "Pagination test",
                site = "https://example.com",
                version = "1",
                language = "English",
                url = "https://example.com/plugin.js",
                repositoryUrl = "https://example.com/plugins.min.json",
                bundlePath = bundle.absolutePath,
            )
            val api = ExtensionMainAPI(extension) { operation, args ->
                when (operation) {
                    "getImageRequestInit" -> """{"headers":{"referrer":"https://example.com","User-Agent":"QuickNovel test"}}"""
                    "searchNovels" -> """[{"name":"Paged Novel","path":"series","cover":"https://example.com/cover.jpg"}]"""
                    "parseNovel" -> """{"name":"Paged Novel","path":"series","cover":"https://example.com/cover.jpg","rating":4.5,"status":"On Hiatus","totalPages":3,"chapters":[{"name":"Chapter 1","path":"chapter-1"}]}"""
                    "parsePage" -> when (args.getString(1)) {
                        "2" -> """{"chapters":[{"name":"Chapter 2","path":"chapter-2"}]}"""
                        "3" -> """{"chapters":[{"name":"Chapter 3","path":"chapter-3"}]}"""
                        else -> throw AssertionError("Unexpected chapter page: ${args.getString(1)}")
                    }
                    else -> null
                }
            }

            val search = kotlinx.coroutines.runBlocking { api.search("paged") }
            assertEquals(1, search?.size)
            assertEquals(
                mapOf("referrer" to "https://example.com", "User-Agent" to "QuickNovel test"),
                search!!.first().posterHeaders,
            )
            val novel = kotlinx.coroutines.runBlocking { api.load(search.first().url) }
                as com.lagradost.quicknovel.StreamResponse
            assertEquals(
                mapOf("referrer" to "https://example.com", "User-Agent" to "QuickNovel test"),
                novel.posterHeaders,
            )
            assertEquals(3, novel.data.size)
            assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), novel.data.map { it.name })
            assertEquals(900, novel.rating)
            assertEquals(com.lagradost.quicknovel.ReleaseStatus.Paused, novel.status)
        } finally {
            bundle.delete()
        }
    }

    @Test
    fun runsCurrentChikariThroughSearchDetailsAndChapterReading() {
        val source = javaClass.getResourceAsStream("/lnreader/chikari.js")!!
            .bufferedReader()
            .use { it.readText() }
        val bundle = File.createTempFile("quicknovel-chikari-e2e-", ".js")
        bundle.writeText(source)
        val extension = InstalledExtension(
            id = "chikari-e2e",
            name = "Chikari E2E",
            site = "https://chikari.moe",
            version = "1.0.2",
            language = "en",
            url = "https://example.com/chikari.js",
            repositoryUrl = "https://example.com/plugins.min.json",
            bundlePath = bundle.absolutePath,
        )
        val requestedUrls = mutableListOf<String>()
        try {
            val api = ExtensionMainAPI(extension, testFetchOverride = { url ->
                requestedUrls += url
                when (url) {
                    "https://chikari.moe/api/series?type=novel&sort=popular&q=sample&limit=36&offset=0" ->
                        ExtensionHttpClient.TextResponse(
                            url, 200, "application/json",
                            """{"items":[{"title":"Sample Novel","slug":"sample-novel","cover_url":"https://chikari.moe/cover.jpg"}]}""",
                        )
                    "https://chikari.moe/api/novels/sample-novel" ->
                        ExtensionHttpClient.TextResponse(
                            url, 200, "application/json",
                            """{"title":"Sample Novel","authors":[{"name":"Test Author"}],"genres":[{"name":"Fantasy"}],"description":"A sample.","status":"releasing"}""",
                        )
                    "https://chikari.moe/api/novels/sample-novel/chapters?order=asc&limit=500&offset=0" ->
                        ExtensionHttpClient.TextResponse(
                            url, 200, "application/json",
                            """{"items":[{"title":"Chapter One","number":1,"created_at":"2026-10-01"}]}""",
                        )
                    "https://chikari.moe/api/novels/sample-novel/chapters/1/read" ->
                        ExtensionHttpClient.TextResponse(
                            url, 200, "application/json",
                            """{"body":"First paragraph\nSecond paragraph"}""",
                        )
                    else -> throw AssertionError("Unexpected Chikari request: $url")
                }
            })

            val results = kotlinx.coroutines.runBlocking { api.search("sample") }
            assertEquals(1, results?.size)
            assertEquals("Sample Novel", results!!.first().name)

            val novel = kotlinx.coroutines.runBlocking { api.load(results.first().url) }
                as com.lagradost.quicknovel.StreamResponse
            assertEquals("Sample Novel", novel.name)
            assertEquals(1, novel.data.size)
            assertEquals("Chapter One", novel.data.first().name)

            val chapterHtml = kotlinx.coroutines.runBlocking { api.loadHtml(novel.data.first().url) }
            assertTrue(chapterHtml!!.contains("<p>First paragraph</p>"))
            assertTrue(chapterHtml.contains("<p>Second paragraph</p>"))
            assertTrue(requestedUrls.contains("https://chikari.moe/api/novels/sample-novel"))
            assertTrue(requestedUrls.contains("https://chikari.moe/api/novels/sample-novel/chapters/1/read"))
        } finally {
            bundle.delete()
        }
    }

    @Test
    fun appliesInstalledCustomJsAndCssWhenLoadingExtensionChapters() {
        val bundle = File.createTempFile("quicknovel-custom-assets-", ".js")
        val customJs = File.createTempFile("quicknovel-custom-", ".js")
        val customCss = File.createTempFile("quicknovel-custom-", ".css")
        bundle.writeText("module.exports = {};")
        customJs.writeText("loadedCheerio('.promo').remove();")
        customCss.writeText("p { font-size: 1.1em; }")
        try {
            val extension = InstalledExtension(
                id = "custom-assets-test",
                name = "Custom assets test",
                site = "https://example.com",
                version = "1",
                language = "English",
                url = "https://example.com/plugin.js",
                repositoryUrl = "https://example.com/plugins.min.json",
                bundlePath = bundle.absolutePath,
                customJsPath = customJs.absolutePath,
                customCssPath = customCss.absolutePath,
            )
            val api = ExtensionMainAPI(extension) { operation, _ ->
                when (operation) {
                    "searchNovels" -> """[{"name":"Asset Novel","path":"series"}]"""
                    "parseChapter" -> JSONObject.quote(
                        """<div><p class="promo">Advertisement</p><p>Chapter text</p></div>"""
                    )
                    else -> null
                }
            }

            val results = kotlinx.coroutines.runBlocking { api.search("asset") }
            val html = kotlinx.coroutines.runBlocking { api.loadHtml(results!!.first().url)!! }
            assertTrue(!html.contains("Advertisement"))
            assertTrue(html.contains("Chapter text"))
            assertTrue(html.contains("qn-extension-custom-styles"))
            assertTrue(html.contains("<style>"))
            assertTrue(html.contains("font-size: 1.1em"))

            // APIRepository removes ads by parsing the whole document; EPUB conversion
            // then extracts body HTML. The extension stylesheet must survive both passes.
            val afterAdRemoval = org.jsoup.Jsoup.parse(html).html()
            val epubBody = org.jsoup.Jsoup.parse(afterAdRemoval).selectFirst("body")!!.html()
            val serializedChapterBody = org.jsoup.Jsoup.parse(epubBody).selectFirst("body")!!.html()
            assertTrue(serializedChapterBody.contains("qn-extension-custom-styles"))
            assertTrue(serializedChapterBody.contains("font-size: 1.1em"))
        } finally {
            bundle.delete()
            customJs.delete()
            customCss.delete()
        }
    }

    @Test
    fun mapsLnReaderPickerFiltersIntoQuickNovelMainPageControls() {
        val bundle = File.createTempFile("quicknovel-filters-", ".js")
        bundle.writeText("module.exports = {};")
        try {
            val extension = InstalledExtension(
                id = "filter-test",
                name = "Filter test",
                site = "https://example.com",
                version = "1",
                language = "English",
                url = "https://example.com/plugin.js",
                repositoryUrl = "https://example.com/plugins.min.json",
                bundlePath = bundle.absolutePath,
            )
            var capturedOptions: JSONObject? = null
            val api = ExtensionMainAPI(extension) { operation, args ->
                when (operation) {
                    "getPluginFilters" -> """{
                        "order":["sort","language","genres"],
                        "filters":{
                            "sort":{"label":"Sort by","type":"Picker","value":"popular","options":[
                                {"label":"Popular","value":"popular"},
                                {"label":"Latest","value":"latest"}
                            ]},
                            "language":{"label":"Language","type":"Picker","value":"en","options":[
                                {"label":"English","value":"en"},
                                {"label":"Japanese","value":"ja"}
                            ]},
                            "genres":{"label":"Genres","type":"XCheckbox","value":{"include":[],"exclude":["horror"]},"options":[
                                {"label":"Fantasy","value":"fantasy"}
                            ]}
                        }
                    }"""
                    "popularNovels" -> {
                        capturedOptions = args.getJSONObject(1)
                        "[]"
                    }
                    else -> null
                }
            }

            assertEquals(
                listOf("Popular" to "popular", "Latest" to "latest"),
                api.orderBys,
            )
            assertEquals(
                listOf("English" to "en", "Japanese" to "ja"),
                api.mainCategories,
            )
            assertEquals(
                listOf("All Genres" to "", "Fantasy" to "fantasy"),
                api.tags,
            )

            kotlinx.coroutines.runBlocking {
                api.loadMainPage(1, "ja", "latest", "fantasy")
            }
            val filters = capturedOptions!!.getJSONObject("filters")
            assertEquals("latest", filters.getJSONObject("sort").getString("value"))
            assertEquals("ja", filters.getJSONObject("language").getString("value"))
            val genreValue = filters.getJSONObject("genres").getJSONObject("value")
            assertEquals("fantasy", genreValue.getJSONArray("include").getString(0))
            assertEquals("horror", genreValue.getJSONArray("exclude").getString(0))
        } finally {
            bundle.delete()
        }
    }

    @Test
    fun runsRealChikariBundleThroughSearchBrowseNovelAndChapterAdapter() {
        val source = javaClass.getResourceAsStream("/lnreader/chikari.js")!!
            .bufferedReader()
            .use { it.readText() }
        val bundle = File.createTempFile("quicknovel-real-chikari-", ".js")
        bundle.writeText(source)
        try {
            val extension = InstalledExtension(
                id = "chikari",
                name = "Chikari",
                site = "https://chikari.moe",
                version = "1.0.2",
                language = "English",
                url = "https://example.com/chikari.js",
                repositoryUrl = "https://example.com/plugins.min.json",
                bundlePath = bundle.absolutePath,
            )
            val api = ExtensionMainAPI(
                extension = extension,
                testFetchOverride = { url ->
                    when (url) {
                        "https://chikari.moe/api/series?type=novel&sort=popular&q=sample&limit=36&offset=0" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """{"items":[{"title":"Sample Novel","slug":"sample-novel","cover_url":"https://chikari.moe/cover.jpg"}]}""",
                            )
                        "https://chikari.moe/api/series?type=novel&sort=trending&genre=fantasy&limit=60&offset=0" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """{"items":[{"title":"Trending Novel","slug":"trending-novel","cover_url":"https://chikari.moe/trending.jpg"}]}""",
                            )
                        "https://chikari.moe/api/novels/sample-novel" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """{"title":"Sample Novel","authors":[{"name":"Test Author"}],"genres":[{"name":"Fantasy"}],"description":"A sample novel.","status":"releasing","cover_url":"https://chikari.moe/cover.jpg"}""",
                            )
                        "https://chikari.moe/api/novels/sample-novel/chapters?order=asc&limit=500&offset=0" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """{"items":[{"title":"Chapter One","number":1,"created_at":"2026-10-01"}]}""",
                            )
                        "https://chikari.moe/api/novels/sample-novel/chapters/1/read" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """{"body":"First paragraph\nSecond paragraph"}""",
                            )
                        else -> throw AssertionError("Unexpected Chikari adapter request: $url")
                    }
                },
            )

            assertEquals(listOf("Popular" to "popular", "Trending" to "trending", "Top Rated" to "top_rated",
                "Recently Updated" to "updated", "Recently Added" to "added", "Most Bookmarked" to "most_bookmarked"),
                api.orderBys)
            assertTrue(api.tags.any { it.second == "fantasy" })

            val search = kotlinx.coroutines.runBlocking { api.search("sample") }
            assertEquals(1, search?.size)
            val searchItem = search!!.first()
            assertEquals("Sample Novel", searchItem.name)
            assertTrue(searchItem.url.contains("#qn_ext_path=sample-novel"))

            val browse = kotlinx.coroutines.runBlocking {
                api.loadMainPage(1, null, "trending", "fantasy")
            }
            assertEquals("Trending Novel", browse.list.single().name)

            val loaded = kotlinx.coroutines.runBlocking { api.load(searchItem.url) }
                as com.lagradost.quicknovel.StreamResponse
            assertEquals("Sample Novel", loaded.name)
            assertEquals("Test Author", loaded.author)
            assertEquals(1, loaded.data.size)
            assertTrue(loaded.data.first().url.contains("qn_ext_path=api%2Fnovels%2Fsample-novel%2Fchapters%2F1%2Fread"))

            val chapterHtml = kotlinx.coroutines.runBlocking {
                api.loadHtml(loaded.data.first().url)
            }
            assertTrue(chapterHtml.orEmpty().contains("<p>First paragraph</p>"))
            assertTrue(chapterHtml.orEmpty().contains("<p>Second paragraph</p>"))
        } finally {
            bundle.delete()
        }
    }

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
                    "popularNovels" -> """[{"name":"Popular Novel","path":"popular-novel"}]"""
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

            val mainPage = kotlinx.coroutines.runBlocking { api.loadMainPage(1, null, null, null) }
            assertEquals(1, mainPage.list.size)
            assertEquals("Popular Novel", mainPage.list[0].name)

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
