package com.blvietsub

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class SSPlayExtractor : ExtractorApi() {
    override val name = "SSPlay"
    override val mainUrl = "https://ssplay.net"
    override val requiresReferer = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // Hàm giải mã đa khối Dean Edwards Packer (quét qua toàn bộ các khối đóng gói trong trang)
    private fun unPackDeanEdwards(code: String): String {
        try {
            val pattern = Regex("""\}\s*\(\s*['"]([\s\S]*?)['"]\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*['"]([\s\S]*?)['"]\s*\.split\(['"]\|['"]\)""")
            val match = pattern.find(code) ?: return ""
            val p = match.groupValues[1]
            val a = match.groupValues[2].toIntOrNull() ?: return ""
            val k = match.groupValues[4].split("|")

            fun base62Decode(str: String): Int? {
                var num = 0
                for (ch in str) {
                    val digit = when (ch) {
                        in '0'..'9' -> ch - '0'
                        in 'a'..'z' -> ch - 'a' + 10
                        in 'A'..'Z' -> ch - 'A' + 36
                        else -> return null
                    }
                    num = num * a + digit
                }
                return num
            }

            return Regex("""\b\w+\b""").replace(p) { m ->
                val idx = base62Decode(m.value)
                if (idx != null && idx in k.indices && k[idx].isNotEmpty()) {
                    k[idx]
                } else {
                    m.value
                }
            }
        } catch (t: Throwable) {
            return ""
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val targetUrl = if (url.startsWith("http")) url else "$mainUrl$url"
        val ref = referer ?: "https://blvietsub.com/"
        val headers = mapOf(
            "Referer" to ref,
            "User-Agent" to userAgent
        )

        val response = try {
            app.get(targetUrl, headers = headers).text
        } catch (t: Throwable) {
            return
        }

        // 1. Kiểm tra trực tiếp đường dẫn /SU/ trong mã HTML
        val directSuMatch = Regex("""["'](/SU/[^"']+)["']""").find(response)?.groupValues?.get(1)
            ?: Regex("""/SU/[a-zA-Z0-9_\-./]+""").find(response)?.value

        if (directSuMatch != null) {
            val streamUrl = if (directSuMatch.startsWith("http")) directSuMatch else "https://ssplay.net$directSuMatch"
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Server SS (SSPlay) - 1080p FHD",
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://ssplay.net/"
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf(
                        "Referer" to "https://ssplay.net/",
                        "User-Agent" to userAgent
                    )
                }
            )
            return
        }

        // 2. Tìm link .m3u8 trực tiếp
        val m3u8Match = Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").find(response)?.value
        if (m3u8Match != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "Server SS (SSPlay) - 1080p FHD",
                    url = m3u8Match,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://ssplay.net/"
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf(
                        "Referer" to "https://ssplay.net/",
                        "User-Agent" to userAgent
                    )
                }
            )
            return
        }

        // 3. QUAN TRỌNG: Quét TOÀN BỘ các khối Dean Edwards Packer (ssplay có tới 5 khối eval, khối video nằm ở khối số 4)
        val packedMatches = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]*?split\(['"]\|['"]\)\)\)""").findAll(response).toList()
        for (match in packedMatches) {
            val unpacked = unPackDeanEdwards(match.value)
            if (unpacked.isEmpty()) continue

            // Tìm /SU/ trong khối script đã giải mã
            val suMatch = Regex("""["'](/SU/[^"']+)["']""").find(unpacked)?.groupValues?.get(1)
                ?: Regex("""/SU/[a-zA-Z0-9_\-./]+""").find(unpacked)?.value

            if (suMatch != null) {
                val finalUrl = if (suMatch.startsWith("http")) suMatch else "https://ssplay.net$suMatch"
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Server SS (SSPlay) - 1080p FHD",
                        url = finalUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://ssplay.net/"
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "Referer" to "https://ssplay.net/",
                            "User-Agent" to userAgent
                        )
                    }
                )
                return
            }

            // Tìm thuộc tính "file": "/SU/..." hoặc ".m3u8"
            val fileMatch = Regex("""["']file["']\s*:\s*["']([^"']+)["']""").find(unpacked)?.groupValues?.get(1)
            if (fileMatch != null && (fileMatch.contains(".m3u8") || fileMatch.contains("/SU/"))) {
                val finalUrl = if (fileMatch.startsWith("http")) fileMatch else "https://ssplay.net$fileMatch"
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Server SS (SSPlay) - 1080p FHD",
                        url = finalUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://ssplay.net/"
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "Referer" to "https://ssplay.net/",
                            "User-Agent" to userAgent
                        )
                    }
                )
                return
            }
        }
    }
}
