package com.lagradost.quicknovel.providers

import android.net.Uri
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * NovelArrow moved to the NovelPing site/API. The API currently provides
 * default/latest listings; sort modes are deliberately not advertised until
 * the service exposes distinct, verified ordering.
 */
class NovelArrowProvider : MainAPI() {
    override val name = "NovelArrow"
    override val mainUrl = "https://novelping.com"
    override val hasMainPage = true
    override val usesCloudFlareKiller = true

    private val apiBase = "$mainUrl/api-web"

    override val mainCategories = listOf(
        "All" to "all",
        "Ongoing" to "ongoing",
        "Completed" to "completed"
    )

    // The site's current API has not been verified to honour sort parameters.
    // Avoid offering Popular/Latest choices that may return identical results.
    override val orderBys = listOf("Default / latest" to "")

    override val tags = listOf(
        "All" to "",
        "Action" to "action",
        "Adventure" to "adventure",
        "Comedy" to "comedy",
        "Drama" to "drama",
        "Eastern" to "eastern",
        "Fan-fiction" to "fan-fiction",
        "Fantasy" to "fantasy",
        "Game" to "game",
        "Gender Bender" to "gender-bender",
        "Historical" to "historical",
        "Horror" to "horror",
        "Isekai" to "isekai",
        "Magic" to "magic",
        "Martial Arts" to "martial-arts",
        "Mecha" to "mecha",
        "Mystery" to "mystery",
        "Psychological" to "psychological",
        "Reincarnation" to "reincarnation",
        "Romance" to "romance",
        "School Life" to "school-life",
        "Sci-fi" to "sci-fi",
        "Seinen" to "seinen",
        "Shoujo" to "shoujo",
        "Shounen" to "shounen",
        "Slice of Life" to "slice-of-life",
        "Sports" to "sports",
        "Supernatural" to "supernatural",
        "System" to "system",
        "Tragedy" to "tragedy",
        "Wuxia" to "wuxia",
        "Xianxia" to "xianxia",
        "Xuanhuan" to "xuanhuan"
    )

    private fun JSONObject.stringOrNull(key: String): String? {
        val value = optString(key, "").trim()
        return value.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun coverUrl(id: String): String = "https://images.novelping.com/novel/$id.jpg"

    private suspend fun getJson(url: String): JSONObject {
        val response = app.get(url)
        val body = response.text
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ErrorLoadingException("NovelArrow returned an unreadable response. Open the source in WebView if verification is shown.")
        }
    }

    private fun parseNovelItems(items: JSONArray): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            // Do not surface novels explicitly classified with age-restricted genres.
            val genres = item.optJSONArray("novel_genres")
            val restrictedGenres = setOf("adult", "ecchi", "smut", "mature")
            val hasRestrictedGenre = genres?.let { values ->
                (0 until values.length()).any { genreIndex ->
                    values.optString(genreIndex).trim().lowercase() in restrictedGenres
                }
            } ?: false
            if (hasRestrictedGenre) continue

            val id = item.stringOrNull("novel_id") ?: continue
            val title = item.stringOrNull("novel_name") ?: continue
            results += newSearchResponse(title, "$mainUrl/novel/$id") {
                posterUrl = coverUrl(id)
            }
        }
        return results
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val status = mainCategory?.takeIf { it.isNotBlank() } ?: "all"
        val genre = tag?.takeIf { it.isNotBlank() } ?: ""
        val url = "$apiBase/novels?page=$page&status=${Uri.encode(status)}&genre=${Uri.encode(genre)}"
        val json = getJson(url)
        return HeadMainPageResponse(url, parseNovelItems(json.optJSONArray("items") ?: JSONArray()))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = Uri.encode(query.trim())
        val combined = linkedMapOf<String, SearchResponse>()
        var previousPageKeys: List<String>? = null

        // The API returns a small page by default. Fetch subsequent pages instead of
        // silently presenting the first ten matches as the complete search result.
        for (page in 1..MAX_SEARCH_PAGES) {
            val url = "$apiBase/novels?page=$page&status=all&genre=&keyword=$encoded"
            val json = getJson(url)
            val pageResults = parseNovelItems(json.optJSONArray("items") ?: JSONArray())
            val pageKeys = pageResults.map { it.url }

            // Some upstream responses ignore the page parameter. Stop rather than
            // repeating the same novels or making unnecessary requests.
            if (pageKeys.isNotEmpty() && pageKeys == previousPageKeys) break
            previousPageKeys = pageKeys

            pageResults.forEach { result ->
                combined.putIfAbsent(result.url, result)
            }
            if (pageResults.isEmpty() || combined.size >= MAX_SEARCH_RESULTS) break
        }

        return combined.values.take(MAX_SEARCH_RESULTS)
    }

    private fun novelIdFromUrl(url: String): String {
        return url.substringBefore("?").trimEnd('/').substringAfterLast('/')
    }

    private fun chapterItems(json: JSONObject): JSONArray? {
        val data = json.optJSONObject("data")
        val item = json.optJSONObject("item")
        return json.optJSONArray("items")
            ?: json.optJSONArray("chapters")
            ?: json.optJSONArray("data")
            ?: data?.optJSONArray("items")
            ?: data?.optJSONArray("chapters")
            ?: data?.optJSONArray("results")
            ?: data?.optJSONArray("docs")
            ?: data?.optJSONArray("list")
            ?: item?.optJSONArray("chapters")
            ?: item?.optJSONObject("novelInfo")?.optJSONArray("chapters")
    }

    private fun JSONObject.firstNonBlank(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> stringOrNull(key) }

    private suspend fun getChapters(novelId: String): List<ChapterData> {
        val combined = linkedMapOf<String, ChapterData>()
        // The site has changed the response envelope and chapter field names before.
        // Accept the common API shapes and page until the server stops returning new rows.
        for (page in 1..MAX_CHAPTER_PAGES) {
            val pageQuery = if (page == 1) "" else "&page=$page"
            val json = getJson("$apiBase/novels/$novelId/chapters?sort=asc$pageQuery")
            val items = chapterItems(json) ?: break
            if (items.length() == 0) break

            val beforePage = combined.size
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val chapterId = item.firstNonBlank(
                    "chapter_id", "chapterId", "id", "_id"
                )
                val chapterUrl = item.firstNonBlank("url", "path", "chapter_url", "chapterUrl")
                val title = item.firstNonBlank(
                    "chapter_name", "chapter_title", "title", "name"
                ) ?: "Chapter ${combined.size + 1}"
                val targetUrl = when {
                    !chapterUrl.isNullOrBlank() && chapterUrl.startsWith("http") -> chapterUrl
                    !chapterUrl.isNullOrBlank() -> "$mainUrl${if (chapterUrl.startsWith("/")) "" else "/"}$chapterUrl"
                    !chapterId.isNullOrBlank() -> "$apiBase/novels/$novelId/chapters/$chapterId"
                    else -> continue
                }
                combined.putIfAbsent(targetUrl, newChapterData(title, targetUrl))
            }

            if (combined.size == beforePage) break
            val pagination = json.optJSONObject("pagination")
                ?: json.optJSONObject("meta")
                ?: json.optJSONObject("data")?.optJSONObject("pagination")
                ?: json.optJSONObject("data")?.optJSONObject("meta")
            val totalPages = pagination?.let {
                it.optInt(
                    "total_pages",
                    it.optInt("totalPages", it.optInt("last_page", it.optInt("lastPage", 0)))
                )
            } ?: 0
            if (totalPages > 0 && page >= totalPages) break
            // Some NovelPing responses omit pagination metadata and return fewer than
            // ten chapters per page. Continue until an empty/repeated page or the cap.
        }
        return combined.values.toList()
    }

    private suspend fun getHtmlChapterFallback(info: JSONObject): List<ChapterData> {
        val slugOrUrl = info.firstNonBlank(
            "novel_slug", "slug", "url_slug", "novel_url", "novel_link", "permalink"
        )
        val bookUrl = slugOrUrl?.let { candidate ->
            when {
                candidate.startsWith("http") -> candidate
                candidate.startsWith("/") -> "$mainUrl$candidate"
                else -> "$mainUrl/book/$candidate"
            }
        }

        // NovelPing (formerly NovelBin/NovelArrow) serves its table of contents from
        // /ajax/chapter-archive?novelId=<slug>. The links are inside a <template>;
        // link text includes comment counts, so the title attribute is authoritative.
        val novelSlug = bookUrl?.let { Uri.parse(it).pathSegments.getOrNull(1) }
            ?: slugOrUrl?.takeIf { !it.contains("/") && !it.startsWith("http") }
        val archiveChapters = novelSlug?.let { slug ->
            runCatching {
                val archiveUrl = "$mainUrl/ajax/chapter-archive?novelId=${Uri.encode(slug)}"
                val archiveDocument = app.get(archiveUrl).document
                val template = archiveDocument.selectFirst("template")
                val chapterDocument = template?.let { Jsoup.parse(it.html()) } ?: archiveDocument
                chapterDocument.select("li a").mapNotNull { link ->
                    val href = link.attr("href").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val chapterUrl = if (href.startsWith("http")) href else "$mainUrl${if (href.startsWith("/")) "" else "/"}$href"
                    val title = link.attr("title").trim().ifBlank { link.text().trim() }
                    if (title.isBlank()) return@mapNotNull null
                    newChapterData(title, chapterUrl)
                }.distinctBy { it.url }
            }.getOrDefault(emptyList())
        }.orEmpty()
        if (archiveChapters.isNotEmpty()) return archiveChapters

        val document = bookUrl?.let { candidate ->
            runCatching { app.get(candidate).document }.getOrNull()
        }
        val linkedChapters = document?.select(
            "#chapter-list a, #chapterList a, .chapter-list a, .chapter-list-content a, a[href*='chapter-']"
        )?.mapNotNull { link ->
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val chapterUrl = if (href.startsWith("http")) href else "$mainUrl${if (href.startsWith("/")) "" else "/"}$href"
            val title = link.text().trim().ifBlank { link.attr("title").trim() }
            if (title.isBlank()) return@mapNotNull null
            newChapterData(title, chapterUrl)
        }?.distinctBy { it.url }.orEmpty()

        if (linkedChapters.isNotEmpty()) return linkedChapters
        val firstChapterUrl = document?.selectFirst("meta[property=og:novel:read_url]")
            ?.attr("content")
            ?.takeIf { it.startsWith("http") }
            ?: info.firstNonBlank("first_chapter_url", "firstChapterUrl", "read_url")
                ?.takeIf { it.startsWith("http") }
            ?: return emptyList()
        return listOf(newChapterData("Chapter 1", firstChapterUrl))
    }

    override suspend fun load(url: String): LoadResponse {
        val novelId = novelIdFromUrl(url)
        if (novelId.isBlank()) throw ErrorLoadingException("NovelArrow novel ID not found")

        val json = getJson("$apiBase/novels/$novelId")
        val info = json.optJSONObject("item")?.optJSONObject("novelInfo")
            ?: throw ErrorLoadingException("NovelArrow novel details were not found")

        val title = info.stringOrNull("novel_name")
            ?: throw ErrorLoadingException("NovelArrow title was not found")
        val restrictedGenres = setOf("adult", "ecchi", "smut", "mature")
        val genres = info.optJSONArray("novel_genres")
        if (genres != null && (0 until genres.length()).any { index ->
                genres.optString(index).trim().lowercase() in restrictedGenres
            }) {
            throw ErrorLoadingException("This title is not available in the reader.")
        }
        val chapters = runCatching { getChapters(novelId) }.getOrDefault(emptyList()).ifEmpty {
            runCatching { getHtmlChapterFallback(info) }.getOrDefault(emptyList())
        }
        val synopsis = info.stringOrNull("novel_desc")?.let { html ->
            val body = Jsoup.parse(html).body()
            body.select("p").joinToString("\n\n") { it.text() }.ifBlank { body.text() }
        }?.takeIf { it.isNotBlank() }

        return newStreamResponse(title, "$mainUrl/novel/$novelId", chapters) {
            author = info.stringOrNull("novel_author")
            posterUrl = coverUrl(novelId)
            this.synopsis = synopsis
            tags = info.optJSONArray("novel_genres")?.let { genres ->
                (0 until genres.length()).mapNotNull { i ->
                    genres.optString(i).takeIf { it.isNotBlank() && it != "null" }
                }
            }?.ifEmpty { null }

            when (info.optInt("novel_status", -1)) {
                0 -> setStatus("Ongoing")
                1 -> setStatus("Completed")
            }

            rating = info.optJSONObject("avgPoint")
                ?.optString("\$numberDecimal", "")
                ?.toFloatOrNull()
                ?.let { (it / 5f * 1000f).toInt().coerceIn(0, 1000) }
            peopleVoted = info.optInt("voteCount", 0).takeIf { it > 0 }
        }
    }

    private companion object {
        const val MAX_SEARCH_PAGES = 10
        const val MAX_CHAPTER_PAGES = 100
        const val MAX_SEARCH_RESULTS = 500
    }

    override suspend fun loadHtml(url: String): String? {
        val chapterPath = url.substringBefore("?").substringAfter("$apiBase/", "")
        val parts = chapterPath.split("/")
        if (parts.size >= 4 && parts[0] == "novels" && parts[2] == "chapters") {
            val novelId = parts[1]
            val chapterId = parts[3]
            val json = getJson("$apiBase/novels/$novelId/chapters/$chapterId")
            val chapterInfo = json.optJSONObject("item")?.optJSONObject("chapterInfo")
                ?: json.optJSONObject("chapterInfo")
                ?: json.optJSONObject("data")?.optJSONObject("chapterInfo")
                ?: json.optJSONObject("data")
                ?: json.optJSONObject("item")
            val content = chapterInfo?.firstNonBlank(
                "chapter_content", "chapterContent", "content", "body", "html"
            )?.trim()?.takeIf { it.isNotBlank() }
            return content ?: throw ErrorLoadingException(
                "NovelPing chapter content was empty (novel $novelId, chapter $chapterId)"
            )
        }

        // Current NovelPing book pages expose chapter content in #chr-content.
        // Keep this HTML route as a fallback for the newer /book/{slug}/chapter-* URLs.
        val document = app.get(url).document
        val content = document.selectFirst("#chr-content")
            ?: throw ErrorLoadingException("NovelPing chapter content container was not found")
        content.select("script, style, iframe, noscript, .js-ad-slot").remove()
        val html = content.html().trim()
        if (html.isBlank()) throw ErrorLoadingException("NovelPing chapter content was empty")
        return html
    }
}
