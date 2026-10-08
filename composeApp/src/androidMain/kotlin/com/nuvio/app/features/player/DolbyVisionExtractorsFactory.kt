package com.nuvio.app.features.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

/**
 * [ExtractorsFactory] bọc extractor mặc định để mọi extractor trả về đều qua
 * [DolbyVisionRewritingExtractor].
 *
 * `DefaultExtractorsFactory` là class `final` nên không kế thừa được; bọc thẳng
 * giữ nguyên danh sách extractor media3 chọn (MP4, MKV, TS...).
 *
 * Chỉ áp cho định dạng tiến trình (file); HLS/DASH dùng extractor riêng, không đi
 * qua đường này — xem [supports].
 *
 * @param delegate factory gốc (đã cấu hình cờ TS extractor...)
 * @param stripDolbyVision bỏ lớp DV để Exo giải lớp nền (dùng khi máy không có decoder DV)
 * @param injectHdrColorInfo chèn tín hiệu màu HDR10 vào Format sau khi bỏ DV
 */
@OptIn(UnstableApi::class)
class DolbyVisionExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val stripDolbyVision: Boolean,
    private val injectHdrColorInfo: Boolean = false,
) : ExtractorsFactory {

    /**
     * Không đụng HLS/DASH.
     *
     * Chúng dùng extractor riêng (`media3-exoplayer-hls`/`-dash`); bọc ở đây sẽ
     * vô nghĩa. `Content-Type` kiểu file (video/mp4...) thì KHÔNG tin: nguồn
     * thật đã trả sai kiểu này cho MKV — `sniff()` của media3 đọc nội dung thật
     * nên không cần đoán.
     */
    internal fun supports(mimeType: String?): Boolean {
        if (!stripDolbyVision) return false
        if (mimeType == null) return true
        val m = mimeType.lowercase()
        return !(m.startsWith("application/vnd.apple.mpegurl") ||
            m.startsWith("application/x-mpegurl") ||
            m.startsWith("application/dash+xml") ||
            m.contains("mpegurl") ||
            m.contains("dash+xml"))
    }

    override fun createExtractors(): Array<Extractor> =
        wrap(delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> {
        val mimeType = responseHeaders["Content-Type"]?.firstOrNull()
            ?.substringBefore(';')
            ?.trim()
        val extractors = delegate.createExtractors(uri, responseHeaders)
        return if (supports(mimeType)) {
            wrap(extractors)
        } else {
            extractors
        }
    }

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> {
        if (!stripDolbyVision) return extractors
        return Array(extractors.size) { i ->
            DolbyVisionRewritingExtractor(
                extractors[i],
                enabled = true,
                injectHdrColorInfo = injectHdrColorInfo,
            )
        }
    }
}
