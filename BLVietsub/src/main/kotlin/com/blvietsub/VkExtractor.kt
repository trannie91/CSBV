package com.blvietsub

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class VkExtractor : ExtractorApi() {
    override val name = "VK"
    override val mainUrl = "https://vk.com"
    override val requiresReferer = false

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Chuẩn hóa link VK: chuyển video{oid}_{id} thành video_ext.php?oid={oid}&id={id}
        var targetUrl = url.replace("&amp;", "&").replace("vkvideo.ru", "vk.com")
        val videoMatch = Regex("""video(-?\d+)_(\d+)""").find(targetUrl)
        if (videoMatch != null) {
            val oid = videoMatch.groupValues[1]
            val id = videoMatch.groupValues[2]
            targetUrl = "https://vk.com/video_ext.php?oid=$oid&id=$id"
        }

        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "https://vk.com/"
        )

        val response = try {
            app.get(targetUrl, headers = headers).text
        } catch (t: Throwable) {
            try {
                app.get(targetUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to (referer ?: "https://www.blvietsub.vip/"))).text
            } catch (t2: Throwable) {
                return
            }
        }

        if (response.isEmpty() || response.contains("Video not found") || response.contains("Access denied")) {
            return
        }

        fun cleanVkUrl(raw: String): String {
            return raw
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .replace("&quot;", "")
                .replace("\\/", "/")
                .trim()
        }

        // 1. Quét tìm url1080 trực tiếp trong JSON/script của VK (Ưu tiên chất lượng 1080p FHD cao nhất)
        val url1080Match = Regex("""["']url1080["']\s*[:=]\s*["']([^"']+)["']""").find(response)?.groupValues?.get(1)
            ?: Regex("""\\?"url1080\\?"\s*:\s*\\?"([^"\\]+)\\?""").find(response)?.groupValues?.get(1)

        if (!url1080Match.isNullOrEmpty()) {
            val videoUrl = cleanVkUrl(url1080Match)
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Server VK - 1080p FHD",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P1080.value
                    this.referer = "https://vk.com/"
                    this.headers = mapOf(
                        "Referer" to "https://vk.com/",
                        "User-Agent" to userAgent
                    )
                }
            )
        }

        // 2. Tìm luồng HLS m3u8 trong VK (HLS chứa luồng thích ứng 1080p FHD)
        val hlsMatch = Regex("""["']hls["']\s*[:=]\s*["']([^"']+)["']""").find(response)?.groupValues?.get(1)
            ?: Regex("""https?://[^\s'"]+\.m3u8[^\s'"]*""").find(response)?.value

        if (!hlsMatch.isNullOrEmpty()) {
            val hlsUrl = cleanVkUrl(hlsMatch)
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Server VK - 1080p FHD (HLS)",
                    url = hlsUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = Qualities.P1080.value
                    this.referer = "https://vk.com/"
                    this.headers = mapOf(
                        "Referer" to "https://vk.com/",
                        "User-Agent" to userAgent
                    )
                }
            )
        }
    }
}
