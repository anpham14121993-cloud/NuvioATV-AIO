package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

actual object NetworkProbe {

    private const val PARALLEL_PROBES = 4
    private const val READ_BUFFER_BYTES = 64 * 1024
    private const val CHUNK_UNBOUNDED = Long.MAX_VALUE

    private fun clientFor(seconds: Long): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(seconds + 5, TimeUnit.SECONDS)
        .callTimeout(seconds + 10, TimeUnit.SECONDS)
        .build()

    actual suspend fun measure(
        target: ProbeTarget,
        onProgress: (ProbeProgress) -> Unit,
    ): ProbeOutcome = withContext(Dispatchers.IO) {
        val length = resolveLength(target)
        if (length <= 0L) {
            return@withContext ProbeOutcome(
                baseline = null,
                parallelRows = emptyList(),
                advice = ThroughputAdvice.interpret(0.0, emptyList()),
            )
        }

        val seconds = ThroughputAdvice.TEST_SECONDS
        val baseline = measureBaseline(target, length, seconds)
        onProgress(ProbeProgress(baseline = baseline, parallelRows = emptyList(), isBaseline = true))

        val rows = mutableListOf<ThroughputAdvice.Row>()
        for (chunkMb in ThroughputAdvice.TEST_CHUNK_MB) {
            val row = measureParallel(target, length, seconds, chunkMb)
            if (row != null) {
                rows.add(row)
            }
            onProgress(ProbeProgress(baseline = baseline, parallelRows = rows.toList(), isBaseline = false))
        }

        val advice = ThroughputAdvice.interpret(
            baselineMbps = baseline?.megabitsPerSecond ?: 0.0,
            rows = rows,
        )
        ProbeOutcome(baseline, rows, advice)
    }

    private fun resolveLength(target: ProbeTarget): Long = runCatching {
        val request = Request.Builder()
            .url(target.url)
            .header("Range", "bytes=0-0")
            .apply { target.headers.forEach { (k, v) -> header(k, v) } }
            .build()
        clientFor(10L).newCall(request).execute().use { response ->
            response.header("Content-Range")
                ?.substringAfterLast('/')
                ?.trim()
                ?.toLongOrNull()
                ?: response.body?.contentLength()
                ?: 0L
        }
    }.getOrDefault(0L)

    private fun measureBaseline(
        target: ProbeTarget,
        length: Long,
        seconds: Long,
    ): ThroughputAdvice.Row? = runCatching {
        val bytes = AtomicLong(0L)
        val elapsed = streamRange(
            client = clientFor(seconds),
            target = target,
            start = 0L,
            chunkBytes = CHUNK_UNBOUNDED,
            length = length,
            seconds = seconds,
            onBytes = { bytes.addAndGet(it) }
        )
        if (elapsed <= 0.0) null
        else ThroughputAdvice.Row(0, bytes.get().toDouble() * 8.0 / elapsed / 1_000_000.0)
    }.getOrNull()

    private fun measureParallel(
        target: ProbeTarget,
        length: Long,
        seconds: Long,
        chunkMb: Int,
    ): ThroughputAdvice.Row? = runCatching {
        val chunkBytes = chunkMb.toLong() * 1024L * 1024L
        val total = AtomicLong(0L)
        val stop = AtomicBoolean(false)
        val segment = length / PARALLEL_PROBES

        val threads = (0 until PARALLEL_PROBES).map { i ->
            val start = segment * i
            val end = if (i == PARALLEL_PROBES - 1) length - 1 else start + segment - 1
            Thread {
                streamRange(
                    client = clientFor(seconds),
                    target = target,
                    start = start,
                    chunkBytes = chunkBytes,
                    length = end - start + 1,
                    seconds = seconds,
                    stop = stop,
                    onBytes = { total.addAndGet(it) }
                )
            }.apply { isDaemon = true; start() }
        }
        val elapsed = measureElapsed { threads.forEach { it.join() } }
        if (elapsed <= 0.0) null
        else ThroughputAdvice.Row(chunkMb, total.get().toDouble() * 8.0 / elapsed / 1_000_000.0)
    }.getOrNull()

    private fun streamRange(
        client: OkHttpClient,
        target: ProbeTarget,
        start: Long,
        chunkBytes: Long,
        length: Long,
        seconds: Long,
        stop: AtomicBoolean = AtomicBoolean(false),
        onBytes: (Long) -> Unit
    ): Double = measureElapsed {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        var cursor = start
        val end = start + length - 1
        while (cursor <= end && !stop.get() && System.nanoTime() < deadline) {
            val last = if (chunkBytes >= length) end else (cursor + chunkBytes - 1).coerceAtMost(end)
            val request = Request.Builder()
                .url(target.url)
                .header("Range", "bytes=$cursor-$last")
                .apply { target.headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use
                val body = response.body ?: return@use
                body.byteStream().use { input ->
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    while (!stop.get()) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        onBytes(read.toLong())
                        if (System.nanoTime() >= deadline) break
                    }
                }
            }
            cursor = last + 1
        }
    }

    private inline fun measureElapsed(block: () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000_000.0
    }
}
