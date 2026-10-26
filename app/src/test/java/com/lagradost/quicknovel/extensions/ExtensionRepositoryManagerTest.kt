package com.lagradost.quicknovel.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionRepositoryManagerTest {
    @Test
    fun acceptsHttpManifestUrls() {
        assertTrue(
            ExtensionRepositoryManager.isValidManifestUrl(
                "https://raw.githubusercontent.com/example/repo/main/plugins.min.json"
            )
        )
        assertTrue(
            ExtensionRepositoryManager.isValidManifestUrl(
                "http://localhost:8080/plugins.min.json"
            )
        )
        assertTrue(
            ExtensionRepositoryManager.isValidManifestUrl(
                "https://example.org/path/PLUGINS.MIN.JSON"
            )
        )
    }

    @Test
    fun rejectsUnsupportedOrMalformedManifestUrls() {
        assertFalse(
            ExtensionRepositoryManager.isValidManifestUrl(
                "file:///tmp/plugins.min.json"
            )
        )
        assertFalse(
            ExtensionRepositoryManager.isValidManifestUrl(
                "javascript:alert(1)"
            )
        )
        assertFalse(
            ExtensionRepositoryManager.isValidManifestUrl(
                "https://user:password@example.org/plugins.min.json"
            )
        )
        assertFalse(
            ExtensionRepositoryManager.isValidManifestUrl(
                "https://example.org/plugins.json"
            )
        )
        assertFalse(
            ExtensionRepositoryManager.isValidManifestUrl(
                "not a url"
            )
        )
    }

    @Test
    fun acceptsSafeExtensionIdsWithoutRewritingThem() {
        assertTrue(ExtensionRepositoryManager.isValidExtensionId("com.example.reader"))
        assertTrue(ExtensionRepositoryManager.isValidExtensionId("reader_2-beta"))
        assertTrue(ExtensionRepositoryManager.isValidExtensionId("A"))
        assertTrue(ExtensionRepositoryManager.isValidExtensionId("a".repeat(120)))
    }

    @Test
    fun rejectsUnsafeOrOverlongExtensionIds() {
        assertFalse(ExtensionRepositoryManager.isValidExtensionId("../reader"))
        assertFalse(ExtensionRepositoryManager.isValidExtensionId("reader/other"))
        assertFalse(ExtensionRepositoryManager.isValidExtensionId("reader\\other"))
        assertFalse(ExtensionRepositoryManager.isValidExtensionId("reader:other"))
        assertFalse(ExtensionRepositoryManager.isValidExtensionId(""))
        assertFalse(ExtensionRepositoryManager.isValidExtensionId("."))
        assertFalse(ExtensionRepositoryManager.isValidExtensionId(".."))
        assertFalse(ExtensionRepositoryManager.isValidExtensionId("a".repeat(121)))
    }
}
