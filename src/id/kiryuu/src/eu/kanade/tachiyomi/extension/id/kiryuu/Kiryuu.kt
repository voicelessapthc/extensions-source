package eu.kanade.tachiyomi.extension.id.kiryuu

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

private class Genre(val name: String, val value: String)
private class GenreFilter : Filter.Group<GenreCheckBox>("Genres", getGenreList().map { GenreCheckBox(it) })
private class GenreCheckBox(genre: Genre) : Filter.CheckBox(genre.name, false) {
    val value = genre.value
}
private class StatusFilter : Filter.Select<String>("Status", arrayOf("All", "Ongoing", "Completed"))
private class TypeFilter : Filter.Select<String>(
    "Type",
    arrayOf("All", "Manga", "Manhwa", "Manhua", "Webtoon"),
)
private class OrderByFilter : Filter.Select<String>(
    "Sort by",
    arrayOf("default", "popular", "update"),
)

private fun getGenreList(): List<Genre> = listOf(
    Genre("Action", "action"),
    Genre("Adventure", "adventure"),
    Genre("Comedy", "comedy"),
    Genre("Drama", "drama"),
    Genre("Fantasy", "fantasy"),
    Genre("Romance", "romance"),
    Genre("School Life", "school-life"),
    Genre("Slice of Life", "slice-of-life"),
    Genre("Sports", "sports"),
    Genre("Supernatural", "supernatural"),
    Genre("Thriller", "thriller"),
    Genre("Webtoon", "webtoon"),
    Genre("Manhwa", "manhwa"),
    Genre("Manhua", "manhua"),
)

class Kiryuu : ParsedHttpSource() {
    override val id: Long = 3639673976007021338
    override val name = "Kiryuu"
    override val baseUrl = "https://kiryuu03.com"
    override val lang = "id"
    override val supportsLatest = true

    override val client: OkHttpClient = super.client.newBuilder()
        .rateLimit(10, 3) 
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36",
        )
        .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")

    private val AJAX_PAGE_SIZE = 24

    private fun toJsonArray(values: List<String>): String =
        values.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]")

    private fun buildAjaxRequest(
        page: Int,
        query: String = "",
        filters: FilterList = FilterList(),
        orderBy: String? = null,
    ): Request {
        val nonce = fetchSearchNonce()
        val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php?type=search_form&action=advanced_search"

        var inclusion = "OR"
        var exclusion = "OR"
        val genreInclude = mutableListOf<String>()
        val authorList = mutableListOf<String>()
        val artistList = mutableListOf<String>()
        var project = "0"
        val typeList = mutableListOf<String>()
        val statusList = mutableListOf<String>()
        var order = "desc"
        var orderByFromFilters = "default"

        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> filter.state.filter { it.state }.mapTo(genreInclude) { it.value }
                is StatusFilter -> if (filter.state != 0) statusList.add(filter.values[filter.state].lowercase(Locale.ENGLISH))
                is TypeFilter -> {
                    val typeValue = filter.values[filter.state].lowercase(Locale.ENGLISH)
                    if (typeValue != "all") typeList.add(typeValue)
                }
                is OrderByFilter -> {
                    val valStr = filter.values[filter.state]
                    orderByFromFilters = valStr
                }
                else -> {
                }
            }
        }

        val orderByVal = orderBy ?: orderByFromFilters

        val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("nonce", nonce ?: "")
            .addFormDataPart("inclusion", inclusion)
            .addFormDataPart("exclusion", exclusion)
            .addFormDataPart("page", page.toString())
            .addFormDataPart("genre", if (genreInclude.isEmpty()) "[]" else toJsonArray(genreInclude))
            .addFormDataPart("genre_exclude", "[]")
            .addFormDataPart("author", "[]")
            .addFormDataPart("artist", "[]")
            .addFormDataPart("project", project)
            .addFormDataPart("type", if (typeList.isEmpty()) "[]" else toJsonArray(typeList))
            .addFormDataPart("status", if (statusList.isEmpty()) "[]" else toJsonArray(statusList))
            .addFormDataPart("order", order)

        if (orderByVal != "default") {
            bodyBuilder.addFormDataPart("orderby", orderByVal)
        }

        bodyBuilder.addFormDataPart("query", query)

        val req = Request.Builder()
            .url(ajaxUrl)
            .post(bodyBuilder.build())
            .headers(headers)
            .build()

        return req
    }

    private fun fetchSearchNonce(): String? {
        return try {
            val resp = client.newCall(GET(baseUrl, headers)).execute()
            val doc = resp.asJsoup()
            doc.selectFirst("input[name=nonce]")?.attr("value")
                ?: doc.selectFirst("input[name=_wpnonce]")?.attr("value")
                ?: doc.selectFirst("meta[name=search_nonce]")?.attr("content")
                ?: run {
                    val scripts = doc.select("script").joinToString("\n") { it.html() }
                    val patterns = listOf(
                        Regex("""search_nonce['"]?\s*[:=]\s*['"]?([a-zA-Z0-9-_]+)['"]?"""),
                        Regex("""nonce['"]?\s*[:=]\s*['"]?([a-zA-Z0-9-_]+)['"]?"""),
                        Regex("""_wpnonce['"]?\s*[:=]\s*['"]?([a-zA-Z0-9-_]+)['"]?"""),
                    )
                    for (p in patterns) {
                        val m = p.find(scripts)
                        if (m != null) return@run m.groupValues[1]
                    }
                    null
                }
        } catch (_: Exception) {
            null
        }
    }

    // -----------------------
    // Popular / Latest / Search requests
    // -----------------------
    override fun popularMangaRequest(page: Int): Request =
        GET("$baseUrl/manga/?orderby=popular&page=$page", headers)

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/manga/?orderby=update&page=$page", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        buildAjaxRequest(page, query = query, filters = filters)

    // -----------------------
    // Listing parsing and selectors
    // -----------------------
    override fun popularMangaSelector(): String =
        "div.flex.rounded-lg.overflow-hidden, div.overflow-hidden.relative.flex.flex-col, div.overflow-hidden, article:has(a.text-base), div:has(a.text-base), div:has(a.font-medium), div:has(img.wp-post-image)"

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        val titleAnchor = element.select("a[href*=\"/manga/\"]").firstOrNull { it.text().isNotBlank() }
            ?: element.selectFirst("a[href*=\"/manga/\"]")
        val rawTitle = titleAnchor?.text()?.trim().orEmpty()
        title = rawTitle
        val href = titleAnchor?.attr("href").orEmpty()
        setUrlWithoutDomain(normalize(href))
        thumbnail_url = element.selectFirst("img.wp-post-image, img")?.absUrl("src").orEmpty()
    }

    override fun popularMangaNextPageSelector(): String = "a.next, .pagination a.next, .wp-pagenavi a"

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val elements = document.select(popularMangaSelector())
        val mangas = elements.map { popularMangaFromElement(it) }
            .filter {
                it.url.isNotBlank()
                    && !it.url.contains("novel", true)
                    && !it.url.contains("orderby=", true)
                    && !it.url.contains("page=", true)
                    && it.title.isNotBlank()
                    && it.title != "1"
            }
            .distinctBy { it.url }

        val hasNextFromDom = document.select(popularMangaNextPageSelector()).isNotEmpty()
        val hasNext = if (hasNextFromDom) true else mangas.size >= AJAX_PAGE_SIZE

        mangas.take(6).forEach { println("Kiryuu: ajax sample -> title='${it.title}' href='${it.url}'") }
        return MangasPage(mangas, hasNext)
    }

    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun latestUpdatesFromElement(element: Element) = popularMangaFromElement(element)
    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()
    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()
    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun getFilterList(): FilterList = FilterList(
        GenreFilter(),
        StatusFilter(),
        TypeFilter(),
        OrderByFilter(),
    )

    override fun mangaDetailsParse(document: Document): SManga {
        val el = document.selectFirst("article > section, .entry-content, .post, .manga-detail, .manga-info") ?: document
        return SManga.create().apply {
            title = el.selectFirst("h1[itemprop=name], h1.entry-title, h1.post-title, h1")?.text()?.trim().orEmpty()
            thumbnail_url = el.selectFirst("img.wp-post-image, .post-thumbnail img, .cover img, .thumb img")?.absUrl("src").orEmpty()
            val syn = document.selectFirst("#tabpanel-description div[data-show='true']")?.text()
                ?: document.selectFirst("#tabpanel-description div[data-show='false']")?.text()
                ?: document.selectFirst(".summary, .entry-summary, .manga-desc")?.text()
            description = syn?.trim().orEmpty()
            genre = el.select("a[itemprop=genre], .genres a, .post-tags a, .tags a").joinToString { it.text() }
            author = el.selectFirst("a[rel=author], .author")?.text()?.replace("Author:", "", true)?.trim().orEmpty()
            val st = el.selectFirst("li:contains(Status), .status, .manga-status")?.text().orEmpty()
            status = when {
                st.contains("ongoing", true) || st.contains("update", true) -> SManga.ONGOING
                st.contains("complete", true) || st.contains("completed", true) -> SManga.COMPLETED
                st.contains("hiatus", true) -> SManga.ON_HIATUS
                st.contains("cancel", true) || st.contains("canceled", true) -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
        }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = Observable.fromCallable {
        val detailsResp = client.newCall(mangaDetailsRequest(manga)).execute()
        val detailsDoc = detailsResp.asJsoup()
        val ajaxSource = detailsDoc.selectFirst("#chapter-list, #gallery-list")
        val ajaxUrlCandidate = ajaxSource?.attr("hx-get")?.trim()

        val ajaxUrl = if (ajaxUrlCandidate != null && ajaxUrlCandidate.contains("chapter_list", ignoreCase = true)) {
            ajaxUrlCandidate
        } else {
            null
        }

        if (ajaxUrl != null) {
            val response = client.newCall(GET(ajaxUrl, headers)).execute()
            val ajaxDoc = response.asJsoup()

            val links = ajaxDoc.select("a[href*='/chapter-']").distinctBy { it.attr("href") }

            val chapters = links.map { el ->
                SChapter.create().apply {
                   
                    val href = el.attr("href")
                    setUrlWithoutDomain(href)

                    
                    val titleClone = el.clone()
                    
                    titleClone.select("time, .date, .chapter-date, .chapter-time, span[class*=date]").remove()
                    name = titleClone.text().trim()

                   
                    val timeEl = el.selectFirst("time")
                    date_upload = tryParseDate(timeEl?.attr("datetime") ?: timeEl?.text())
                }
            }

            return@fromCallable chapters
        } else {
            throw Exception("Could not find chapter_list endpoint")
        }
    }

    override fun chapterFromElement(element: Element): SChapter = throw UnsupportedOperationException("Not used")
    override fun chapterListSelector(): String = throw UnsupportedOperationException("Not used")

    override fun pageListParse(document: Document): List<Page> {
        val selectors = listOf(
            "main .relative section img",
            ".reading-content img",
            ".chapter-content img",
            "img.alignnone",
            ".wp-manga-reader img",
            ".entry-content img",
            ".post-content img",
        )
        for (sel in selectors) {
            val imgs = document.select(sel)
            if (imgs.isNotEmpty()) {
                val pages = imgs.mapIndexedNotNull { i, img ->
                    val url = img.absUrl("src").ifEmpty { img.attr("src").trim() }
                    if (url.isNotBlank()) Page(i, imageUrl = url) else null
                }
                try {
                    println("Kiryuu: pageListParse found ${pages.size} images, first=${pages.firstOrNull()?.imageUrl}")
                } catch (_: Throwable) {
                }
                if (pages.isNotEmpty()) return pages
            }
        }
        val scriptText = document.select("script").joinToString("\n") { it.html() }
        val regex = "\"(https?://[^\"]+\\.(?:jpg|jpeg|png|webp))\"".toRegex(RegexOption.IGNORE_CASE)
        val found = regex.findAll(scriptText).map { it.groupValues[1] }.toList()
        if (found.isNotEmpty()) return found.mapIndexed { i, u -> Page(i, imageUrl = u) }
        val nos = document.select("noscript img")
        if (nos.isNotEmpty()) return nos.mapIndexed { i, img -> Page(i, imageUrl = img.absUrl("src").ifEmpty { img.attr("src") }) }
        throw Exception("No images found for chapter")
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    private fun normalize(href: String): String {
        val h = href.trim()
        if (h.isEmpty()) return ""
        return if (h.startsWith(baseUrl)) h.removePrefix(baseUrl) else h
    }

    private fun tryParseDate(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return try {
            Instant.parse(s).toEpochMilli()
        } catch (_: Exception) {
            try {
                dateFormat.parse(s)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }

    companion object {
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
