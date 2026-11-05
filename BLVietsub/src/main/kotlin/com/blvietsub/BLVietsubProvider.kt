package com.blvietsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
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
    private val v19Api = "https://blvietsub-v19-api.yaoivn.workers.dev"

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
        // 1. Kiểm tra thuộc tính data-image trên thẻ hoặc thẻ con
        val dataImg = element.attr("data-image").ifEmpty {
            element.selectFirst("[data-image]")?.attr("data-image") ?: ""
        }
        if (dataImg.isNotEmpty() && !dataImg.startsWith("data:image")) {
            return enhancePoster(dataImg)
        }

        // 2. Kiểm tra thẻ img với các thuộc tính phổ biến
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

        // 3. Kiểm tra ảnh nền background-image
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

        // 4. Quét regex TMDB image nếu có trong HTML thẻ
        if (poster.isEmpty()) {
            val tmdbMatch = Regex("""https://image\.tmdb\.org/t/p/[^\s"'<>]+""").find(element.outerHtml())?.value
            if (!tmdbMatch.isNullOrEmpty()) {
                poster = tmdbMatch
            }
        }

        return enhancePoster(poster)
    }

    private fun extractPosterFromFeedEntry(entry: JSONObject): String {
        // 1. Tìm trong link enclosures (đặc biệt chuẩn TMDB original image trên blvietsub v18/v19)
        val links = entry.optJSONArray("link")
        if (links != null) {
            for (j in 0 until links.length()) {
                val l = links.getJSONObject(j)
                val rel = l.optString("rel")
                val type = l.optString("type")
                val href = l.optString("href")
                if (rel == "enclosure" || type.startsWith("image/") || href.contains("image.tmdb.org")) {
                    val enhanced = enhancePoster(href)
                    if (enhanced.isNotEmpty()) return enhanced
                }
            }
        }

        // 2. Tìm trong summary hoặc content text
        val summary = entry.optJSONObject("summary")?.optString("\$t") ?: ""
        val content = entry.optJSONObject("content")?.optString("\$t") ?: ""
        val combinedText = "$summary $content"

        if (combinedText.isNotEmpty()) {
            val dataImgMatch = Regex("""data-image=['"]([^'"]+)['"]""").find(combinedText)?.groupValues?.get(1)
            if (!dataImgMatch.isNullOrEmpty() && !dataImgMatch.startsWith("data:image")) {
                val enhanced = enhancePoster(dataImgMatch)
                if (enhanced.isNotEmpty()) return enhanced
            }

            val tmdbMatch = Regex("""https://image\.tmdb\.org/t/p/[^\s"'<>]+""").find(combinedText)?.value
            if (!tmdbMatch.isNullOrEmpty()) {
                val enhanced = enhancePoster(tmdbMatch)
                if (enhanced.isNotEmpty()) return enhanced
            }

            val candImgs = Regex("""(?:data-src|data-lazy-src|data-original|src)=['"]([^'"]+)['"]""").findAll(combinedText).map { it.groupValues[1] }
            for (cand in candImgs) {
                if (!cand.startsWith("data:image") && !cand.contains("blank.gif") && !cand.contains("placeholder")) {
                    val enhanced = enhancePoster(cand)
                    if (enhanced.isNotEmpty()) return enhanced
                }
            }
        }

        // 3. Fallback: media$thumbnail
        val mediaThumb = entry.optJSONObject("media\$thumbnail")?.optString("url") ?: ""
        if (mediaThumb.isNotEmpty()) {
            return enhancePoster(mediaThumb)
        }

        return ""
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

                        val poster = extractPosterFromFeedEntry(entry)
                        val title = cleanTitle(rawTitle)
                        val summaryText = entry.optJSONObject("summary")?.optString("\$t") ?: ""
                        val contentText = entry.optJSONObject("content")?.optString("\$t") ?: ""
                        val allText = "$title $summaryText $contentText"

                        val tvType = if (allText.contains("phim-le") || allText.contains("[br/FULL]") || allText.contains("Full Phim", ignoreCase = true)) {
                            TvType.Movie
                        } else {
                            TvType.AsianDrama
                        }

                        items.add(
                            newTvSeriesSearchResponse(title, postUrl, tvType) {
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            // Fallback: Parse HTML từ web page nếu feed gặp sự cố
            try {
                val htmlUrl = if (request.data.isEmpty()) {
                    "$mainUrl/search?max-results=$maxResults"
                } else {
                    "$mainUrl/search/label/${URLEncoder.encode(request.data, "UTF-8")}?max-results=$maxResults"
                }
                val doc = app.get(htmlUrl, headers = mapOf("User-Agent" to userAgent)).document
                val htmlItems = doc.select("a.lable-about, .lable-update, article.post, .all-post .item").mapNotNull { el ->
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

        val tvType = if (title.contains("FULL", ignoreCase = true) || href.contains("phim-le")) {
            TvType.Movie
        } else {
            TvType.AsianDrama
        }

        return newTvSeriesSearchResponse(title, href, tvType) {
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

                    val poster = extractPosterFromFeedEntry(entry)
                    val title = cleanTitle(rawTitle)
                    val summaryText = entry.optJSONObject("summary")?.optString("\$t") ?: ""
                    val contentText = entry.optJSONObject("content")?.optString("\$t") ?: ""
                    val allText = "$title $summaryText $contentText"

                    val tvType = if (allText.contains("phim-le") || allText.contains("[br/FULL]") || allText.contains("Full Phim", ignoreCase = true)) {
                        TvType.Movie
                    } else {
                        TvType.AsianDrama
                    }

                    results.add(
                        newTvSeriesSearchResponse(title, postUrl, tvType) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
        } catch (_: Throwable) {}

        // Fallback: HTML search
        if (results.isEmpty()) {
            try {
                val searchUrl = "$mainUrl/search?q=$encodedQuery"
                val doc = app.get(searchUrl, headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$mainUrl/"
                )).document

                val htmlResults = doc.select("a.lable-about, .lable-update, article.post").mapNotNull {
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

        // 1. Tiêu đề phim
        val rawTitle = doc.selectFirst("h1.post-title, h1, .post-title, .film-info h1")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.split("-")?.firstOrNull()?.trim()
            ?: return null
        val title = cleanTitle(rawTitle)

        // 2. Poster phim chuẩn HD (TMDB / Enclosure / Poster)
        var poster = ""
        val tmdbImg = doc.selectFirst("img[src*='image.tmdb.org']")?.attr("src")
        if (!tmdbImg.isNullOrEmpty()) {
            poster = enhancePoster(tmdbImg)
        }
        if (poster.isEmpty()) {
            val dataImg = doc.selectFirst("[data-image]")?.attr("data-image")
            if (!dataImg.isNullOrEmpty()) {
                poster = enhancePoster(dataImg)
            }
        }
        if (poster.isEmpty()) {
            val thumbEl = doc.selectFirst("#thumbnail-phim, .poster img, .page-cover img, [name=main-movie-content] img")
            if (thumbEl != null) {
                poster = extractPoster(thumbEl)
            }
        }
        if (poster.isEmpty()) {
            val ogImg = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content") ?: ""
            poster = enhancePoster(ogImg)
        }
        if (poster.isEmpty()) {
            val tmdbRegex = Regex("""https://image\.tmdb\.org/t/p/[^\s"'<>]+""").find(htmlFull)?.value
            if (!tmdbRegex.isNullOrEmpty()) {
                poster = enhancePoster(tmdbRegex)
            }
        }

        // 3. Nội dung mô tả [nd]...[/nd]
        val mainContent = doc.selectFirst("[name=main-movie-list], .movie-content-1, article")?.html() ?: htmlFull
        val ndMatch = Regex("""\[nd\]([\s\S]*?)\[/nd\]""").find(mainContent) ?: Regex("""\[nd\]([\s\S]*?)\[/nd\]""").find(htmlFull)
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

        val episodes = mutableListOf<Episode>()

        // 4. KIỂM TRA HỆ THỐNG BLVIETSUB V19 (Cloud Player manifest API)
        val movieId = Regex("""data-blv-movie=["']([^"']+)["']""").find(htmlFull)?.groupValues?.get(1)

        if (!movieId.isNullOrEmpty()) {
            try {
                val manifestUrl = "$v19Api/api/v1/movies/$movieId/manifest"
                val manifestRes = app.get(
                    manifestUrl,
                    headers = mapOf(
                        "Origin" to mainUrl,
                        "Referer" to url,
                        "User-Agent" to userAgent
                    )
                ).text

                val manifestJson = JSONObject(manifestRes)
                val serversArr = manifestJson.optJSONArray("servers")

                if (serversArr != null && serversArr.length() > 0) {
                    val v19EpMap = linkedMapOf<String, MutableMap<String, String>>()

                    for (sIdx in 0 until serversArr.length()) {
                        val sObj = serversArr.getJSONObject(sIdx)
                        val sName = sObj.optString("name", "Server ${sIdx + 1}")
                        val sLower = sName.lowercase()
                        val sKey = when {
                            sLower.contains("ss") -> "ss"
                            sLower.contains("dl") || sLower.contains("dailymotion") -> "dl"
                            sLower.contains("ok") -> "ok"
                            sLower.contains("vk") -> "vk"
                            sLower.contains("hx") -> "hx"
                            sLower.contains("gd") -> "gd"
                            else -> "s${sIdx + 1}"
                        }

                        val epsArr = sObj.optJSONArray("episodes") ?: continue
                        for (eIdx in 0 until epsArr.length()) {
                            val epObj = epsArr.getJSONObject(eIdx)
                            val epId = epObj.optString("id")
                            val label = epObj.optString("label").trim()
                            if (epId.isNotEmpty() && label.isNotEmpty()) {
                                if (!v19EpMap.containsKey(label)) {
                                    v19EpMap[label] = mutableMapOf()
                                }
                                v19EpMap[label]?.put(sKey, epId)
                            }
                        }
                    }

                    val sortedEntries = v19EpMap.entries.sortedBy { entry ->
                        Regex("""\d+""").find(entry.key)?.value?.toIntOrNull() ?: 999
                    }

                    for (entry in sortedEntries) {
                        val label = entry.key
                        val sMap = entry.value
                        val epNum = Regex("""\d+""").find(label)?.value?.toIntOrNull() ?: 1

                        val epName = if (sortedEntries.size == 1 && (title.contains("FULL", ignoreCase = true) || label.contains("full", ignoreCase = true))) {
                            "Full Movie (1080p FHD)"
                        } else {
                            "Tập $label"
                        }

                        val serverParts = sMap.map { "\"${it.key}\":\"${it.value.replace("\"", "\\\"")}\"" }
                        val serversJson = "{" + serverParts.joinToString(",") + "}"
                        val payload = """{"v":19,"movie_id":"$movieId","page_url":"${url.replace("\"", "\\\"")}","ep_num":$epNum,"ep_label":"$label","servers":$serversJson}"""

                        episodes.add(
                            newEpisode(payload) {
                                this.name = epName
                                this.episode = epNum
                            }
                        )
                    }
                }
            } catch (_: Throwable) {}
        }

        // 5. NẾU KHÔNG CÓ MANIFEST HOẶC ĐỐI VỚI PHIM CŨ: Quét toàn bộ thẻ <id>...</id> trong trang
        if (episodes.isEmpty()) {
            val idBlocks = mutableListOf<String>()
            Regex("""<id>([\s\S]*?)</id>""").findAll(htmlFull).forEach {
                idBlocks.add(it.groupValues[1])
            }
            if (idBlocks.isEmpty()) {
                idBlocks.add(mainContent)
            }

            val epMap = linkedMapOf<Int, MutableMap<String, String>>()

            for (idBlock in idBlocks) {
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

                        when {
                            lowerUrl.contains("ssplay.net") || lowerSName.contains("ss") || lowerTag == "ss" -> {
                                epMap[epNum]?.put("ss", rawUrl)
                                val okIdMatch = Regex("""/ok/(\d+)""").find(rawUrl)?.groupValues?.get(1)
                                if (!okIdMatch.isNullOrEmpty() && epMap[epNum]?.get("ok").isNullOrEmpty()) {
                                    epMap[epNum]?.put("ok", "https://ok.ru/videoembed/$okIdMatch?autoplay=1")
                                }
                            }
                            lowerUrl.contains("dailymotion.com") || lowerSName.contains("dl") || lowerTag == "dl" -> {
                                epMap[epNum]?.put("dl", rawUrl)
                            }
                            lowerUrl.contains("ok.ru") || lowerSName.contains("ok") || lowerTag == "ok" -> {
                                epMap[epNum]?.put("ok", rawUrl)
                            }
                            lowerUrl.contains("vk.com") || lowerUrl.contains("vkvideo.ru") || lowerSName.contains("vk") || lowerTag == "vk" -> {
                                epMap[epNum]?.put("vk", rawUrl)
                            }
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
            }
        }

        // 6. Fallback cuối cùng: iframe trực tiếp trong trang
        if (episodes.isEmpty()) {
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

        val tvType = if (distinctEpisodes.size <= 1 && (title.contains("FULL", ignoreCase = true) || url.contains("phim-le"))) {
            TvType.Movie
        } else {
            TvType.AsianDrama
        }

        return newTvSeriesLoadResponse(title, url, tvType, distinctEpisodes) {
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
        var linkFound = false

        // 1. KIỂM TRA NẾU ĐÂY LÀ TẬP PHIM TỪ HỆ THỐNG BLVIETSUB V19 CLOUD PLAYER
        if (data.contains(""""v":19""") || data.contains(""""movie_id"""")) {
            val movieId = Regex(""""movie_id"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)
            val pageUrl = Regex(""""page_url"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
            val epLabel = Regex(""""ep_label"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1) ?: "01"

            val ssEpId = Regex(""""ss"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)
            val dlEpId = Regex(""""dl"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)
            val okEpId = Regex(""""ok"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)
            val vkEpId = Regex(""""vk"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)
            val hxEpId = Regex(""""hx"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)

            val orderedServers = listOf(
                "ss" to ssEpId,
                "dl" to dlEpId,
                "ok" to okEpId,
                "vk" to vkEpId,
                "hx" to hxEpId
            ).filter { !it.second.isNullOrEmpty() }

            // Thử giải quyết trực tiếp từng server qua API /api/v1/resolve
            for ((serverKey, targetEpId) in orderedServers) {
                if (targetEpId.isNullOrEmpty() || movieId.isNullOrEmpty()) continue
                try {
                    val resolveRes = app.post(
                        "$v19Api/api/v1/resolve",
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "Origin" to mainUrl,
                            "Referer" to (pageUrl ?: "$mainUrl/"),
                            "User-Agent" to userAgent
                        ),
                        json = mapOf(
                            "movie_id" to movieId,
                            "episode_id" to targetEpId
                        )
                    ).text

                    if (resolveRes.contains(""""url"""")) {
                        val resolvedUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(resolveRes)?.groupValues?.get(1)?.replace("\\/", "/")
                        if (!resolvedUrl.isNullOrEmpty()) {
                            when {
                                resolvedUrl.contains("ssplay.net") -> {
                                    SSPlayExtractor().getUrl(resolvedUrl, "$mainUrl/", subtitleCallback) {
                                        linkFound = true
                                        callback.invoke(it)
                                    }
                                }
                                resolvedUrl.contains("dailymotion.com") -> {
                                    DailymotionExtractor().getUrl(resolvedUrl, "$mainUrl/", subtitleCallback) {
                                        linkFound = true
                                        callback.invoke(it)
                                    }
                                }
                                resolvedUrl.contains("ok.ru") -> {
                                    OkRuExtractor().getUrl(resolvedUrl, "$mainUrl/", subtitleCallback) {
                                        linkFound = true
                                        callback.invoke(it)
                                    }
                                }
                                resolvedUrl.contains("vk.com") || resolvedUrl.contains("vkvideo.ru") -> {
                                    VkExtractor().getUrl(resolvedUrl, "$mainUrl/", subtitleCallback) {
                                        linkFound = true
                                        callback.invoke(it)
                                    }
                                }
                                resolvedUrl.endsWith(".m3u8") || resolvedUrl.contains(".m3u8") -> {
                                    linkFound = true
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = "Server SS (SSPlay) - 1080p FHD",
                                            url = resolvedUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.quality = Qualities.P1080.value
                                            this.referer = "$mainUrl/"
                                        }
                                    )
                                }
                                else -> {
                                    loadExtractor(resolvedUrl, "$mainUrl/", subtitleCallback) {
                                        linkFound = true
                                        callback.invoke(it)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }

            // Nếu Cloudflare Turnstile được bật (PLAY_SESSION_REQUIRED), sử dụng WebViewResolver
            // với script tự động kích hoạt nút phát tập phim trong WebView
            if (!linkFound && !pageUrl.isNullOrEmpty()) {
                try {
                    val cleanLabel = Regex("""\d+""").find(epLabel)?.value ?: epLabel
                    val targetPageUrl = if (cleanLabel.length == 1) "$pageUrl#0$cleanLabel" else "$pageUrl#$cleanLabel"

                    val autoClickScript = """
                        (function() {
                            function tryClick() {
                                var xemBtn = document.querySelector('.xemvideo');
                                if (xemBtn) xemBtn.click();
                                var epButtons = document.querySelectorAll('.blv-cloud-player button, .playlst button, button.episode');
                                if (epButtons.length > 0) {
                                    var targetLabel = '$cleanLabel';
                                    var clicked = false;
                                    for (var i = 0; i < epButtons.length; i++) {
                                        var btn = epButtons[i];
                                        if (btn.textContent.trim() === targetLabel || btn.title.includes(targetLabel)) {
                                            btn.click();
                                            clicked = true;
                                            break;
                                        }
                                    }
                                    if (!clicked && epButtons[0]) {
                                        epButtons[0].click();
                                    }
                                } else {
                                    setTimeout(tryClick, 400);
                                }
                            }
                            if (document.readyState === 'complete' || document.readyState === 'interactive') {
                                setTimeout(tryClick, 800);
                            } else {
                                document.addEventListener('DOMContentLoaded', function() { setTimeout(tryClick, 800); });
                            }
                        })();
                    """.trimIndent()

                    val resolver = WebViewResolver(
                        interceptUrl = Regex("""ssplay\.net|dailymotion\.com|ok\.ru|vkvideo\.ru|vk\.com|abyssplayer\.com|/api/v1/resolve""")
                    )

                    val webViewResponse = app.get(targetPageUrl, interceptor = resolver)
                    val interceptedUrl = webViewResponse.url
                    val interceptedBody = webViewResponse.text

                    var streamOrPlayerUrl = ""
                    if (interceptedBody.contains(""""url"""")) {
                        streamOrPlayerUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(interceptedBody)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
                    }
                    if (streamOrPlayerUrl.isEmpty() && (interceptedUrl.contains("ssplay.net") || interceptedUrl.contains("ok.ru") || interceptedUrl.contains("dailymotion.com") || interceptedUrl.contains("vk"))) {
                        streamOrPlayerUrl = interceptedUrl
                    }

                    if (streamOrPlayerUrl.isNotEmpty()) {
                        when {
                            streamOrPlayerUrl.contains("ssplay.net") -> {
                                SSPlayExtractor().getUrl(streamOrPlayerUrl, "$mainUrl/", subtitleCallback) {
                                    linkFound = true
                                    callback.invoke(it)
                                }
                            }
                            streamOrPlayerUrl.contains("dailymotion.com") -> {
                                DailymotionExtractor().getUrl(streamOrPlayerUrl, "$mainUrl/", subtitleCallback) {
                                    linkFound = true
                                    callback.invoke(it)
                                }
                            }
                            streamOrPlayerUrl.contains("ok.ru") -> {
                                OkRuExtractor().getUrl(streamOrPlayerUrl, "$mainUrl/", subtitleCallback) {
                                    linkFound = true
                                    callback.invoke(it)
                                }
                            }
                            streamOrPlayerUrl.contains("vk") -> {
                                VkExtractor().getUrl(streamOrPlayerUrl, "$mainUrl/", subtitleCallback) {
                                    linkFound = true
                                    callback.invoke(it)
                                }
                            }
                            else -> {
                                loadExtractor(streamOrPlayerUrl, "$mainUrl/", subtitleCallback) {
                                    linkFound = true
                                    callback.invoke(it)
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        // 2. GIẢI QUYẾT CÁC LINK TRỰC TIẾP (Phim cũ hoặc link có sẵn trong payload)
        val ssUrl = Regex(""""ss"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val dlUrl = Regex(""""dl"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val okUrl = Regex(""""ok"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val vkUrl = Regex(""""vk"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val hxUrl = Regex(""""hx"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val directUrl = Regex(""""direct"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")

        // ƯU TIÊN SỐ 1 CAO NHẤT: Phát và hiển thị nguồn SSPlay 1080p FHD lên đầu tiên
        if (!ssUrl.isNullOrEmpty() && ssUrl.startsWith("http")) {
            try {
                val ssExtractor = SSPlayExtractor()
                ssExtractor.getUrl(ssUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (_: Throwable) {}
        }

        // NGUỒN DỰ PHÒNG 1: Server DL (Dailymotion) 1080p FHD
        if (!dlUrl.isNullOrEmpty() && dlUrl.startsWith("http")) {
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

        // NGUỒN DỰ PHÒNG 2: Server OK (OK.ru) 1080p FHD
        val effectiveOkUrl = okUrl ?: (if (ssUrl?.contains("/ok/") == true) {
            val okId = Regex("""/ok/(\d+)""").find(ssUrl)?.groupValues?.get(1)
            if (!okId.isNullOrEmpty()) "https://ok.ru/videoembed/$okId?autoplay=1" else null
        } else null)

        if (!effectiveOkUrl.isNullOrEmpty() && effectiveOkUrl != dlUrl && effectiveOkUrl.startsWith("http")) {
            try {
                val okExtractor = OkRuExtractor()
                okExtractor.getUrl(effectiveOkUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (_: Throwable) {}
        }

        // NGUỒN DỰ PHÒNG 3: Server VK 1080p FHD
        if (!vkUrl.isNullOrEmpty() && vkUrl.startsWith("http")) {
            try {
                val vkExtractor = VkExtractor()
                vkExtractor.getUrl(vkUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (_: Throwable) {}
        }

        // Nguồn dự phòng khác (HX hoặc direct M3U8)
        val backupUrl = directUrl ?: hxUrl
        if (!backupUrl.isNullOrEmpty() && backupUrl.startsWith("http") && backupUrl != ssUrl && backupUrl != dlUrl && backupUrl != effectiveOkUrl && backupUrl != vkUrl) {
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
