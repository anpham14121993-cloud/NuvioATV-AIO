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
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Microsoft Edge TTS online narration engine.
 *
 * Streams high quality Vietnamese neural voices (Hoài My, Nam Minh) via Microsoft Edge
 * Read Aloud WebSocket API. Uses a rolling RAM cache (30 sentences) and persistent disk
 * cache (up to 128 MB), with fast MediaPlayer playback and deduplicated in-flight
 * prefetching for zero-latency, pause-free continuous subtitle synchronization.
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

        private const val MAX_DISK_CACHE_BYTES = 128L * 1024L * 1024L // 128 MB
        private const val MAX_RAM_CACHE_ENTRIES = 30
        private const val SYNTH_TIMEOUT_SEC = 8L
        private const val CONNECT_TIMEOUT_SEC = 6L
        private const val MAX_CONSECUTIVE_ERRORS_FOR_FALLBACK = 5
        private const val PREFETCH_PACE_MS = 350L
    }

    private class InFlightJob {
        val latch = CountDownLatch(1)
        @Volatile var audioBytes: ByteArray? = null
        @Volatile var failed: Boolean = false
    }

    private val inFlightJobs = ConcurrentHashMap<String, InFlightJob>()

    private val consecutiveErrors = AtomicInteger(0)
    private val speakWaiting = AtomicInteger(0)

    // High priority single-thread executor for live speech network fetching
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-edge-tts").apply { isDaemon = true }
    }

    // Paced background prefetch executor with clearable queue
    private val prefetchQueue = LinkedBlockingQueue<Runnable>()
    private val prefetchExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, prefetchQueue
    ) { runnable ->
        Thread(runnable, "nuvio-edge-prefetch").apply { isDaemon = true }
    }

    // Playback worker for MediaPlayer
    private val playbackWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-edge-player").apply { isDaemon = true }
    }

    // Rolling RAM cache for exactly 30 sentences (FIFO/LRU)
    private val ramCache = object : LinkedHashMap<String, ByteArray>(MAX_RAM_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > MAX_RAM_CACHE_ENTRIES
        }
    }
    private val ramLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cacheDir = File(appContext.cacheDir, "edge_tts_cache_v2").apply { mkdirs() }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(SYNTH_TIMEOUT_SEC + 2L, TimeUnit.SECONDS)
            .writeTimeout(SYNTH_TIMEOUT_SEC, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
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
        worker.execute {
            if (!released) {
                runCatching { trimCacheIfNeeded() }
            }
        }
    }

    override fun clearPrefetchQueue() {
        prefetchQueue.clear()
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
        val key = getCacheKey(text, voice)
        val cacheFile = getCacheFile(text, voice)

        // 1. Instant RAM Cache Hit (0ms delay)
        val ramAudio = synchronized(ramLock) { ramCache[key] }
        if (ramAudio != null && ramAudio.isNotEmpty()) {
            consecutiveErrors.set(0)
            NarrationDiag.log(appContext, "edge: ram hit len=${text.length}")
            playAudio(cacheFile, ramAudio, seq, onResult)
            return
        }

        // 2. Instant Disk Cache Hit (<2ms delay)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            val diskBytes = runCatching { cacheFile.readBytes() }.getOrNull()
            if (diskBytes != null && diskBytes.isNotEmpty()) {
                consecutiveErrors.set(0)
                synchronized(ramLock) { ramCache[key] = diskBytes }
                NarrationDiag.log(appContext, "edge: disk hit len=${text.length}")
                playAudio(cacheFile, diskBytes, seq, onResult)
                return
            }
        }

        // 3. Network Fetch with in-flight deduplication (high priority)
        speakWaiting.incrementAndGet()
        worker.execute {
            try {
                if (released || seq != reqSeq.get()) {
                    onResult?.invoke(false, "superseded")
                    return@execute
                }
                NarrationDiag.log(appContext, "edge: fetch live len=${text.length} v=$voice")

                val audioBytes = fetchAudioShared(text, voice, ratePercent, key)
                if (released) return@execute

                if (audioBytes != null && audioBytes.isNotEmpty()) {
                    consecutiveErrors.set(0)
                    synchronized(ramLock) { ramCache[key] = audioBytes }
                    runCatching {
                        cacheFile.parentFile?.mkdirs()
                        FileOutputStream(cacheFile).use { it.write(audioBytes) }
                        trimCacheIfNeeded()
                    }
                    if (seq == reqSeq.get()) {
                        playAudio(cacheFile, audioBytes, seq, onResult)
                    } else {
                        onResult?.invoke(false, "superseded")
                    }
                } else {
                    val errCount = consecutiveErrors.incrementAndGet()
                    NarrationDiag.log(appContext, "edge: live failed (consecutive=$errCount)")
                    if (errCount >= MAX_CONSECUTIVE_ERRORS_FOR_FALLBACK) {
                        NarrationDiag.log(appContext, "edge: $errCount consecutive failures -> fallback")
                        mainHandler.post { onError(text, ratePercent) }
                    } else {
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
        val key = getCacheKey(text, voice)

        synchronized(ramLock) {
            if (ramCache.containsKey(key)) return
        }

        val cacheFile = getCacheFile(text, voice)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            runCatching {
                val b = cacheFile.readBytes()
                if (b.isNotEmpty()) {
                    synchronized(ramLock) { ramCache[key] = b }
                }
            }
            return
        }

        if (inFlightJobs.containsKey(key)) return

        prefetchExecutor.execute {
            try {
                if (released) return@execute
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    runCatching {
                        val b = cacheFile.readBytes()
                        if (b.isNotEmpty()) {
                            synchronized(ramLock) { ramCache[key] = b }
                        }
                    }
                    return@execute
                }

                // If live speech is fetching, pause prefetch to grant full network bandwidth
                while (speakWaiting.get() > 0 && !released) {
                    Thread.sleep(60)
                }
                if (released) return@execute

                val audioBytes = fetchAudioShared(text, voice, ratePercent, key)
                if (audioBytes != null && audioBytes.isNotEmpty() && !released) {
                    synchronized(ramLock) { ramCache[key] = audioBytes }
                    cacheFile.parentFile?.mkdirs()
                    FileOutputStream(cacheFile).use { it.write(audioBytes) }
                    trimCacheIfNeeded()
                    NarrationDiag.log(appContext, "edge: prefetch ok len=${text.length}")
                }
                // Paced prefetch to respect Microsoft burst limits
                Thread.sleep(PREFETCH_PACE_MS)
            } catch (t: Throwable) {
                Log.w(TAG, "Prefetch error: ${t.message}")
            }
        }
    }

    override fun stop() {
        stopPlayback()
    }

    override fun release() {
        released = true
        stopPlayback()
        clearPrefetchQueue()
        synchronized(ramLock) { ramCache.clear() }
        inFlightJobs.clear()
        runCatching { worker.shutdownNow() }
        runCatching { prefetchExecutor.shutdownNow() }
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

    private fun getCacheKey(text: String, voice: String): String {
        return sha256Hex("$voice|$text")
    }

    private fun getCacheFile(text: String, voice: String): File {
        val hash = getCacheKey(text, voice)
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

    private fun fetchAudioShared(text: String, voice: String, ratePercent: Int, key: String): ByteArray? {
        val newJob = InFlightJob()
        val existingJob = inFlightJobs.putIfAbsent(key, newJob)
        if (existingJob != null) {
            try {
                existingJob.latch.await(SYNTH_TIMEOUT_SEC + 2L, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                return null
            }
            return existingJob.audioBytes
        }

        return try {
            val bytes = fetchAudioWithRetry(text, voice, ratePercent)
            newJob.audioBytes = bytes
            newJob.failed = (bytes == null || bytes.isEmpty())
            bytes
        } finally {
            newJob.latch.countDown()
            inFlightJobs.remove(key)
        }
    }

    private fun fetchAudioWithRetry(text: String, voice: String, ratePercent: Int, maxRetries: Int = 2): ByteArray? {
        var attempts = 0
        while (attempts <= maxRetries && !released) {
            attempts++
            val bytes = fetchAudioNetwork(text, voice, ratePercent)
            if (bytes != null && bytes.isNotEmpty()) {
                return bytes
            }
            if (attempts <= maxRetries && !released) {
                NarrationDiag.log(appContext, "edge: retry attempt $attempts len=${text.length}")
                runCatching { Thread.sleep(200) }
            }
        }
        return null
    }

    private fun fetchAudioNetwork(text: String, voice: String, ratePercent: Int): ByteArray? {
        if (released) return null
        val latch = CountDownLatch(1)
        val buffer = ByteArrayOutputStream()
        val secToken = EdgeDrm.token()
        val cid = UUID.randomUUID().toString().replace("-", "")
        val rid = UUID.randomUUID().toString().replace("-", "")
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
        val configMsg = "X-Timestamp:$date\r\nContent-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
            "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
            "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"

        val ssmlBody = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voiceName'>" +
            "<prosody pitch='+0Hz' rate='$rateStr' volume='+0%'>" +
            escapedText +
            "</prosody></voice></speak>"

        val ssmlMsg = "X-RequestId:$rid\r\nContent-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${date}Z\r\nPath:ssml\r\n\r\n$ssmlBody"

        var wsRef: WebSocket? = null
        var completedCleanly = false

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(configMsg)
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, textMsg: String) {
                if (textMsg.contains("Path:turn.end")) {
                    completedCleanly = true
                    latch.countDown()
                    runCatching { webSocket.close(1000, "done") }
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
                                synchronized(buffer) {
                                    buffer.write(d, 2 + headerLen, audioDataLen)
                                }
                            }
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message} code=${response?.code}")
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        })
        wsRef = ws

        try {
            latch.await(SYNTH_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            runCatching { wsRef?.cancel() }
            return null
        }

        val resultBytes = synchronized(buffer) { buffer.toByteArray() }
        if (completedCleanly && resultBytes.isNotEmpty()) {
            return resultBytes
        }
        if (resultBytes.size > 3000) {
            return resultBytes
        }
        runCatching { wsRef?.cancel() }
        return null
    }

    private fun formatUtcDate(): String {
        val fmt = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return fmt.format(Date())
    }

    private fun playAudio(file: File, bytes: ByteArray, seq: Int, onResult: ((Boolean, String) -> Unit)?) {
        if (released) {
            onResult?.invoke(false, "released")
            return
        }

        playbackWorker.execute {
            if (released || (seq > 0 && seq != reqSeq.get())) {
                onResult?.invoke(false, "superseded")
                return@execute
            }

            stopPlayback()

            try {
                if (!file.exists() || file.length() == 0L) {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { it.write(bytes) }
                }

                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setVolume(narrationVolume, narrationVolume)
                    setDataSource(file.absolutePath)
                    prepare()
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
                    activePlayer = player
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
