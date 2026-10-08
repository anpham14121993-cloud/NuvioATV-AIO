package com.nuvio.app.features.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Quyết định có nên bỏ lớp Dolby Vision để phát lớp nền HEVC/H.264 hay không.
 *
 * ## Vấn đề
 *
 * MKV báo Dolby Vision bằng `dvcC`/`dvvC`. `MatroskaExtractor` đọc cấu hình đó
 * rồi đổi mime track video thành `video/dolby-vision` — với mọi profile, kể cả
 * P8 vốn có lớp nền HEVC hợp lệ.
 *
 * Trên máy không có decoder DV: `MediaCodecUtil` không tìm thấy decoder cho
 * `video/dolby-vision`, ExoPlayer bỏ track hình, âm thanh vẫn chạy — triệu chứng
 * "có tiếng mà không có hình".
 *
 * ## Nguyên tắc: sửa ở tầng `Format`, không sửa ở codec selector
 *
 * Selector chỉ nhận mime, không thấy profile, nên không phân biệt được P8 (bỏ DV
 * là hợp lệ) với P5 (nền H.264, không tương thích ngược). Sửa ở tầng `Format`
 * thì đọc được `Format.codecs` (media3 đã điền từ `DolbyVisionConfig`).
 *
 * ## Thích ứng theo thiết bị
 *
 * [isDolbyVisionDecoderAvailable] quyết định có bọc extractor hay không:
 * máy có decoder DV thì giữ nguyên DV (Exo giải mã DV thật), máy không có thì
 * bỏ lớp DV để Exo giải lớp nền — chỉ khi Exo thất bại thật sự mới chuyển libmpv.
 *
 * Kết quả trung thực: khi bỏ DV thì luôn có hình, nhưng không phải DV thật:
 * - P8/P8.1 — nền HEVC hợp lệ, HDR10 thay Dolby Vision.
 * - P5 (`dvav`/`dva1`) — nền H.264, mất HDR.
 * - P7 (`dvhe`/`dvh1`) — DV thuần, HEVC giải được khung nhưng màu có thể sai.
 */
@OptIn(UnstableApi::class)
object DolbyVisionFallback {

    private const val HEVC_PREFIX = "hvc1"

    /** NAL unit type 33 = SPS trong HEVC. */
    private const val NAL_UNIT_TYPE_SPS = 33

    /**
     * Máy có decoder `video/dolby-vision` không.
     *
     * Dùng đúng đường query của track selector (`MediaCodecUtil.getDecoderInfos`),
     * nên kết quả khớp với những gì ExoPlayer sẽ tìm thấy — kể cả lọc theo khả
     * năng hiển thị HDR của màn hình mà media3 áp cho DV.
     */
    fun isDolbyVisionDecoderAvailable(): Boolean =
        runCatching {
            MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false)
                .isNotEmpty()
        }.getOrDefault(false)

    /**
     * Mime của lớp nền cho một chuỗi codec DV, hoặc null nếu không đoán được.
     *
     * Đọc từ `Format.codecs` mà media3 đã điền sẵn từ `DolbyVisionConfig`:
     * - `dvhe` / `dvh1` → nền HEVC
     * - `dvav` / `dva1` → nền H.264
     * - `hev1` / `avc3` → profile 8/9, media3 đã tự sinh tiền tố này
     */
    fun baseLayerMimeFor(dvCodecs: String?): String? {
        if (dvCodecs.isNullOrBlank()) return null
        val c = dvCodecs.lowercase()
        return when {
            c.startsWith("dvhe.") || c.startsWith("dvh1.") -> MimeTypes.VIDEO_H265
            c.startsWith("dvav.") || c.startsWith("dva1.") -> MimeTypes.VIDEO_H264
            c.startsWith("hev1.") || c.startsWith("hvc1.") -> MimeTypes.VIDEO_H265
            c.startsWith("avc3.") || c.startsWith("av01.") -> MimeTypes.VIDEO_H264
            else -> null
        }
    }

    /** Trả về profile DV, hoặc null nếu `codecs` không phải DV (`dvhe.05.06` → 5). */
    fun profileOf(codecs: String?): Int? {
        if (codecs.isNullOrBlank()) return null
        val parts = codecs.trim().lowercase().split('.')
        val head = parts.firstOrNull() ?: return null
        if (
            !(head.startsWith("dvhe") || head.startsWith("dvh1") ||
                head.startsWith("dvav") || head.startsWith("dva1") ||
                head.startsWith("hev1") || head.startsWith("avc3"))
        ) {
            return null
        }
        return parts.getOrNull(1)?.toIntOrNull()
    }

    /**
     * Có cần sửa `Format` này không — chỉ track video mime `video/dolby-vision`.
     *
     * Không đọc được profile thì vẫn bỏ DV: đánh đổi màu sai còn hơn mất hình.
     */
    fun needsRewrite(format: Format): Boolean =
        MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType, ignoreCase = true)

    /**
     * Trả về `Format` đã bỏ lớp Dolby Vision.
     *
     * Giữ nguyên `initializationData`: với MKV, nhánh `CODEC_ID_H265` đã parse
     * `hvcC` từ CodecPrivate trước khi DV đổi mime, nên dữ liệu khởi tạo vẫn là
     * của HEVC và dùng được nguyên vẹn (thiếu nó decoder sẽ ra hình xanh/đen).
     *
     * @param format format gốc từ extractor
     * @param fallbackMime mime của lớp nền, do [baseLayerMimeFor] quyết định
     * @param injectHdrColorInfo chèn tín hiệu màu HDR10 (BT.2020/PQ) vào Format
     */
    fun rewrite(
        format: Format,
        fallbackMime: String,
        injectHdrColorInfo: Boolean = false,
    ): Format {
        val codecs = hevcCodecsString(format)
        val builder = format.buildUpon()
            .setSampleMimeType(fallbackMime)
            // Format.codecs phải khớp mime mới, không thì media3 tính profile/level ra số vô nghĩa.
            .setCodecs(codecs)
            .setSelectionFlags(format.selectionFlags and C.SELECTION_FLAG_DEFAULT.inv())

        val containerMime = format.containerMimeType
        if (containerMime != null &&
            MimeTypes.VIDEO_DOLBY_VISION.equals(containerMime, ignoreCase = true)
        ) {
            builder.setContainerMimeType(fallbackMime)
        }

        if (injectHdrColorInfo && shouldInjectHdrColorInfo(format)) {
            builder.setColorInfo(HDR10_COLOR_INFO)
        }
        return builder.build()
    }

    /**
     * Có cần chèn `colorInfo` HDR10 không.
     *
     * Lớp nền sau khi bỏ DV vẫn là HDR10 thật — SPS VUI trong dòng bit giữ nguyên
     * vì không đụng vào sample. Nhưng `Format.colorInfo` thường null trên container
     * DV (MKV remux để HDR trong RPU, không khai trong header). Nhiều màn hình
     * quyết định chế độ HDR theo `colorInfo` của framework chứ không theo SPS nên
     * ở lại SDR và PQ hiện ra nhạt/ám tím.
     *
     * Không chèn khi format đã khai PQ: tín hiệu đã đúng, ghi đè có thể làm mất
     * `hdrStaticInfo` mà file cung cấp.
     */
    fun shouldInjectHdrColorInfo(format: Format): Boolean {
        val transfer = format.colorInfo?.colorTransfer ?: return true
        return transfer != C.COLOR_TRANSFER_ST2084
    }

    /**
     * Tín hiệu màu HDR10 cố định: BT.2020, limited range, PQ.
     *
     * Không kèm `hdrStaticInfo`: lớp nền đã mang metadata mastering trong dòng
     * bit, đặt `hdrStaticInfo` rỗng có thể làm framework bỏ qua.
     */
    private val HDR10_COLOR_INFO = ColorInfo.Builder()
        .setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorRange(C.COLOR_RANGE_LIMITED)
        .setColorTransfer(C.COLOR_TRANSFER_ST2084)
        .build()

    /**
     * Dựng chuỗi codec HEVC (vd `hvc1.2.4.L153.B0`) từ SPS trong `hvcC`.
     *
     * Trả null nếu không dựng được — thiếu `codecs` chỉ mất thông tin hiển thị,
     * HEVC decoder vẫn chạy. Mọi đọc byte bọc try vì `hvcC` hỏng không được làm
     * chết extractor.
     *
     * Layout theo `HevcConfig.parse` của media3: 22 byte cố định, 1 byte
     * numOfArrays, mỗi mảng: 1 byte NAL type + 2 byte numNalus + (2 byte len + payload)*.
     */
    private fun hevcCodecsString(format: Format): String? {
        val initData = format.initializationData
        if (initData.isNullOrEmpty()) return null
        val buffer = initData[0]
        return try {
            var pos = 22
            if (buffer.size < pos + 1) return null
            val numArrays = buffer[pos].toInt() and 0xFF
            pos++
            repeat(numArrays) {
                if (pos + 3 > buffer.size) return@repeat
                val nalType = buffer[pos].toInt() and 0x3F
                val numNalus = ((buffer[pos + 1].toInt() and 0xFF) shl 8) or
                    (buffer[pos + 2].toInt() and 0xFF)
                pos += 3
                if (nalType == NAL_UNIT_TYPE_SPS && numNalus > 0) {
                    if (pos + 2 > buffer.size) return null
                    val nalLength = ((buffer[pos].toInt() and 0xFF) shl 8) or
                        (buffer[pos + 1].toInt() and 0xFF)
                    val start = pos + 2
                    val end = start + nalLength
                    if (nalLength <= 0 || end > buffer.size) return null
                    val sps = NalUnitUtil.parseH265SpsNalUnit(buffer, start, end, null)
                        ?: return null
                    val ptl = sps.profileTierLevel ?: return null
                    val tier = if (ptl.generalTierFlag) "H" else "L"
                    val head = "$HEVC_PREFIX." + ptl.generalProfileSpace + "." +
                        ptl.generalProfileIdc + "." + tier
                    return head + "." + ptl.generalLevelIdc
                }
                repeat(numNalus) {
                    if (pos + 2 > buffer.size) return@repeat
                    val len = ((buffer[pos].toInt() and 0xFF) shl 8) or
                        (buffer[pos + 1].toInt() and 0xFF)
                    pos += 2 + len
                }
            }
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: NullPointerException) {
            // parseH265SpsNalUnit trả null hoặc vpsData null không hỗ trợ.
            null
        }
    }

    /** Log/debug: "DV profile=5 base=video/hevc". */
    fun describe(format: Format): String {
        val p = profileOf(format.codecs)
        val base = baseLayerMimeFor(format.codecs)
        return "DV profile=${p ?: "?"} base=${base ?: "khong ro"}"
    }
}
