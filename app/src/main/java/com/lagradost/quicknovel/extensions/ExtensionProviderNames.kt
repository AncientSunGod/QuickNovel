package com.lagradost.quicknovel.extensions

/**
 * Keeps imported providers distinct from built-in providers and from other imported
 * extensions that happen to use the same display name.
 */
internal object ExtensionProviderNames {
    fun forExtensions(
        extensions: List<InstalledExtension>,
        reservedNames: Set<String>,
    ): Map<String, String> {
        val nameCounts = extensions.groupingBy { it.name }.eachCount()
        return extensions.associate { extension ->
            val needsDisambiguation =
                extension.name in reservedNames || (nameCounts[extension.name] ?: 0) > 1
            extension.id to if (needsDisambiguation) {
                "${extension.name} (LNReader: ${extension.id})"
            } else {
                extension.name
            }
        }
    }
}
