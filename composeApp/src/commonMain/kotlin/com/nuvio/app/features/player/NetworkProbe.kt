package com.nuvio.app.features.player

data class ProbeTarget(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

data class ProbeProgress(
    val baseline: ThroughputAdvice.Row? = null,
    val parallelRows: List<ThroughputAdvice.Row> = emptyList(),
    val isBaseline: Boolean = false,
)

data class ProbeOutcome(
    val baseline: ThroughputAdvice.Row?,
    val parallelRows: List<ThroughputAdvice.Row>,
    val advice: ThroughputAdvice.Result,
)

expect object NetworkProbe {
    suspend fun measure(
        target: ProbeTarget,
        onProgress: (ProbeProgress) -> Unit = {},
    ): ProbeOutcome
}

object LastProbeStreamStore {
    @Volatile
    var lastUrl: String? = null

    @Volatile
    var lastHeaders: Map<String, String> = emptyMap()

    fun record(url: String, headers: Map<String, String>) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            lastUrl = url
            lastHeaders = headers
        }
    }

    fun target(): ProbeTarget? {
        val url = lastUrl ?: return null
        return ProbeTarget(url = url, headers = lastHeaders)
    }
}
