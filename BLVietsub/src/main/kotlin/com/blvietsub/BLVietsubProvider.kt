package com.blvietsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder
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

    // User-Agent Microsoft Edge Windows 10 chuẩn mực để Cloudflare Turnstile cấp quyền vượt rào tự động (auto-clearance) không bị ô vuông xác minh
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.2478.80"
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
            // Check data-image attribute
            val dataImgMatch = Regex("""data-image=['"]([^'"]+)['"]""").find(combinedText)?.groupValues?.get(1)
            if (!dataImgMatch.isNullOrEmpty() && !dataImgMatch.startsWith("data:image")) {
                val enhanced = enhancePoster(dataImgMatch)
                if (enhanced.isNotEmpty()) return enhanced
            }

            // Check TMDB url
            val tmdbMatch = Regex("""https://image\.tmdb\.org/t/p/[^\s"'<>]+""").find(combinedText)?.value
            if (!tmdbMatch.isNullOrEmpty()) {
                val enhanced = enhancePoster(tmdbMatch)
                if (enhanced.isNotEmpty()) return enhanced
            }

            // Check standard img tags
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
                    val firstServer = serversArr.getJSONObject(0)
                    val firstServerName = firstServer.optString("name").trim()
                    val firstServerEps = firstServer.optJSONArray("episodes")
                    val isFullMovieLayout = serversArr.length() == 1 && (
                        firstServerName.contains("Full", ignoreCase = true) ||
                        (firstServerEps != null && (0 until firstServerEps.length()).any { idx ->
                            val lbl = firstServerEps.getJSONObject(idx).optString("label").uppercase()
                            lbl in listOf("SS", "OK", "VK", "DL", "HX", "GD")
                        })
                    )

                    if (isFullMovieLayout && firstServerEps != null) {
                        val fullServerMap = mutableMapOf<String, String>()
                        for (eIdx in 0 until firstServerEps.length()) {
                            val epObj = firstServerEps.getJSONObject(eIdx)
                            val epId = epObj.optString("id")
                            val lbl = epObj.optString("label").lowercase().trim()
                            val sKey = when {
                                lbl.contains("ss") -> "ss"
                                lbl.contains("dl") || lbl.contains("dailymotion") -> "dl"
                                lbl.contains("ok") -> "ok"
                                lbl.contains("vk") -> "vk"
                                lbl.contains("hx") -> "hx"
                                lbl.contains("gd") -> "gd"
                                else -> "s${eIdx + 1}"
                            }
                            if (epId.isNotEmpty()) {
                                fullServerMap[sKey] = epId
                            }
                        }

                        val serverParts = fullServerMap.map { "\"${it.key}\":\"${it.value.replace("\"", "\\\"")}\"" }
                        val serversJson = "{" + serverParts.joinToString(",") + "}"
                        val payload = """{"v":19,"movie_id":"$movieId","page_url":"${url.replace("\"", "\\\"")}","ep_num":1,"ep_label":"Full","servers":$serversJson}"""

                        episodes.add(
                            newEpisode(payload) {
                                this.name = "Full Movie (1080p FHD)"
                                this.episode = 1
                            }
                        )
                    } else {
                        // Standard series layout
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
                            lowerUrl.contains("drive.google.com") || lowerUrl.contains("docs.google.com") || lowerUrl.contains("gdriveplayer") || lowerSName.contains("gd") || lowerTag == "gd" -> {
                                epMap[epNum]?.put("gd", rawUrl)
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
                    servers["gd"]?.let { jsonParts.add(""""gd":"${it.replace("\"", "\\\"")}"""") }
                    servers["hx"]?.let { jsonParts.add(""""hx":"${it.replace("\"", "\\\"")}"""") }
                    val direct = servers["other"] ?: servers["ss"] ?: servers["dl"] ?: servers["ok"] ?: servers["vk"] ?: servers["gd"] ?: servers["hx"]
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

    private suspend fun extractGoogleDrive(
        url: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val clean = url.replace("&amp;", "&")
            val targetUrl = if (clean.contains("gdriveplayer.me") && clean.contains("link=")) {
                Regex("""link=([^&]+)""").find(clean)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: clean
            } else {
                clean
            }

            val fileId = Regex("""(?:file/d/|id=|open\?id=)([a-zA-Z0-9_\-]+)""").find(targetUrl)?.groupValues?.get(1)
                ?: (if (targetUrl.contains("/d/")) targetUrl.split("/d/").getOrNull(1)?.split("/")?.firstOrNull() else null)

            if (!fileId.isNullOrEmpty()) {
                val directDownload = "https://drive.google.com/uc?id=$fileId&export=download"
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Server GD (Google Drive) - 1080p FHD",
                        url = directDownload,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = "https://drive.google.com/"
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to "https://drive.google.com/"
                        )
                    }
                )
                return true
            }
        } catch (_: Throwable) {}
        return false
    }

    private suspend fun extractHxServer(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            if (url.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Server HX (Abyss) - 1080p FHD",
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = "$mainUrl/"
                    }
                )
                return true
            }

            val res = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")).text
            val m3u8Match = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(res)?.value
            if (!m3u8Match.isNullOrEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Server HX (Abyss) - 1080p FHD",
                        url = m3u8Match,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = url
                    }
                )
                return true
            }
        } catch (_: Throwable) {}
        return false
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
            val dataJson = try { JSONObject(data) } catch (_: Throwable) { null }
            val movieId = dataJson?.optString("movie_id")
                ?: Regex(""""movie_id"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1) ?: ""
            val pageUrl = dataJson?.optString("page_url")
                ?: Regex(""""page_url"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/") ?: ""
            val epLabel = dataJson?.optString("ep_label")
                ?: Regex(""""ep_label"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1) ?: "01"
            val serversObj = dataJson?.optJSONObject("servers")
            val serversJson = serversObj?.toString()
                ?: Regex(""""servers"\s*:\s*(\{[^}]+\})""").find(data)?.groupValues?.get(1) ?: "{}"

            val resolvedServerUrls = mutableMapOf<String, String>()

            // Bước A: Thử gọi trực tiếp /api/v1/resolve phòng khi Turnstile không ép buộc
            if (serversObj != null && movieId.isNotEmpty()) {
                try {
                    val sKeys = serversObj.keys()
                    while (sKeys.hasNext()) {
                        val sKey = sKeys.next()
                        val targetEpId = serversObj.optString(sKey)
                        if (targetEpId.isNotEmpty()) {
                            val resolveRes = app.post(
                                "$v19Api/api/v1/resolve",
                                headers = mapOf(
                                    "Content-Type" to "application/json",
                                    "Origin" to mainUrl,
                                    "Referer" to pageUrl,
                                    "User-Agent" to userAgent
                                ),
                                json = mapOf(
                                    "movie_id" to movieId,
                                    "episode_id" to targetEpId
                                )
                            ).text

                            if (resolveRes.contains(""""url"""")) {
                                val rUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(resolveRes)?.groupValues?.get(1)?.replace("\\/", "/")
                                if (!rUrl.isNullOrEmpty() && rUrl.startsWith("http")) {
                                    resolvedServerUrls[sKey] = rUrl
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }

            // Bước B: Vượt rào Cloudflare Turnstile trên Android TV Box bằng cơ chế ngụy trang phần cứng & Auto-clearance
            if (resolvedServerUrls.isEmpty() && pageUrl.isNotEmpty()) {
                try {
                    val cleanLabel = Regex("""\d+""").find(epLabel)?.value?.toIntOrNull()?.toString() ?: epLabel

                    val injectedScript = """
                    (function() {
                      try {
                        // 1. POLYFILL HIGH-PRECISION REQUEST ANIMATION FRAME
                        // Đảm bảo không bị đóng băng khi WebView chạy nền trên Android TV Box
                        window.requestAnimationFrame = function(cb) {
                          return setTimeout(function() { cb(performance.now()); }, 16);
                        };
                        window.cancelAnimationFrame = function(id) {
                          clearTimeout(id);
                        };

                        // 2. NGỤY TRANG DANH TÍNH HOÀN HẢO THEO CHUẨN MICROSOFT EDGE WINDOWS 10
                        // Triệt tiêu 100% sự chênh lệch giữa Windows User-Agent và chip đồ họa ARM Mali/Adreno trên TV Box
                        try {
                          Object.defineProperty(Navigator.prototype, 'platform', { get: function() { return 'Win32'; }, configurable: true });
                          Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; }, configurable: true });
                          Object.defineProperty(Navigator.prototype, 'webdriver', { get: function() { return false; }, configurable: true });
                          Object.defineProperty(navigator, 'webdriver', { get: function() { return false; }, configurable: true });
                          Object.defineProperty(Navigator.prototype, 'hardwareConcurrency', { get: function() { return 8; }, configurable: true });
                          Object.defineProperty(navigator, 'hardwareConcurrency', { get: function() { return 8; }, configurable: true });
                          Object.defineProperty(Navigator.prototype, 'deviceMemory', { get: function() { return 8; }, configurable: true });
                          Object.defineProperty(navigator, 'deviceMemory', { get: function() { return 8; }, configurable: true });
                          Object.defineProperty(Navigator.prototype, 'maxTouchPoints', { get: function() { return 0; }, configurable: true });
                          Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; }, configurable: true });
                        } catch(e) {}

                        // Client Hints navigator.userAgentData cho Microsoft Edge
                        try {
                          var uaData = {
                            brands: [
                              { brand: 'Not-A.Brand', version: '99' },
                              { brand: 'Chromium', version: '124' },
                              { brand: 'Microsoft Edge', version: '124' }
                            ],
                            mobile: false,
                            platform: 'Windows',
                            getHighEntropyValues: function() {
                              return Promise.resolve({
                                architecture: 'x86',
                                bitness: '64',
                                brands: [
                                  { brand: 'Not-A.Brand', version: '99.0.0.0' },
                                  { brand: 'Chromium', version: '124.0.6367.119' },
                                  { brand: 'Microsoft Edge', version: '124.0.2478.80' }
                                ],
                                mobile: false,
                                model: '',
                                platform: 'Windows',
                                platformVersion: '15.0.0'
                              });
                            }
                          };
                          Object.defineProperty(Navigator.prototype, 'userAgentData', { get: function() { return uaData; }, configurable: true });
                          Object.defineProperty(navigator, 'userAgentData', { get: function() { return uaData; }, configurable: true });
                        } catch(e) {}

                        // 3. GIẢ LẬP WEBGL CARD ĐỒ HỌA INTEL UHD GRAPHICS D3D11
                        // Giấu hoàn toàn Mali/Adreno để Turnstile đánh giá độ tin cậy tuyệt đối
                        function hookWebGL(proto) {
                          if (!proto) return;
                          var origGetParam = proto.getParameter;
                          proto.getParameter = function(pname) {
                            if (pname === 37445) return 'Google Inc. (Intel)'; // UNMASKED_VENDOR_WEBGL
                            if (pname === 37446) return 'ANGLE (Intel, Intel(R) UHD Graphics 630 Direct3D11 vs_5_0 ps_5_0, D3D11)'; // UNMASKED_RENDERER_WEBGL
                            if (pname === 7936) return 'WebKit';
                            if (pname === 7937) return 'WebKit WebGL';
                            if (pname === 7938) return 'WebGL 1.0 (OpenGL ES 2.0 Chromium)';
                            return origGetParam.apply(this, arguments);
                          };
                          var origGetExt = proto.getExtension;
                          proto.getExtension = function(name) {
                            if (name === 'WEBGL_debug_renderer_info') {
                              return { UNMASKED_VENDOR_WEBGL: 37445, UNMASKED_RENDERER_WEBGL: 37446 };
                            }
                            return origGetExt.apply(this, arguments);
                          };
                        }
                        try {
                          if (window.WebGLRenderingContext) hookWebGL(WebGLRenderingContext.prototype);
                          if (window.WebGL2RenderingContext) hookWebGL(WebGL2RenderingContext.prototype);
                        } catch(e) {}

                        // 4. THIẾT LẬP KÍCH THƯỚC MÀN HÌNH 1080P & TRẠNG THÁI HIỂN THỊ THẬT
                        try {
                          Object.defineProperty(Document.prototype, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                          Object.defineProperty(Document.prototype, 'hidden', { get: function() { return false; }, configurable: true });
                          Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                          Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                          Document.prototype.hasFocus = function() { return true; };
                          document.hasFocus = function() { return true; };

                          Object.defineProperty(window, 'innerWidth', { get: function() { return 1920; }, configurable: true });
                          Object.defineProperty(window, 'innerHeight', { get: function() { return 960; }, configurable: true });
                          Object.defineProperty(window, 'outerWidth', { get: function() { return 1920; }, configurable: true });
                          Object.defineProperty(window, 'outerHeight', { get: function() { return 1040; }, configurable: true });
                          Object.defineProperty(screen, 'width', { get: function() { return 1920; }, configurable: true });
                          Object.defineProperty(screen, 'height', { get: function() { return 1080; }, configurable: true });
                          Object.defineProperty(screen, 'availWidth', { get: function() { return 1920; }, configurable: true });
                          Object.defineProperty(screen, 'availHeight', { get: function() { return 1040; }, configurable: true });
                          Object.defineProperty(screen, 'colorDepth', { get: function() { return 24; }, configurable: true });
                          Object.defineProperty(screen, 'pixelDepth', { get: function() { return 24; }, configurable: true });
                        } catch(e) {}

                        if (!window.chrome) {
                          window.chrome = { runtime: {}, app: {} };
                        }

                        // 5. ĐẢM BẢO KHUNG TURNSTILE CÓ KÍCH THƯỚC ĐẦY ĐỦ VÀ LUÔN NẰM TRONG VIEWPORT
                        function fixContainers() {
                          try {
                            var elements = document.querySelectorAll('.blv19-turnstile-wrap, .blv-cloud-player, [data-blv-movie]');
                            for (var i = 0; i < elements.length; i++) {
                              var el = elements[i];
                              el.style.setProperty('display', 'block', 'important');
                              el.style.setProperty('visibility', 'visible', 'important');
                              el.style.setProperty('opacity', '1', 'important');
                              el.style.setProperty('width', '320px', 'important');
                              el.style.setProperty('min-width', '320px', 'important');
                              el.style.setProperty('min-height', '70px', 'important');
                              el.style.setProperty('height', '90px', 'important');
                            }
                          } catch(e) {}
                        }
                        fixContainers();
                        setInterval(fixContainers, 200);

                        var origGetBoundingClientRect = Element.prototype.getBoundingClientRect;
                        Element.prototype.getBoundingClientRect = function() {
                          var rect = origGetBoundingClientRect.apply(this, arguments);
                          var isTurnstile = (this.classList && this.classList.contains('blv19-turnstile-wrap')) ||
                                            (this.tagName === 'IFRAME' && (this.src || '').indexOf('challenges.cloudflare.com') !== -1);
                          if (isTurnstile && (rect.width === 0 || rect.height === 0)) {
                            return {
                              top: 0, left: 0, right: 320, bottom: 90,
                              width: 320, height: 90, x: 0, y: 0
                            };
                          }
                          return rect;
                        };

                        var targetServers = $serversJson;
                        var mId = "$movieId";
                        var targetLabel = "$epLabel";
                        var cleanLabel = "$cleanLabel";
                        var resolvedMap = {};
                        var sessionCaptured = false;
                        var beaconSent = false;
                        var resolveInitiated = false;

                        function sendBeacon() {
                          if (beaconSent) return;
                          var keys = Object.keys(resolvedMap);
                          if (keys.length === 0) return;
                          beaconSent = true;
                          var q = encodeURIComponent(JSON.stringify(resolvedMap));
                          var beaconUrl = 'https://www.blvietsub.vip/favicon.ico?blv_stream_data=' + q;
                          try { window.location.replace(beaconUrl); } catch(e) {}
                          try { window.location.href = beaconUrl; } catch(e) {}
                          try { var img = new Image(); img.src = beaconUrl; } catch(e) {}
                          try { fetch(beaconUrl); } catch(e) {}
                        }

                        function recordUrl(key, url) {
                          if (!url || typeof url !== 'string') return;
                          if (url.indexOf('ssplay.net') !== -1) resolvedMap['ss'] = url;
                          else if (url.indexOf('dailymotion.com') !== -1 || url.indexOf('dai.ly') !== -1) resolvedMap['dl'] = url;
                          else if (url.indexOf('ok.ru') !== -1) resolvedMap['ok'] = url;
                          else if (url.indexOf('vk') !== -1) resolvedMap['vk'] = url;
                          else if (url.indexOf('drive.google.com') !== -1 || url.indexOf('gdriveplayer') !== -1 || key === 'gd') resolvedMap['gd'] = url;
                          else if (url.indexOf('short.icu') !== -1 || url.indexOf('abyss') !== -1 || key === 'hx') resolvedMap['hx'] = url;
                          else if (key && key !== 'direct') resolvedMap[key] = url;
                          else resolvedMap['direct'] = url;
                        }

                        function resolveAllServers(token) {
                          if (resolveInitiated) return;
                          resolveInitiated = true;
                          var sKeys = Object.keys(targetServers);
                          if (sKeys.length === 0) return;
                          var pending = sKeys.length;

                          sKeys.forEach(function(sKey) {
                            var epId = targetServers[sKey];
                            if (!epId) {
                              pending--;
                              if (pending <= 0) sendBeacon();
                              return;
                            }
                            fetch('https://blvietsub-v19-api.yaoivn.workers.dev/api/v1/resolve', {
                              method: 'POST',
                              headers: { 'Content-Type': 'application/json' },
                              body: JSON.stringify({ movie_id: mId, episode_id: epId, session_token: token })
                            })
                            .then(function(r) { return r.json(); })
                            .then(function(data) {
                              if (data && data.url) {
                                recordUrl(sKey, data.url);
                              }
                            })
                            .catch(function(){})
                            .finally(function() {
                              pending--;
                              if (pending <= 0) {
                                sendBeacon();
                              }
                            });
                          });

                          setTimeout(function() {
                            sendBeacon();
                          }, 2500);
                        }

                        function handleTurnstileToken(token) {
                          if (!token || sessionCaptured) return;
                          fetch('https://blvietsub-v19-api.yaoivn.workers.dev/api/v1/session', {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ movie_id: mId, turnstile_token: token })
                          })
                          .then(function(r) { return r.json(); })
                          .then(function(data) {
                            if (data && data.session_token && !sessionCaptured) {
                              sessionCaptured = true;
                              resolveAllServers(data.session_token);
                            }
                          })
                          .catch(function(){});
                        }

                        // 6. HOOK WINDOW.TURNSTILE VÀ ĐÓN ĐẦU CALLBACK XÁC THỰC
                        function wrapTurnstile(ts) {
                          if (!ts || ts._blvHooked) return;
                          ts._blvHooked = true;
                          var origRender = ts.render;
                          ts.render = function(container, options) {
                            if (options) {
                              // Ép kích thước normal chuẩn 300x65px tránh lỗi co cụm container 0 width trên TV Box
                              options.size = 'normal';
                              options.appearance = 'interaction-only';
                              if (container && container.style) {
                                container.style.width = '320px';
                                container.style.height = '90px';
                                container.style.display = 'block';
                                container.style.visibility = 'visible';
                                container.style.opacity = '1';
                              }
                              var origCb = options.callback;
                              options.callback = function(tok) {
                                handleTurnstileToken(tok);
                                if (typeof origCb === 'function') origCb.apply(this, arguments);
                              };
                            }
                            return origRender.apply(this, arguments);
                          };
                        }

                        if (window.turnstile) {
                          wrapTurnstile(window.turnstile);
                        } else {
                          var _tsVal = undefined;
                          Object.defineProperty(window, 'turnstile', {
                            configurable: true,
                            get: function() { return _tsVal; },
                            set: function(v) {
                              _tsVal = v;
                              wrapTurnstile(_tsVal);
                            }
                          });
                        }

                        // 7. HOOK WINDOW.TAP VÀ WINDOW.FETCH ĐỂ BẮT TRỌN CẢ SESSION LẪN STREAM
                        var origTap = window.tap;
                        window.tap = function(url, mode) {
                          if (url) {
                            recordUrl(null, url);
                            setTimeout(function() { sendBeacon(); }, 150);
                          }
                          if (typeof origTap === 'function') origTap(url, mode);
                        };

                        var origFetch = window.fetch;
                        window.fetch = function() {
                          var arg = arguments[0];
                          var url = typeof arg === 'string' ? arg : (arg && arg.url ? arg.url : '');
                          var p = origFetch.apply(this, arguments);
                          if (url) {
                            if (url.indexOf('/api/v1/session') !== -1) {
                              p.then(function(r) {
                                r.clone().json().then(function(sData) {
                                  if (sData && sData.session_token && !sessionCaptured) {
                                    sessionCaptured = true;
                                    resolveAllServers(sData.session_token);
                                  }
                                }).catch(function(){});
                              });
                            } else if (url.indexOf('/api/v1/resolve') !== -1) {
                              p.then(function(r) {
                                r.clone().json().then(function(rData) {
                                  if (rData && rData.url) {
                                    recordUrl(null, rData.url);
                                    setTimeout(function() { sendBeacon(); }, 300);
                                  }
                                }).catch(function(){});
                              });
                            }
                          }
                          return p;
                        };

                        // 8. TẠO CHUYỂN ĐỘNG CHUỘT VÀ TƯƠNG TÁC TỰ NHIÊN ĐỂ THỎA MÃN CHỈ SỐ CON NGƯỜI
                        function simulateHumanMovement() {
                          var steps = [
                            { x: 50, y: 35 }, { x: 100, y: 40 }, { x: 150, y: 35 },
                            { x: 200, y: 40 }, { x: 300, y: 50 }, { x: 500, y: 150 }
                          ];
                          steps.forEach(function(s, idx) {
                            setTimeout(function() {
                              ['pointermove', 'mousemove', 'pointerdown', 'mousedown', 'pointerup', 'mouseup', 'click'].forEach(function(etype) {
                                var evt = new MouseEvent(etype, {
                                  bubbles: true, cancelable: true, view: window,
                                  clientX: s.x, clientY: s.y, screenX: s.x, screenY: s.y + 60
                                });
                                document.dispatchEvent(evt);
                                var wrapEl = document.querySelector('.blv19-turnstile-wrap');
                                if (wrapEl) wrapEl.dispatchEvent(evt);
                              });
                            }, idx * 100);
                          });
                        }
                        simulateHumanMovement();
                        setInterval(simulateHumanMovement, 1200);

                        // 9. KÍCH HOẠT LẠI TURNSTILE RESET ĐỂ ĐÁNH GIÁ LẠI TRÊN MÔI TRƯỜNG ĐÃ ĐƯỢC SPOOF
                        setTimeout(function() {
                          if (window.turnstile && typeof window.turnstile.reset === 'function') {
                            try { window.turnstile.reset(); } catch(e) {}
                          }
                        }, 250);

                        // 10. POLLER TỰ ĐỘNG THU THẬP TOKEN TỪ INPUT HOẶC GETRESPONSE
                        var tokenPoller = setInterval(function() {
                          if (sessionCaptured || beaconSent) {
                            clearInterval(tokenPoller);
                            return;
                          }
                          var t = null;
                          if (window.turnstile && typeof window.turnstile.getResponse === 'function') {
                            try { t = window.turnstile.getResponse(); } catch(e) {}
                          }
                          if (!t) {
                            var inp = document.querySelector('input[name="cf-turnstile-response"]') ||
                                      document.querySelector('input[name="g-recaptcha-response"]');
                            if (inp && inp.value) t = inp.value;
                          }
                          if (t) {
                            handleTurnstileToken(t);
                          }
                        }, 200);

                        // 11. BẬT KHUNG XEM PHIM VÀ BẤM CHỌN TẬP PHIM
                        var xem = document.querySelector('.xemvideo, #xemvideo, a.bwac-btn');
                        if (xem) {
                          xem.click();
                          xem.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                        }

                        var mount = document.querySelector('.blv-cloud-player');
                        if (mount) mount.scrollIntoView();

                        var wrap = document.querySelector('.blv19-turnstile-wrap');
                        if (wrap) {
                          wrap.style.cssText = 'position:fixed;top:0;left:0;width:320px;height:90px;z-index:2147483647;display:block !important;visibility:visible !important;opacity:1 !important;';
                        }

                        function findBestEpisodeButton() {
                          var headings = Array.from(document.querySelectorAll('.movie-list p.newsv, .playlist p.newsv, p.newsv'));
                          var serverOrder = ['ss', 'dl', 'ok', 'vk', 'gd', 'hx'];
                          
                          for (var s = 0; s < serverOrder.length; s++) {
                            var key = serverOrder[s];
                            for (var h = 0; h < headings.length; h++) {
                              var hTxt = headings[h].textContent.toLowerCase();
                              if (hTxt.indexOf(key) !== -1 || (key === 'dl' && hTxt.indexOf('dailymotion') !== -1)) {
                                var nextEl = headings[h].nextElementSibling;
                                if (nextEl && nextEl.classList.contains('playlst')) {
                                  var btnCandidates = Array.from(nextEl.querySelectorAll('.episode, button[name="videos"]'));
                                  for (var b = 0; b < btnCandidates.length; b++) {
                                    var btn = btnCandidates[b];
                                    var t = btn.textContent.trim();
                                    var c = t.replace(/^0+/, '') || t;
                                    var title = btn.getAttribute('title') || '';
                                    if (t === targetLabel || c === cleanLabel || title === 'Tập ' + targetLabel || title === 'Tập ' + cleanLabel || targetLabel === 'Full') {
                                      return btn;
                                    }
                                  }
                                }
                              }
                            }
                          }

                          var allButtons = Array.from(document.querySelectorAll('.episode, button[name="videos"]'));
                          for (var i = 0; i < allButtons.length; i++) {
                            var ab = allButtons[i];
                            var atxt = ab.textContent.trim();
                            var ac = atxt.replace(/^0+/, '') || atxt;
                            var atitle = ab.getAttribute('title') || '';
                            if (atxt === targetLabel || ac === cleanLabel || atitle === 'Tập ' + targetLabel || atitle === 'Tập ' + cleanLabel) {
                              return ab;
                            }
                          }

                          return allButtons.length > 0 ? allButtons[0] : null;
                        }

                        function unlockButtons() {
                          var allB = document.querySelectorAll('button[name="videos"], .episode');
                          allB.forEach(function(b) {
                            b.disabled = false;
                            b.removeAttribute('disabled');
                          });
                        }

                        var clickCount = 0;
                        var clickInterval = setInterval(function() {
                          if (beaconSent || sessionCaptured || clickCount > 35) {
                            clearInterval(clickInterval);
                            return;
                          }
                          clickCount++;
                          unlockButtons();

                          var btn = findBestEpisodeButton();
                          if (btn) {
                            btn.click();
                            btn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                          }
                        }, 300);

                      } catch(e) {}
                    })();
                    """.trimIndent()

                    val resolver = WebViewResolver(
                        interceptUrl = Regex("""blv_stream_data=[^&]+|ssplay\.net|dailymotion\.com|geo\.dailymotion\.com|dai\.ly|ok\.ru|vkvideo\.ru|vk\.com|gdriveplayer|drive\.google\.com|docs\.google\.com|short\.icu|abyss|\.m3u8"""),
                        additionalUrls = listOf(
                            Regex("""blv_stream_data=[^&]+"""),
                            Regex("""ssplay\.net"""),
                            Regex("""dailymotion\.com|dai\.ly"""),
                            Regex("""ok\.ru"""),
                            Regex("""vkvideo\.ru|vk\.com"""),
                            Regex("""gdriveplayer|drive\.google\.com"""),
                            Regex("""short\.icu|abyss"""),
                            Regex("""\.m3u8""")
                        ),
                        script = injectedScript,
                        userAgent = userAgent,
                        useOkhttp = false,
                        timeout = 45L
                    )

                    val webViewResponse = app.get(pageUrl, interceptor = resolver)
                    val interceptedUrl = webViewResponse.url

                    if (interceptedUrl.contains("blv_stream_data=")) {
                        val dataParam = Regex("""[?&]blv_stream_data=([^&#]+)""").find(interceptedUrl)?.groupValues?.get(1)
                        if (!dataParam.isNullOrEmpty()) {
                            val decodedJson = URLDecoder.decode(dataParam, "UTF-8")
                            val jsonMap = JSONObject(decodedJson)
                            val kIter = jsonMap.keys()
                            while (kIter.hasNext()) {
                                val k = kIter.next()
                                val u = jsonMap.optString(k)
                                if (u.isNotEmpty() && u.startsWith("http")) {
                                    resolvedServerUrls[k] = u
                                }
                            }
                        }
                    } else if (interceptedUrl.startsWith("http") && !interceptedUrl.contains("blvietsub.vip")) {
                        when {
                            interceptedUrl.contains("ssplay.net") -> resolvedServerUrls["ss"] = interceptedUrl
                            interceptedUrl.contains("dailymotion.com") || interceptedUrl.contains("dai.ly") -> resolvedServerUrls["dl"] = interceptedUrl
                            interceptedUrl.contains("ok.ru") -> resolvedServerUrls["ok"] = interceptedUrl
                            interceptedUrl.contains("vk") -> resolvedServerUrls["vk"] = interceptedUrl
                            interceptedUrl.contains("drive.google.com") || interceptedUrl.contains("gdriveplayer") -> resolvedServerUrls["gd"] = interceptedUrl
                            interceptedUrl.contains("short.icu") || interceptedUrl.contains("abyss") -> resolvedServerUrls["hx"] = interceptedUrl
                            else -> resolvedServerUrls["direct"] = interceptedUrl
                        }
                    }
                } catch (_: Throwable) {}
            }

            // Bước C: Bóc tách và xuất phát luồng theo đúng thứ tự ưu tiên tuyệt đối & CHỈ LẤY 1080P FHD
            val ssUrl = resolvedServerUrls["ss"]
            val dlUrl = resolvedServerUrls["dl"]
            val okUrl = resolvedServerUrls["ok"]
            val vkUrl = resolvedServerUrls["vk"]
            val gdUrl = resolvedServerUrls["gd"]
            val hxUrl = resolvedServerUrls["hx"]
            val directUrl = resolvedServerUrls["direct"]

            // 1. ƯU TIÊN SỐ 1 CAO NHẤT: Máy chủ SSPlay 1080p FHD
            if (!ssUrl.isNullOrEmpty() && ssUrl.startsWith("http")) {
                try {
                    val ssExtractor = SSPlayExtractor()
                    ssExtractor.getUrl(ssUrl, "$mainUrl/", subtitleCallback) { link ->
                        linkFound = true
                        callback.invoke(link)
                    }
                } catch (_: Throwable) {}
            }

            // 2. NGUỒN HIỂN THỊ TRONG DANH SÁCH: Server DL (Dailymotion) 1080p FHD
            if (!dlUrl.isNullOrEmpty() && dlUrl.startsWith("http")) {
                try {
                    if (dlUrl.contains("dailymotion.com") || dlUrl.contains("dai.ly")) {
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

            // 3. NGUỒN HIỂN THỊ TRONG DANH SÁCH: Server OK (OK.ru) 1080p FHD
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

            // 4. NGUỒN HIỂN THỊ TRONG DANH SÁCH: Server VK 1080p FHD
            if (!vkUrl.isNullOrEmpty() && vkUrl.startsWith("http")) {
                try {
                    val vkExtractor = VkExtractor()
                    vkExtractor.getUrl(vkUrl, "$mainUrl/", subtitleCallback) { link ->
                        linkFound = true
                        callback.invoke(link)
                    }
                } catch (_: Throwable) {}
            }

            // 5. NGUỒN HIỂN THỊ TRONG DANH SÁCH: Server GD (Google Drive) 1080p FHD
            if (!gdUrl.isNullOrEmpty() && gdUrl.startsWith("http")) {
                try {
                    if (extractGoogleDrive(gdUrl, callback)) {
                        linkFound = true
                    }
                } catch (_: Throwable) {}
            }

            // 6. NGUỒN HIỂN THỊ TRONG DANH SÁCH: Server HX (Abyss) 1080p FHD
            if (!hxUrl.isNullOrEmpty() && hxUrl.startsWith("http")) {
                try {
                    if (extractHxServer(hxUrl, subtitleCallback, callback)) {
                        linkFound = true
                    }
                } catch (_: Throwable) {}
            }

            // 7. Nguồn dự phòng khác (Direct M3U8 hoặc loadExtractor)
            val backupUrl = directUrl
            if (!backupUrl.isNullOrEmpty() && backupUrl.startsWith("http") && backupUrl != ssUrl && backupUrl != dlUrl && backupUrl != effectiveOkUrl && backupUrl != vkUrl && backupUrl != gdUrl && backupUrl != hxUrl) {
                try {
                    if (backupUrl.contains(".m3u8")) {
                        linkFound = true
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = "Server SS (SSPlay) - 1080p FHD",
                                url = backupUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = "$mainUrl/"
                            }
                        )
                    } else if (backupUrl.contains("drive.google.com") || backupUrl.contains("gdriveplayer")) {
                        if (extractGoogleDrive(backupUrl, callback)) {
                            linkFound = true
                        }
                    } else {
                        loadExtractor(backupUrl, "$mainUrl/", subtitleCallback) { link ->
                            linkFound = true
                            callback.invoke(link)
                        }
                    }
                } catch (_: Throwable) {}
            }

            if (linkFound) return true
        }

        // 2. GIẢI QUYẾT CÁC LINK TRỰC TIẾP (Phim cũ hoặc link có sẵn trong payload)
        val ssUrl = Regex(""""ss"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val dlUrl = Regex(""""dl"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val okUrl = Regex(""""ok"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val vkUrl = Regex(""""vk"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val gdUrl = Regex(""""gd"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
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
                if (dlUrl.contains("dailymotion.com") || dlUrl.contains("dai.ly")) {
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

        // NGUỒN DỰ PHÒNG 4: Server GD (Google Drive) 1080p FHD
        if (!gdUrl.isNullOrEmpty() && gdUrl.startsWith("http")) {
            try {
                if (extractGoogleDrive(gdUrl, callback)) {
                    linkFound = true
                }
            } catch (_: Throwable) {}
        }

        // NGUỒN DỰ PHÒNG 5: Server HX (Abyss) 1080p FHD
        if (!hxUrl.isNullOrEmpty() && hxUrl.startsWith("http")) {
            try {
                if (extractHxServer(hxUrl, subtitleCallback, callback)) {
                    linkFound = true
                }
            } catch (_: Throwable) {}
        }

        // Nguồn dự phòng khác (Direct M3U8 hoặc loadExtractor)
        val backupUrl = directUrl
        if (!backupUrl.isNullOrEmpty() && backupUrl.startsWith("http") && backupUrl != ssUrl && backupUrl != dlUrl && backupUrl != effectiveOkUrl && backupUrl != vkUrl && backupUrl != gdUrl && backupUrl != hxUrl) {
            try {
                if (backupUrl.contains(".m3u8")) {
                    linkFound = true
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "Server SS (SSPlay) - 1080p FHD",
                            url = backupUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.P1080.value
                            this.referer = "$mainUrl/"
                        }
                    )
                } else if (backupUrl.contains("drive.google.com") || backupUrl.contains("gdriveplayer")) {
                    if (extractGoogleDrive(backupUrl, callback)) {
                        linkFound = true
                    }
                } else {
                    loadExtractor(backupUrl, "$mainUrl/", subtitleCallback) { link ->
                        linkFound = true
                        callback.invoke(link)
                    }
                }
            } catch (_: Throwable) {}
        }

        return linkFound
    }
}
