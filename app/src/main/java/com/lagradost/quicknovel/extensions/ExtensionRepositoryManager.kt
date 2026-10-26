package com.lagradost.quicknovel.extensions

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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

object ExtensionRepositoryManager {
    private const val PREFS = "extension_repository_manager"
    private const val REPOSITORIES_KEY = "repository_urls"

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
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val array = JSONArray(body)
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    if (id.isBlank() || name.isBlank() || url.isBlank()) continue
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
}
