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
 * Read Aloud WebSocket API. Uses a persistent WebSocket connection with keep-alive,
 * aggressive prefetching, disk caching, and fast synchronous playback preparation
 * to maintain exact subtitle synchronization without lag or engine alternation.
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
        private const val SYNTH_TIMEOUT_SEC = 7L
        private const val CONNECT_TIMEOUT_SEC = 6L
        private const val MAX_CONSECUTIVE_ERRORS_FOR_FALLBACK = 3
    }

    private val synthLock = Any()
    private val wsLock = Any()

    @Volatile
    private var activeWs: WebSocket? = null
    @Volatile
    private var isConnected = false
    private var connectLatch: CountDownLatch? = null

    private val consecutiveErrors = AtomicInteger(0)
    private val speakWaiting = AtomicInteger(0)

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-edge-tts").apply { isDaemon = true }
    }
    private val prefetchWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-edge-prefetch").apply { isDaemon = true }
    }
    private val playbackWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-edge-player").apply { isDaemon = true }
    }
    private val prefetchInFlight = ConcurrentHashMap.newKeySet<String>()

    private class PendingSynth(
        val rid: String,
        val buffer: ByteArrayOutputStream = ByteArrayOutputStream(),
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var failed: Boolean = false,
    )
    private val pendingRequests = ConcurrentHashMap<String, PendingSynth>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cacheDir = File(appContext.cacheDir, "edge_tts_cache").apply { mkdirs() }

    private val client by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
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
        // Pre-connect persistent WebSocket in the background so it is ready before the first cue
        worker.execute {
            if (!released) {
                runCatching { getOrCreateConnection() }
            }
        }
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
        val voice = resolveVoice(voiceApiName)
        val cacheFile = getCacheFile(text, voice)

        // Instant Cache Hit: 0ms delay!
        if (cacheFile.exists() && cacheFile.length() > 0) {
            NarrationDiag.log(appContext, "edge: cache hit len=${text.length} v=$voice")
            playAudioFile(cacheFile, seq, onResult)
            return
        }

        speakWaiting.incrementAndGet()
        worker.execute {
            try {
                if (released || seq != reqSeq.get()) return@execute
                NarrationDiag.log(appContext, "edge: fetch live len=${text.length} v=$voice")
                val audioBytes = fetchAudioWithRetry(text, voice, ratePercent)
                if (released) return@execute

                if (audioBytes != null && audioBytes.isNotEmpty()) {
                    consecutiveErrors.set(0)
                    runCatching {
                        cacheFile.parentFile?.mkdirs()
                        FileOutputStream(cacheFile).use { it.write(audioBytes) }
                        trimCacheIfNeeded()
                    }
                    if (seq == reqSeq.get()) {
                        playAudioFile(cacheFile, seq, onResult)
                    } else {
                        onResult?.invoke(false, "superseded")
                    }
                } else {
                    val errCount = consecutiveErrors.incrementAndGet()
                    NarrationDiag.log(appContext, "edge: fetch failed (consecutive=$errCount)")
                    if (errCount >= MAX_CONSECUTIVE_ERRORS_FOR_FALLBACK) {
                        NarrationDiag.log(appContext, "edge: $errCount consecutive failures -> fallback")
                        mainHandler.post { onError(text, ratePercent) }
                    } else {
                        // Avoid alternating engines for transient single failure
                        NarrationDiag.log(appContext, "edge: skip cue to avoid alternating engine")
                    }
                    onResult?.invoke(false, "edge_fetch_failed")
                }
            } finally {
                speakWaiting.decrementAndGet()
            }
        }
    }

    override fun prefetch(text: String, voiceApiName: String, ratePercent: Int) {
        if (released || text.isBlank()) return
        val voice = resolveVoice(voiceApiName)
        val cacheFile = getCacheFile(text, voice)
        if (cacheFile.exists() && cacheFile.length() > 0) return

        val key = "$voice|$text"
        if (!prefetchInFlight.add(key)) return

        prefetchWorker.execute {
            try {
                if (released) return@execute
                // Yield network priority to live speech requests
                var waited = 0
                while (speakWaiting.get() > 0 && !released && waited < 2000) {
                    Thread.sleep(50)
                    waited += 50
                }
                if (released || speakWaiting.get() > 0) return@execute

                val audioBytes = fetchAudioWithRetry(text, voice, ratePercent)
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
        closeConnection()
        runCatching { worker.shutdownNow() }
        runCatching { prefetchWorker.shutdownNow() }
        runCatching { playbackWorker.shutdownNow() }
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

    private fun getCacheFile(text: String, voice: String): File {
        val hash = sha256Hex("$voice|$text")
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

    private fun getOrCreateConnection(): WebSocket? {
        if (released) return null
        var latchToWait: CountDownLatch? = null
        synchronized(wsLock) {
            if (isConnected && activeWs != null) {
                return activeWs
            }
            val existing = connectLatch
            if (existing != null && existing.count > 0) {
                latchToWait = existing
            } else {
                val latch = CountDownLatch(1)
                connectLatch = latch
                latchToWait = latch
                startNewConnection(latch)
            }
        }

        try {
            latchToWait?.await(CONNECT_TIMEOUT_SEC + 1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            // ignore
        }

        synchronized(wsLock) {
            return if (isConnected) activeWs else null
        }
    }

    private fun startNewConnection(latch: CountDownLatch) {
        val secToken = EdgeDrm.token()
        val cid = UUID.randomUUID().toString().replace("-", "")
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TOKEN_URL_PART&ConnectionId=$cid" +
            "&Sec-MS-GEC=$secToken&Sec-MS-GEC-Version=${EdgeDrm.CHROMIUM_VERSION}"

        val request = Request.Builder()
            .url(url)
            .addHeader("Pragma", "no-cache")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("Cookie", "muid=${UUID.randomUUID().toString().replace("-", "").uppercase()};")
            .build()

        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val date = formatUtcDate()
                val config = "X-Timestamp:$date\r\nContent-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                    "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                    "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                webSocket.send(config)
                synchronized(wsLock) {
                    activeWs = webSocket
                    isConnected = true
                }
                NarrationDiag.log(appContext, "edge: ws connected")
                latch.countDown()
            }

            override fun onMessage(webSocket: WebSocket, textMsg: String) {
                if (textMsg.contains("Path:turn.end")) {
                    val rid = extractRequestId(textMsg)
                    if (rid != null) {
                        pendingRequests[rid]?.latch?.countDown()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val d = bytes.toByteArray()
                if (d.size > 2) {
                    val headerLen = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
                    if (d.size >= 2 + headerLen) {
                        val header = String(d, 2, headerLen, Charsets.US_ASCII)
                        if (header.contains("Path:audio")) {
                            val rid = extractRequestId(header)
                            val audioDataLen = d.size - (2 + headerLen)
                            if (audioDataLen > 0 && rid != null) {
                                pendingRequests[rid]?.let { req ->
                                    synchronized(req.buffer) {
                                        req.buffer.write(d, 2 + headerLen, audioDataLen)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect("closing code=$code")
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect("closed code=$code")
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                val msg = t.message ?: "unknown"
                Log.w(TAG, "WebSocket failure: $msg code=$code")
                NarrationDiag.log(appContext, "edge: ws fail code=$code msg=$msg")
                handleDisconnect("failure")
                latch.countDown()
            }
        })
    }

    private fun handleDisconnect(reason: String) {
        synchronized(wsLock) {
            isConnected = false
            activeWs = null
            connectLatch = null
        }
        for ((_, req) in pendingRequests) {
            req.failed = true
            req.latch.countDown()
        }
    }

    private fun closeConnection() {
        synchronized(wsLock) {
            isConnected = false
            val ws = activeWs
            activeWs = null
            connectLatch = null
            runCatching { ws?.close(1000, "normal") }
        }
        for ((_, req) in pendingRequests) {
            req.failed = true
            req.latch.countDown()
        }
        pendingRequests.clear()
    }

    private fun fetchAudioWithRetry(text: String, voice: String, ratePercent: Int): ByteArray? {
        // Serialized synthesis so Bing receives 1 request at a time on the persistent socket
        synchronized(synthLock) {
            val firstTry = fetchAudioSingle(text, voice, ratePercent)
            if (firstTry != null && firstTry.isNotEmpty()) {
                return firstTry
            }
            if (released) return null
            // If first attempt failed (e.g. stale socket dropped by server), reconnect and retry once!
            NarrationDiag.log(appContext, "edge: synth retry after disconnect")
            closeConnection()
            return fetchAudioSingle(text, voice, ratePercent)
        }
    }

    private fun fetchAudioSingle(text: String, voice: String, ratePercent: Int): ByteArray? {
        if (released) return null
        val ws = getOrCreateConnection() ?: return null
        val rid = UUID.randomUUID().toString().replace("-", "")
        val req = PendingSynth(rid)
        pendingRequests[rid] = req

        try {
            val date = formatUtcDate()
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

            val sent = ws.send(ssmlMsg)
            if (!sent) {
                Log.w(TAG, "Failed to send SSML frame on WebSocket")
                return null
            }

            val completed = try {
                req.latch.await(SYNTH_TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                false
            }

            val bytes = synchronized(req.buffer) { req.buffer.toByteArray() }
            if (bytes.isNotEmpty() && (completed || bytes.size > 2000)) {
                return bytes
            }
            return null
        } finally {
            pendingRequests.remove(rid)
        }
    }

    private fun extractRequestId(str: String): String? {
        val key = "X-RequestId:"
        val idx = str.indexOf(key)
        if (idx < 0) return null
        val start = idx + key.length
        val end = str.indexOf("\r\n", start).takeIf { it >= 0 }
            ?: str.indexOf("\n", start).takeIf { it >= 0 }
            ?: str.length
        return str.substring(start, end).trim()
    }

    private fun formatUtcDate(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date())
    }

    private fun playAudioFile(file: File, seq: Int, onResult: ((Boolean, String) -> Unit)?) {
        if (released) {
            onResult?.invoke(false, "released")
            return
        }

        playbackWorker.execute {
            if (released || (seq > 0 && seq != reqSeq.get())) {
                onResult?.invoke(false, "superseded")
                return@execute
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
                    prepare() // Synchronous on dedicated playback worker (~10ms)
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
                }

                synchronized(playerLock) {
                    if (released || (seq > 0 && seq != reqSeq.get())) {
                        runCatching { player.release() }
                        return@execute
                    }
                    val old = activePlayer
                    activePlayer = player
                    if (old != null) {
                        runCatching {
                            if (old.isPlaying) old.stop()
                            old.release()
                        }
                    }
                    player.start()
                    isSpeaking.set(true)
                    mainHandler.post { onSpeechStart() }
                    onResult?.invoke(true, "playing")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start MediaPlayer", t)
                NarrationDiag.log(appContext, "edge: player failed ${t.javaClass.simpleName}")
                onResult?.invoke(false, "player_failed: ${t.message}")
            }
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
