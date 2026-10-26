package com.lagradost.quicknovel.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtensionJsRuntimeTest {
    private fun extension(source: String): Pair<InstalledExtension, File> {
        val file = File.createTempFile("quicknovel-extension-", ".js")
        file.writeText(source)
        return InstalledExtension(
            id = "test-extension",
            name = "Test Extension",
            site = "https://example.com",
            version = "1",
            language = "en",
            url = "https://example.com/plugin.js",
            repositoryUrl = "https://example.com/plugins.min.json",
            bundlePath = file.absolutePath,
        ) to file
    }

    @Test
    fun executesSimpleCommonJsExportAndReturnsJson() {
        val (extension, file) = extension(
            "module.exports = { searchNovels: function(term, page) { " +
                "return [{ title: term, url: 'https://example.com/' + page }]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension,
                "searchNovels",
                """["hello",2]""",
            ).getOrThrow()
            assertEquals("""[{"title":"hello","url":"https://example.com/2"}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsUnknownImportsRatherThanFakingCompatibility() {
        val (extension, file) = extension(
            "var helper = require('@unsupported/module'); module.exports = { " +
                "searchNovels: function() { return []; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Unsupported LNReader module import"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun stopsInstructionHeavyExtensionCode() {
        val (extension, file) = extension(
            "module.exports = { searchNovels: function() { while (true) {} } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("instruction budget"))
        } finally {
            file.delete()
        }
    }
    @Test
    fun supportsAsyncStyleBundleAndKnownLnReaderModules() {
        val (extension, file) = extension(
            "var cover = require('@libs/defaultCover'); " +
                "var filters = require('@libs/filterInputs'); " +
                "var status = require('@libs/novelStatus'); " +
                "module.exports = { searchNovels: function(term) { " +
                "return Promise.resolve([{name:term,path:'slug',cover:cover.defaultCover," +
                "filter:filters.FilterTypes.Picker,status:status.NovelStatus.Ongoing}]); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension,
                "searchNovels",
                """["sample"]""",
            ).getOrThrow()
            assertTrue(result.json.contains("\"name\":\"sample\""))
            assertTrue(result.json.contains("\"filter\":\"Picker\""))
            assertTrue(result.json.contains("\"status\":\"ongoing\""))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsPromiseAllForBundledAsyncHelpers() {
        val (extension, file) = extension(
            "module.exports = { searchNovels: function() { " +
                "return Promise['all']([Promise.resolve('a'), Promise.resolve('b')])" +
                ".then(function(values) { return values; }); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""["a","b"]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsCommonCheerioLoadSelectTextAndAttributeCalls() {
        val (extension, file) = extension(
            "var cheerio = require('cheerio'); " +
                "module.exports = { searchNovels: function() { " +
                "var $ = cheerio.load('<div class=\"entry\"><a href=\"/novel\">Sample Novel</a></div>'); " +
                "return [{name:$('.entry a').text(),path:$('.entry a').attr('href')}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""[{"name":"Sample Novel","path":"/novel"}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsCheerioEachAndNestedFind() {
        val (extension, file) = extension(
            "var cheerio = require('cheerio'); " +
                "module.exports = { searchNovels: function() { " +
                "var $ = cheerio.load('<ul><li><a href=\"/one\"> One </a></li><li><a href=\"/two\">Two</a></li></ul>'); " +
                "var items = []; $('li').each(function(i, el) { " +
                "items.push({name:$(el).find('a').text().trim(),path:$(el).find('a').attr('href')}); }); " +
                "return items; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""[{"name":"One","path":"/one"},{"name":"Two","path":"/two"}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsFetchTextAndCommonUrlHelpers() {
        val (extension, file) = extension(
            "var fetch = require('@libs/fetch'); " +
                "var absolute = require('@libs/isAbsoluteUrl'); " +
                "var encoding = require('urlencode'); " +
                "module.exports = { searchNovels: function() { " +
                "return fetch.fetchText('https://example.com/data').then(function(body) { " +
                "return [{name:body,path:encoding.encode('a b'),absolute:absolute.isUrlAbsolute('https://example.com/x')}]; }); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension,
                "searchNovels",
                "[]",
                testFetchOverride = { url ->
                    assertEquals("https://example.com/data", url)
                    ExtensionHttpClient.TextResponse(url, 200, "text/plain", "sample")
                },
            ).getOrThrow()
            assertEquals("""[{"name":"sample","path":"a%20b","absolute":true}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsHtmlparser2ParserCallbacksForCommonHtmlScrapers() {
        val (extension, file) = extension(
            "var Parser = require('htmlparser2').Parser; " +
                "module.exports = { searchNovels: function() { " +
                "var items = [], current = {}, inAnchor = false; " +
                "var parser = new Parser({ " +
                "onopentag:function(name,attrs){if(name==='a'){current.path=attrs.href;inAnchor=true;}}, " +
                "ontext:function(text){if(inAnchor)current.name=(current.name||'')+text;}, " +
                "onclosetag:function(name){if(name==='a'){items.push(current);current={};inAnchor=false;}} " +
                "}); parser.write('<a href=/novel>Sample Novel</a>'); parser.end(); return items; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""[{"path":"/novel","name":"Sample Novel"}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsCommonDayjsDateParsingFormattingAndSubtraction() {
        val (extension, file) = extension(
            "var dayjs = require('dayjs').default; " +
                "module.exports = { searchNovels: function() { " +
                "var parsed = dayjs('07.08.2026','DD.MM.YYYY'); " +
                "return [{name:parsed.format('YYYY-MM-DD'),path:dayjs('2026-08-01').subtract(1,'day').format('YYYY-MM-DD')}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""[{"name":"2026-08-07","path":"2026-07-31"}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun exposesStorageHelperMethodsToBundles() {
        val (extension, file) = extension(
            "var store = require('@libs/storage').storage; " +
                "module.exports = { searchNovels: function() { " +
                "return [{name:typeof store.get,path:typeof store.set}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""[{"name":"function","path":"function"}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun fetchBridgeRejectsRequestsOutsideTheExtensionSite() {
        val (extension, file) = extension(
            "var fetch = require('@libs/fetch'); " +
                "module.exports = { searchNovels: function() { " +
                "return fetch.fetchApi('https://other.example/data').then(function(r) { return r.json(); }); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("approved site"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun executesRealChikariPluginBundleWithMockedSiteResponse() {
        val source = javaClass.getResourceAsStream("/lnreader/chikari.js")!!
            .bufferedReader()
            .use { it.readText() }
        val (extension, file) = extension(source)
        try {
            val result = ExtensionJsRuntime.execute(
                extension = extension.copy(site = "https://chikari.moe"),
                operation = "searchNovels",
                argumentsJson = """["sample",1]""",
                testFetchOverride = { url ->
                    assertEquals(
                        "https://chikari.moe/api/novels?sort=popular&q=sample&limit=36&offset=0",
                        url,
                    )
                    ExtensionHttpClient.TextResponse(
                        url = url,
                        statusCode = 200,
                        contentType = "application/json",
                        body = """{"items":[{"title":"Sample Novel","slug":"sample-novel","cover_url":"https://chikari.moe/cover.jpg"}]}""",
                    )
                },
            ).getOrThrow()
            val items = org.json.JSONArray(result.json)
            assertEquals(1, items.length())
            assertEquals("Sample Novel", items.getJSONObject(0).getString("name"))
            assertEquals("sample-novel", items.getJSONObject(0).getString("path"))
            assertEquals("https://chikari.moe/cover.jpg", items.getJSONObject(0).getString("cover"))
        } finally {
            file.delete()
        }
    }


    @Test
    fun executesRealChikariNovelDetailsAndChapterOperations() {
        val source = javaClass.getResourceAsStream("/lnreader/chikari.js")!!
            .bufferedReader()
            .use { it.readText() }
        val (extension, file) = extension(source)
        try {
            val details = ExtensionJsRuntime.execute(
                extension = extension.copy(site = "https://chikari.moe"),
                operation = "parseNovel",
                argumentsJson = """["sample-novel"]""",
                testFetchOverride = { url ->
                    when (url) {
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
                        else -> throw AssertionError("Unexpected Chikari request: $url")
                    }
                },
            ).getOrThrow()
            val novel = org.json.JSONObject(details.json)
            assertEquals("Sample Novel", novel.getString("name"))
            assertEquals("Test Author", novel.getString("author"))
            assertEquals("api/novels/sample-novel/chapters/1/read",
                novel.getJSONArray("chapters").getJSONObject(0).getString("path"))

            val chapter = ExtensionJsRuntime.execute(
                extension = extension.copy(site = "https://chikari.moe"),
                operation = "parseChapter",
                argumentsJson = """["api/novels/sample-novel/chapters/1/read"]""",
                testFetchOverride = { url ->
                    assertEquals("https://chikari.moe/api/novels/sample-novel/chapters/1/read", url)
                    ExtensionHttpClient.TextResponse(
                        url, 200, "application/json",
                        """{"body":"First paragraph\nSecond paragraph"}""",
                    )
                },
            ).getOrThrow()
            assertTrue(chapter.json.contains("<p>First paragraph</p>"))
            assertTrue(chapter.json.contains("<p>Second paragraph</p>"))
        } finally {
            file.delete()
        }
    }

}
