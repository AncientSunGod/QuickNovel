package com.lagradost.quicknovel.extensions

import java.io.File

/**
 * Validates the downloaded package without executing third-party JavaScript.
 *
 * This class deliberately performs static package checks only; it does not validate
 * JavaScript syntax or compatibility. The restricted ExtensionJsRuntime handles a
 * narrow subset of LNReader bundles separately and is not a process-isolated sandbox.
 */
object ExtensionRuntime {
    data class BundleCheck(
        val id: String,
        val name: String,
        val version: String,
        val bytes: Long,
        /** Null means JavaScript was not executed or syntax-checked. */
        val syntaxValid: Boolean?,
        val message: String,
    )

    fun checkBundle(extension: InstalledExtension): Result<BundleCheck> = runCatching {
        val file = File(extension.bundlePath)
        require(file.isFile) { "Extension bundle is missing." }
        val size = file.length()
        require(size in 1..(8L * 1024L * 1024L)) {
            "Extension bundle is empty or exceeds the 8 MB limit."
        }

        val source = file.readText(Charsets.UTF_8)
        val trimmed = source.trimStart()
        require(!trimmed.startsWith("<!DOCTYPE", ignoreCase = true) &&
            !trimmed.startsWith("<html", ignoreCase = true)) {
            "The download is an HTML page, not a JavaScript bundle."
        }
        require(source.isNotBlank()) { "Extension bundle is empty." }

        BundleCheck(
            id = extension.id,
            name = extension.name,
            version = extension.version,
            bytes = size,
            syntaxValid = null,
            message = "Package checks passed. This check does not validate JavaScript syntax or LNReader module compatibility.",
        )
    }
}
