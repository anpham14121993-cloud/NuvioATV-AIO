package com.nuvio.app.features.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

@UnstableApi
data class PlaybackHttpOptions(
    val parallelConnections: Int = 1,
    val chunkSizeMb: Int = 1,
    val chunkCount: Int = 1,
)

@UnstableApi
class NuvioOkHttpDataSource(
    private val client: OkHttpClient,
    private val chunkSizeBytes: Int,
    private val userAgent: String?,
) : BaseDataSource(true), HttpDataSource {

    private var httpResponse: okhttp3.Response? = null
    private var input: InputStream? = null
    private var currentUri: Uri? = null
    private var bytesRemaining: Long = 0
    private var bytesServed: Long = 0L
    private var openStart: Long = 0L
    private var currentDataSpec: DataSpec? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        opened = true

        val target = dataSpec.uri
        currentUri = target
        currentDataSpec = dataSpec
        bytesServed = 0L
        bytesRemaining = C.LENGTH_UNSET.toLong()

        discardPreviousSource()

        val start = if (dataSpec.position == C.POSITION_UNSET.toLong()) 0L else dataSpec.position
        openStart = start

        return openViaHttp(dataSpec, start)
    }

    private fun openViaHttp(dataSpec: DataSpec, start: Long): Long {
        val target = dataSpec.uri
        currentUri = target
        bytesServed = 0L

        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            null
        } else {
            start + dataSpec.length - 1
        }

        val builder = Request.Builder()
            .url(target.toString())
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .apply {
                dataSpec.httpRequestHeaders?.forEach { (key, value) ->
                    if (!key.equals("Range", ignoreCase = true)) header(key, value)
                }
                extraHeaders.forEach { (k, v) ->
                    if (!k.equals("Range", ignoreCase = true)) header(k, v)
                }
                if (start != 0L || end != null) {
                    header(
                        "Range",
                        if (end != null) "bytes=$start-$end" else "bytes=$start-"
                    )
                }
            }

        val res = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            closeQuietly()
            throw HttpDataSourceException.createForIOException(
                e, dataSpec, HttpDataSourceException.TYPE_OPEN
            )
        }
        httpResponse = res

        val code = res.code
        if (code != 200 && code != 206) {
            res.close()
            httpResponse = null
            closeQuietly()
            throw HttpDataSourceException(
                "Nguồn phát trả về lỗi HTTP $code", dataSpec,
                HttpDataSourceException.TYPE_OPEN, code
            )
        }

        val partial = code == 206
        val declared = res.body?.contentLength() ?: -1L

        val stream = res.body?.byteStream()
        if (stream == null) {
            closeQuietly()
            throw DataSourceException(
                "Nguồn phát trả về nội dung rỗng", DataSourceException.POSITION_OUT_OF_RANGE
            )
        }
        input = stream

        var skipped = 0L
        if (!partial && start > 0) {
            val scratch = ByteArray(minOf(chunkSizeBytes, 64 * 1024))
            while (skipped < start) {
                val want = minOf(scratch.size.toLong(), start - skipped).toInt()
                val n = stream.read(scratch, 0, want)
                if (n <= 0) break
                skipped += n
            }
            bytesTransferred(skipped.toInt())
        }

        bytesRemaining = resolveRemaining(
            httpCode = code,
            contentRangeHeader = res.header("Content-Range"),
            contentLength = declared,
            start = start,
            skipped = skipped
        )

        return bytesRemaining
    }

    private fun resolveRemaining(
        httpCode: Int,
        contentRangeHeader: String?,
        contentLength: Long,
        start: Long,
        skipped: Long
    ): Long {
        if (httpCode == 206) {
            contentRangeHeader?.let { range ->
                val total = range.substringAfterLast('/').trim().toLongOrNull()
                if (total != null && total > 0) {
                    val rangeStart = range.substringBefore('-').substringAfterLast(' ').trim().toLongOrNull() ?: start
                    return (total - rangeStart).coerceAtLeast(0L)
                }
            }
        }
        if (contentLength > 0) {
            return (contentLength - skipped).coerceAtLeast(0L)
        }
        return C.LENGTH_UNSET.toLong()
    }

    private fun reopenAfterIoError(): InputStream? {
        val spec = currentDataSpec ?: return null
        val resumeAt = openStart + bytesServed
        val resumed = spec.subrange(resumeAt)
        repeat(READ_RESUME_ATTEMPTS) { attempt ->
            runCatching {
                input?.close()
                httpResponse?.close()
            }
            input = null
            httpResponse = null
            if (runCatching { openViaHttp(resumed, resumeAt) }.isSuccess) {
                bytesServed = 0L
                openStart = resumeAt
                return input
            }
        }
        return null
    }

    private fun discardPreviousSource() {
        try { input?.close() } catch (_: IOException) {}
        input = null
        try { httpResponse?.close() } catch (_: IOException) {}
        httpResponse = null
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val stream = input ?: throw DataSourceException(
            "Chưa mở DataSource, phải gọi open() trước", DataSourceException.POSITION_OUT_OF_RANGE
        )
        val read = try {
            stream.read(buffer, offset, minOf(length, chunkSizeBytes))
        } catch (io: IOException) {
            val recovered = reopenAfterIoError()
            if (recovered == null) {
                throw HttpDataSourceException.createForIOException(
                    io, currentDataSpec ?: throw io, HttpDataSourceException.TYPE_READ
                )
            }
            recovered.read(buffer, offset, minOf(length, chunkSizeBytes))
        }
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        bytesTransferred(read)
        bytesServed += read
        return read
    }

    private val extraHeaders = mutableMapOf<String, String>()

    override fun setRequestProperty(key: String, value: String) {
        extraHeaders[key] = value
    }

    override fun clearRequestProperty(key: String) {
        extraHeaders.remove(key)
    }

    override fun clearAllRequestProperties() {
        extraHeaders.clear()
    }

    override fun getUri(): Uri? = currentUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        httpResponse?.headers?.toMultimap() ?: emptyMap()

    override fun getResponseCode(): Int = httpResponse?.code ?: -1

    override fun close() {
        try { input?.close() } catch (_: IOException) {}
        input = null
        try { httpResponse?.close() } catch (_: IOException) {}
        httpResponse = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun closeQuietly() {
        try { input?.close() } catch (_: IOException) {}
        input = null
        try { httpResponse?.close() } catch (_: IOException) {}
        httpResponse = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    companion object {
        private const val READ_RESUME_ATTEMPTS = 3
    }
}

@UnstableApi
class NuvioOkHttpDataSourceFactory(
    private val clientProvider: () -> OkHttpClient,
    private val chunkSizeBytes: Int,
    private val userAgent: String,
) : DataSource.Factory {

    private val defaultRequestProperties = mutableMapOf<String, String>()

    fun setDefaultRequestProperties(properties: Map<String, String>) {
        defaultRequestProperties.clear()
        defaultRequestProperties.putAll(properties)
    }

    override fun createDataSource(): DataSource {
        val ds = NuvioOkHttpDataSource(clientProvider(), chunkSizeBytes, userAgent)
        defaultRequestProperties.forEach { (k, v) -> ds.setRequestProperty(k, v) }
        return ds
    }
}
