package com.lagradost.quicknovel.providers

import android.net.Uri
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrlNull
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
        val url = "$apiBase/novels?page=1&status=all&genre=&keyword=$encoded"
        val json = getJson(url)
        return parseNovelItems(json.optJSONArray("items") ?: JSONArray())
    }

    private fun novelIdFromUrl(url: String): String {
        return url.substringBefore("?").trimEnd('/').substringAfterLast('/')
    }

    private suspend fun getChapters(novelId: String): List<ChapterData> {
        val json = getJson("$apiBase/novels/$novelId/chapters?sort=asc")
        val items = json.optJSONArray("items") ?: JSONArray()
        val chapters = mutableListOf<ChapterData>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val chapterId = item.stringOrNull("chapter_id") ?: continue
            val title = item.stringOrNull("chapter_name") ?: "Chapter ${index + 1}"
            chapters += newChapterData(title, "$apiBase/novels/$novelId/chapters/$chapterId")
        }
        return chapters
    }

    override suspend fun load(url: String): LoadResponse {
        val novelId = novelIdFromUrl(url)
        if (novelId.isBlank()) throw ErrorLoadingException("NovelArrow novel ID not found")

        val json = getJson("$apiBase/novels/$novelId")
        val info = json.optJSONObject("item")?.optJSONObject("novelInfo")
            ?: throw ErrorLoadingException("NovelArrow novel details were not found")

        val title = info.stringOrNull("novel_name")
            ?: throw ErrorLoadingException("NovelArrow title was not found")
        val chapters = getChapters(novelId)
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

    override suspend fun loadHtml(url: String): String? {
        val chapterPath = url.substringBefore("?").removePrefix("$apiBase/")
        val parts = chapterPath.split("/")
        if (parts.size < 4 || parts[0] != "novels" || parts[2] != "chapters") {
            throw ErrorLoadingException("Unrecognised NovelArrow chapter URL")
        }
        val novelId = parts[1]
        val chapterId = parts[3]
        val json = getJson("$apiBase/novels/$novelId/chapters/$chapterId")
        val content = json.optJSONObject("item")
            ?.optJSONObject("chapterInfo")
            ?.stringOrNull("chapter_content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        return content ?: throw ErrorLoadingException("NovelArrow chapter content was empty")
    }
}
