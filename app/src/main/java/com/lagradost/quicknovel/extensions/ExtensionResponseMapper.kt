package com.lagradost.quicknovel.extensions

import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.StreamResponse
import com.lagradost.quicknovel.setStatus
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import kotlin.math.roundToInt

/**
 * Converts LNReader-style JSON results into QuickNovel's native response models.
 * This class does not execute JavaScript or perform network requests; ExtensionMainAPI
 * obtains JSON from the restricted runtime/host bridge before mapping it.
 *
 * JSON contract:
 * Search: [{ "title": "...", "url": "...", "posterUrl": "...", "latestChapter": "..." }]
 * Novel: { "title": "...", "url": "...", "author": "...", "posterUrl": "...",
 *          "synopsis": "...", "tags": ["..."],
 *          "chapters": [{ "title": "...", "url": "...", "dateOfRelease": "..." }] }
 */
object ExtensionResponseMapper {
    private const val MAX_SEARCH_RESULTS = 100
    private const val MAX_CHAPTERS = 10_000
    private const val MAX_TEXT_LENGTH = 100_000

    fun parseSearchResults(
        json: String,
        apiName: String,
        baseUrl: String,
        posterHeaders: Map<String, String>? = null,
    ): List<SearchResponse> {
        val array = JSONArray(json)
        require(array.length() <= MAX_SEARCH_RESULTS) {
            "Extension returned too many search results."
        }

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val title = item.safeText("title").ifBlank { item.safeText("name") }
                val url = resolveHttpUrl(
                    baseUrl,
                    item.safeText("url").ifBlank { item.safeText("path") },
                ) ?: continue
                if (title.isBlank()) continue
                add(
                    SearchResponse(
                        name = title,
                        url = url,
                        posterUrl = item.safeText("posterUrl")
                            .ifBlank { item.safeText("cover") }
                            .takeIf { it.isNotBlank() }
                            ?.let { resolveHttpUrl(baseUrl, it) },
                        latestChapter = item.safeText("latestChapter").takeIf { it.isNotBlank() },
                        apiName = apiName,
                        posterHeaders = posterHeaders,
                    )
                )
            }
        }
    }

    fun parseNovel(
        json: String,
        apiName: String,
        baseUrl: String,
        requestedUrl: String,
        posterHeaders: Map<String, String>? = null,
    ): StreamResponse {
        val item = JSONObject(json)
        val title = item.safeText("title").ifBlank { item.safeText("name") }
        require(title.isNotBlank()) { "Extension response is missing the novel title." }
        val novelUrl = resolveHttpUrl(
            baseUrl,
            item.safeText("url").ifBlank { item.safeText("path").ifBlank { requestedUrl } },
        )
            ?: throw IllegalArgumentException("Extension returned an invalid novel URL.")

        val chaptersJson = item.optJSONArray("chapters")
            ?: item.optJSONArray("chapterList")
            ?: item.optJSONArray("chapter_list")
            ?: item.optJSONObject("data")?.optJSONArray("chapters")
            ?: item.optJSONObject("novel")?.optJSONArray("chapters")
            ?: throw IllegalArgumentException("Extension response is missing its chapter list.")
        require(chaptersJson.length() <= MAX_CHAPTERS) {
            "Extension returned too many chapters."
        }

        val chapters = buildList {
            for (index in 0 until chaptersJson.length()) {
                val chapter = chaptersJson.optJSONObject(index) ?: continue
                val chapterTitle = chapter.safeText("title")
                    .ifBlank { chapter.safeText("name") }
                    .ifBlank { chapter.safeText("chapterTitle") }
                    .ifBlank { chapter.safeText("chapter_name") }
                val chapterPath = chapter.safeText("url")
                    .ifBlank { chapter.safeText("path") }
                    .ifBlank { chapter.safeText("chapterUrl") }
                    .ifBlank { chapter.safeText("chapter_url") }
                    .ifBlank { chapter.safeText("link") }
                val chapterUrl = resolveHttpUrl(baseUrl, chapterPath) ?: continue
                if (chapterTitle.isBlank()) continue
                add(
                    ChapterData(
                        name = chapterTitle,
                        url = chapterUrl,
                        dateOfRelease = chapter.safeText("dateOfRelease")
                            .ifBlank { chapter.safeText("releaseTime") }
                            .ifBlank { chapter.safeText("date") }
                            .ifBlank { chapter.safeText("updatedAt") }
                            .takeIf { it.isNotBlank() },
                    )
                )
            }
        }

        val response = StreamResponse(
            url = novelUrl,
            name = title,
            data = chapters,
            apiName = apiName,
            author = item.safeText("author").ifBlank { item.safeText("artist") }
                .takeIf { it.isNotBlank() },
            posterUrl = item.safeText("posterUrl")
                .ifBlank { item.safeText("cover") }
                .takeIf { it.isNotBlank() }
                ?.let { resolveHttpUrl(baseUrl, it) },
            rating = item.optDouble("rating", Double.NaN)
                .takeIf { it.isFinite() && it >= 0.0 }
                ?.let { (it.coerceAtMost(5.0) * 200.0).roundToInt().coerceIn(0, 1000) },
            posterHeaders = posterHeaders,
            synopsis = item.safeText("synopsis")
                .ifBlank { item.safeText("summary") }
                .takeIf { it.isNotBlank() },
            tags = item.optJSONArray("tags").toTextList()
                ?: item.optJSONArray("genres").toTextList()
                ?: item.safeText("genres").split(",").map { it.trim() }
                    .filter { it.isNotBlank() }.takeIf { it.isNotEmpty() },
        )
        response.setStatus(item.safeText("status").ifBlank { item.safeText("releaseStatus") })
        return response
    }

    private fun JSONObject.safeText(key: String): String =
        optString(key, "").take(MAX_TEXT_LENGTH).trim()

    private fun JSONArray?.toTextList(): List<String>? {
        if (this == null) return null
        return buildList {
            for (index in 0 until minOf(length(), 100)) {
                val objectValue = optJSONObject(index)
                val value = if (objectValue != null) {
                    objectValue.safeText("name").ifBlank { objectValue.safeText("title") }
                } else {
                    optString(index, "").take(MAX_TEXT_LENGTH).trim()
                }
                if (value.isNotBlank()) add(value)
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun resolveHttpUrl(baseUrl: String, candidate: String): String? {
        if (candidate.isBlank()) return null
        return runCatching {
            val base = URI(baseUrl)
            require(base.scheme == "https" || base.scheme == "http")
            require(!base.host.isNullOrBlank())
            val resolved = base.resolve(candidate).normalize()
            require(resolved.scheme == "https" || resolved.scheme == "http")
            require(!resolved.host.isNullOrBlank())
            require(resolved.userInfo == null)
            resolved.toString()
        }.getOrNull()
    }
}
