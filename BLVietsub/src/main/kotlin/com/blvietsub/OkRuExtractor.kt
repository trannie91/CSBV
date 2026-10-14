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
        val videoId = Regex("""(?:videoembed|/video/)/?(\d+)""").find(cleanUrl)?.groupValues?.get(1)
        val targetUrl = if (!videoId.isNullOrEmpty()) {
            "https://ok.ru/videoembed/$videoId"
        } else if (cleanUrl.contains("videoembed")) {
            cleanUrl.split("?")[0]
        } else {
            cleanUrl
        }

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
                        "Referer" to (referer ?: "https://www.blvietsub.vip/")
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

        if (fullUrl.isNullOrEmpty()) {
            val fullDirect = Regex("""(?:"|&quot;)name(?:"|&quot;)\s*:\s*(?:"|&quot;)full(?:"|&quot;)\s*,\s*(?:"|&quot;)url(?:"|&quot;)\s*:\s*(?:"|&quot;)([^"&]+(?:&(?:quot;|amp;)[^"&]+)*)(?:"|&quot;)""").find(html)
            if (fullDirect != null) {
                fullUrl = fullDirect.groupValues[1]
            }
        }

        if (hlsUrl.isNullOrEmpty()) {
            val hlsDirect = Regex("""(?:"|&quot;)hlsManifestUrl(?:"|&quot;)\s*:\s*(?:"|&quot;)([^"&]+(?:&(?:quot;|amp;)[^"&]+)*)(?:"|&quot;)""").find(html)
                ?: Regex("""https?://[^\s'"]*(?:okcdn\.ru|ok\.ru)/video\.m3u8[^\s'"]*""").find(html)
            if (hlsDirect != null) {
                hlsUrl = hlsDirect.groupValues.getOrNull(1) ?: hlsDirect.value
            }
        }

        fun cleanStreamUrl(raw: String): String {
            return raw
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .replace("&quot;", "")
                .replace("\\/", "/")
                .trim()
        }

        var emittedLink = false

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
