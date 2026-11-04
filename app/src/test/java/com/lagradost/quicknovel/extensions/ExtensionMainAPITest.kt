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
                    "searchNovels" -> """[{"name":"Paged Novel","path":"series"}]"""
                    "parseNovel" -> """{"name":"Paged Novel","path":"series","rating":4.5,"status":"On Hiatus","totalPages":3,"chapters":[{"name":"Chapter 1","path":"chapter-1"}]}"""
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
            val novel = kotlinx.coroutines.runBlocking { api.load(search!!.first().url) }
                as com.lagradost.quicknovel.StreamResponse
            assertEquals(3, novel.data.size)
            assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), novel.data.map { it.name })
            assertEquals(900, novel.rating)
            assertEquals(com.lagradost.quicknovel.ReleaseStatus.Paused, novel.status)
        } finally {
            bundle.delete()
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
                        "sort":{"label":"Sort by","type":"Picker","value":"popular","options":[
                            {"label":"Popular","value":"popular"},
                            {"label":"Latest","value":"latest"}
                        ]},
                        "language":{"label":"Language","type":"Picker","value":"en","options":[
                            {"label":"English","value":"en"},
                            {"label":"Japanese","value":"ja"}
                        ]},
                        "genres":{"label":"Genres","type":"Checkbox","value":[],"options":[
                            {"label":"Fantasy","value":"fantasy"}
                        ]}
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

            kotlinx.coroutines.runBlocking {
                api.loadMainPage(1, "ja", "latest", null)
            }
            val filters = capturedOptions!!.getJSONObject("filters")
            assertEquals("latest", filters.getJSONObject("sort").getString("value"))
            assertEquals("ja", filters.getJSONObject("language").getString("value"))
            assertEquals(0, filters.getJSONObject("genres").getJSONArray("value").length())
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
