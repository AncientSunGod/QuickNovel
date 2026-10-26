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
    fun rejectsUnimplementedImportsRatherThanFakingCompatibility() {
        val (extension, file) = extension(
            "var helper = require('@libs/fetch'); module.exports = { " +
                "searchNovels: function() { return []; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("not available"))
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

}
