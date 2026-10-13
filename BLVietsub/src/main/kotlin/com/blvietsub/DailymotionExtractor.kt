package com.blvietsub

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class DailymotionExtractor : ExtractorApi() {
    override val name = "Dailymotion"
    override val mainUrl = "https://www.dailymotion.com"
    override val requiresReferer = false

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = Regex("""(?:video|embed/video)/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val metadataApiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId"

        val metadataResponse = try {
            app.get(
                metadataApiUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "https://www.dailymotion.com/"
                )
            ).text
        } catch (t: Throwable) {
            return
        }

        try {
            // Trích xuất master m3u8 url từ qualities
            val masterM3u8Url = Regex(""""qualities"\s*:\s*\{[^}]*"auto"\s*:\s*\[\s*\{[^}]*"url"\s*:\s*"([^"]+)"""").find(metadataResponse)?.groupValues?.get(1)
                ?: Regex(""""url"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(metadataResponse)?.groupValues?.get(1)
                ?: return

            val cleanMasterUrl = masterM3u8Url.replace("\\/", "/").replace("&amp;", "&")

            // Tải playlist master để tìm luồng 1080p FHD
            val m3u8Content = try {
                app.get(
                    cleanMasterUrl,
                    headers = mapOf(
                        "User-Agent" to userAgent
                    )
                ).text
            } catch (t: Throwable) {
                ""
            }

            if (m3u8Content.isNotEmpty() && m3u8Content.contains("#EXT-X-STREAM-INF")) {
                val lines = m3u8Content.lines()
                var bestStreamUrl: String? = null
                var has1080p = false

                // 1. Quét tìm dòng chứa 1080p FHD
                for (i in lines.indices) {
                    val line = lines[i]
                    if (line.startsWith("#EXT-X-STREAM-INF")) {
                        val nextUrl = lines.getOrNull(i + 1)?.trim() ?: continue
                        if (line.contains("1920x1080") || line.contains("1080")) {
                            bestStreamUrl = nextUrl
                            has1080p = true
                            break
                        }
                    }
                }

                // 2. Nếu không có dòng 1080p, chọn luồng phân giải cao nhất hiện có
                if (bestStreamUrl == null) {
                    for (i in lines.indices) {
                        val line = lines[i]
                        if (line.startsWith("#EXT-X-STREAM-INF")) {
                            val nextUrl = lines.getOrNull(i + 1)?.trim() ?: continue
                            bestStreamUrl = nextUrl
                            break
                        }
                    }
                }

                val finalUrl = bestStreamUrl ?: cleanMasterUrl

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Server DL (Dailymotion) - 1080p FHD",
                        url = finalUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = "https://www.dailymotion.com/"
                        this.headers = mapOf(
                            "Referer" to "https://www.dailymotion.com/",
                            "User-Agent" to userAgent
                        )
                    }
                )
            } else {
                // Fallback dùng master manifest m3u8
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Server DL (Dailymotion) - 1080p FHD",
                        url = cleanMasterUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = "https://www.dailymotion.com/"
                        this.headers = mapOf(
                            "Referer" to "https://www.dailymotion.com/",
                            "User-Agent" to userAgent
                        )
                    }
                )
            }
        } catch (t: Throwable) {}
    }
}
