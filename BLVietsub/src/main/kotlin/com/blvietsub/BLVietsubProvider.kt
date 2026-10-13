package com.blvietsub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class BLVietsubProvider : MainAPI() {
    override var mainUrl = "https://blvietsub.com"
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
        "category/phim-bo" to "Phim Bộ Đam Mỹ",
        "category/phim-le" to "Phim Lẻ Đam Mỹ",
        "category/thai-lan" to "BL Thái Lan",
        "category/trung-quoc" to "BL Trung Quốc",
        "category/han-quoc" to "BL Hàn Quốc",
        "category/nhat-ban" to "BL Nhật Bản",
        "category/dai-loan" to "BL Đài Loan",
        "category/hoan-tat" to "Phim Đã Hoàn Tất",
        "category/phim-doc" to "Phim Dọc"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    data class EpisodePayload(
        val ss: String? = null,
        val dl: String? = null,
        val ok: String? = null,
        val vk: String? = null,
        val direct: String? = null
    )

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
                img.attr("src")
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

        if (poster.isEmpty()) return ""
        return fixUrl(poster)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) {
            if (request.data.isEmpty()) "$mainUrl/" else "$mainUrl/${request.data}/"
        } else {
            if (request.data.isEmpty()) "$mainUrl/page/$page/" else "$mainUrl/${request.data}/page/$page/"
        }
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val items = doc.select("article, .item-film, .halim-item, .film-item, .post-item").mapNotNull { article ->
            toSearchResult(article)
        }.distinctBy { it.url }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                )
            ),
            hasNext = items.isNotEmpty()
        )
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val link = element.selectFirst("a[href^=https://blvietsub.com/]") 
            ?: element.selectFirst("a[href*='blvietsub.com']")
            ?: element.selectFirst("a") 
            ?: return null

        val href = fixUrl(link.attr("href"))
        val slug = href.removePrefix("https://blvietsub.com/").removePrefix("http://blvietsub.com/").trim('/')
        
        if (slug.isEmpty() || listOf("category", "tag", "actor", "page", "xmlrpc", "feed", "wp-admin", "lien-he", "privacy-policy").any { slug.startsWith(it) }) {
            return null
        }

        val title = element.selectFirst("h2, h3, h4, .entry-title, .title, .film-name")?.text()?.trim() 
            ?: link.attr("title").ifEmpty { slug.replace("-", " ") }
            
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
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        
        val doc = app.get(searchUrl, headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$mainUrl/"
        )).document

        val results = doc.select("article, .item-film, .halim-item, .film-item, .post-item").mapNotNull {
            toSearchResult(it)
        }.distinctBy { it.url }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val title = doc.selectFirst("h1, .entry-title, .film-info h1")?.text()?.trim() ?: return null
        
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.ifEmpty { doc.selectFirst("meta[name=twitter:image]")?.attr("content") }
            ?: extractPoster(doc.selectFirst("article, .film-info, .poster") ?: doc)

        val description = doc.select("article p, .film-content p, .entry-content p")
            .filter { it.text().length > 20 && !it.text().contains("Xem phim") && !it.text().contains("Server SS") && !it.text().contains("Server DL") && !it.text().contains("Server OK") && !it.text().contains("Server VK") }
            .joinToString("\n\n") { it.text().trim() }
            .ifEmpty { title }

        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("article a[href*=/category/], .film-info a[href*=/category/]").map { it.text().trim() }

        // Bóc tách toàn bộ các nút server: hỗ trợ Server SS, Server DL, Server OK, Server VK, Server NT (cả phim mới lẫn phim cũ)
        val episodes = mutableListOf<Episode>()
        val allServerButtons = doc.select("[data-server-url], .blv-server-button, .server-item button, .server-item a, .list-server a, .btn-episode, [data-server]")

        val epMap = linkedMapOf<Int, MutableMap<String, String>>()

        allServerButtons.forEachIndexed { index, btn ->
            val rawUrl = btn.attr("data-server-url").ifEmpty {
                btn.attr("data-url").ifEmpty {
                    btn.attr("href")
                }
            }.trim()

            val serverUrl = rawUrl.replace("&amp;", "&")

            if (serverUrl.isNotEmpty() && !serverUrl.startsWith("#") && !serverUrl.startsWith("javascript")) {
                val label = btn.attr("data-server-label").ifEmpty { btn.text().trim() }
                val numMatch = Regex("""\d+""").find(label) ?: Regex("""\d+""").find(btn.text())
                val epNum = numMatch?.value?.toIntOrNull() ?: (index + 1)

                if (!epMap.containsKey(epNum)) {
                    epMap[epNum] = mutableMapOf()
                }

                val lowerLabel = label.lowercase()
                val lowerUrl = serverUrl.lowercase()
                when {
                    lowerLabel.contains("ss") || lowerUrl.contains("ssplay.net") -> {
                        epMap[epNum]?.put("ss", serverUrl)
                    }
                    lowerLabel.contains("ok") || lowerUrl.contains("ok.ru") -> {
                        epMap[epNum]?.put("ok", serverUrl)
                    }
                    lowerLabel.contains("dl") || lowerUrl.contains("dailymotion.com") -> {
                        epMap[epNum]?.put("dl", serverUrl)
                    }
                    lowerLabel.contains("vk") || lowerUrl.contains("vk.com") || lowerUrl.contains("vkvideo.ru") -> {
                        epMap[epNum]?.put("vk", serverUrl)
                    }
                    else -> {
                        epMap[epNum]?.put("other", serverUrl)
                    }
                }
            }
        }

        if (epMap.isNotEmpty()) {
            epMap.entries.sortedBy { it.key }.forEach { (epNum, servers) ->
                // Đóng gói JSON thuần an toàn, tương thích mọi phiên bản Android
                val jsonParts = mutableListOf<String>()
                servers["ss"]?.let { jsonParts.add(""""ss":"${it.replace("\"", "\\\"")}"""") }
                servers["dl"]?.let { jsonParts.add(""""dl":"${it.replace("\"", "\\\"")}"""") }
                servers["ok"]?.let { jsonParts.add(""""ok":"${it.replace("\"", "\\\"")}"""") }
                servers["vk"]?.let { jsonParts.add(""""vk":"${it.replace("\"", "\\\"")}"""") }
                val direct = servers["other"] ?: servers["ss"] ?: servers["ok"] ?: servers["dl"] ?: servers["vk"]
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
            // Fallback iframe nếu không có button
            val iframes = doc.select("article iframe, .film-player iframe")
            iframes.forEachIndexed { index, iframe ->
                val src = iframe.attr("src").trim().replace("&amp;", "&")
                if (src.isNotEmpty()) {
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
        // Trích xuất an toàn không phụ thuộc Jackson để tránh lỗi NoClassDefFoundError trên Android
        val ssUrl = Regex(""""ss"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val okUrl = Regex(""""ok"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val dlUrl = Regex(""""dl"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val vkUrl = Regex(""""vk"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")
        val directUrl = Regex(""""direct"\s*:\s*"([^"]+)"""").find(data)?.groupValues?.get(1)?.replace("\\/", "/")

        var linkFound = false

        // 1. ƯU TIÊN CAO NHẤT: Nguồn SSPlay 1080p FHD (Dành cho các phim mới)
        // SSPlay được nạp đầu tiên để Cloudstream tự động chọn phát nguồn này trước tiên
        if (!ssUrl.isNullOrEmpty()) {
            try {
                val ssExtractor = SSPlayExtractor()
                ssExtractor.getUrl(ssUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (t: Throwable) {}
        }

        // 2. Nguồn OK.ru 1080p FHD (Dành cho cả phim cũ và phim mới có server OK hoặc server DL là OK.ru)
        // Nếu Server DL là OK.ru thì gộp chung vào đây để lấy chuẩn 1080p FHD cao nhất
        val effectiveOkUrl = okUrl ?: (if (dlUrl?.contains("ok.ru") == true) dlUrl else null)
        if (!effectiveOkUrl.isNullOrEmpty()) {
            try {
                val okExtractor = OkRuExtractor()
                okExtractor.getUrl(effectiveOkUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (t: Throwable) {}

            // Thử thêm extractor mặc định của Cloudstream làm dự phòng an toàn
            try {
                loadExtractor(effectiveOkUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (t: Throwable) {}
        }

        // 3. Nguồn Dailymotion 1080p FHD (Dành cho Server DL của phim mới)
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
            } catch (t: Throwable) {}
        }

        // 4. Nguồn VK 1080p FHD (Dành cho phim cũ dùng server VK)
        if (!vkUrl.isNullOrEmpty()) {
            try {
                val vkExtractor = VkExtractor()
                vkExtractor.getUrl(vkUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (t: Throwable) {}

            // Thử thêm extractor mặc định của Cloudstream cho VK
            try {
                loadExtractor(vkUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (t: Throwable) {}
        }

        // 5. Nguồn dự phòng khác (Direct link, Netu / Hqq, Hydrax) nếu có
        val backupUrl = directUrl ?: (if (!data.startsWith("{")) data else null)
        if (!backupUrl.isNullOrEmpty() && backupUrl != ssUrl && backupUrl != effectiveOkUrl && backupUrl != dlUrl && backupUrl != vkUrl) {
            try {
                loadExtractor(backupUrl, "$mainUrl/", subtitleCallback) { link ->
                    linkFound = true
                    callback.invoke(link)
                }
            } catch (t: Throwable) {}
        }

        return linkFound
    }
}
