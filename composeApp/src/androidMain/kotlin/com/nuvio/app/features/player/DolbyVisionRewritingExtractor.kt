package com.nuvio.app.features.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput

/**
 * Bọc [Extractor] để đổi mime track Dolby Vision thành mime lớp nền, ngay tại chỗ
 * `Format` được phát ra.
 *
 * ## Vì sao can thiệp ở đây
 *
 * media3 không có hook cho phép đổi `Format` sau khi extractor tạo ra nó, mà
 * `MatroskaExtractor` là class nội bộ gói trong AAR. Bọc `TrackOutput` và sửa
 * `Format` trước khi nó tới track selector là đường vào sạch.
 *
 * Sửa ở `MediaCodecSelector` sẽ sai: selector chỉ nhận mime, không thấy profile.
 *
 * ## An toàn
 *
 * - Chỉ đụng track có mime `video/dolby-vision`; track khác đi qua nguyên vẹn.
 * - `initializationData` giữ nguyên (media3 đã parse `hvcC` từ CodecPrivate
 *   trước khi DV đổi mime, nên dữ liệu khởi tạo vẫn đúng cho HEVC).
 * - Mọi thao tác đọc byte đều bọc try, `hvcC` hỏng thì bỏ qua.
 */
@OptIn(UnstableApi::class)
class DolbyVisionRewritingExtractor(
    private val delegate: Extractor,
    /** Bật bỏ lớp Dolby Vision hay không. */
    private val enabled: Boolean,
    /** Sau khi bỏ DV thì có chèn tín hiệu màu HDR10 vào `Format` hay không. */
    private val injectHdrColorInfo: Boolean = false,
) : Extractor by delegate {

    override fun init(output: ExtractorOutput) {
        // Chỉ bọc output khi cần; tắt thì trả nguyên vẹn, không tốn overhead.
        if (enabled) {
            delegate.init(RewritingExtractorOutput(output, injectHdrColorInfo))
        } else {
            delegate.init(output)
        }
    }

    private class RewritingExtractorOutput(
        private val delegate: ExtractorOutput,
        private val injectHdrColorInfo: Boolean,
    ) : ExtractorOutput by delegate {

        override fun track(id: Int, type: Int): TrackOutput {
            val trackOutput = delegate.track(id, type)
            return if (type == C.TRACK_TYPE_VIDEO) {
                RewritingTrackOutput(trackOutput, injectHdrColorInfo)
            } else {
                trackOutput
            }
        }
    }

    private class RewritingTrackOutput(
        private val delegate: TrackOutput,
        private val injectHdrColorInfo: Boolean,
    ) : TrackOutput by delegate {

        override fun format(format: Format) {
            delegate.format(rewriteIfDolbyVision(format))
        }

        private fun rewriteIfDolbyVision(format: Format): Format {
            if (!DolbyVisionFallback.needsRewrite(format)) return format
            val fallbackMime = DolbyVisionFallback.baseLayerMimeFor(format.codecs)
                // Không đoán được lớp nền thì mặc định HEVC: phần lớn DV là HEVC
                // và hơn cả có hình.
                ?: MimeTypes.VIDEO_H265
            val out = DolbyVisionFallback.rewrite(format, fallbackMime, injectHdrColorInfo)
            android.util.Log.i(
                TAG,
                "Bo lop Dolby Vision: ${DolbyVisionFallback.describe(format)} " +
                    "-> $fallbackMime, giu initializationData=${out.initializationData != null}, " +
                    "chen colorInfo HDR=${out.colorInfo?.colorTransfer}",
            )
            return out
        }

        private companion object {
            const val TAG = "DolbyVision"
        }
    }
}
