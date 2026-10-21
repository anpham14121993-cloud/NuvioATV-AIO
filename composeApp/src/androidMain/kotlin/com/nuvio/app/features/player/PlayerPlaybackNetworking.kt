package com.nuvio.app.features.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.app.core.diagnostics.SentryNetworkBreadcrumbInterceptor
import com.nuvio.app.core.network.IPv4FirstDns
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object PlayerPlaybackNetworking {
    internal const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val DEFAULT_STREAM_HEADERS = mapOf(
        "User-Agent" to DEFAULT_USER_AGENT,
    )

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val playbackHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    private fun buildPlaybackHttpClient(parallelConnections: Int): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = parallelConnections * 2
            maxRequestsPerHost = parallelConnections
        }
        val connectionPool = ConnectionPool(
            maxIdleConnections = (parallelConnections * 2).coerceIn(8, 64),
            keepAliveDuration = 5,
            timeUnit = TimeUnit.MINUTES
        )
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .dns(IPv4FirstDns())
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(playbackHostnameVerifier)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor(SentryNetworkBreadcrumbInterceptor())
            .build()
    }

    private val playbackHttpClientCache = mutableMapOf<Int, OkHttpClient>()

    private fun getPlaybackHttpClient(parallelConnections: Int): OkHttpClient {
        return playbackHttpClientCache.getOrPut(parallelConnections) {
            buildPlaybackHttpClient(parallelConnections)
        }
    }

    private fun getLoopbackPlaybackHttpClient(parallelConnections: Int): OkHttpClient {
        val baseClient = getPlaybackHttpClient(parallelConnections)
        return baseClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request()
                val requestChain = if (isLoopbackHost(request.url.host)) {
                    chain.withReadTimeout(65, TimeUnit.SECONDS)
                } else {
                    chain
                }
                requestChain.proceed(request)
            }
            .build()
    }

    fun createHttpDataSourceFactory(
        sourceUrl: String = "",
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
        parallelConnections: Int = 1,
        chunkSizeMb: Int = 1,
        chunkCount: Int = 1,
    ): DataSource.Factory {
        val requestHeaders = sanitizeHeaders(defaultHeaders)
        LastProbeStreamStore.record(url = sourceUrl, headers = requestHeaders)
        val baseClient = if (useLongReadTimeout)
            getLoopbackPlaybackHttpClient(parallelConnections)
        else
            getPlaybackHttpClient(parallelConnections)
        val client = requestHeaders.headerValue("Authorization")?.let { authorization ->
            baseClient.newBuilder()
                .addNetworkInterceptor { chain ->
                    val request = chain.request()
                    if (request.header("Authorization") == null) {
                        chain.proceed(
                            request.newBuilder()
                                .header("Authorization", authorization)
                                .build()
                        )
                    } else {
                        chain.proceed(request)
                    }
                }
                .build()
        } ?: baseClient

        val chunkSizeBytes = (chunkSizeMb * 1024 * 1024).coerceIn(64 * 1024, 64 * 1024 * 1024)
        val userAgent = requestHeaders.headerValue("User-Agent") ?: DEFAULT_USER_AGENT

        return NuvioOkHttpDataSourceFactory(
            clientProvider = { client },
            chunkSizeBytes = chunkSizeBytes,
            userAgent = userAgent,
        ).apply {
            setDefaultRequestProperties(requestHeaders)
        }
    }

    fun createDataSourceFactory(
        context: Context,
        sourceUrl: String = "",
        defaultHeaders: Map<String, String> = emptyMap(),
        useLongReadTimeout: Boolean = false,
    ): DataSource.Factory {
        val parallelConnections = PlayerSettingsRepository.uiState.value.parallelConnections
        val chunkSizeMb = PlayerSettingsRepository.uiState.value.chunkSizeMb
        val chunkCount = PlayerSettingsRepository.uiState.value.chunkCount
        return DefaultDataSource.Factory(
            context,
            createHttpDataSourceFactory(sourceUrl, defaultHeaders, useLongReadTimeout, parallelConnections, chunkSizeMb, chunkCount),
        )
    }

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null,
    ): HttpURLConnection {
        val mergedHeaders = withDefaultUserAgent(headers)
        return (URL(url).openConnection() as HttpURLConnection).apply {
            if (this is HttpsURLConnection) {
                sslSocketFactory = sslContext.socketFactory
                hostnameVerifier = playbackHostnameVerifier
            }
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", mergedHeaders.headerValue("User-Agent") ?: DEFAULT_USER_AGENT)
            mergedHeaders.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }

    private fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapNotNull { (rawKey, rawValue) ->
            val key = rawKey.trim()
            val value = rawValue.trim()
            if (key.isBlank() || value.isBlank() || key.equals("Range", ignoreCase = true)) {
                null
            } else {
                key to value
            }
        }.toMap()

    private fun isLoopbackHost(host: String): Boolean = when (host.lowercase()) {
        "127.0.0.1", "localhost", "::1" -> true
        else -> false
    }

    private fun withDefaultUserAgent(headers: Map<String, String>): Map<String, String> {
        val sanitized = sanitizeHeaders(headers)
        if (sanitized.headerValue("User-Agent") != null) return sanitized
        return DEFAULT_STREAM_HEADERS + sanitized
    }

    private fun Map<String, String>.headerValue(name: String): String? =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
}
