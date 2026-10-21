package com.nuvio.app.features.player

actual object NetworkProbe {
    actual suspend fun measure(
        target: ProbeTarget,
        onProgress: (ProbeProgress) -> Unit,
    ): ProbeOutcome {
        return ProbeOutcome(
            baseline = null,
            parallelRows = emptyList(),
            advice = ThroughputAdvice.Result(
                advice = ThroughputAdvice.Advice.UNKNOWN,
                bestChunkMb = 0,
                suggestedConnections = ThroughputAdvice.SUGGESTED_CONNECTIONS,
                message = "Đo tốc độ mạng chưa hỗ trợ trên iOS.",
            ),
        )
    }
}
