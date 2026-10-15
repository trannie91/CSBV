package com.blvietsub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BLVietsubPlugin: Plugin() {
    override fun load(context: Context) {
        // Đăng ký Provider chính cào toàn bộ danh mục & tập từ https://www.blvietsub.vip/
        registerMainAPI(BLVietsubProvider())

        // Đăng ký các Extractor chuyên dụng 1080p FHD
        registerExtractorAPI(SSPlayExtractor())
        registerExtractorAPI(DailymotionExtractor())
        registerExtractorAPI(OkRuExtractor())
        registerExtractorAPI(VkExtractor())
    }
}
