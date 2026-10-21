package com.nuvio.app.features.player

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource

internal object PlatformPlaybackDataSourceFactory {
    fun create(
        context: Context,
        sourceUrl: String = "",
        defaultRequestHeaders: Map<String, String> = emptyMap(),
        defaultResponseHeaders: Map<String, String> = emptyMap(),
        useYoutubeChunkedPlayback: Boolean = false,
        useLongReadTimeout: Boolean = false,
        externalSubtitles: List<com.nuvio.app.features.streams.StreamSubtitle> = emptyList(),
    ): DataSource.Factory {
        val parallelConnections = PlayerSettingsRepository.uiState.value.parallelConnections
        val chunkSizeMb = PlayerSettingsRepository.uiState.value.chunkSizeMb
        val chunkCount = PlayerSettingsRepository.uiState.value.chunkCount
        val httpFactory = PlayerPlaybackNetworking.createHttpDataSourceFactory(
            sourceUrl = sourceUrl,
            defaultHeaders = defaultRequestHeaders,
            useLongReadTimeout = useLongReadTimeout,
            parallelConnections = parallelConnections,
            chunkSizeMb = chunkSizeMb,
            chunkCount = chunkCount,
        )
        val subtitleHeaderFactory = SubtitleRequestHeaderDataSourceFactory(
            upstreamFactory = httpFactory,
            externalSubtitles = externalSubtitles
        )
        val baseFactory: DataSource.Factory = DefaultDataSource.Factory(context, subtitleHeaderFactory)
        return if (defaultResponseHeaders.isEmpty()) {
            baseFactory
        } else {
            ResponseHeaderOverridingDataSourceFactory(
                upstreamFactory = baseFactory,
                defaultResponseHeaders = defaultResponseHeaders,
            )
        }
    }
}
