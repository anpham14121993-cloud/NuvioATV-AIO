package com.nuvio.tv.core.player.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Microsoft Edge TTS online narration engine.
 *
 * Streams high quality Vietnamese neural voices (Hoài My, Nam Minh) via Microsoft Edge
 * Read Aloud WebSocket API with prefetching and disk caching.
 * Falls back to System TTS / Piper TTS on network or synthesis errors.
 */
class EdgeNarrationEngine(
    private val appContext: Context,
    private val onSpeechStart: () -> Unit = {},
    private val onSpeechEnd: () -> Unit = {},
    private val onError: (text: String, rate: Int) -> Unit = { _, _ -> },
    private val onStatus: (NarrationEngineStatus) -> Unit = {},
    private val onStage: (String) -> Unit = {},
) : NarrationTtsEngine {

    companion object {
        private const val TAG = "EdgeNarration"
        private const val TOKEN_URL_PART = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        const val VOICE_FEMALE = "vi-VN-HoaiMyNeural"
        const val VOICE_MALE = "vi-VN-NamMinhNeural"

        private const val MAX_DISK_CACHE_BYTES = 64L * 1024L * 1024L // 64 MB
        private const val SYNTH_TIMEOUT_SEC = 8L
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-edge-tts").apply { isDaemon = true }
    }
    private val prefetchPool = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-edge-prefetch").apply { isDaemon = true }
    }
    private val prefetchInFlight = ConcurrentHashMap.newKeySet<String>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cacheDir = File(appContext.cacheDir, "edge_tts_cache").apply { mkdirs() }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var narrationVolume = 1f

    @Volatile
    private var activePlayer: MediaPlayer? = null
    private val playerLock = Any()

    private val reqSeq = AtomicInteger(0)
    private val isSpeaking = AtomicBoolean(false)
    @Volatile
    private var released = false

    override fun warmUp() {
        if (released) return
        onStatus(NarrationEngineStatus.READY)
        NarrationDiag.log(appContext, "edge: warmed up (ready)")
    }

    override fun setNarrationVolume(volume01: Float) {
        narrationVolume = volume01.coerceIn(0f, 1f)
        synchronized(playerLock) {
            runCatching { activePlayer?.setVolume(narrationVolume, narrationVolume) }
        }
    }

    override fun speak(
        text: String,
        voiceApiName: String,
        ratePercent: Int,
        cutOnNewer: Boolean,
        budgetMs: Long?,
        onResult: ((ok: Boolean, detail: String) -> Unit)?,
    ) {
        if (released) {
            onResult?.invoke(false, "released")
            return
        }
        val seq = reqSeq.incrementAndGet()
        worker.execute {
            if (released || seq != reqSeq.get()) return@execute
            val voice = resolveVoice(voiceApiName)
            val cacheFile = getCacheFile(text, voice, ratePercent)

            if (cacheFile.exists() && cacheFile.length() > 0) {
                NarrationDiag.log(appContext, "edge: cache hit len=${text.length} v=$voice")
                playAudioFile(cacheFile, seq, onResult)
                return@execute
            }

            NarrationDiag.log(appContext, "edge: fetch live len=${text.length} v=$voice")
            val audioBytes = fetchAudio(text, voice, ratePercent)
            if (released || seq != reqSeq.get()) return@execute

            if (audioBytes != null && audioBytes.isNotEmpty()) {
                runCatching {
                    cacheFile.parentFile?.mkdirs()
                    FileOutputStream(cacheFile).use { it.write(audioBytes) }
                    trimCacheIfNeeded()
                }
                playAudioFile(cacheFile, seq, onResult)
            } else {
                NarrationDiag.log(appContext, "edge: fetch failed -> fallback")
                mainHandler.post { onError(text, ratePercent) }
                onResult?.invoke(false, "edge_fetch_failed")
            }
        }
    }

    override fun prefetch(text: String, voiceApiName: String, ratePercent: Int) {
        if (released || text.isBlank()) return
        val voice = resolveVoice(voiceApiName)
        val key = "$voice|$ratePercent|$text"
        if (!prefetchInFlight.add(key)) return

        val cacheFile = getCacheFile(text, voice, ratePercent)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            prefetchInFlight.remove(key)
            return
        }

        prefetchPool.execute {
            try {
                if (released) return@execute
                val audioBytes = fetchAudio(text, voice, ratePercent)
                if (audioBytes != null && audioBytes.isNotEmpty() && !released) {
                    cacheFile.parentFile?.mkdirs()
                    FileOutputStream(cacheFile).use { it.write(audioBytes) }
                    trimCacheIfNeeded()
                    NarrationDiag.log(appContext, "edge: prefetch ok len=${text.length}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Prefetch failed: ${t.message}")
            } finally {
                prefetchInFlight.remove(key)
            }
        }
    }

    override fun stop() {
        stopPlayback()
    }

    override fun release() {
        released = true
        stopPlayback()
        runCatching { worker.shutdownNow() }
        runCatching { prefetchPool.shutdownNow() }
    }

    private fun resolveVoice(voiceApiName: String): String {
        return if (voiceApiName.contains("Nam", ignoreCase = true) ||
            voiceApiName.contains("Adam", ignoreCase = true)
        ) {
            VOICE_MALE
        } else {
            VOICE_FEMALE
        }
    }

    private fun getCacheFile(text: String, voice: String, rate: Int): File {
        val hash = sha256Hex("$voice|$rate|$text")
        return File(cacheDir, "$hash.mp3")
    }

    private fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (b in digest) sb.append(String.format(Locale.US, "%02x", b))
        return sb.toString()
    }

    private fun trimCacheIfNeeded() {
        val files = cacheDir.listFiles() ?: return
        var totalSize = files.sumOf { it.length() }
        if (totalSize <= MAX_DISK_CACHE_BYTES) return

        val sorted = files.sortedBy { it.lastModified() }
        for (f in sorted) {
            val sz = f.length()
            if (f.delete()) {
                totalSize -= sz
                if (totalSize <= MAX_DISK_CACHE_BYTES * 0.75) break
            }
        }
    }

    private fun fetchAudio(text: String, voice: String, ratePercent: Int): ByteArray? {
        val secToken = EdgeDrm.token()
        val cid = UUID.randomUUID().toString().replace("-", "")
        val rid = UUID.randomUUID().toString().replace("-", "")
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TOKEN_URL_PART&ConnectionId=$cid" +
            "&Sec-MS-GEC=$secToken&Sec-MS-GEC-Version=${EdgeDrm.CHROMIUM_VERSION}"

        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = fmt.format(Date())

        val config = "X-Timestamp:$date\r\nContent-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
            "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"

        val escapedText = text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val rateStr = if (ratePercent >= 0) "+$ratePercent%" else "$ratePercent%"
        val voiceName = if (voice.startsWith("Microsoft Server Speech")) {
            voice
        } else {
            "Microsoft Server Speech Text to Speech Voice (vi-VN, ${voice.removePrefix("vi-VN-")})"
        }
        val ssmlBody = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voiceName'>" +
            "<prosody pitch='+0Hz' rate='$rateStr' volume='+0%'>" +
            escapedText +
            "</prosody></voice></speak>"

        val ssmlMsg = "X-RequestId:$rid\r\nContent-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${date}Z\r\nPath:ssml\r\n\r\n$ssmlBody"

        val request = Request.Builder()
            .url(url)
            .addHeader("Pragma", "no-cache")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase()};")
            .build()

        val audioBuffer = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        var errorOccurred = false

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(config)
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, textMsg: String) {
                if (textMsg.contains("Path:turn.end")) {
                    webSocket.close(1000, null)
                    latch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val d = bytes.toByteArray()
                if (d.size > 2) {
                    val headerLen = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
                    if (d.size >= 2 + headerLen) {
                        val header = String(d, 2, headerLen, Charsets.US_ASCII)
                        if (header.contains("Path:audio")) {
                            val audioDataLen = d.size - (2 + headerLen)
                            if (audioDataLen > 0) {
                                audioBuffer.write(d, 2 + headerLen, audioDataLen)
                            }
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code != 1000) {
                    NarrationDiag.log(appContext, "edge: ws close code=$code r=$reason")
                }
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                val msg = t.message ?: "unknown"
                Log.e(TAG, "WebSocket failure: $msg code=$code")
                NarrationDiag.log(appContext, "edge: ws fail code=$code err=$msg")
                errorOccurred = true
                latch.countDown()
            }
        })

        val ok = try {
            latch.await(SYNTH_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }

        if (!ok || errorOccurred) {
            runCatching { ws.cancel() }
            return null
        }

        val result = audioBuffer.toByteArray()
        return if (result.isNotEmpty()) result else null
    }

    private fun playAudioFile(file: File, seq: Int, onResult: ((Boolean, String) -> Unit)?) {
        stopPlayback()
        if (released || seq != reqSeq.get()) {
            onResult?.invoke(false, "superseded")
            return
        }

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setVolume(narrationVolume, narrationVolume)
                setDataSource(file.absolutePath)
                setOnPreparedListener { mp ->
                    if (released || seq != reqSeq.get()) {
                        runCatching { mp.release() }
                        return@setOnPreparedListener
                    }
                    isSpeaking.set(true)
                    mainHandler.post { onSpeechStart() }
                    mp.start()
                    onResult?.invoke(true, "playing")
                }
                setOnCompletionListener { mp ->
                    isSpeaking.set(false)
                    mainHandler.post { onSpeechEnd() }
                    runCatching { mp.release() }
                    synchronized(playerLock) {
                        if (activePlayer === mp) activePlayer = null
                    }
                }
                setOnErrorListener { mp, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    isSpeaking.set(false)
                    mainHandler.post { onSpeechEnd() }
                    runCatching { mp.release() }
                    synchronized(playerLock) {
                        if (activePlayer === mp) activePlayer = null
                    }
                    true
                }
                prepareAsync()
            }
            synchronized(playerLock) {
                activePlayer = player
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start MediaPlayer", t)
            onResult?.invoke(false, "player_failed: ${t.message}")
        }
    }

    private fun stopPlayback() {
        synchronized(playerLock) {
            val mp = activePlayer
            activePlayer = null
            if (mp != null) {
                runCatching {
                    if (mp.isPlaying) mp.stop()
                    mp.release()
                }
            }
        }
        if (isSpeaking.getAndSet(false)) {
            mainHandler.post { onSpeechEnd() }
        }
    }
}
