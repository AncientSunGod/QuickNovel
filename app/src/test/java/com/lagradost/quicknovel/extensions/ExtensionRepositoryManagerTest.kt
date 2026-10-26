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
}
