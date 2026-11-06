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
    val html = json.optString("html", "").takeIf { it.isNotBlank() }.orEmpty()
    val totalPages = json.optInt("totalPage", json.optInt("totalPages", 1)).coerceAtLeast(1)
    return FreeWebNovelTocAjaxPage(html, totalPages)
}

/** Parse the chapter-list markup used by both the initial page and AJAX fragments. */
internal fun parseFreeWebNovelTocLinks(document: Document): List<ChapterData> =
    document.select(
        "ul#idData a[href], #list-chapter ul.ul-list5 a.con[href], ul#idData.ul-list5 a.con[href]"
    ).mapNotNull { link ->
        val href = link.absUrl("href").takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val title = link.attr("title").trim().ifBlank { link.text().trim() }
        if (title.isBlank()) return@mapNotNull null
        newChapterData(title, href)
    }.distinctBy { it.url }

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
    suspend fun getChapterList(doc: Document, url: String): List<ChapterData> {
        val pageUrl = url.substringBefore("?").substringBefore("#")
        val baseUrl = pageUrl.removeSuffix("/").removeSuffix(".html")
        val acode = getAcode(baseUrl)
        val chaptersByUrl = linkedMapOf<String, ChapterData>()

        fun parseTocPage(tocDocument: Document) {
            parseFreeWebNovelTocLinks(tocDocument).forEach { chapter ->
                chaptersByUrl.putIfAbsent(chapter.url, chapter)
            }
        }

        // The official LNReader Free Web Novel source confirms this endpoint contract:
        // GET {novelUrl}?ajax=chapters&page={n}&pageSize=40 -> { html, totalPage }.
        // In particular, responses do not always include a numeric "code" field. Requiring
        // code == 200 silently discarded valid HTML and returned a 0-chapter novel.
        parseTocPage(doc)
        val pageSize = 40
        val selectedPageCount = doc.select("#indexselect option")
            .mapNotNull { it.attr("value").toIntOrNull() }
            .maxOrNull()
            ?: doc.select("#indexselect option").size.takeIf { it > 0 }
            ?: 1
        val totalChapterCount = doc.selectFirst("#indexListPage")
            ?.attr("data-total-chapters")
            ?.toIntOrNull()
            ?: doc.select("script").firstNotNullOfOrNull { script ->
                Regex("""totalChapters:\s*(\d+)""")
                    .find(script.html())?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        val scriptedPageCount = doc.select("script").firstNotNullOfOrNull { script ->
            Regex("""totalPage:\s*(\d+)""")
                .find(script.html())?.groupValues?.getOrNull(1)?.toIntOrNull()
        } ?: 1
        var totalPages = maxOf(
            selectedPageCount,
            scriptedPageCount,
            totalChapterCount?.let { (it + pageSize - 1) / pageSize } ?: 1
        ).coerceIn(1, MAX_TOC_PAGES)

        // Some current site responses include only the chapter-list container shell in
        // the novel HTML. In that case page 1 must also be fetched over AJAX; the old code
        // only fetched page 2 onward and therefore returned an empty chapter list.
        var page = if (chaptersByUrl.isEmpty()) 1 else 2
        while (page <= totalPages) {
            val ajaxUrl = "$pageUrl?ajax=chapters&page=$page&pageSize=$pageSize"
            val pageResult = runCatching {
                parseFreeWebNovelTocAjaxResponse(app.get(ajaxUrl).text)
            }.getOrNull()
            if (pageResult == null || pageResult.html.isBlank()) {
                // Stop at the first empty/unparseable page. Continuing after this point
                // merely hides a broken endpoint behind a long wait and many requests.
                if (page == 1 && chaptersByUrl.isEmpty()) break
                page++
                continue
            }
            parseTocPage(Jsoup.parse(pageResult.html, ajaxUrl))
            totalPages = maxOf(totalPages, pageResult.totalPages).coerceAtMost(MAX_TOC_PAGES)
            page++
        }
        if (chaptersByUrl.isNotEmpty()) return chaptersByUrl.values.toList()

        // Older site variants still expose the chapter list through chapterlist.php.
        val articleImageUrls = listOfNotNull(
            doc.selectFirst("meta[name=image]")?.attr("content"),
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            doc.selectFirst("div.pic img")?.attr("src"),
            doc.selectFirst("picture source")?.attr("srcset")?.substringBefore(",")?.substringBefore(" "),
        )
        val novelId = doc.selectFirst("a.set-case.add")?.attr("data-articleid")
            ?.takeIf { it.isNotBlank() }
            ?: articleImageUrls.firstNotNullOfOrNull { imageUrl ->
                Regex("""(\d+)s\.(?:jpe?g|png|webp)""", RegexOption.IGNORE_CASE)
                    .find(imageUrl)?.groupValues?.getOrNull(1)
            }

        if (novelId != null) {
            // Match the current LNReader source contract: chapterlist.php is a GET
            // endpoint with the article ID in the query string. The old POST body
            // (aid/acode/cid) is rejected by the current site and silently produced
            // an empty TOC in QuickNovel.
            val apiChapters = runCatching {
                val endpoint = "$secondUrl/api/chapterlist.php?aid=${Uri.encode(novelId)}"
                val responseText = app.get(endpoint).text
                // Some mirrors wrap the returned fragment in { html: "..." } while
                // the primary source returns the fragment directly.
                val fragment = runCatching {
                    JSONObject(responseText).optString("html").takeIf { it.isNotBlank() }
                }.getOrNull() ?: responseText
                val chapterDocument = Jsoup.parse(fragment, endpoint)
                chapterDocument.select("a[href], option[value]").mapNotNull { element ->
                    val rawPath = when (element.normalName()) {
                        "a" -> element.attr("href").trim()
                        "option" -> element.attr("value").trim()
                        else -> ""
                    }.takeIf { it.isNotBlank() } ?: return@mapNotNull null

                    val chapterUrl = when {
                        rawPath.startsWith("https://", ignoreCase = true) ||
                            rawPath.startsWith("http://", ignoreCase = true) -> rawPath
                        rawPath.startsWith("/") -> "$secondUrl$rawPath"
                        rawPath.contains("/") -> "$secondUrl/${rawPath.trimStart('/')}"
                        else -> "$secondUrl/novel/$acode/$rawPath"
                    }
                    val title = element.attr("title").trim().ifBlank { element.text().trim() }
                        .ifBlank { "Chapter ${rawPath.substringAfterLast("/")}" }
                    newChapterData(name = title, url = chapterUrl)
                }.distinctBy { it.url }
            }.getOrDefault(emptyList())
            if (apiChapters.isNotEmpty()) return apiChapters
        }

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
        val restrictedGenres = setOf("adult", "ecchi", "smut", "mature")
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