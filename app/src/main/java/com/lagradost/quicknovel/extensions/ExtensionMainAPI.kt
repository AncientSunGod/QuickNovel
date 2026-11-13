package com.lagradost.quicknovel.extensions

import android.content.Context
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.StreamResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
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
    private val context: Context? = null,
    private val testFetchOverride: ((String) -> ExtensionHttpClient.TextResponse)? = null,
    private val testRequestOverride: ((String, String, Map<String, String>, String?) -> ExtensionHttpClient.TextResponse)? = null,
    providerName: String = extension.name,
    private val operationOverride: ((String, JSONArray) -> String?)? = null,
) : MainAPI() {
    override val name: String = providerName
    override val mainUrl: String = extension.site.trimEnd('/')
    override val lang: String = normalizeLanguage(extension.language)
    private val imageRequestHeaders: Map<String, String>? by lazy {
        val raw = execute("getImageRequestInit", JSONArray()) ?: return@lazy null
        val headers = runCatching { JSONObject(raw).optJSONObject("headers") }.getOrNull()
            ?: return@lazy null
        val allowed = setOf(
            "accept", "accept-language", "authorization", "cookie", "origin",
            "referer", "referrer", "user-agent", "x-requested-with",
        )
        buildMap {
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next().trim().take(100)
                val value = headers.optString(key, "").take(2_000)
                if (key.lowercase() in allowed && value.isNotBlank() &&
                    value.none { it.code == 10 || it.code == 13 }
                ) {
                    put(key, value)
                }
            }
        }.takeIf { it.isNotEmpty() }
    }
    private val pluginFilterResponse: JSONObject by lazy {
        execute("getPluginFilters", JSONArray())?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject()
    }
    private val pluginFilters: JSONObject by lazy {
        pluginFilterResponse.optJSONObject("filters") ?: pluginFilterResponse
    }
    private val pluginFilterOrder: List<String> by lazy {
        val declaredOrder = pluginFilterResponse.optJSONArray("order")
        if (declaredOrder != null) {
            buildList {
                for (index in 0 until declaredOrder.length()) {
                    val key = declaredOrder.optString(index).take(500)
                    if (key.isNotBlank()) add(key)
                }
            }
        } else {
            buildList {
                val keys = pluginFilters.keys()
                while (keys.hasNext()) add(keys.next())
            }.sorted()
        }
    }
    private val pickerFilters: List<Pair<String, JSONObject>> by lazy {
        pluginFilterOrder.mapNotNull { key ->
            val definition = pluginFilters.optJSONObject(key) ?: return@mapNotNull null
            if (definition.optString("type") == "Picker") key to definition else null
        }
    }
    private val genreFilter: Pair<String, JSONObject>? by lazy {
        if (pickerFilters.size > 2) return@lazy null
        pluginFilterOrder.forEach { key ->
            val definition = pluginFilters.optJSONObject(key) ?: return@forEach
            if (definition.optString("type") == "Checkbox" ||
                definition.optString("type") == "XCheckbox"
            ) return@lazy key to definition
        }
        null
    }
    override val hasMainPage: Boolean = true
    override val orderBys: List<Pair<String, String>> by lazy { filterOptions(0) }
    override val mainCategories: List<Pair<String, String>> by lazy { filterOptions(1) }
    override val tags: List<Pair<String, String>> by lazy {
        filterOptions(2).ifEmpty {
            genreFilter?.let { (key, definition) ->
                listOf("All ${definition.optString("label", key)}" to "") + filterOptions(definition)
            }.orEmpty()
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? = withContext(Dispatchers.IO) {
        val combined = linkedMapOf<String, SearchResponse>()
        var receivedAnyPage = false
        var previousPageKey: List<String>? = null

        for (page in 1..MAX_SEARCH_PAGES) {
            // Some providers expose only one page, or throw when the requested page is
            // beyond their final page. Keep valid earlier results in that case, but surface
            // a failure on the first page so the UI can explain the provider problem.
            val json = try {
                executeRequired("searchNovels", JSONArray().put(query).put(page))
            } catch (error: Exception) {
                if (receivedAnyPage) break
                throw error
            }
            receivedAnyPage = true
            val results = mapSearchItems(json)
            val pageKey = results.map { it.url }
            // Some extensions ignore the page argument. Stop instead of returning duplicates.
            if (pageKey.isNotEmpty() && pageKey == previousPageKey) break
            previousPageKey = pageKey
            for (result in results) {
                combined.putIfAbsent(result.url, result)
                if (combined.size >= MAX_SEARCH_RESULTS) break
            }
            if (results.isEmpty() || combined.size >= MAX_SEARCH_RESULTS) break
        }

        combined.values.toList()
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): HeadMainPageResponse = withContext(Dispatchers.IO) {
        val options = JSONObject()
            .put("showLatestNovels", orderBy?.contains("latest", ignoreCase = true) == true)
            .put("filters", buildFilterValues(mainCategory, orderBy, tag))
        val json = executeRequired("popularNovels", JSONArray().put(page).put(options))
        HeadMainPageResponse(
            url = mainUrl,
            list = mapSearchItems(json),
        )
    }

    private fun chapterArray(novel: JSONObject): JSONArray =
        novel.optJSONArray("chapters")
            ?: novel.optJSONArray("chapterList")
            ?: novel.optJSONArray("chapter_list")
            ?: novel.optJSONObject("data")?.optJSONArray("chapters")
            ?: novel.optJSONObject("novel")?.optJSONArray("chapters")
            ?: JSONArray()

    private fun loadAdditionalChapterPages(novelPath: String, novel: JSONObject) {
        val totalPages = novel.optInt(
            "totalPages",
            novel.optInt("totalPage", novel.optInt("total_pages", novel.optInt("pageCount", 1))),
        )
        if (totalPages <= 1) return
        require(totalPages <= MAX_CHAPTER_PAGES) {
            "Extension chapter pagination exceeds the $MAX_CHAPTER_PAGES page limit."
        }

        val combined = JSONArray()
        val firstPage = chapterArray(novel)
        for (index in 0 until firstPage.length()) {
            combined.put(firstPage.opt(index))
        }

        for (page in 2..totalPages) {
            val rawPage = execute(
                "parsePage",
                JSONArray().put(novelPath).put(page.toString()),
            ) ?: throw IllegalStateException("Extension did not return chapter page $page.")
            val pageValue = org.json.JSONTokener(rawPage).nextValue()
            val pageChapters = when (pageValue) {
                is JSONArray -> pageValue
                is JSONObject -> chapterArray(pageValue)
                else -> null
            } ?: throw IllegalStateException("Extension chapter page $page is missing its chapters array.")
            for (index in 0 until pageChapters.length()) {
                combined.put(pageChapters.opt(index))
                require(combined.length() <= MAX_TOTAL_CHAPTERS) {
                    "Extension returned more than $MAX_TOTAL_CHAPTERS chapters."
                }
            }
        }
        novel.put("chapters", combined)
    }

    private fun filterOptions(index: Int): List<Pair<String, String>> =
        pickerFilters.getOrNull(index)?.second?.let(::filterOptions).orEmpty()

    private fun filterOptions(definition: JSONObject): List<Pair<String, String>> {
        val options = definition.optJSONArray("options") ?: return emptyList()
        return buildList {
            for (optionIndex in 0 until options.length()) {
                val option = options.optJSONObject(optionIndex) ?: continue
                val label = option.optString("label").take(200)
                val value = option.optString("value").take(500)
                if (label.isNotBlank() && value.isNotBlank()) add(label to value)
            }
        }
    }

    private fun buildFilterValues(
        mainCategory: String?,
        orderBy: String?,
        tag: String?,
    ): JSONObject {
        val selections = listOf(orderBy, mainCategory, tag)
        val selectedFilters = JSONObject()
        val pickerIndexes = pickerFilters.mapIndexed { index, pair -> pair.first to index }.toMap()
        val keys = pluginFilters.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val definition = pluginFilters.optJSONObject(key) ?: continue
            val defaultValue = definition.opt("value")
            val selectedPickerValue = pickerIndexes[key]
                ?.let { selections.getOrNull(it) }
                ?.takeIf { it.isNotBlank() }
            val filterState = runCatching { JSONObject(definition.toString()) }
                .getOrDefault(JSONObject())
            when {
                selectedPickerValue != null -> filterState.put("value", selectedPickerValue)
                genreFilter?.first == key -> {
                    val selectedGenre = selections.getOrNull(2).orEmpty()
                    val type = definition.optString("type")
                    if (type == "XCheckbox") {
                        val value = (defaultValue as? JSONObject)
                            ?.let { runCatching { JSONObject(it.toString()) }.getOrNull() }
                            ?: JSONObject()
                        val include = JSONArray()
                        if (selectedGenre.isNotBlank()) include.put(selectedGenre)
                        value.put("include", include)
                        if (!value.has("exclude")) value.put("exclude", JSONArray())
                        filterState.put("value", value)
                    } else {
                        val values = JSONArray()
                        if (selectedGenre.isNotBlank()) values.put(selectedGenre)
                        filterState.put("value", values)
                    }
                }
                defaultValue != null -> filterState.put("value", defaultValue)
            }
            selectedFilters.put(key, filterState)
        }
        return selectedFilters
    }

    private fun mapSearchItems(json: String): List<SearchResponse> {
        val array = JSONArray(json)
        return buildList {
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
                        posterHeaders = imageRequestHeaders,
                    )
                )
            }
        }
    }

    override suspend fun load(url: String): StreamResponse? = withContext(Dispatchers.IO) {
        val path = extractPluginPath(url) ?: return@withContext null
        val raw = executeRequired("parseNovel", JSONArray().put(path))
        val novel = JSONObject(raw)
        loadAdditionalChapterPages(path, novel)
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
            posterHeaders = imageRequestHeaders,
        )
    }

    override suspend fun loadHtml(url: String): String? = withContext(Dispatchers.IO) {
        val path = extractPluginPath(url) ?: return@withContext null
        val result = executeRequired("parseChapter", JSONArray().put(path))
        var html = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
            ?: return@withContext null

        // LNReader customJS runs against the parsed chapter with Cheerio-style globals.
        // If an optional script uses an unsupported API, keep the original chapter readable.
        readCustomAsset(extension.customJsPath)?.let { script ->
            val transformed = ExtensionJsRuntime.execute(
                extension = extension,
                operation = "transformChapterHtml",
                argumentsJson = JSONArray().put(html).toString(),
                storageContext = context,
                customScript = script,
            ).getOrNull()?.json
            html = transformed?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
                ?: html
        }

        readCustomAsset(extension.customCssPath)?.takeIf { it.isNotBlank() }?.let { css ->
            val safeCss = css.replace(Regex("(?i)</style"), "<\\/style")
            // Keep the stylesheet in the chapter body: EPUB conversion extracts body HTML.
            html = "<div id=\"qn-extension-custom-styles\"><style>\n$safeCss\n</style></div>\n$html"
        }
        html
    }

    private fun readCustomAsset(path: String?): String? = runCatching {
        path ?: return@runCatching null
        val bundleDirectory = File(extension.bundlePath).canonicalFile.parentFile
            ?: return@runCatching null
        val assetFile = File(path).canonicalFile
        require(assetFile.parentFile == bundleDirectory) {
            "Extension custom assets must remain inside the installed extension directory."
        }
        require(assetFile.isFile && assetFile.length() in 1..(512L * 1024L)) {
            "Extension custom asset is missing or exceeds the 512 KB limit."
        }
        assetFile.readText(Charsets.UTF_8)
    }.getOrNull()

    private fun execute(operation: String, args: JSONArray): String? =
        operationOverride?.invoke(operation, args)
            ?: ExtensionJsRuntime.execute(
                extension = extension,
                operation = operation,
                argumentsJson = args.toString(),
                testFetchOverride = testFetchOverride,
                storageContext = context,
                testRequestOverride = testRequestOverride,
            ).getOrNull()?.json

    /**
     * Required provider operations must not silently turn runtime failures into empty pages.
     * Returning an empty list is valid; failing to execute the extension is not. Propagating
     * the cause lets the provider screen show a useful error instead of appearing as a blank page.
     */
    private fun executeRequired(operation: String, args: JSONArray): String {
        val result = if (operationOverride != null) {
            operationOverride.invoke(operation, args)
        } else {
            ExtensionJsRuntime.execute(
                extension = extension,
                operation = operation,
                argumentsJson = args.toString(),
                testFetchOverride = testFetchOverride,
                storageContext = context,
                testRequestOverride = testRequestOverride,
            ).getOrElse { error ->
                throw IllegalStateException(
                    "LNReader extension '" + name + "' failed during " + operation + ": " +
                        (error.message ?: error.javaClass.simpleName),
                    error,
                )
            }.json
        }
        return result ?: throw IllegalStateException(
            "LNReader extension '" + name + "' returned no result for " + operation + ".",
        )
    }

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

    private companion object {
        const val MAX_SEARCH_PAGES = 5
        const val MAX_SEARCH_RESULTS = 250
        const val MAX_CHAPTER_PAGES = 50
        const val MAX_TOTAL_CHAPTERS = 10_000
    }

    private fun stripInternalFragment(url: String): String =
        url.substringBefore("#")

    private fun JSONObject.text(key: String): String = optString(key, "").take(100_000).trim()

    private fun normalizeLanguage(language: String): String {
        // LNReader manifests include localized labels and native-script names. Strip
        // directional/formatting marks first (some Arabic labels contain an LRM prefix).
        val value = language
            .filterNot { Character.getType(it) == Character.FORMAT.toInt() }
            .trim()
            .lowercase()
        return when {
            value in setOf("english", "en-us", "en-gb", "multi") -> "en"
            value in setOf("arabic") || value.any { it in '\u0600'..'\u06ff' } -> "ar"
            value in setOf("bengali", "bangla") || value.any { it in '\u0980'..'\u09ff' } -> "bn"
            value in setOf("japanese", "日本語") ||
                value.any { it in '\u3040'..'\u30ff' } -> "ja"
            value in setOf("chinese", "simplified chinese") ||
                value.any { it in '\u4e00'..'\u9fff' } -> "zh"
            value in setOf("korean", "조선말, 한국어") ||
                value.any { it in '\uac00'..'\ud7af' } -> "ko"
            value in setOf("french", "français") -> "fr"
            value in setOf("german", "deutsch") -> "de"
            value in setOf("indonesian", "bahasa indonesia") -> "id"
            value in setOf("polish", "polski") -> "pl"
            value in setOf("portuguese", "português") -> "pt"
            value in setOf("ukrainian", "українська") ||
                value.any { it in setOf('і', 'ї', 'є', 'ґ') } -> "uk"
            value in setOf("russian", "русский") ||
                value.any { it in '\u0400'..'\u04ff' } -> "ru"
            value in setOf("spanish", "español") -> "es"
            value in setOf("thai", "ไทย") || value.any { it in '\u0e00'..'\u0e7f' } -> "th"
            value in setOf("turkish", "türkçe") -> "tr"
            value in setOf("vietnamese", "tiếng việt") -> "vi"
            value.isBlank() || value in setOf("unknown", "und", "undefined") -> "en"
            else -> value.take(2).ifBlank { "en" }
        }
    }
}
