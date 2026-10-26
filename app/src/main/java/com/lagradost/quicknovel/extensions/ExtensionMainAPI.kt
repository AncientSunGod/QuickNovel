package com.lagradost.quicknovel.extensions

import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.StreamResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Adapts the LNReader plugin operations to QuickNovel's native provider contract.
 *
 * Search results and chapters carry an internal URL fragment containing the plugin path.
 * Fragments are never sent to the website, but preserve LNReader's opaque path values so
 * a saved novel/chapter can still be loaded after the in-memory search result is gone.
 */
class ExtensionMainAPI internal constructor(
    private val extension: InstalledExtension,
    private val operationOverride: ((String, JSONArray) -> String?)? = null,
) : MainAPI() {
    override val name: String = extension.name
    override val mainUrl: String = extension.site.trimEnd('/')
    override val lang: String = normalizeLanguage(extension.language)

    override suspend fun search(query: String): List<SearchResponse>? = withContext(Dispatchers.IO) {
        val json = execute("searchNovels", JSONArray().put(query).put(1)) ?: return@withContext null
        val array = JSONArray(json)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val title = item.text("name").ifBlank { item.text("title") }
                val path = item.text("path").ifBlank { item.text("url") }
                if (title.isBlank() || path.isBlank()) continue
                val resolved = resolvePath(path)
                val realUrl = resolveHttpUrl(resolved) ?: continue
                val poster = item.text("cover").ifBlank { item.text("posterUrl") }
                    .takeIf { it.isNotBlank() }
                    ?.let(::resolveHttpUrl)
                add(
                    SearchResponse(
                        name = title,
                        url = internalUrl(realUrl, path),
                        posterUrl = poster,
                        latestChapter = item.text("latestChapter").takeIf { it.isNotBlank() },
                        apiName = name,
                    )
                )
            }
        }
    }

    override suspend fun load(url: String): StreamResponse? = withContext(Dispatchers.IO) {
        val path = extractPluginPath(url) ?: return@withContext null
        val raw = execute("parseNovel", JSONArray().put(path)) ?: return@withContext null
        val novel = JSONObject(raw)
        novel.put("url", url)
        novel.put("path", url)

        val chapters = novel.optJSONArray("chapters") ?: JSONArray()
        for (index in 0 until chapters.length()) {
            val chapter = chapters.optJSONObject(index) ?: continue
            val chapterPath = chapter.text("path").ifBlank { chapter.text("url") }
            if (chapterPath.isBlank()) continue
            val resolved = resolveHttpUrl(resolvePath(chapterPath)) ?: continue
            chapter.put("url", internalUrl(resolved, chapterPath))
        }
        ExtensionResponseMapper.parseNovel(
            json = novel.toString(),
            apiName = name,
            baseUrl = "$mainUrl/",
            requestedUrl = url,
        )
    }

    override suspend fun loadHtml(url: String): String? = withContext(Dispatchers.IO) {
        val path = extractPluginPath(url) ?: return@withContext null
        val result = execute("parseChapter", JSONArray().put(path)) ?: return@withContext null
        runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
    }

    private fun execute(operation: String, args: JSONArray): String? =
        operationOverride?.invoke(operation, args)
            ?: ExtensionJsRuntime.execute(extension, operation, args.toString())
                .getOrNull()?.json

    private fun resolvePath(path: String): String {
        if (path.startsWith("https://", true) || path.startsWith("http://", true)) return path
        // The opaque LNReader path is stored in our URL fragment below. Resolving it here
        // only needs a same-site placeholder URL; calling the JS bundle's resolveUrl once
        // per search result would re-parse/re-execute the entire bundle dozens of times.
        return runCatching {
            URI("$mainUrl/").resolve(path.trimStart('/')).normalize().toString()
        }.getOrDefault("$mainUrl/")
    }

    private fun resolveHttpUrl(value: String): String? = runCatching {
        val uri = URI(mainUrl + "/").resolve(value).normalize()
        require(uri.scheme == "http" || uri.scheme == "https")
        require(!uri.host.isNullOrBlank() && uri.userInfo == null)
        uri.toString()
    }.getOrNull()

    private fun internalUrl(realUrl: String, pluginPath: String): String {
        val encoded = URLEncoder.encode(pluginPath, StandardCharsets.UTF_8.name())
        return stripInternalFragment(realUrl) + "#qn_ext_path=" + encoded
    }

    private fun extractPluginPath(url: String): String? {
        val fragment = runCatching { URI(url).rawFragment }.getOrNull()
        if (fragment?.startsWith("qn_ext_path=") == true) {
            return runCatching {
                URLDecoder.decode(fragment.removePrefix("qn_ext_path="), StandardCharsets.UTF_8.name())
            }.getOrNull()
        }
        // Fallback for a manually opened canonical URL; this is best-effort because some
        // LNReader plugins use opaque non-URL paths.
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.path.orEmpty().trim('/')
        return path.substringAfterLast("/novels/", path).takeIf { it.isNotBlank() }
    }

    private fun stripInternalFragment(url: String): String =
        url.substringBefore("#qn_ext_path=")

    private fun JSONObject.text(key: String): String = optString(key, "").take(100_000).trim()

    private fun normalizeLanguage(language: String): String {
        val value = language.trim().lowercase()
        return when (value) {
            "english", "en-us", "en-gb" -> "en"
            "arabic" -> "ar"
            "bengali", "bangla" -> "bn"
            "chinese", "simplified chinese" -> "zh"
            "french" -> "fr"
            "german" -> "de"
            "indonesian" -> "id"
            "japanese" -> "ja"
            "korean" -> "ko"
            "portuguese" -> "pt"
            "russian" -> "ru"
            "spanish" -> "es"
            "thai" -> "th"
            "turkish" -> "tr"
            "vietnamese" -> "vi"
            "", "unknown", "und", "undefined" -> "en"
            else -> value.take(2).ifBlank { "en" }
        }
    }
}
