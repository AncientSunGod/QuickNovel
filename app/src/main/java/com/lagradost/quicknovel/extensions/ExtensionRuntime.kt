package com.lagradost.quicknovel.extensions

import app.cash.quickjs.QuickJs
import java.io.File

/**
 * Thin execution boundary for downloaded extension bundles.
 *
 * Extension code is third-party code. Call this only from a worker thread, never from
 * the UI thread. LNReader's module helpers and QuickNovel's provider adapter are not
 * yet implemented; this class deliberately does not claim that arbitrary LNReader
 * extensions are compatible.
 */
object ExtensionRuntime {
    data class BundleCheck(
        val id: String,
        val name: String,
        val version: String,
        val bytes: Long,
        val syntaxValid: Boolean,
        val message: String,
    )

    /**
     * Performs a JavaScript syntax/runtime smoke check without exposing Android APIs
     * or QuickNovel internals to the bundle. It does execute the bundle, so callers
     * must run it off the main thread and only for an extension the user explicitly
     * chose to inspect.
     */
    fun checkBundle(extension: InstalledExtension): Result<BundleCheck> = runCatching {
        val file = File(extension.bundlePath)
        require(file.isFile) { "Extension bundle is missing." }
        require(file.length() in 1..(8L * 1024L * 1024L)) {
            "Extension bundle is empty or exceeds the 8 MB limit."
        }
        val source = file.readText(Charsets.UTF_8)
        require(!source.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) &&
            !source.trimStart().startsWith("<html", ignoreCase = true)) {
            "The download is an HTML page, not a JavaScript bundle."
        }

        val engine = QuickJs.create()
        try {
            // Provide only the CommonJS export placeholders. No Android or Java
            // objects are passed into the JS environment.
            engine.evaluate("var module = { exports: {} }; var exports = module.exports;")
            engine.evaluate(source)
            val inspection = engine.evaluate(
                "(function(){var p=module.exports; return JSON.stringify({" +
                    "exportType:typeof p," +
                    "exportKeys:p && (typeof p === 'object' || typeof p === 'function') ? Object.keys(p) : []," +
                    "prototypeKeys:p && p.prototype ? Object.getOwnPropertyNames(p.prototype) : []" +
                "});})()"
            )?.toString().orEmpty()

            BundleCheck(
                id = extension.id,
                name = extension.name,
                version = extension.version,
                bytes = file.length(),
                syntaxValid = true,
                message = if (inspection.isBlank()) {
                    "JavaScript executed, but no export details were returned. LNReader helpers and the QuickNovel adapter are still required."
                } else {
                    "JavaScript executed successfully. Export inspection: $inspection. This is a runtime smoke test, not proof of provider compatibility."
                },
            )
        } finally {
            engine.close()
        }
    }
}
