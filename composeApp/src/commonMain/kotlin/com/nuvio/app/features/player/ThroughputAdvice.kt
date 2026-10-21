package com.nuvio.app.features.player

object ThroughputAdvice {

    const val WIN_RATIO = 1.20
    const val SUGGESTED_CONNECTIONS = 4
    const val TEST_SECONDS = 8L
    val TEST_CHUNK_MB = listOf(1, 4, 8, 16)

    data class Row(
        val chunkMb: Int,
        val megabitsPerSecond: Double
    )

    enum class Advice {
        UNKNOWN,
        KEEP_SINGLE_CONNECTION,
        TURN_ON_PARALLEL,
        SOURCE_TOO_HEAVY
    }

    data class Result(
        val advice: Advice,
        val bestChunkMb: Int,
        val suggestedConnections: Int,
        val message: String
    )

    fun interpret(
        baselineMbps: Double,
        rows: List<Row>,
        videoBitrateMbps: Double? = null
    ): Result {
        val best = rows.filter { it.megabitsPerSecond > 0.0 }.maxByOrNull { it.megabitsPerSecond }
        if (baselineMbps <= 0.0 || best == null) {
            return Result(
                Advice.UNKNOWN, 0, SUGGESTED_CONNECTIONS,
                "Chưa đo được tốc độ. Thử lại, hoặc kiểm tra xem luồng vừa xem còn sống không."
            )
        }

        val bestMbps = best.megabitsPerSecond
        val beatsBaseline = bestMbps >= baselineMbps * WIN_RATIO

        if (videoBitrateMbps != null && videoBitrateMbps > 0.0 && bestMbps < videoBitrateMbps) {
            return Result(
                Advice.SOURCE_TOO_HEAVY,
                best.chunkMb,
                SUGGESTED_CONNECTIONS,
                "Cả lần đo nhanh nhất cũng chỉ đạt ${fmt(bestMbps)} Mbps, thấp hơn " +
                    "bitrate luồng ${fmt(videoBitrateMbps)} Mbps. " +
                    if (beatsBaseline) {
                        "Song song có giúp (nên bật, chunk ${best.chunkMb} MB) nhưng vẫn " +
                            "không đủ — hãy chọn bản nhẹ hơn hoặc nguồn khác."
                    } else {
                        "Bật song song cũng không giúp — luồng này vượt xa đường truyền. " +
                            "Hãy chọn bản nhẹ hơn hoặc nguồn khác."
                    }
            )
        }

        if (beatsBaseline) {
            return Result(
                Advice.TURN_ON_PARALLEL,
                best.chunkMb,
                SUGGESTED_CONNECTIONS,
                "Song song thắng: ${fmt(bestMbps)} Mbps so với ${fmt(baselineMbps)} Mbps " +
                    "khi chỉ một kết nối (chunk ${best.chunkMb} MB). " +
                    "Nên bật $SUGGESTED_CONNECTIONS kết nối."
            )
        }

        return Result(
            Advice.KEEP_SINGLE_CONNECTION,
            0,
            1,
            "Một kết nối đã đủ: ${fmt(baselineMbps)} Mbps, còn lần song song tốt nhất chỉ " +
                "${fmt(bestMbps)} Mbps. Để tắt song song."
        )
    }

    fun fmt(mbps: Double): String = String.format("%.1f", mbps)
}
