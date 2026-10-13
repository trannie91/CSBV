package com.blvietsub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BLVietsubPlugin: Plugin() {
    override fun load(context: Context) {
        // Đăng ký Provider chính BLVietsub
        registerMainAPI(BLVietsubProvider())
        // Đăng ký Extractor SSPlay (1080p FHD đa khối packer)
        registerExtractorAPI(SSPlayExtractor())
        // Đăng ký Extractor OK.ru (Server OK / Server DL 1080p FHD)
        registerExtractorAPI(OkRuExtractor())
        // Đăng ký Extractor VK (Server VK 1080p FHD)
        registerExtractorAPI(VkExtractor())
        // Đăng ký Extractor Dailymotion (Server DL 1080p FHD)
        registerExtractorAPI(DailymotionExtractor())
    }
}
