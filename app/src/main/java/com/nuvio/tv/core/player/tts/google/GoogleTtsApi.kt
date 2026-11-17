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

    data class SynthesisResult(
        val file: File?,
        val error: String? = null,
    )

    @Volatile
    private var connectionWarmed = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Pre-warms DNS + TCP + TLS connection to Google TTS so the first live cue avoids
     * cold-start handshake latency (~300-700ms). Safe to call from any background thread.
     */
    fun warmUpConnection() {
        if (connectionWarmed) return
        connectionWarmed = true
        runCatching {
            val request = Request.Builder()
                .url("https://translate.google.com/translate_tts?ie=UTF-8&tl=vi&client=tw-ob&q=a")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                .build()
            httpClient.newCall(request).execute().close()
        }
    }

    /**
     * Splits long sentences at punctuation or spaces to respect the 200-char limit of Google TTS.
     */
    fun splitText(text: String): List<String> {
        val trimmed = text.trim().trimEnd('?', '!', '.', '…')
        if (trimmed.isBlank()) {
            return emptyList()
        }
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

    private fun looksLikeAudio(bytes: ByteArray): Boolean {
        if (bytes.size <= 128) return false
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        // Reject obvious HTML/JSON/XML error payloads even if HTTP 200.
        if (b0 == '<'.code || b0 == '{'.code) return false
        // MPEG frame sync (0xFFE0), ID3 tag, RIFF/WAV, or OggS
        val isMpegSync = b0 == 0xFF && (b1 and 0xE0) == 0xE0
        val isId3 = b0 == 'I'.code && b1 == 'D'.code && b2 == '3'.code
        val isRiff = b0 == 'R'.code && b1 == 'I'.code && b2 == 'F'.code
        val isOgg = b0 == 'O'.code && b1 == 'g'.code && b2 == 'g'.code
        return isMpegSync || isId3 || isRiff || isOgg
    }

    private fun fetchChunkAudio(encoded: String): Pair<ByteArray?, String?> {
        val urls = listOf(
            "https://translate.google.com/translate_tts?ie=UTF-8&tl=vi&client=tw-ob&q=$encoded",
            "https://translate.googleapis.com/translate_tts?ie=UTF-8&tl=vi&client=gtx&q=$encoded",
        )
        var lastError: String? = null
        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}"
                        Log.w(TAG, "Google TTS HTTP error ${response.code} for $url")
                        return@use
                    }
                    val contentType = response.header("Content-Type")?.lowercase().orEmpty()
                    if (contentType.contains("text/") || contentType.contains("html") || contentType.contains("json")) {
                        lastError = "non-audio ($contentType)"
                        Log.w(TAG, "Google TTS returned non-audio content-type=$contentType")
                        return@use
                    }
                    val bytes = response.body.bytes()
                    if (!looksLikeAudio(bytes)) {
                        lastError = "invalid audio payload (${bytes.size}B)"
                        Log.w(TAG, "Google TTS returned invalid payload (${bytes.size}B, ct=$contentType)")
                        return@use
                    }
                    return bytes to null
                }
            } catch (t: Throwable) {
                lastError = "${t.javaClass.simpleName}: ${t.message ?: ""}".trimEnd(':', ' ')
                Log.w(TAG, "Google TTS chunk error for $url: $lastError")
            }
        }
        return null to (lastError ?: "unknown error")
    }

    /**
     * Synthesizes [text] to an MP3 file saved in context.cacheDir/tts_cache/,
     * returning the file alongside any failure reason for diagnostics.
     */
    fun synthesize(context: Context, text: String): SynthesisResult {
        val clean = text.trim()
        if (clean.isBlank()) return SynthesisResult(null, "empty text")

        val chunks = splitText(clean)
        if (chunks.isEmpty()) return SynthesisResult(null, "empty text")

        val cacheDir = File(context.cacheDir, "tts_cache").apply { mkdirs() }
        val destFile = File(cacheDir, "google_tts_${UUID.randomUUID()}.mp3")

        return try {
            var errorReason: String? = null
            FileOutputStream(destFile).use { outStream ->
                for (chunk in chunks) {
                    if (chunk.isBlank()) continue
                    val encoded = URLEncoder.encode(chunk, "UTF-8")
                    val (bytes, err) = fetchChunkAudio(encoded)
                    if (bytes == null) {
                        errorReason = err ?: "download failed"
                        break
                    }
                    outStream.write(bytes)
                }
            }

            if (errorReason == null && destFile.exists() && destFile.length() > 44L) {
                SynthesisResult(destFile, null)
            } else {
                runCatching { destFile.delete() }
                SynthesisResult(null, errorReason ?: "empty audio output")
            }
        } catch (t: Throwable) {
            val detail = "${t.javaClass.simpleName}: ${t.message ?: ""}".trimEnd(':', ' ')
            Log.e(TAG, "Google TTS synthesis failed: $detail", t)
            runCatching { destFile.delete() }
            SynthesisResult(null, detail)
        }
    }

    /**
     * Synthesizes [text] to an MP3 file saved in context.cacheDir/tts_cache/.
     * Returns null if synthesis failed.
     */
    fun synthesizeToFile(context: Context, text: String): File? = synthesize(context, text).file
}
