package com.lagradost.quicknovel.extensions

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository metadata and package management for LNReader-compatible extension manifests.
 *
 * Execution is handled separately by ExtensionJsRuntime and ExtensionMainAPI; only a
 * subset of LNReader module imports is currently supported.
 */
/**
 * Emits a lightweight invalidation whenever installed extension files change.
 * ViewModels can rebuild their provider snapshots without restarting the app.
 */
object ExtensionProviderRegistry {
    private val _revision = MutableStateFlow(0)
    val revision = _revision.asStateFlow()

    fun notifyChanged() {
        _revision.value = _revision.value + 1
    }
}

data class ExtensionListing(
    val id: String,
    val name: String,
    val site: String,
    val version: String,
    val language: String,
    val url: String,
    val repositoryUrl: String,
    val iconUrl: String? = null,
    val customJsUrl: String? = null,
    val customCssUrl: String? = null,
    val hasSettings: Boolean = false,
    val hasUpdate: Boolean = false,
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
    val iconUrl: String? = null,
    val customJsUrl: String? = null,
    val customCssUrl: String? = null,
    val hasSettings: Boolean = false,
    val hasUpdate: Boolean = false,
    val customJsPath: String? = null,
    val customCssPath: String? = null,
)

object ExtensionRepositoryManager {
    private const val PREFS = "extension_repository_manager"
    private const val REPOSITORIES_KEY = "repository_urls"
    private const val EXTENSIONS_DIR = "extensions"
    private const val MAX_BUNDLE_BYTES = 8 * 1024 * 1024
    private const val MAX_CUSTOM_ASSET_BYTES = 512 * 1024
    private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    private val extensionIdPattern = Regex("[A-Za-z0-9._-]{1,120}")

    @Volatile
    var lastRefreshWarning: String? = null
        private set

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
                !isBlockedHost(parsed.host) &&
                parsed.userInfo == null &&
                parsed.path.lowercase(Locale.ROOT).endsWith("plugins.min.json")
        } catch (_: Exception) {
            false
        }
    }

    /** IDs become directory names, so accept only a collision-free safe alphabet. */
    fun isValidExtensionId(value: String): Boolean =
        value != "." && value != ".." && extensionIdPattern.matches(value)

    fun refresh(context: Context): Result<List<ExtensionListing>> = runCatching {
        val repositories = getRepositories(context)
        val listings = mutableListOf<ExtensionListing>()
        val failures = mutableListOf<String>()
        var successfulRepositories = 0

        repositories.forEach { repositoryUrl ->
            val repositoryResult = runCatching {
                val repositoryListings = mutableListOf<ExtensionListing>()
                val connection = openValidatedConnection(
                    repositoryUrl, 12_000, 20_000, "application/json"
                )
                try {
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        throw IllegalStateException("Repository returned HTTP $status.")
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
                        val site = validOptionalHttpUrl(item.optString("site").trim()) ?: continue
                        if (!isValidExtensionId(id) || name.isBlank() || !isValidBundleUrl(url)) continue
                        repositoryListings += ExtensionListing(
                            id = id,
                            name = name,
                            site = site,
                            version = item.optString("version").trim(),
                            language = item.optString("lang", item.optString("language", "unknown")).trim(),
                            url = url,
                            repositoryUrl = repositoryUrl,
                            iconUrl = item.optString("iconUrl").trim().takeIf { it.isNotBlank() }?.let(::validOptionalHttpUrl),
                            customJsUrl = item.optString("customJS").trim().takeIf { it.isNotBlank() }?.let(::validOptionalHttpUrl),
                            customCssUrl = item.optString("customCSS").trim().takeIf { it.isNotBlank() }?.let(::validOptionalHttpUrl),
                            hasSettings = item.optBoolean("hasSettings", false),
                            hasUpdate = item.optBoolean("hasUpdate", false),
                        )
                    }
                } finally {
                    connection.disconnect()
                }
                repositoryListings
            }

            repositoryResult.onSuccess { repositoryListings ->
                successfulRepositories++
                listings.addAll(repositoryListings)
            }.onFailure { error ->
                failures += "${repositoryUrl}: ${error.message ?: "refresh failed"}"
            }
        }

        lastRefreshWarning = failures.takeIf { it.isNotEmpty() }
            ?.joinToString("; ") { it.take(300) }
            ?.take(1_000)

        if (repositories.isNotEmpty() && successfulRepositories == 0) {
            throw IllegalStateException(
                failures.firstOrNull()?.take(500)
                    ?: "Could not refresh any extension repository."
            )
        }

        listings.distinctBy { it.id }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /**
     * Follow redirects manually so every hop is checked before a request is made.
     * Only HTTP(S) URLs without credentials or obvious local IP literals are allowed.
     */
    private fun openValidatedConnection(
        initialUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        accept: String,
    ): HttpURLConnection {
        var current = URL(initialUrl)
        repeat(6) { redirectIndex ->
            require(isValidBundleUrl(current.toString())) {
                "Extension URL must be a valid public HTTP or HTTPS URL."
            }
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("Cache-Control", "no-cache")
            val status = try {
                connection.responseCode
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank()) {
                    "Remote server returned a redirect without a location."
                }
                require(redirectIndex < 5) {
                    "Remote server exceeded the 5-redirect limit."
                }
                val next = URL(current, location)
                require(!(current.protocol.equals("https", ignoreCase = true) &&
                    !next.protocol.equals("https", ignoreCase = true))) {
                    "HTTPS downloads may not redirect to HTTP."
                }
                require(isValidBundleUrl(next.toString())) {
                    "Remote redirect target is not an allowed HTTP(S) URL."
                }
                current = next
            } else {
                return connection
            }
        }
        throw IllegalStateException("Remote request could not be completed.")
    }

    /** Return only absolute HTTP(S) URLs without credentials for optional manifest assets. */
    private fun validOptionalHttpUrl(value: String): String? = runCatching {
        val parsed = URL(value)
        val host = parsed.host?.lowercase(Locale.ROOT)?.trimEnd('.').orEmpty()
        if ((parsed.protocol == "https" || parsed.protocol == "http") &&
            host.isNotBlank() && !isBlockedHost(host) &&
            parsed.userInfo == null
        ) value else null
    }.getOrNull()

    private fun isValidBundleUrl(value: String): Boolean =
        validOptionalHttpUrl(value) != null

    /**
     * Reject obvious loopback/private IP literals before any connection is opened.
     * Hostnames that resolve to private addresses and DNS rebinding are not fully
     * covered by this static check; the extension runtime has additional request checks.
     */
    private fun isBlockedHost(rawHost: String): Boolean {
        val host = rawHost.lowercase(Locale.ROOT).trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost")) return true
        val literal = host.removePrefix("[").removeSuffix("]")
        val isIpLiteral = literal.contains(':') ||
            literal.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))
        if (!isIpLiteral) return false
        val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return true
        val bytes = address.address
        val privateV4 = bytes.size == 4 && (
            (bytes[0].toInt() and 0xff) == 10 ||
            (bytes[0].toInt() and 0xff) == 127 ||
            ((bytes[0].toInt() and 0xff) == 169 && (bytes[1].toInt() and 0xff) == 254) ||
            ((bytes[0].toInt() and 0xff) == 172 && (bytes[1].toInt() and 0xff) in 16..31) ||
            ((bytes[0].toInt() and 0xff) == 192 && (bytes[1].toInt() and 0xff) == 168) ||
            ((bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xff) in 64..127) ||
            (bytes[0].toInt() and 0xff) == 0 ||
            (bytes[0].toInt() and 0xff) >= 224
        )
        val firstV6 = if (bytes.size == 16) bytes[0].toInt() and 0xff else 0
        val uniqueLocalV6 = bytes.size == 16 && (firstV6 and 0xfe) == 0xfc
        return privateV4 || uniqueLocalV6 || address.isAnyLocalAddress ||
            address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
    }

    private fun downloadCustomAsset(
        rawUrl: String,
        label: String,
    ): ByteArray {
        require(isValidBundleUrl(rawUrl)) { "$label URL is not a valid public HTTP(S) URL." }
        val connection = openValidatedConnection(
            rawUrl,
            15_000,
            30_000,
            "text/plain, application/javascript, text/javascript, text/css, */*",
        )
        return try {
            val status = connection.responseCode
            require(status in 200..299) { "$label download returned HTTP $status." }
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            require(
                contentType == null ||
                    contentType.startsWith("text/") ||
                    contentType in setOf("application/javascript", "application/x-javascript")
            ) { "$label download did not return a text asset." }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_CUSTOM_ASSET_BYTES) {
                        "$label asset exceeds the 512 KB limit."
                    }
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray().also { bytes ->
                require(bytes.isNotEmpty()) { "$label asset is empty." }
                val decoded = String(bytes, Charsets.UTF_8)
                require(!decoded.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) &&
                    !decoded.trimStart().startsWith("<html", ignoreCase = true)) {
                    "$label download returned an HTML page, not a text asset."
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads an extension bundle into private app storage. The file is not executed
     * by this method; execution must go through the dedicated runtime/adapter.
     */
    fun install(context: Context, listing: ExtensionListing): Result<InstalledExtension> = runCatching {
        require(isValidBundleUrl(listing.url)) {
            "Extension bundle must use a valid HTTP or HTTPS URL without embedded credentials."
        }
        require(isValidExtensionId(listing.id)) {
            "Extension ID must contain only letters, numbers, dots, underscores, or hyphens (maximum 120 characters)."
        }

        // Do not rewrite IDs into directory names: e.g. "a/b" and "a_b" would collide.
        val extensionDir = File(File(context.filesDir, EXTENSIONS_DIR), listing.id)
        if (!extensionDir.exists() && !extensionDir.mkdirs()) {
            throw IllegalStateException("Could not create extension storage.")
        }

        val connection = openValidatedConnection(
            listing.url, 15_000, 30_000, "application/javascript, text/javascript, */*"
        )
        val bundleBytes = try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalStateException("Extension download returned HTTP $status.")
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

        val customJsBytes = listing.customJsUrl?.let { downloadCustomAsset(it, "Custom JavaScript") }
        val customCssBytes = listing.customCssUrl?.let { downloadCustomAsset(it, "Custom CSS") }

        val bundleFile = File(extensionDir, "plugin.js")
        val metadataFile = File(extensionDir, "manifest.json")
        val customJsFile = File(extensionDir, "custom.js")
        val customCssFile = File(extensionDir, "custom.css")
        val manifest = JSONObject()
            .put("id", listing.id)
            .put("name", listing.name)
            .put("site", listing.site)
            .put("version", listing.version)
            .put("lang", listing.language)
            .put("url", listing.url)
            .put("repositoryUrl", listing.repositoryUrl)
            .put("iconUrl", listing.iconUrl)
            .put("customJS", listing.customJsUrl)
            .put("customCSS", listing.customCssUrl)
            .put("hasSettings", listing.hasSettings)
            .put("hasUpdate", listing.hasUpdate)

        val tempBundle = File(extensionDir, "plugin.js.tmp")
        val tempMetadata = File(extensionDir, "manifest.json.tmp")
        val tempCustomJs = File(extensionDir, "custom.js.tmp")
        val tempCustomCss = File(extensionDir, "custom.css.tmp")
        val backupBundle = File(extensionDir, "plugin.js.bak")
        val backupMetadata = File(extensionDir, "manifest.json.bak")
        val backupCustomJs = File(extensionDir, "custom.js.bak")
        val backupCustomCss = File(extensionDir, "custom.css.bak")

        // Stage every new asset before touching the currently installed version.
        tempBundle.writeBytes(bundleBytes)
        tempMetadata.writeText(manifest.toString())
        customJsBytes?.let(tempCustomJs::writeBytes)
        customCssBytes?.let(tempCustomCss::writeBytes)
        listOf(backupBundle, backupMetadata, backupCustomJs, backupCustomCss).forEach(File::delete)

        var bundleBackedUp = false
        var metadataBackedUp = false
        var customJsBackedUp = false
        var customCssBackedUp = false
        var newBundleInstalled = false
        var newMetadataInstalled = false
        var newCustomJsInstalled = false
        var newCustomCssInstalled = false
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
            if (customJsFile.exists()) {
                if (!customJsFile.renameTo(backupCustomJs)) {
                    throw IllegalStateException("Could not preserve the previous custom JavaScript asset.")
                }
                customJsBackedUp = true
            }
            if (customCssFile.exists()) {
                if (!customCssFile.renameTo(backupCustomCss)) {
                    throw IllegalStateException("Could not preserve the previous custom CSS asset.")
                }
                customCssBackedUp = true
            }
            if (!tempBundle.renameTo(bundleFile)) {
                throw IllegalStateException("Could not save the new extension bundle.")
            }
            newBundleInstalled = true
            if (!tempMetadata.renameTo(metadataFile)) {
                throw IllegalStateException("Could not save the new extension metadata.")
            }
            newMetadataInstalled = true
            if (customJsBytes != null) {
                if (!tempCustomJs.renameTo(customJsFile)) {
                    throw IllegalStateException("Could not save the extension's custom JavaScript asset.")
                }
                newCustomJsInstalled = true
            }
            if (customCssBytes != null) {
                if (!tempCustomCss.renameTo(customCssFile)) {
                    throw IllegalStateException("Could not save the extension's custom CSS asset.")
                }
                newCustomCssInstalled = true
            }
        } catch (error: Exception) {
            if (newBundleInstalled) bundleFile.delete()
            if (newMetadataInstalled) metadataFile.delete()
            if (newCustomJsInstalled) customJsFile.delete()
            if (newCustomCssInstalled) customCssFile.delete()
            if (bundleBackedUp) backupBundle.renameTo(bundleFile)
            if (metadataBackedUp) backupMetadata.renameTo(metadataFile)
            if (customJsBackedUp) backupCustomJs.renameTo(customJsFile)
            if (customCssBackedUp) backupCustomCss.renameTo(customCssFile)
            throw error
        } finally {
            listOf(tempBundle, tempMetadata, tempCustomJs, tempCustomCss).forEach(File::delete)
        }
        listOf(backupBundle, backupMetadata, backupCustomJs, backupCustomCss).forEach(File::delete)

        InstalledExtension(
            id = listing.id,
            name = listing.name,
            site = listing.site,
            version = listing.version,
            language = listing.language,
            url = listing.url,
            repositoryUrl = listing.repositoryUrl,
            bundlePath = bundleFile.absolutePath,
            iconUrl = listing.iconUrl,
            customJsUrl = listing.customJsUrl,
            customCssUrl = listing.customCssUrl,
            hasSettings = listing.hasSettings,
            hasUpdate = listing.hasUpdate,
            customJsPath = customJsFile.takeIf { customJsBytes != null && it.isFile }?.absolutePath,
            customCssPath = customCssFile.takeIf { customCssBytes != null && it.isFile }?.absolutePath,
        ).also { ExtensionProviderRegistry.notifyChanged() }
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
                    iconUrl = json.optString("iconUrl").takeIf { it.isNotBlank() }?.let(::validOptionalHttpUrl),
                    customJsUrl = json.optString("customJS").takeIf { it.isNotBlank() }?.let(::validOptionalHttpUrl),
                    customCssUrl = json.optString("customCSS").takeIf { it.isNotBlank() }?.let(::validOptionalHttpUrl),
                    hasSettings = json.optBoolean("hasSettings", false),
                    hasUpdate = json.optBoolean("hasUpdate", false),
                    customJsPath = File(directory, "custom.js").takeIf { it.isFile }?.absolutePath,
                    customCssPath = File(directory, "custom.css").takeIf { it.isFile }?.absolutePath,
                )
            }.getOrNull()
        }.filterNotNull().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun uninstall(context: Context, id: String): Boolean {
        if (!isValidExtensionId(id)) return false
        val directory = File(File(context.filesDir, EXTENSIONS_DIR), id)
        val removed = !directory.exists() || directory.deleteRecursively()
        if (removed) {
            ExtensionJsRuntime.clearExtensionData(context, id)
            ExtensionProviderRegistry.notifyChanged()
        }
        return removed
    }
}
