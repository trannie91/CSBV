package com.blvietsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
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
        if (url.isEmpty() || url.startsWith("data:image")) return ""
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
            "$mainUrl/feeds/posts/default/-/${URLEncoder.encode(request.data, "UTF-8")}?alt=json&max-results=$maxResults&start-index=$startIndex"
        }

        val items = mutableListOf<SearchResponse>()
        var totalResults = 0

        try {
            val responseText = app.get(
                feedUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/"
                )
            ).text

            val json = JSONObject(responseText)
            val feed = json.optJSONObject("feed")
            if (feed != null) {
                totalResults = feed.optJSONObject("openSearch\$totalResults")?.optInt("\$t") ?: 0
                val entries = feed.optJSONArray("entry")
                if (entries != null) {
                    for (i in 0 until entries.length()) {
                        val entry = entries.getJSONObject(i)
                        val titleObj = entry.optJSONObject("title")
                        val rawTitle = titleObj?.optString("\$t") ?: ""

                        var postUrl = ""
                        val links = entry.optJSONArray("link")
                        if (links != null) {
                            for (j in 0 until links.length()) {
                                val l = links.getJSONObject(j)
                                if (l.optString("rel") == "alternate") {
                                    postUrl = l.optString("href")
                                    break
                                }
                            }
                        }
                        if (postUrl.isEmpty()) continue

                        val contentObj = entry.optJSONObject("content")
                        val content = contentObj?.optString("\$t") ?: ""

                        var poster = ""
                        val candImgs = Regex("""(?:data-src|data-lazy-src|data-original|src)=['"]([^'"]+)['"]""").findAll(content).map { it.groupValues[1] }
                        for (cand in candImgs) {
                            if (!cand.startsWith("data:image") && !cand.contains("blank.gif") && !cand.contains("placeholder")) {
                                poster = enhancePoster(cand)
                                if (poster.isNotEmpty()) break
                            }
                        }

                        val title = cleanTitle(rawTitle)
                        val tvType = if (content.contains("phim-le") || content.contains("[br/FULL]")) TvType.Movie else TvType.AsianDrama

                        items.add(
                            newTvSeriesSearchResponse(title, postUrl, tvType) {
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            // Fallback: Parse HTML from web page nếu feed gặp sự cố
            try {
                val htmlUrl = if (request.data.isEmpty()) {
                    "$mainUrl/search?max-results=$maxResults"
                } else {
                    "$mainUrl/search/label/${request.data}?max-results=$maxResults"
                }
                val doc = app.get(htmlUrl, headers = mapOf("User-Agent" to userAgent)).document
                val htmlItems = doc.select("a.lable-about, article.post, .all-post .item").mapNotNull { el ->
                    toSearchResult(el)
                }.distinctBy { it.url }
                items.addAll(htmlItems)
            } catch (_: Throwable) {}
        }

        val distinctItems = items.distinctBy { it.url }
        val hasNext = if (totalResults > 0) {
            (startIndex + distinctItems.size) < totalResults
        } else {
            distinctItems.size >= 15
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = distinctItems,
                    isHorizontalImages = false
                )
            ),
            hasNext = hasNext
        )
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val link = if (element.tagName() == "a") element else (element.selectFirst("a[href*='blvietsub.vip']") ?: element.selectFirst("a") ?: return null)
        val href = fixUrl(link.attr("href"))

        // Bắt buộc là URL bài viết phim hợp lệ (.html)
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

            val json = JSONObject(responseText)
            val feed = json.optJSONObject("feed")
            val entries = feed?.optJSONArray("entry")

            if (entries != null) {
                for (i in 0 until entries.length()) {
                    val entry = entries.getJSONObject(i)
                    val titleObj = entry.optJSONObject("title")
                    val rawTitle = titleObj?.optString("\$t") ?: ""

                    var postUrl = ""
                    val links = entry.optJSONArray("link")
                    if (links != null) {
                        for (j in 0 until links.length()) {
                            val l = links.getJSONObject(j)
                            if (l.optString("rel") == "alternate") {
                                postUrl = l.optString("href")
                                break
                            }
                        }
                    }
                    if (postUrl.isEmpty()) continue

                    val contentObj = entry.optJSONObject("content")
                    val content = contentObj?.optString("\$t") ?: ""

                    var poster = ""
                    val candImgs = Regex("""(?:data-src|data-lazy-src|data-original|src)=['"]([^'"]+)['"]""").findAll(content).map { it.groupValues[1] }
                    for (cand in candImgs) {
                        if (!cand.startsWith("data:image") && !cand.contains("blank.gif") && !cand.contains("placeholder")) {
                            poster = enhancePoster(cand)
                            if (poster.isNotEmpty()) break
                        }
                    }

                    val title = cleanTitle(rawTitle)
                    val tvType = if (content.contains("[br/FULL]")) TvType.Movie else TvType.AsianDrama

                    results.add(
                        newTvSeriesSearchResponse(title, postUrl, tvType) {
                            this.posterUrl = poster
                        }
                    )
                }
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
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val htmlFull = doc.html()

        // Tiêu đề phim
        val rawTitle = doc.selectFirst("h1.post-title, h1, .post-title, .film-info h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.split("-")?.firstOrNull()?.trim()
            ?: return null
        val title = cleanTitle(rawTitle)

        // Poster phim chuẩn HD
        val thumbEl = doc.selectFirst("#thumbnail-phim, .poster img, [name=main-movie-content] img")
        val poster = if (thumbEl != null) {
            extractPoster(thumbEl)
        } else {
            val ogImg = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content") ?: ""
            enhancePoster(ogImg)
        }

        // Nội dung mô tả [nd]...[/nd]
        val mainContent = doc.selectFirst("[name=main-movie-list], .movie-content-1, article")?.html() ?: htmlFull
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

        // Bóc tách TOÀN BỘ các thẻ <id>...</id> trong toàn bộ trang (hỗ trợ đầy đủ phim mới và phim cũ)
        val episodes = mutableListOf<Episode>()
        val idBlocks = mutableListOf<String>()

        Regex("""<id>([\s\S]*?)</id>""").findAll(htmlFull).forEach {
            idBlocks.add(it.groupValues[1])
        }
        if (idBlocks.isEmpty()) {
            idBlocks.add(mainContent)
        }

        val epMap = linkedMapOf<Int, MutableMap<String, String>>()

        for (idBlock in idBlocks) {
            // Phân tách các cụm server [br/...]
            val sections = idBlock.split(Regex("""\[br/([^\]]+)\]"""))
            val serverNames = Regex("""\[br/([^\]]+)\]""").findAll(idBlock).map { it.groupValues[1].trim() }.toList()

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

                    // Phân loại nguồn server chính xác
                    when {
                        // 1. Máy chủ SSPlay
                        lowerUrl.contains("ssplay.net") || lowerSName.contains("ss") || lowerTag == "ss" -> {
                            epMap[epNum]?.put("ss", rawUrl)

                            // Nếu URL SSPlay có dạng ssplay.net/ok/{id}, trích xuất thêm ID làm nguồn OK.ru dự phòng
                            val okIdMatch = Regex("""/ok/(\d+)""").find(rawUrl)?.groupValues?.get(1)
                            if (!okIdMatch.isNullOrEmpty() && epMap[epNum]?.get("ok").isNullOrEmpty()) {
                                epMap[epNum]?.put("ok", "https://ok.ru/videoembed/$okIdMatch?autoplay=1")
                            }
                        }

                        // 2. Máy chủ Dailymotion (Server DL)
                        lowerUrl.contains("dailymotion.com") || lowerSName.contains("dl") || lowerTag == "dl" -> {
                            epMap[epNum]?.put("dl", rawUrl)
                        }

                        // 3. Máy chủ OK.ru (Server OK)
                        lowerUrl.contains("ok.ru") || lowerSName.contains("ok") || lowerTag == "ok" -> {
                            epMap[epNum]?.put("ok", rawUrl)
                        }

                        // 4. Máy chủ VK (Server VK)
                        lowerUrl.contains("vk.com") || lowerUrl.contains("vkvideo.ru") || lowerSName.contains("vk") || lowerTag == "vk" -> {
                            epMap[epNum]?.put("vk", rawUrl)
                        }

                        // 5. Máy chủ HX / Abyss
                        lowerUrl.contains("abyssplayer.com") || lowerUrl.contains("abyss") || lowerUrl.contains("hxfile") || lowerUrl.contains("short.icu") || lowerSName.contains("hx") || lowerTag == "hx" -> {
                            epMap[epNum]?.put("hx", rawUrl)
                        }

                        else -> {
                            if (epMap[epNum]?.get("other").isNullOrEmpty()) {
                                epMap[epNum]?.put("other", rawUrl)
                            }
                        }
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
                val direct = servers["other"] ?: servers["ss"] ?: servers["dl"] ?: servers["ok"] ?: servers["vk"] ?: servers["hx"]
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
        val dlUrl = Regex(""""dl"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val okUrl = Regex(""""ok"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val vkUrl = Regex(""""vk"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val hxUrl = Regex(""""hx"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val directUrl = Regex(""""direct"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")

        var linkFound = false

        // 1. ƯU TIÊN SỐ 1 CAO NHẤT: Phát và hiển thị nguồn SSPlay 1080p FHD đầu tiên
        if (!ssUrl.isNullOrEmpty()) {
            try {
                val ssExtractor = SSPlayExtractor()
                ssExtractor.getUrl(ssUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (_: Throwable) {}
        }

        // 2. NGUỒN DỰ PHÒNG 1: Server DL (Dailymotion) 1080p FHD
        if (!dlUrl.isNullOrEmpty()) {
            try {
                if (dlUrl.contains("dailymotion.com")) {
                    val dmExtractor = DailymotionExtractor()
                    dmExtractor.getUrl(dlUrl, "$mainUrl/", subtitleCallback) { link ->
                        linkFound = true
                        callback.invoke(link)
                    }
                } else if (dlUrl.contains("ok.ru")) {
                    val okExtractor = OkRuExtractor()
                    okExtractor.getUrl(dlUrl, "$mainUrl/", subtitleCallback) { link ->
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

        // 3. NGUỒN DỰ PHÒNG 2: Server OK (OK.ru) 1080p FHD
        val effectiveOkUrl = okUrl ?: (if (ssUrl?.contains("/ok/") == true) {
            val okId = Regex("""/ok/(\d+)""").find(ssUrl)?.groupValues?.get(1)
            if (!okId.isNullOrEmpty()) "https://ok.ru/videoembed/$okId?autoplay=1" else null
        } else null)

        if (!effectiveOkUrl.isNullOrEmpty() && effectiveOkUrl != dlUrl) {
            try {
                val okExtractor = OkRuExtractor()
                okExtractor.getUrl(effectiveOkUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (_: Throwable) {}
        }

        // 4. NGUỒN DỰ PHÒNG 3: Server VK 1080p FHD
        if (!vkUrl.isNullOrEmpty()) {
            try {
                val vkExtractor = VkExtractor()
                vkExtractor.getUrl(vkUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (_: Throwable) {}
        }

        // 5. Nguồn dự phòng khác (HX hoặc direct M3U8)
        val backupUrl = directUrl ?: hxUrl
        if (!backupUrl.isNullOrEmpty() && backupUrl != ssUrl && backupUrl != dlUrl && backupUrl != effectiveOkUrl && backupUrl != vkUrl) {
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
