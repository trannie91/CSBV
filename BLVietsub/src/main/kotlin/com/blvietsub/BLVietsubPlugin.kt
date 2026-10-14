package com.blvietsub

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BLVietsubPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BLVietsubProvider())
        registerExtractorAPI(SSPlayExtractor())
        registerExtractorAPI(DailymotionExtractor())
        registerExtractorAPI(OkRuExtractor())
        registerExtractorAPI(VkExtractor())
    }
}
