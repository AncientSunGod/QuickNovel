package com.lagradost.quicknovel.providers

import android.net.Uri
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.USER_AGENT
import com.lagradost.quicknovel.UserReview
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newReview
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.providers.LibReadProvider.ChaptersResponse
import com.lagradost.quicknovel.providers.LibReadProvider.LibReadCommentsResponse
import com.lagradost.quicknovel.setStatus
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal data class FreeWebNovelTocAjaxPage(
    val html: String,
    val totalPages: Int,
)

/**
 * FreeWebNovel's current AJAX chapter list returns JSON with "html" and "totalPage".
 * Older mirrors sometimes return the HTML fragment directly or wrap it without "code".
 */
internal fun parseFreeWebNovelTocAjaxResponse(responseText: String): FreeWebNovelTocAjaxPage {
    val trimmed = responseText.trim()
    if (trimmed.isBlank()) return FreeWebNovelTocAjaxPage("", 1)

    val json = runCatching { JSONObject(trimmed) }.getOrNull()
        ?: return FreeWebNovelTocAjaxPage(trimmed, 1)
    val payload = json.optJSONObject("data")
        ?: json.optJSONObject("result")
        ?: json
    val html = sequenceOf(
        payload.optString("html", ""),
        payload.optString("content", ""),
        json.optString("html", ""),
        json.optString("data", ""),
    ).firstOrNull { it.isNotBlank() }.orEmpty()
    val totalPages = payload.optInt(
        "totalPage",
        payload.optInt("totalPages", json.optInt("totalPage", json.optInt("totalPages", 1)))
    ).coerceAtLeast(1)
    return FreeWebNovelTocAjaxPage(html, totalPages)
}

/** Build the paginated AJAX URL used by the LNReader ReadNovelFull multisrc source. */
internal fun buildFreeWebNovelAjaxChapterPageUrl(
    pageUrl: String,
    page: Int,
    pageSize: Int = 40,
): String {
    // Keep this URL builder JVM-testable: android.net.Uri is a framework stub in
    // local unit tests and throws "Method parse not mocked" outside an Android runtime.
    // The dynamic query values below are integers, so they need no additional encoding.
    val uri = java.net.URI(pageUrl)
    return buildString {
        append(uri.scheme)
        append("://")
        append(uri.rawAuthority)
        append(uri.rawPath.orEmpty())
        append("?ajax=chapters&page=")
        append(page.coerceAtLeast(1))
        append("&pageSize=")
        append(pageSize.coerceIn(1, 100))
    }
}

/** Parse chapter links from the novel page or the chapter-archive fragment. */
internal fun parseFreeWebNovelTocLinks(
    document: Document,
    siteRoot: String = "https://freewebnovel.com",
    includeOptions: Boolean = false,
): List<ChapterData> {
    val selector = if (includeOptions) {
        "a[href], option[value]"
    } else {
        "ul#idData a[href], #list-chapter ul.ul-list5 a.con[href]"
    }
    return document.select(selector).mapNotNull { element ->
        val rawPath = when (element.normalName()) {
            "a" -> element.attr("href").trim()
            "option" -> element.attr("value").trim()
            else -> ""
        }.takeIf { it.isNotBlank() } ?: return@mapNotNull null

        // LNReader's Free Web Novel bundle resolves opaque paths from the source root.
        val chapterUrl = when {
            rawPath.startsWith("https://", ignoreCase = true) ||
                rawPath.startsWith("http://", ignoreCase = true) -> rawPath
            else -> "${siteRoot.trimEnd('/')}/${rawPath.trimStart('/')}"
        }
        val title = element.attr("title").trim().ifBlank { element.text().trim() }
        if (title.isBlank()) return@mapNotNull null
        ChapterData(name = title, url = chapterUrl, dateOfRelease = null)
    }.distinctBy { it.url }
}

/** Resolve the article identifier used by the current chapterlist.php endpoint. */
internal fun extractFreeWebNovelArticleId(document: Document): String? {
    val directId = sequenceOf(
        document.selectFirst("#indexListPage")?.attr("data-novel-id"),
        document.selectFirst("#indexListPage")?.attr("data-articleid"),
        document.selectFirst("a.set-case.add")?.attr("data-articleid"),
        document.selectFirst("[data-novel-id]")?.attr("data-novel-id"),
        document.selectFirst("[data-articleid]")?.attr("data-articleid"),
    ).firstOrNull { !it.isNullOrBlank() }
    if (!directId.isNullOrBlank()) return directId

    return document.select("script").firstNotNullOfOrNull { script ->
        Regex("""sourceid\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
            .find(script.data().ifBlank { script.html() })
            ?.groupValues
            ?.getOrNull(1)
    }
}

/** Extract the chapter-pagination count emitted by FreeWebNovel's page script. */
internal fun extractFreeWebNovelTotalChapters(document: Document): Int? {
    document.selectFirst("#indexListPage")?.attr("data-total-chapters")
        ?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }

    return document.select("script").firstNotNullOfOrNull { script ->
        Regex("""totalChapters:\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(script.data().ifBlank { script.html() })
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
    }
}

internal fun extractFreeWebNovelImageArticleId(imageUrl: String): String? =
    Regex("""(\d+)s\.(?:jpe?g|png|webp)""", RegexOption.IGNORE_CASE)
        .find(imageUrl)
        ?.groupValues
        ?.getOrNull(1)

/** The upstream source prefers its Disqus identifier for the archive's acode. */
internal fun extractFreeWebNovelAcode(document: Document, fallback: String): String =
    document.selectFirst("[data-disqus-identifier]")
        ?.attr("data-disqus-identifier")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: fallback

open class FreewebnovelProvider : MainAPI() {
    private companion object { const val MAX_TOC_PAGES = 200 }
    override val name = "FreeWebNovel"
    override val mainUrl = "https://freewebnovel.com"
    override val iconId = R.drawable.icon_freewebnovel
    override val iconBackgroundId = R.color.wuxiaWorldOnlineColor
    open val secondUrl = mainUrl
    override val hasMainPage = true
    open val removeHtml = true // because the two sites use .html or not for no reason
    override val hasReviews = true
    override val usesCloudFlareKiller = true
    override val mainCategories = listOf(
        "All" to "",
        "Completed" to "completed"
    )
    override val tags = listOf(
        "All" to "",
        "Action" to "Action",
        "Adventure" to "Adventure",
        "Comedy" to "Comedy",
        "Drama" to "Drama",
        "Eastern" to "Eastern",
        "Fantasy" to "Fantasy",
        "Game" to "Game",
        "Gender Bender" to "Gender Bender",
        "Harem" to "Harem",
        "Historical" to "Historical",
        "Horror" to "Horror",
        "Josei" to "Josei",
        "Martial Arts" to "Martial Arts",
        "Mecha" to "Mecha",
        "Mystery" to "Mystery",
        "Psychological" to "Psychological",
        "Reincarnation" to "Reincarnation",
        "Romance" to "Romance",
        "School Life" to "School Life",
        "Sci-fi" to "Sci-fi",
        "Seinen" to "Seinen",
        "Shoujo" to "Shoujo",
        "Shounen Ai" to "Shounen Ai",
        "Shounen" to "Shounen",
        "Slice of Life" to "Slice of Life",
        "Sports" to "Sports",
        "Supernatural" to "Supernatural",
        "Tragedy" to "Tragedy",
        "Wuxia" to "Wuxia",
        "Xianxia" to "Xianxia",
        "Xuanhuan" to "Xuanhuan",
        "Yaoi" to "Yaoi"
    )

    override val orderBys = listOf(
        "Latest Release" to "latest-release",
        "Latest Novels" to "latest-novel",
        "Most Popular" to "most-popular"
    )
    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url =
            if (tag.isNullOrBlank()) "$mainUrl/sort/$orderBy/${if(mainCategory.isNullOrBlank()) "" else "$mainCategory/"}$page" else "$mainUrl/genre/$tag/${if (mainCategory.isNullOrBlank()) "" else "$mainCategory/"}$page"
        val document = app.get(url).document
        val headers = document.select("div.ul-list1.ul-list1-2.ss-custom > div.li-row")
        val returnValue = headers.mapNotNull { h ->
            val h3 = h.selectFirst("h3.tit > a") ?: return@mapNotNull null
            newSearchResponse(
                name = h3.attr("title"),
                url = h3.attr("href") ?: return@mapNotNull null
            ) {
                posterUrl = fixUrlNull(
                    h.selectFirst("picture source")
                        ?.attr("srcset")
                        ?.split(",")
                        ?.lastOrNull()
                        ?.trim()
                        ?.substringBefore(" ")
                ) ?: fixUrlNull(document.selectFirst("div.pic img")?.attr("src"))
                latestChapter = h.select("div.item")[2].selectFirst("> div > a")?.text()
            }
        }
        return HeadMainPageResponse(url, returnValue)
    }

    //used to getChapterList. It's necesary because LibRead and Freewebnovel requires different things.
    open fun getAcode(url: String): String = url.substringAfterLast("/")
    private suspend fun getPaginatedAjaxChapters(
        pageUrl: String,
        pageRoot: String,
    ): List<ChapterData> {
        val chapters = linkedMapOf<String, ChapterData>()
        var page = 1
        var totalPages = 1

        while (page <= totalPages && page <= MAX_TOC_PAGES) {
            val requestUrl = buildFreeWebNovelAjaxChapterPageUrl(pageUrl, page)
            val responseText = app.get(
                requestUrl,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            ).text
            val response = parseFreeWebNovelTocAjaxResponse(responseText)
            if (page == 1) totalPages = response.totalPages.coerceAtMost(MAX_TOC_PAGES)

            val pageChapters = parseFreeWebNovelTocLinks(
                Jsoup.parse(response.html, pageRoot),
                siteRoot = pageRoot,
                includeOptions = true,
            )
            if (pageChapters.isEmpty()) break

            pageChapters.forEach { chapter -> chapters.putIfAbsent(chapter.url, chapter) }
            page++
            if (page <= totalPages) kotlinx.coroutines.delay(150)
        }

        return chapters.values.toList()
    }

    suspend fun getChapterList(doc: Document, url: String): List<ChapterData> {
        val pageUrl = url.substringBefore("?").substringBefore("#")
        val baseUrl = pageUrl.removeSuffix("/").removeSuffix(".html")
        val pageUri = Uri.parse(pageUrl)
        val pageRoot = "${pageUri.scheme}://${pageUri.authority}"
        val acode = extractFreeWebNovelAcode(doc, getAcode(baseUrl))
        val chaptersByUrl = linkedMapOf<String, ChapterData>()

        fun parseTocPage(tocDocument: Document) {
            // The detail page may be redirected between LibRead and FreeWebNovel.
            // Resolve initial-page links against the final URL returned by the request.
            parseFreeWebNovelTocLinks(tocDocument, siteRoot = pageRoot).forEach { chapter ->
                chaptersByUrl.putIfAbsent(chapter.url, chapter)
            }
        }

        // First use the paginated AJAX contract used by the official LNReader
        // ReadNovelFull multisrc source (40 chapters per page). Fetch every page:
        // a single random-cursor request to chapterlist.php can return only a
        // fragment and is a common reason large novels appear to have 0 chapters.
        parseTocPage(doc)
        val paginatedChapters = runCatching {
            getPaginatedAjaxChapters(pageUrl, pageRoot)
        }.getOrDefault(emptyList())
        if (paginatedChapters.isNotEmpty()) return paginatedChapters

        val totalChapters = extractFreeWebNovelTotalChapters(doc)

        val articleImageUrls = listOfNotNull(
            doc.selectFirst("meta[name=image]")?.attr("content"),
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            doc.selectFirst("div.pic img")?.attr("src"),
            doc.selectFirst("picture source")?.attr("srcset")?.substringBefore(",")?.substringBefore(" "),
        )
        val novelId = extractFreeWebNovelArticleId(doc)
            ?: Regex("""(\d+)""").find(pageUrl)?.groupValues?.getOrNull(1)
            ?: articleImageUrls.firstNotNullOfOrNull { imageUrl ->
                extractFreeWebNovelImageArticleId(imageUrl)
            }

        if (!novelId.isNullOrBlank()) {
            val apiChapters = runCatching {
                val endpoint = "$secondUrl/api/chapterlist.php"
                val responseText = if (totalChapters != null && totalChapters > 0) {
                    // Mirrors the official plugin's cursor request. Do not use this
                    // fallback for titles marked Adult/Ecchi/Smut/Mature: load() rejects
                    // those genres before the chapter list is requested.
                    val chapterCursor = kotlin.random.Random.nextInt(totalChapters)
                    app.post(
                        endpoint,
                        headers = mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded",
                            "X-Requested-With" to "XMLHttpRequest",
                        ),
                        data = mapOf(
                            "aid" to novelId,
                            "acode" to acode,
                            "cid" to chapterCursor.toString(),
                        ),
                    ).text
                } else {
                    // Older pages without total-chapter metadata use the source's GET form.
                    app.get("$endpoint?aid=${Uri.encode(novelId)}").text
                }

                val fragment = parseFreeWebNovelTocAjaxResponse(responseText).html
                parseFreeWebNovelTocLinks(
                    Jsoup.parse(fragment, endpoint),
                    siteRoot = secondUrl,
                    includeOptions = true,
                )
            }.getOrDefault(emptyList())

            if (apiChapters.isNotEmpty()) return apiChapters
        }
        if (chaptersByUrl.isNotEmpty()) return chaptersByUrl.values.toList()

        // Last-resort selectors for older page layouts; keep only links belonging to
        // this novel so latest-chapter widgets and related novels cannot leak in.
        val novelPath = Uri.parse(baseUrl).path?.trimEnd('/').orEmpty()
        return doc.select(
            "a[href*=/chapter/], a[href*=chapter-], a[href*=chapter_], #chapterlist a, #chapter-list a, .chapter-list a, .list-chapter a, div.m-newest1 ul.ul-list5 a"
        ).mapNotNull { link ->
            val href = link.attr("href").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val chapterUrl = fixUrlNull(href) ?: return@mapNotNull null
            val chapterPath = Uri.parse(chapterUrl).path ?: return@mapNotNull null
            if (novelPath.isNotBlank() && !chapterPath.startsWith("$novelPath/")) {
                return@mapNotNull null
            }
            val chapterName = link.text().trim().ifBlank { link.attr("title").trim() }
            if (chapterName.isBlank()) return@mapNotNull null
            newChapterData(name = chapterName, url = chapterUrl)
        }.distinctBy { it.url }.sortedBy { chapter ->
            Regex("""chapter[-_/]?(\d+)""", RegexOption.IGNORE_CASE)
                .find(chapter.url)?.groupValues?.getOrNull(1)?.toLongOrNull()
                ?: Long.MAX_VALUE
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url)
        val document = response.document
        val name = document.selectFirst("h1.tit")?.text() ?: return null
        val genreText = document.selectFirst("span.glyphicon.glyphicon-th-list")
            ?.nextElementSiblings()?.firstOrNull()?.text().orEmpty()
        val restrictedGenres = setOf("adult", "ecchi", "smut", "mature", "explicit", "18+", "nsfw")
        if (genreText.split(",").any { it.trim().lowercase() in restrictedGenres }) {
            return null
        }
        val chaptersDataphp = getChapterList(document, response.url)
        return newStreamResponse(url = response.url, name = name, data = chaptersDataphp) {
            author = document.selectFirst("a[href^=/author/]")?.text()
                ?: document.selectFirst("div.item:has(span.glyphicon-user) a")?.text()
                ?: document.selectFirst("span[title=Author]")?.parent()?.selectFirst("a")?.text()
            tags =
                document.selectFirst("span.glyphicon.glyphicon-th-list")?.nextElementSiblings()
                    ?.get(0)
                    ?.text()
                    ?.splitToSequence(", ")?.toList()
            posterUrl = fixUrlNull(
                document.selectFirst("picture source")
                    ?.attr("srcset")
                    ?.split(",")
                    ?.lastOrNull()
                    ?.trim()
                    ?.substringBefore(" ")
            ) ?: fixUrlNull(document.selectFirst("div.pic img")?.attr("src"))
            synopsis = document.selectFirst("div.inner")?.text()
            val votes = document.selectFirst("div.m-desc > div.score > p:nth-child(2)")
            if (votes != null) {
                rating = votes.text().substringBefore('/').toFloat().times(200).toInt()
                peopleVoted = votes.text().substringAfter('(').filter { it.isDigit() }.toInt()
            }
            val statusHeader0 = document.selectFirst("span.s1.s2")
            val statusHeader = document.selectFirst("span.s1.s3")

            reviewData = document.selectFirst("a.set-case.add")?.attr("data-articleid")

            setStatus(
                statusHeader?.selectFirst("a")?.text() ?: statusHeader0?.selectFirst("a")?.text()
            )
            related = getRelated(document)
        }
    }

    fun getRelated(dc: Document): List<SearchResponse> {
        return dc.select("div.col-l > ul.ul-list6 > li").mapNotNull { element ->
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst("h3")?.text() ?: return@mapNotNull null
            newSearchResponse(
                name = title,
                url = href
            ) {
                posterUrl = fixUrlNull(
                    element.selectFirst("img")?.attr("src")
                )
            }
        }
    }

    override suspend fun loadReviews(url: String, page: Int, data: String?): List<UserReview> {
        val realUrl = "$mainUrl/api/comments.php"
        val id = data ?: return emptyList()

        val responses: List<LibReadCommentsResponse> = if (page == 1) {
            (1..4).mapNotNull { i ->
                app.post(
                    realUrl, data = mapOf(
                        "action" to "list",
                        "articleid" to id,
                        "chapterid" to "0",
                        "page" to i.toString()
                    )
                ).parsedSafe<LibReadCommentsResponse>()
            }
        } else {
            listOfNotNull(
                app.post(
                    realUrl, data = mapOf(
                        "action" to "list",
                        "articleid" to id,
                        "chapterid" to "0",
                        "page" to (page + 3).toString()
                    )
                ).parsedSafe<LibReadCommentsResponse>()
            )
        }

        return responses.flatMap { it.data?.dataList ?: emptyList() }.mapNotNull { item ->
            newReview(item.content ?: return@mapNotNull null) {
                username = item.userInfo?.nickname ?: "User"
                date = item.createdAt
                avatarUrl = item.userInfo?.picture
            }
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val document = app.get(url).document
        document.selectFirst("div.txt>.notice-text")?.remove()
        document.select(".slot-frame").remove()
        document.select("div.reader-ad-skip").remove()

        /*for (e in document.select("p")) {
            if (e.text().contains("The source of this ") || e.selectFirst("a")?.hasAttr("href") == true) {
                e.remove()
            }
        }*/
        return document.selectFirst("div.txt")?.html()
            ?.replace("New novel chapters are published on Freewebnovel.com.", "")
            ?.replace("The source of this content is Freewebnᴏvel.com.", "")
            ?.replace(
                "☞ We are moving Freewebnovel.com to Libread.com, Please visit libread.com for more chapters! ☜",
                ""
            )
    }
     override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/search?keyword=${Uri.encode(query.trim()).replace("%20","+")}",
            headers = mapOf(
                "referer" to mainUrl,
                "x-requested-with" to "XMLHttpRequest",
                "content-type" to "application/x-www-form-urlencoded",
                "accept" to "*/*",
                "user-agent" to USER_AGENT
            ),
        ).document

        return document.select("div.li-row > div.li > div.con").mapNotNull { h ->
            val h3 = h.selectFirst("div.txt > h3.tit > a") ?: return@mapNotNull null

            newSearchResponse(
                name = h3.attr("title") ?: return@mapNotNull null,
                url = h3.attr("href") ?: return@mapNotNull null
            ) {
                posterUrl = fixUrlNull(h.selectFirst("div.pic img")?.attr("src"))
                //latestChapter = h.select("div.item")[2].selectFirst("> div > a")?.text()
            }
        }
    }
}

/*
class FreewebnovelProvider : MainAPI() {
    override val name = "FreeWebNovel"
    override val mainUrl = "https://freewebnovel.com"
    override val hasMainPage = true

    override val iconId = R.drawable.icon_freewebnovel

    override val iconBackgroundId = R.color.wuxiaWorldOnlineColor

    override val tags = listOf(
        "All" to "",
        "Action" to "Action",
        "Adult" to "Adult",
        "AdventCure" to "AdventCure",
        "Comedy" to "Comedy",
        "Drama" to "Drama",
        "Ecchi" to "Ecchi",
        "Fantasy" to "Fantasy",
        //"Editor's choice" to  "Editor's choice",
        "Gender Bender" to "Gender+Bender",
        "Harem" to "Harem",
        "Historical" to "Historical",
        "Horror" to "Horror",
        "Josei" to "Josei",
        "Game" to "Game",
        "Martial Arts" to "Martial+Art",
        "Mature" to "Mature",
        "Mecha" to "Mecha",
        "Mystery" to "Mystery",
        "Psychological" to "Psychological",
        "Romance" to "Romance",
        "School Life" to "School+Life",
        "Sci-fi" to "Sci-fi",
        "Seinen" to "Seinen",
        "Shoujo" to "Shoujo",
        "Shounen Ai" to "Shounen+Ai",
        "Shounen" to "Shounen",
        "Slice of Life" to "Slice+of+Life",
        "Smut" to "Smut",
        "Sports" to "Sports",
        "Supernatural" to "Supernatural",
        "Tragedy" to "Tragedy",
        "Wuxia" to "Wuxia",
        "Xianxia" to "Xianxia",
        "Xuanhuan" to "Xuanhuan",
        "Yaoi" to "Yaoi",
        "Eastern" to "Eastern",
        "Reincarnation" to "Reincarnation",
    )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url =
            if (tag.isNullOrBlank()) "$mainUrl/latest-novel/tag/$page.html" else "$mainUrl/genre/$tag/$page.html"
        val response = app.get(url)

        val document = Jsoup.parse(response.text)

        val headers = document.select("div.ul-list1.ul-list1-2.ss-custom > div.li-row")
        if (headers.size <= 0) return HeadMainPageResponse(url, ArrayList())
        val returnValue: ArrayList<SearchResponse> = ArrayList()
        for (h in headers) {
            val h3 = h?.selectFirst("h3.tit > a")
            val cUrl = fixUrl(h3?.attr("href") ?: continue)

            val name = h3.attr("title")
            val posterUrl = h.selectFirst("div.pic > a > img")?.attr("src")

            val latestChap = h.select("div.item")[2].selectFirst("> div > a")?.text()
            returnValue.add(
                SearchResponse(
                    name,
                    cUrl,
                    fixUrlNull(posterUrl),
                    null,
                    latestChap,
                    this.name
                )
            )
        }
        return HeadMainPageResponse(url, returnValue)
    }

    override suspend fun loadHtml(url: String): String? {
        val response = app.get(url)
        val document = Jsoup.parse(
            response.text
                .replace("New novel chapters are published on Freewebnovel.com.", "")
                .replace("The source of this content is Freewebnᴏvel.com.", "")
        )
        return document.selectFirst("div.txt")?.html()
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "$mainUrl/search/",
            headers = mapOf(
                "referer" to mainUrl,
                "x-requested-with" to "XMLHttpRequest",
                "content-type" to "application/x-www-form-urlencoded",
                "user-agent" to USER_AGENT
            ),
            data = mapOf("searchkey" to query)
        )
        val document = Jsoup.parse(response.text)


        val headers = document.select("div.li-row")
        if (headers.size <= 0) return ArrayList()
        val returnValue: ArrayList<SearchResponse> = ArrayList()
        for (h in headers) {
            val h3 = h?.selectFirst("h3.tit > a")
            val cUrl = fixUrl(h3?.attr("href") ?: continue)

            val name = h3.attr("title") ?: continue
            val posterUrl = h.selectFirst("div.pic > a > img")?.attr("src")

            val latestChap = h.select("div.item")[2].selectFirst("> div > a")?.text()
            returnValue.add(
                SearchResponse(
                    name,
                    cUrl,
                    fixUrlNull(posterUrl),
                    null,
                    latestChap,
                    this.name
                )
            )
        }
        return returnValue
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url)

        val document = Jsoup.parse(response.text)
        val name = document.selectFirst("h1.tit")?.text() ?: return null

        val author =
            document.selectFirst("span.glyphicon.glyphicon-user")?.nextElementSibling()?.text()
        val tags =
            document.selectFirst("span.glyphicon.glyphicon-th-list")?.nextElementSiblings()?.get(0)
                ?.text()
                ?.splitToSequence(", ")?.toList()

        val posterUrl = document.select(" div.pic > img").attr("src")
        val synopsis = document.selectFirst("div.inner")?.text()

        val data: ArrayList<ChapterData> = ArrayList()
        val chapternumber0 = document.select("div.m-newest1 > ul.ul-list5 > li")[1]
        val chapternumber1 = chapternumber0.selectFirst("a")?.attr("href")
        val aid = "[0-9]+s.jpg".toRegex().find(response.text)?.value?.substringBefore("s")
        val acode = "(?<=r_url\" content=\"https://freewebnovel.com/)(.*)(?=/chapter)".toRegex()
            .find(response.text)?.value
        val chaptersDataphp = app.post(
            "$mainUrl/api/chapterlist.php",
            data = mapOf(
                "acode" to acode!!,
                "aid" to aid!!
            )
        )
        val parsed = Jsoup.parse(chaptersDataphp.text.replace("""\""", "")).select("option")

        for (c in parsed) {

            val cUrl = mainUrl + c?.attr("value")
            val cName = if (c.text().isEmpty()) {
                "chapter $c"
            } else {
                c.text()
            }
            data.add(ChapterData(cName, cUrl, null, null))
        }


        val statusHeader0 = document.selectFirst("span.s1.s2")
        val statusHeader = document.selectFirst("span.s1.s3")

        val status = if (statusHeader != null) {
            when (statusHeader.selectFirst("a")?.text()) {
                "OnGoing" -> STATUS_ONGOING
                "Completed" -> STATUS_COMPLETE
                else -> STATUS_NULL
            }

        } else {
            when (statusHeader0?.selectFirst("> a")?.text()) {
                "OnGoing" -> STATUS_ONGOING
                "Completed" -> STATUS_COMPLETE
                else -> STATUS_NULL
            }
        }

        var rating = 0
        var peopleVoted = 0
        try {
            rating = (document.selectFirst("div.m-desc > div.score > p:nth-child(2)")?.text()!!
                .substringBefore("/").toFloat() * 200).toInt()

            peopleVoted = document.selectFirst("div.m-desc > div.score > p:nth-child(2)")?.text()!!
                .substringAfter("(").filter { it.isDigit() }.toInt()
        } catch (e: Exception) {
            // NO RATING
        }

        return StreamResponse(
            url,
            name,
            data,
            author,
            fixUrlNull(posterUrl),
            rating,
            peopleVoted,
            null,
            synopsis,
            tags,
            status
        )
    }
}*/