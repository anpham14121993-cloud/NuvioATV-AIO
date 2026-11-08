package com.nuvio.tv.core.player.tts.google

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * High-speed, lightweight Vietnamese Google TTS client directly matching Vax architecture (l02 / k02).
 *
 * Uses Google Translate audio endpoint with client=tw-ob to synthesize crystal-clear, natural
 * Vietnamese speech with 0 MB disk models and near-zero CPU load (~150ms network latency).
 */
object GoogleTtsApi {
    private const val TAG = "GoogleTtsApi"
    private const val MAX_CHUNK_CHARS = 190

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Splits long sentences at punctuation or spaces to respect the 200-char limit of Google TTS.
     */
    fun splitText(text: String): List<String> {
        val trimmed = text.trim().trimEnd('?', '!', '.', '…')
        if (trimmed.length <= MAX_CHUNK_CHARS) {
            return listOf(trimmed)
        }

        val chunks = mutableListOf<String>()
        val words = trimmed.split(Regex("\\s+"))
        val sb = StringBuilder()

        for (word in words) {
            if (sb.isNotEmpty() && sb.length + word.length + 1 > MAX_CHUNK_CHARS) {
                chunks.add(sb.toString())
                sb.clear()
            }
            if (sb.isNotEmpty()) sb.append(" ")
            sb.append(word)
        }
        if (sb.isNotEmpty()) {
            chunks.add(sb.toString())
        }
        return chunks
    }

    /**
     * Synthesizes [text] to an MP3 file saved in context.cacheDir/tts_cache/.
     * Returns null if synthesis failed.
     */
    fun synthesizeToFile(context: Context, text: String): File? {
        val clean = text.trim()
        if (clean.isBlank()) return null

        val cacheDir = File(context.cacheDir, "tts_cache").apply { mkdirs() }
        val destFile = File(cacheDir, "google_tts_${UUID.randomUUID()}.mp3")
        val chunks = splitText(clean)

        return try {
            FileOutputStream(destFile).use { outStream ->
                for (chunk in chunks) {
                    if (chunk.isBlank()) continue
                    val encoded = URLEncoder.encode(chunk, "UTF-8")
                    val url = "https://translate.google.com/translate_tts?ie=UTF-8&tl=vi&client=tw-ob&q=$encoded"
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(TAG, "Google TTS chunk HTTP error ${response.code} for: $chunk")
                            return null
                        }
                        val body = response.body ?: return null
                        body.byteStream().use { input ->
                            input.copyTo(outStream)
                        }
                    }
                }
            }

            if (destFile.exists() && destFile.length() > 0) {
                destFile
            } else {
                destFile.delete()
                null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Google TTS synthesis failed: ${t.message}", t)
            runCatching { destFile.delete() }
            null
        }
    }
}
