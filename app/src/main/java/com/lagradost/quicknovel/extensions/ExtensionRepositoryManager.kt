package com.lagradost.quicknovel.extensions

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Repository metadata support for LNReader-compatible extension manifests.
 *
 * This intentionally handles repository/catalogue data only. Executing an extension
 * requires a compatible JavaScript runtime and a QuickNovel API adapter.
 */
data class ExtensionListing(
    val id: String,
    val name: String,
    val site: String,
    val version: String,
    val language: String,
    val url: String,
    val repositoryUrl: String,
)

data class InstalledExtension(
    val id: String,
    val name: String,
    val site: String,
    val version: String,
    val language: String,
    val url: String,
    val repositoryUrl: String,
    val bundlePath: String,
)

object ExtensionRepositoryManager {
    private const val PREFS = "extension_repository_manager"
    private const val REPOSITORIES_KEY = "repository_urls"
    private const val EXTENSIONS_DIR = "extensions"
    private const val MAX_BUNDLE_BYTES = 8 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024

    fun getRepositories(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(REPOSITORIES_KEY, emptySet())
            .orEmpty()
            .toList()
            .sorted()

    fun addRepository(context: Context, rawUrl: String): Result<List<String>> {
        val url = rawUrl.trim()
        if (!isValidManifestUrl(url)) {
            return Result.failure(IllegalArgumentException(
                "Enter a repository manifest URL ending in plugins.min.json."
            ))
        }
        val current = getRepositories(context)
        if (current.any { it.equals(url, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("That repository is already added."))
        }
        val updated = (current + url).distinct()
        saveRepositories(context, updated)
        return Result.success(updated)
    }

    fun removeRepository(context: Context, url: String): List<String> {
        val updated = getRepositories(context).filterNot { it == url }
        saveRepositories(context, updated)
        return updated
    }

    private fun saveRepositories(context: Context, urls: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(REPOSITORIES_KEY, urls.toSet())
            .apply()
    }

    fun isValidManifestUrl(value: String): Boolean {
        return try {
            val parsed = URL(value)
            (parsed.protocol == "https" || parsed.protocol == "http") &&
                !parsed.host.isNullOrBlank() &&
                parsed.userInfo == null &&
                parsed.path.lowercase(Locale.ROOT).endsWith("plugins.min.json")
        } catch (_: Exception) {
            false
        }
    }

    fun refresh(context: Context): Result<List<ExtensionListing>> = runCatching {
        val listings = mutableListOf<ExtensionListing>()
        getRepositories(context).forEach { repositoryUrl ->
            val connection = URL(repositoryUrl).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 12_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Cache-Control", "no-cache")
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IllegalStateException("Repository returned HTTP $status: $repositoryUrl")
                }
                require(connection.url.protocol == "https" || connection.url.protocol == "http") {
                    "Repository redirected to an unsupported protocol."
                }
                val body = connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_MANIFEST_BYTES) {
                            "Repository manifest exceeds the 2 MB limit."
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                val array = JSONArray(body)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    if (id.isBlank() || name.isBlank() || !isValidBundleUrl(url)) continue
                    listings += ExtensionListing(
                        id = id,
                        name = name,
                        site = item.optString("site").trim(),
                        version = item.optString("version").trim(),
                        language = item.optString("lang", item.optString("language", "unknown")).trim(),
                        url = url,
                        repositoryUrl = repositoryUrl,
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
        listings.distinctBy { it.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    private fun isValidBundleUrl(value: String): Boolean = runCatching {
        val parsed = URL(value)
        (parsed.protocol == "https" || parsed.protocol == "http") &&
            !parsed.host.isNullOrBlank() &&
            parsed.userInfo == null
    }.getOrDefault(false)

    /**
     * Downloads an extension bundle into private app storage. The file is not executed
     * by this method; execution must go through the dedicated runtime/adapter.
     */
    fun install(context: Context, listing: ExtensionListing): Result<InstalledExtension> = runCatching {
        require(isValidBundleUrl(listing.url)) {
            "Extension bundle must use a valid HTTP or HTTPS URL without embedded credentials."
        }
        val bundleUrl = URL(listing.url)
        require(listing.id.isNotBlank()) { "Extension ID is missing." }

        val safeId = listing.id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        require(safeId.isNotBlank()) { "Extension ID is invalid." }
        val extensionDir = File(File(context.filesDir, EXTENSIONS_DIR), safeId)
        if (!extensionDir.exists() && !extensionDir.mkdirs()) {
            throw IllegalStateException("Could not create extension storage.")
        }

        val connection = bundleUrl.openConnection() as HttpURLConnection
        val bundleBytes = try {
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/javascript, text/javascript, */*")
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("Extension download returned HTTP $status.")
            }
            require(connection.url.protocol == "https" || connection.url.protocol == "http") {
                "Extension download redirected to an unsupported protocol."
            }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BUNDLE_BYTES) {
                        throw IllegalStateException("Extension bundle exceeds the 8 MB limit.")
                    }
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        } finally {
            connection.disconnect()
        }
        require(bundleBytes.isNotEmpty()) { "Downloaded extension bundle is empty." }

        val bundleFile = File(extensionDir, "plugin.js")
        val metadataFile = File(extensionDir, "manifest.json")
        val manifest = JSONObject()
            .put("id", listing.id)
            .put("name", listing.name)
            .put("site", listing.site)
            .put("version", listing.version)
            .put("lang", listing.language)
            .put("url", listing.url)
            .put("repositoryUrl", listing.repositoryUrl)

        val tempBundle = File(extensionDir, "plugin.js.tmp")
        val tempMetadata = File(extensionDir, "manifest.json.tmp")
        val backupBundle = File(extensionDir, "plugin.js.bak")
        val backupMetadata = File(extensionDir, "manifest.json.bak")

        // Stage both new files before touching the currently installed version.
        tempBundle.writeBytes(bundleBytes)
        tempMetadata.writeText(manifest.toString())
        backupBundle.delete()
        backupMetadata.delete()

        var bundleBackedUp = false
        var metadataBackedUp = false
        try {
            if (bundleFile.exists()) {
                if (!bundleFile.renameTo(backupBundle)) {
                    throw IllegalStateException("Could not preserve the previous extension bundle.")
                }
                bundleBackedUp = true
            }
            if (metadataFile.exists()) {
                if (!metadataFile.renameTo(backupMetadata)) {
                    throw IllegalStateException("Could not preserve the previous extension metadata.")
                }
                metadataBackedUp = true
            }
            if (!tempBundle.renameTo(bundleFile)) {
                throw IllegalStateException("Could not save the new extension bundle.")
            }
            if (!tempMetadata.renameTo(metadataFile)) {
                throw IllegalStateException("Could not save the new extension metadata.")
            }
        } catch (error: Exception) {
            bundleFile.delete()
            metadataFile.delete()
            if (bundleBackedUp) backupBundle.renameTo(bundleFile)
            if (metadataBackedUp) backupMetadata.renameTo(metadataFile)
            throw error
        } finally {
            tempBundle.delete()
            tempMetadata.delete()
        }
        backupBundle.delete()
        backupMetadata.delete()

        InstalledExtension(
            id = listing.id,
            name = listing.name,
            site = listing.site,
            version = listing.version,
            language = listing.language,
            url = listing.url,
            repositoryUrl = listing.repositoryUrl,
            bundlePath = bundleFile.absolutePath,
        )
    }

    fun getInstalledExtensions(context: Context): List<InstalledExtension> {
        val root = File(context.filesDir, EXTENSIONS_DIR)
        return root.listFiles().orEmpty().mapNotNull { directory ->
            val manifestFile = File(directory, "manifest.json")
            if (!manifestFile.isFile) return@mapNotNull null
            runCatching {
                val json = JSONObject(manifestFile.readText())
                val bundle = File(directory, "plugin.js")
                if (!bundle.isFile) return@runCatching null
                InstalledExtension(
                    id = json.optString("id"),
                    name = json.optString("name"),
                    site = json.optString("site"),
                    version = json.optString("version"),
                    language = json.optString("lang", "unknown"),
                    url = json.optString("url"),
                    repositoryUrl = json.optString("repositoryUrl"),
                    bundlePath = bundle.absolutePath,
                )
            }.getOrNull()
        }.filterNotNull().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun uninstall(context: Context, id: String): Boolean {
        val safeId = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
        if (safeId.isBlank()) return false
        val directory = File(File(context.filesDir, EXTENSIONS_DIR), safeId)
        return !directory.exists() || directory.deleteRecursively()
    }
}
