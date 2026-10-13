package com.blvietsub

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class OkRuExtractor : ExtractorApi() {
    override val name = "OK.ru"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace("&amp;", "&")
        // Chuẩn hóa URL embed: lấy videoId nếu có
        val videoId = Regex("""(?:videoembed|/video/)/?(\d+)""").find(cleanUrl)?.groupValues?.get(1)
        val targetUrl = if (!videoId.isNullOrEmpty()) {
            "https://ok.ru/videoembed/$videoId"
        } else if (cleanUrl.contains("videoembed")) {
            cleanUrl.split("?")[0]
        } else {
            cleanUrl
        }

        // Tải trang embed của OK.ru với 2 phương án Header (Referer blvietsub hoặc ok.ru)
        var html = try {
            app.get(
                targetUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "https://ok.ru/"
                )
            ).text
        } catch (t: Throwable) {
            ""
        }

        if (html.isEmpty()) {
            html = try {
                app.get(
                    targetUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to (referer ?: "https://blvietsub.com/")
                    )
                ).text
            } catch (t: Throwable) {
                ""
            }
        }

        if (html.isEmpty()) return

        var fullUrl: String? = null
        var hdUrl: String? = null
        var hlsUrl: String? = null

        // 1. Trích xuất từ data-options
        val optMatch = Regex("""data-options="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""data-options='([^']+)'""").find(html)?.groupValues?.get(1)

        if (!optMatch.isNullOrEmpty()) {
            val decoded = optMatch
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&amp;", "&")

            val fullM = Regex(""""name"\s*:\s*"full"\s*,\s*"url"\s*:\s*"([^"]+)"""").find(decoded)
                ?: Regex(""""url"\s*:\s*"([^"]+)"\s*,\s*"name"\s*:\s*"full"""").find(decoded)
            if (fullM != null) {
                fullUrl = fullM.groupValues[1]
            }

            val hdM = Regex(""""name"\s*:\s*"hd"\s*,\s*"url"\s*:\s*"([^"]+)"""").find(decoded)
                ?: Regex(""""url"\s*:\s*"([^"]+)"\s*,\s*"name"\s*:\s*"hd"""").find(decoded)
            if (hdM != null) {
                hdUrl = hdM.groupValues[1]
            }

            val hlsM = Regex(""""hlsManifestUrl"\s*:\s*"([^"]+)"""").find(decoded)
            if (hlsM != null) {
                hlsUrl = hlsM.groupValues[1]
            }
        }

        // 2. Fallback quét trực tiếp trong HTML nếu data-options bị mã hóa khác
        if (fullUrl.isNullOrEmpty()) {
            val fullDirect = Regex("""(?:"|&quot;)name(?:"|&quot;)\s*:\s*(?:"|&quot;)full(?:"|&quot;)\s*,\s*(?:"|&quot;)url(?:"|&quot;)\s*:\s*(?:"|&quot;)([^"&]+(?:&(?:quot;|amp;)[^"&]+)*)(?:"|&quot;)""").find(html)
            if (fullDirect != null) {
                fullUrl = fullDirect.groupValues[1]
            }
        }

        if (hlsUrl.isNullOrEmpty()) {
            val hlsDirect = Regex("""(?:"|&quot;)hlsManifestUrl(?:"|&quot;)\s*:\s*(?:"|&quot;)([^"&]+(?:&(?:quot;|amp;)[^"&]+)*)(?:"|&quot;)""").find(html)
                ?: Regex("""https?://[^\s"']*(?:okcdn\.ru|ok\.ru)/video\.m3u8[^\s"']*""").find(html)
            if (hlsDirect != null) {
                hlsUrl = hlsDirect.groupValues.getOrNull(1) ?: hlsDirect.value
            }
        }

        // 3. Hàm làm sạch URL: thay thế \u0026, &amp;, \/
        fun cleanStreamUrl(raw: String): String {
            return raw
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .replace("&quot;", "")
                .replace("\\/", "/")
                .trim()
        }

        var emittedLink = false

        // 4. Phát luồng 1080p FHD Direct MP4 từ máy chủ OK.ru
        if (!fullUrl.isNullOrEmpty()) {
            val cleanedFull = cleanStreamUrl(fullUrl)
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Server OK (OK.ru) - 1080p FHD",
                    url = cleanedFull,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P1080.value
                    this.referer = "https://ok.ru/"
                    this.headers = mapOf(
                        "Referer" to "https://ok.ru/",
                        "User-Agent" to userAgent
                    )
                }
            )
            emittedLink = true
        }

        // 5. Phát luồng 1080p FHD HLS Adaptive từ máy chủ OK.ru (Chuyển đổi độ phân giải mượt mà)
        if (!hlsUrl.isNullOrEmpty()) {
            val cleanedHls = cleanStreamUrl(hlsUrl)
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Server OK (OK.ru) - 1080p FHD (HLS)",
                    url = cleanedHls,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.P1080.value
                    this.referer = "https://ok.ru/"
                    this.headers = mapOf(
                        "Referer" to "https://ok.ru/",
                        "User-Agent" to userAgent
                    )
                }
            )
            emittedLink = true
        }

        // 6. Fallback: Nếu không có bản 1080p thì phát bản 720p HD
        if (!emittedLink && !hdUrl.isNullOrEmpty()) {
            val cleanedHd = cleanStreamUrl(hdUrl)
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Server OK (OK.ru) - 720p HD",
                    url = cleanedHd,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P720.value
                    this.referer = "https://ok.ru/"
                    this.headers = mapOf(
                        "Referer" to "https://ok.ru/",
                        "User-Agent" to userAgent
                    )
                }
            )
        }
    }
}
