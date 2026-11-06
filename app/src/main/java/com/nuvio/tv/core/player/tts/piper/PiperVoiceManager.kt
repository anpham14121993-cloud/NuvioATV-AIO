package com.nuvio.tv.core.player.tts.piper

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Manages downloading, deleting, checking, and audio previewing of Vietnamese Piper TTS voices.
 */
object PiperVoiceManager {
    private const val TAG = "PiperVoiceManager"
    private const val MIN_MODEL_SIZE = 10_000_000L // ~10 MB minimum for valid ONNX model
    private const val MIN_CONFIG_SIZE = 100L

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var activePreviewPlayer: MediaPlayer? = null
    @Volatile
    private var activeAfd: AssetFileDescriptor? = null
    private val previewLock = Any()

    fun getModelsDir(context: Context): File {
        return File(context.filesDir, "piper_models").apply { mkdirs() }
    }

    fun isVoiceDownloaded(context: Context, voiceId: String): Boolean {
        val dir = getModelsDir(context)
        val model = File(dir, "$voiceId.onnx")
        val config = File(dir, "$voiceId.onnx.json")
        return model.exists() && model.length() >= MIN_MODEL_SIZE && config.exists() && config.length() >= MIN_CONFIG_SIZE
    }

    fun getVoiceFileSizeMb(context: Context, voiceId: String): Float {
        val dir = getModelsDir(context)
        val model = File(dir, "$voiceId.onnx")
        return if (model.exists()) model.length() / (1024f * 1024f) else 0f
    }

    fun deleteVoice(context: Context, voiceId: String): Boolean {
        val dir = getModelsDir(context)
        val model = File(dir, "$voiceId.onnx")
        val config = File(dir, "$voiceId.onnx.json")
        val r1 = runCatching { model.delete() }.getOrDefault(false)
        val r2 = runCatching { config.delete() }.getOrDefault(false)
        return r1 || r2
    }

    suspend fun downloadVoice(
        context: Context,
        voiceId: String,
        onProgress: (Float) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val dir = getModelsDir(context)
        val modelFile = File(dir, "$voiceId.onnx")
        val configFile = File(dir, "$voiceId.onnx.json")

        val modelUrl = PiperVoiceCatalog.getModelUrl(voiceId)
        val configUrl = PiperVoiceCatalog.getConfigUrl(voiceId)

        try {
            // 1. Download config JSON
            downloadHttpStream(configUrl, configFile) { /* small, no progress needed */ }

            // 2. Download ONNX model with live progress
            downloadHttpStream(modelUrl, modelFile) { p ->
                onProgress(p)
            }

            val valid = modelFile.exists() && modelFile.length() >= MIN_MODEL_SIZE &&
                        configFile.exists() && configFile.length() >= MIN_CONFIG_SIZE
            if (!valid) {
                runCatching { modelFile.delete() }
                runCatching { configFile.delete() }
            }
            valid
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to download voice $voiceId: ${t.message}", t)
            runCatching { modelFile.delete() }
            runCatching { configFile.delete() }
            false
        }
    }

    private fun downloadHttpStream(urlStr: String, dest: File, onProgress: (Float) -> Unit) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        val req = Request.Builder()
            .url(urlStr)
            .header("User-Agent", "NuvioATV/1.1.0")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code}: ${resp.message} for $urlStr")
            }
            val body = resp.body ?: throw IOException("Empty response body from $urlStr")
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    var lastReported = 0f

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val progress = downloadedBytes.toFloat() / totalBytes.toFloat()
                            if (progress - lastReported >= 0.02f || progress >= 0.99f) {
                                lastReported = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
        }

        if (tmp.length() > 0) {
            dest.delete()
            if (!tmp.renameTo(dest)) {
                FileInputStream(tmp).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                tmp.delete()
            }
        } else {
            tmp.delete()
            throw IOException("Downloaded 0 bytes")
        }
    }

    /**
     * Plays a voice sample preview.
     * Looks first in asset directory "voice_samples/{voiceId}.wav".
     */
    fun playVoicePreview(
        context: Context,
        voiceId: String,
        onPlaybackStarted: () -> Unit = {},
        onPlaybackEnded: () -> Unit = {}
    ): Boolean {
        stopVoicePreview()

        synchronized(previewLock) {
            try {
                val afd = runCatching { context.assets.openFd("voice_samples/$voiceId.wav") }.getOrNull()

                if (afd != null) {
                    activeAfd = afd
                    val player = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                .build()
                        )
                        setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        setOnCompletionListener {
                            stopVoicePreview()
                            onPlaybackEnded()
                        }
                        setOnErrorListener { _, _, _ ->
                            stopVoicePreview()
                            onPlaybackEnded()
                            true
                        }
                        prepare()
                        start()
                    }
                    activePreviewPlayer = player
                    onPlaybackStarted()
                    return true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not play asset preview for $voiceId: ${t.message}")
                stopVoicePreview()
            }

            return false
        }
    }

    fun stopVoicePreview() {
        synchronized(previewLock) {
            runCatching {
                activePreviewPlayer?.stop()
                activePreviewPlayer?.release()
            }
            activePreviewPlayer = null
            runCatching {
                activeAfd?.close()
            }
            activeAfd = null
        }
    }
}
