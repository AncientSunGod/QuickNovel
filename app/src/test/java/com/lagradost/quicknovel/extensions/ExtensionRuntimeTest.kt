package com.lagradost.quicknovel.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtensionRuntimeTest {
    private fun installedBundle(contents: String): Pair<File, InstalledExtension> {
        val file = File.createTempFile("quicknovel-extension-", ".js")
        file.writeText(contents)
        return file to InstalledExtension(
            id = "test.extension",
            name = "Test extension",
            site = "https://reader.example/",
            version = "1.0.0",
            language = "en",
            url = "https://reader.example/plugin.js",
            repositoryUrl = "https://reader.example/plugins.min.json",
            bundlePath = file.absolutePath,
        )
    }

    @Test
    fun acceptsNonEmptyJavaScriptWithoutClaimingItWasExecuted() {
        val (file, extension) = installedBundle("module.exports = {};")
        try {
            val result = ExtensionRuntime.checkBundle(extension)
            assertTrue(result.isSuccess)
            val check = result.getOrThrow()
            assertEquals(file.length(), check.bytes)
            assertEquals("test.extension", check.id)
            assertEquals(null, check.syntaxValid)
            assertTrue(check.message.contains("not executed"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsAnHtmlErrorPage() {
        val (file, extension) = installedBundle("<!DOCTYPE html><html><body>Blocked</body></html>")
        try {
            val result = ExtensionRuntime.checkBundle(extension)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("HTML page"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsAnEmptyBundle() {
        val (file, extension) = installedBundle("")
        try {
            val result = ExtensionRuntime.checkBundle(extension)
            assertTrue(result.isFailure)
            assertFalse(result.isSuccess)
        } finally {
            file.delete()
        }
    }
}
