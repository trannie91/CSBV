package com.blvietsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class BLVietsubProvider : MainAPI() {
    override var mainUrl = "https://www.blvietsub.vip"
    override var name = "BLVietsub"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "" to "BLVietsub - Mới Cập Nhật",
        "phim-bo" to "Phim Bộ Đam Mỹ",
        "phim-le" to "Phim Lẻ Đam Mỹ",
        "reels" to "Phim Dọc (Reels)",
        "thai-lan" to "BL Thái Lan",
        "trung-quoc" to "BL Trung Quốc",
        "han-quoc" to "BL Hàn Quốc",
        "nhat-ban" to "BL Nhật Bản",
        "dai-loan" to "BL Đài Loan",
        "phim-tinh-cam" to "Phim Tình Cảm",
        "phim-tam-ly" to "Phim Tâm Lý",
        "phim-thanh-xuan" to "Phim Thanh Xuân"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private fun cleanTitle(str: String): String {
        return str
            .replace(Regex("""^\[+|\]+$"""), "")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun enhancePoster(url: String): String {
        if (url.isEmpty()) return ""
        var poster = url.trim().replace("&amp;", "&")
        if (poster.contains("googleusercontent.com") || poster.contains("bp.blogspot.com")) {
            poster = poster.replace(Regex("""=w\d+(-h\d+)?(-p-k-no-nu)?"""), "=s0")
            poster = poster.replace(Regex("""=s\d+(-c)?"""), "=s0")
        }
        return fixUrl(poster)
    }

    private fun extractPoster(element: Element): String {
        val img = element.selectFirst("img")
        var poster = ""

        if (img != null) {
            val candidates = listOf(
                img.attr("data-src"),
                img.attr("data-lazy-src"),
                img.attr("data-original"),
                img.attr("data-srcset"),
                img.attr("srcset"),
                img.attr("src"),
                img.attr("scr")
            )

            for (cand in candidates) {
                val trimmed = cand.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("data:image") && !trimmed.contains("blank.gif") && !trimmed.contains("placeholder")) {
                    poster = if (trimmed.contains(",")) {
                        trimmed.split(",").lastOrNull()?.trim()?.split(" ")?.firstOrNull() ?: ""
                    } else if (trimmed.contains(" ")) {
                        trimmed.split(" ").firstOrNull() ?: ""
                    } else {
                        trimmed
                    }
                    if (poster.isNotEmpty() && !poster.startsWith("data:image")) break
                }
            }
        }

        if (poster.isEmpty() || poster.startsWith("data:image")) {
            val styleEl = element.selectFirst("[style*='background-image'], [data-bg], [data-background]")
            val bgStyle = styleEl?.attr("style") ?: ""
            val bgMatch = Regex("""url\(['"]?(.*?)['"]?\)""").find(bgStyle)?.groupValues?.get(1)
            if (!bgMatch.isNullOrEmpty()) {
                poster = bgMatch
            } else {
                poster = styleEl?.attr("data-bg")?.ifEmpty { styleEl.attr("data-background") } ?: ""
            }
        }

        return enhancePoster(poster)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val maxResults = 30
        val startIndex = (page - 1) * maxResults + 1

        val feedUrl = if (request.data.isEmpty()) {
            "$mainUrl/feeds/posts/default?alt=json&max-results=$maxResults&start-index=$startIndex"
        } else {
            "$mainUrl/feeds/posts/default/-/${request.data}?alt=json&max-results=$maxResults&start-index=$startIndex"
        }

        val items = mutableListOf<SearchResponse>()

        try {
            val responseText = app.get(
                feedUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/"
                )
            ).text

            val entries = Regex("""\{"title":\{"\$t":"([^"]+)"\}[\s\S]*?"link":\[([\s\S]*?)\][\s\S]*?"content":\{"\$t":"([\s\S]*?)"\}""").findAll(responseText).toList()

            // If manual regex found entries
            if (entries.isNotEmpty()) {
                for (entry in entries) {
                    val rawTitle = entry.groupValues[1].replace("\\\"", "\"").replace("\\/", "/")
                    val linksBlock = entry.groupValues[2]
                    val content = entry.groupValues[3].replace("\\\"", "\"").replace("\\/", "/").replace("\\n", "\n")

                    val altLinkMatch = Regex(""""rel":"alternate"[\s\S]*?"href":"([^"]+)"""").find(linksBlock)
                        ?: Regex(""""href":"([^"]+)"[\s\S]*?"rel":"alternate"""").find(linksBlock)
                    val postUrl = altLinkMatch?.groupValues?.get(1) ?: continue

                    var poster = ""
                    val imgMatch = Regex("""<img[^>]+(?:src|data-src|scr)=['"]([^'"]+)['"]""").find(content)
                    if (imgMatch != null) {
                        poster = enhancePoster(imgMatch.groupValues[1])
                    }

                    val title = cleanTitle(rawTitle)
                    val tvType = if (content.contains("phim-le") || content.contains("[br/FULL]")) TvType.Movie else TvType.AsianDrama

                    items.add(
                        newTvSeriesSearchResponse(title, postUrl, tvType) {
                            this.posterUrl = poster
                        }
                    )
                }
            } else {
                // Fallback: Parse HTML from web page
                val htmlUrl = if (request.data.isEmpty()) {
                    "$mainUrl/"
                } else {
                    "$mainUrl/search/label/${request.data}?max-results=$maxResults"
                }
                val doc = app.get(htmlUrl, headers = mapOf("User-Agent" to userAgent)).document
                val htmlItems = doc.select("a.lable-about, article.post, .all-post .item").mapNotNull { el ->
                    toSearchResult(el)
                }.distinctBy { it.url }
                items.addAll(htmlItems)
            }
        } catch (t: Throwable) {
            // HTML fallback on error
            try {
                val doc = app.get("$mainUrl/", headers = mapOf("User-Agent" to userAgent)).document
                val htmlItems = doc.select("a.lable-about, article.post").mapNotNull { el ->
                    toSearchResult(el)
                }.distinctBy { it.url }
                items.addAll(htmlItems)
            } catch (_: Throwable) {}
        }

        val distinctItems = items.distinctBy { it.url }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = distinctItems,
                    isHorizontalImages = false
                )
            ),
            hasNext = distinctItems.size >= 15
        )
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val link = if (element.tagName() == "a") element else (element.selectFirst("a[href*='blvietsub.vip']") ?: element.selectFirst("a") ?: return null)
        val href = fixUrl(link.attr("href"))

        // Must be a valid movie post URL
        if (!href.contains(".html") || !href.contains("/20")) {
            return null
        }

        val rawTitle = element.selectFirst("h3[name=title], .post-title, h2, h3, h4")?.text()?.trim()
            ?: link.attr("title").ifEmpty {
                href.split("/").lastOrNull()?.replace(".html", "")?.replace("-", " ") ?: ""
            }

        val title = cleanTitle(rawTitle)
        val poster = extractPoster(element)

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.trim().isEmpty()) return emptyList()

        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val feedUrl = "$mainUrl/feeds/posts/default?alt=json&q=$encodedQuery&max-results=30"

        val results = mutableListOf<SearchResponse>()

        try {
            val responseText = app.get(
                feedUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/"
                )
            ).text

            val entries = Regex("""\{"title":\{"\$t":"([^"]+)"\}[\s\S]*?"link":\[([\s\S]*?)\][\s\S]*?"content":\{"\$t":"([\s\S]*?)"\}""").findAll(responseText).toList()

            for (entry in entries) {
                val rawTitle = entry.groupValues[1].replace("\\\"", "\"").replace("\\/", "/")
                val linksBlock = entry.groupValues[2]
                val content = entry.groupValues[3].replace("\\\"", "\"").replace("\\/", "/").replace("\\n", "\n")

                val altLinkMatch = Regex(""""rel":"alternate"[\s\S]*?"href":"([^"]+)"""").find(linksBlock)
                    ?: Regex(""""href":"([^"]+)"[\s\S]*?"rel":"alternate"""").find(linksBlock)
                val postUrl = altLinkMatch?.groupValues?.get(1) ?: continue

                var poster = ""
                val imgMatch = Regex("""<img[^>]+(?:src|data-src|scr)=['"]([^'"]+)['"]""").find(content)
                if (imgMatch != null) {
                    poster = enhancePoster(imgMatch.groupValues[1])
                }

                val title = cleanTitle(rawTitle)
                val tvType = if (content.contains("[br/FULL]")) TvType.Movie else TvType.AsianDrama

                results.add(
                    newTvSeriesSearchResponse(title, postUrl, tvType) {
                        this.posterUrl = poster
                    }
                )
            }
        } catch (t: Throwable) {}

        // Fallback: HTML search
        if (results.isEmpty()) {
            try {
                val searchUrl = "$mainUrl/search?q=$encodedQuery"
                val doc = app.get(searchUrl, headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/"
                )).document

                val htmlResults = doc.select("a.lable-about, article.post").mapNotNull {
                    toSearchResult(it)
                }.distinctBy { it.url }

                results.addAll(htmlResults)
            } catch (_: Throwable) {}
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val targetUrl = fixUrl(url)
        val doc = app.get(targetUrl, headers = mapOf("User-Agent" to userAgent)).document

        val rawTitle = doc.selectFirst("h1.post-title, h1, .movie-title, title")?.text()?.trim() ?: return null
        val title = cleanTitle(rawTitle)

        // Poster extraction
        val thumbEl = doc.selectFirst("#thumbnail-phim, img#thumbnail-phim, .movie-content-1 img, .mvic-desc img")
        val poster = if (thumbEl != null) {
            extractPoster(thumbEl)
        } else {
            val ogImg = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content") ?: ""
            enhancePoster(ogImg)
        }

        // Main content block containing [info], [nd], and <id>
        val mainContent = doc.selectFirst("[name=main-movie-list], .movie-content-1, article")?.html() ?: doc.html()

        // Synopsis from [nd]...[/nd]
        val ndMatch = Regex("""\[nd\]([\s\S]*?)\[/nd\]""").find(mainContent)
        val description = if (ndMatch != null) {
            Jsoup.parse(ndMatch.groupValues[1]).text().trim()
        } else {
            doc.select("article p, .entry-content p")
                .filter { it.text().length > 25 && !it.text().contains("blvietsub", ignoreCase = true) }
                .joinToString("\n\n") { it.text().trim() }
                .ifEmpty { title }
        }

        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("a[href*='/search/label/']").map { it.text().trim() }.distinct()

        // Bóc tách toàn bộ tập phim và các server từ thẻ <id>...</id>
        val episodes = mutableListOf<Episode>()
        val idMatch = Regex("""<id>([\s\S]*?)</id>""").find(mainContent)
        val idBlock = idMatch?.groupValues?.get(1) ?: mainContent

        // Phân tách các cụm server [br/...]
        val sections = idBlock.split(Regex("""\[br/([^\]]+)\]"""))
        val serverNames = Regex("""\[br/([^\]]+)\]""").findAll(idBlock).map { it.groupValues[1].trim() }.toList()

        val epMap = linkedMapOf<Int, MutableMap<String, String>>()

        val parsedSections = mutableListOf<Pair<String, String>>()
        if (serverNames.isEmpty()) {
            parsedSections.add("Default" to idBlock)
        } else {
            for (i in serverNames.indices) {
                val sName = serverNames[i]
                val sContent = sections.getOrNull(i + 1) ?: ""
                parsedSections.add(sName to sContent)
            }
        }

        for ((sName, sContent) in parsedSections) {
            val lowerSName = sName.lowercase()
            val buttonMatches = Regex("""\[([^\]|*-\/]+)[|*-]([^\]]+)\]""").findAll(sContent).toList()

            for (bm in buttonMatches) {
                val rawTag = bm.groupValues[1].trim()
                val rawUrl = bm.groupValues[2].trim().replace("&amp;", "&")
                val lowerTag = rawTag.lowercase()
                val lowerUrl = rawUrl.lowercase()

                val numMatch = Regex("""\d+""").find(rawTag)
                val isNumeric = numMatch != null && lowerTag !in listOf("ss", "ok", "vk", "hx", "dl")
                val epNum = if (isNumeric) numMatch!!.value.toIntOrNull() ?: 1 else 1

                if (!epMap.containsKey(epNum)) {
                    epMap[epNum] = mutableMapOf()
                }

                when {
                    lowerTag == "ss" || lowerSName.contains("ss") || lowerUrl.contains("ssplay.net") -> {
                        epMap[epNum]?.put("ss", rawUrl)
                    }
                    lowerTag == "ok" || lowerSName.contains("ok") || lowerUrl.contains("ok.ru") -> {
                        epMap[epNum]?.put("ok", rawUrl)
                    }
                    lowerTag == "vk" || lowerSName.contains("vk") || lowerUrl.contains("vk.com") || lowerUrl.contains("vkvideo.ru") -> {
                        epMap[epNum]?.put("vk", rawUrl)
                    }
                    lowerTag == "dl" || lowerSName.contains("dl") || lowerUrl.contains("dailymotion.com") -> {
                        epMap[epNum]?.put("dl", rawUrl)
                    }
                    lowerTag == "hx" || lowerUrl.contains("abyssplayer.com") || lowerUrl.contains("abyss") -> {
                        epMap[epNum]?.put("hx", rawUrl)
                    }
                    else -> {
                        epMap[epNum]?.put("other", rawUrl)
                    }
                }
            }
        }

        if (epMap.isNotEmpty()) {
            epMap.entries.sortedBy { it.key }.forEach { (epNum, servers) ->
                val jsonParts = mutableListOf<String>()
                servers["ss"]?.let { jsonParts.add(""""ss":"${it.replace("\"", "\\\"")}"""") }
                servers["dl"]?.let { jsonParts.add(""""dl":"${it.replace("\"", "\\\"")}"""") }
                servers["ok"]?.let { jsonParts.add(""""ok":"${it.replace("\"", "\\\"")}"""") }
                servers["vk"]?.let { jsonParts.add(""""vk":"${it.replace("\"", "\\\"")}"""") }
                servers["hx"]?.let { jsonParts.add(""""hx":"${it.replace("\"", "\\\"")}"""") }
                val direct = servers["other"] ?: servers["ss"] ?: servers["ok"] ?: servers["dl"] ?: servers["vk"] ?: servers["hx"]
                direct?.let { jsonParts.add(""""direct":"${it.replace("\"", "\\\"")}"""") }
                val dataJson = "{" + jsonParts.joinToString(",") + "}"

                val epName = if (epMap.size == 1 && (title.contains("FULL", ignoreCase = true) || !title.contains("Tập", ignoreCase = true))) {
                    "Full Movie (1080p FHD)"
                } else {
                    "Tập $epNum"
                }

                episodes.add(
                    newEpisode(dataJson) {
                        this.name = epName
                        this.episode = epNum
                    }
                )
            }
        } else {
            // Fallback iframe
            val iframes = doc.select("iframe")
            iframes.forEachIndexed { index, iframe ->
                val src = iframe.attr("src").trim().replace("&amp;", "&")
                if (src.isNotEmpty() && !src.contains("blogger") && !src.contains("facebook")) {
                    val payload = """{"direct":"${src.replace("\"", "\\\"")}"}"""
                    episodes.add(
                        newEpisode(payload) {
                            this.name = "Tập ${index + 1}"
                            this.episode = index + 1
                        }
                    )
                }
            }
        }

        val distinctEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, distinctEpisodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ssUrl = Regex(""""ss"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val okUrl = Regex(""""ok"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val dlUrl = Regex(""""dl"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val vkUrl = Regex(""""vk"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val hxUrl = Regex(""""hx"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val directUrl = Regex(""""direct"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")

        var linkFound = false

        // 1. ƯU TIÊN CAO NHẤT: Máy chủ SSPlay 1080p FHD (Hỗ trợ cả /ST/ và /SU/)
        if (!ssUrl.isNullOrEmpty()) {
            try {
                val ssExtractor = SSPlayExtractor()
                ssExtractor.getUrl(ssUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (_: Throwable) {}
        }

        // 2. Máy chủ OK.ru 1080p FHD
        val effectiveOkUrl = okUrl ?: (if (dlUrl?.contains("ok.ru") == true) dlUrl else null)
        if (!effectiveOkUrl.isNullOrEmpty()) {
            try {
                val okExtractor = OkRuExtractor()
                okExtractor.getUrl(effectiveOkUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (_: Throwable) {}

            try {
                loadExtractor(effectiveOkUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (_: Throwable) {}
        }

        // 3. Máy chủ Dailymotion 1080p FHD
        if (!dlUrl.isNullOrEmpty() && dlUrl != effectiveOkUrl) {
            try {
                if (dlUrl.contains("dailymotion")) {
                    val dmExtractor = DailymotionExtractor()
                    dmExtractor.getUrl(dlUrl, "$mainUrl/", subtitleCallback) { link ->
                        linkFound = true
                        callback.invoke(link)
                    }
                } else {
                    loadExtractor(dlUrl, "$mainUrl/", subtitleCallback) { link ->
                        linkFound = true
                        callback.invoke(link)
                    }
                }
            } catch (_: Throwable) {}
        }

        // 4. Máy chủ VK 1080p FHD
        if (!vkUrl.isNullOrEmpty()) {
            try {
                val vkExtractor = VkExtractor()
                vkExtractor.getUrl(vkUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (_: Throwable) {}
        }

        // 5. Nguồn trực tiếp hoặc dự phòng
        val backupUrl = directUrl ?: (if (!data.startsWith("{")) data else null)
        if (!backupUrl.isNullOrEmpty() && backupUrl != ssUrl && backupUrl != effectiveOkUrl && backupUrl != dlUrl && backupUrl != vkUrl) {
            try {
                loadExtractor(backupUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (_: Throwable) {}
        }

        return linkFound
    }
}
