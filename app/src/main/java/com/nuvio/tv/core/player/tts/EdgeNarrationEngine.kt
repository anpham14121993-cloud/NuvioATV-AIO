package com.nuvio.tv.core.player.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Microsoft Edge TTS online narration engine.
 *
 * Streams high quality Vietnamese neural voices (Hoài My, Nam Minh) via Microsoft Edge
 * Read Aloud WebSocket API. Uses a persistent WebSocket connection with keep-alive,
 * 100-sentence rolling prefetching to disk, in-memory PCM decoding with AudioTrack
 * and MediaPlayer fail-safe fallback for zero-latency, pause-free continuous subtitle synchronization.
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
        private const val MAX_PCM_CACHE_ENTRIES = 50
        private const val SYNTH_TIMEOUT_SEC = 7L
        private const val CONNECT_TIMEOUT_SEC = 6L
        private const val MAX_CONSECUTIVE_ERRORS_FOR_FALLBACK = 3
    }

    private class PcmAudio(
        val pcmBytes: ByteArray,
        val sampleRate: Int,
        val channelCount: Int,
    )

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

    private val pcmCache = object : LinkedHashMap<String, PcmAudio>(MAX_PCM_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PcmAudio>?): Boolean {
            return size > MAX_PCM_CACHE_ENTRIES
        }
    }
    private val pcmLock = Any()

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
    private var activeTrack: AudioTrack? = null
    private val trackLock = Any()

    @Volatile
    private var activePlayer: MediaPlayer? = null
    private val playerLock = Any()

    @Volatile
    private var currentWatchdog: Runnable? = null

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
        synchronized(trackLock) {
            runCatching { activeTrack?.setVolume(narrationVolume) }
        }
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

        // 1. Instant RAM PCM Hit: 0ms delay!
        val cachedPcm = synchronized(pcmLock) { pcmCache[key] }
        if (cachedPcm != null) {
            NarrationDiag.log(appContext, "edge: ram pcm hit len=${text.length} v=$voice")
            playPcm(cacheFile, cachedPcm, seq, onResult)
            return
        }

        // 2. Instant Disk Cache Hit: decode and play!
        if (cacheFile.exists() && cacheFile.length() > 0) {
            NarrationDiag.log(appContext, "edge: disk cache hit len=${text.length} v=$voice")
            val pcm = decodeMp3ToPcm(cacheFile)
            if (pcm != null) {
                synchronized(pcmLock) { pcmCache[key] = pcm }
                playPcm(cacheFile, pcm, seq, onResult)
            } else {
                playAudioFile(cacheFile, seq, onResult)
            }
            return
        }

        // 3. Network Fetch
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
                    val pcm = decodeMp3ToPcm(cacheFile)
                    if (pcm != null) {
                        synchronized(pcmLock) { pcmCache[key] = pcm }
                    }
                    if (seq == reqSeq.get()) {
                        if (pcm != null) {
                            playPcm(cacheFile, pcm, seq, onResult)
                        } else {
                            playAudioFile(cacheFile, seq, onResult)
                        }
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

        synchronized(pcmLock) {
            if (pcmCache.containsKey(key)) return
        }

        val cacheFile = getCacheFile(text, voice)
        // If already on disk, decode to RAM PCM if needed
        if (cacheFile.exists() && cacheFile.length() > 0) {
            val pcm = decodeMp3ToPcm(cacheFile)
            if (pcm != null) {
                synchronized(pcmLock) { pcmCache[key] = pcm }
                NarrationDiag.log(appContext, "edge: disk->ram pcm ok len=${text.length}")
            }
            return
        }

        if (!prefetchInFlight.add(key)) return

        prefetchWorker.execute {
            try {
                if (released) return@execute

                // If already downloaded while waiting in queue, skip fetch
                if (cacheFile.exists() && cacheFile.length() > 0) {
                    val pcm = decodeMp3ToPcm(cacheFile)
                    if (pcm != null) {
                        synchronized(pcmLock) { pcmCache[key] = pcm }
                    }
                    return@execute
                }

                // If live speech is currently fetching, pause gracefully until it finishes - NEVER ABORT!
                while (speakWaiting.get() > 0 && !released) {
                    Thread.sleep(50)
                }
                if (released) return@execute

                val audioBytes = fetchAudioWithRetry(text, voice, ratePercent)
                if (audioBytes != null && audioBytes.isNotEmpty() && !released) {
                    cacheFile.parentFile?.mkdirs()
                    FileOutputStream(cacheFile).use { it.write(audioBytes) }
                    trimCacheIfNeeded()

                    val pcm = decodeMp3ToPcm(cacheFile)
                    if (pcm != null) {
                        synchronized(pcmLock) { pcmCache[key] = pcm }
                        NarrationDiag.log(appContext, "edge: prefetch+pcm ok len=${text.length}")
                    } else {
                        NarrationDiag.log(appContext, "edge: prefetch disk ok len=${text.length}")
                    }
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
        synchronized(pcmLock) { pcmCache.clear() }
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
        synchronized(synthLock) {
            val firstTry = fetchAudioSingle(text, voice, ratePercent)
            if (firstTry != null && firstTry.isNotEmpty()) {
                return firstTry
            }
            if (released) return null
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

    private fun decodeMp3ToPcm(file: File): PcmAudio? {
        if (!file.exists() || file.length() == 0L) return null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            var audioTrackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }
            if (audioTrackIndex < 0 || format == null) return null
            extractor.selectTrack(audioTrackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 24000
            var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val outStream = ByteArrayOutputStream(32 * 1024)
            val info = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            val timeoutUs = 5000L
            var noOutputCycles = 0

            while (!sawOutputEos && noOutputCycles < 100) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            inBuffer.clear()
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIndex >= 0) {
                    noOutputCycles = 0
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && info.size > 0) {
                        outBuffer.position(info.offset)
                        outBuffer.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        outBuffer.get(chunk)
                        outStream.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEos = true
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (sawInputEos) {
                        noOutputCycles++
                    }
                }
            }

            val pcm = outStream.toByteArray()
            return if (pcm.isNotEmpty()) PcmAudio(pcm, sampleRate, channelCount) else null
        } catch (t: Throwable) {
            Log.w(TAG, "decodeMp3ToPcm failed: ${t.message}")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun playPcm(file: File, pcm: PcmAudio, seq: Int, onResult: ((Boolean, String) -> Unit)?) {
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
                val channelConfig = if (pcm.channelCount == 2) {
                    AudioFormat.CHANNEL_OUT_STEREO
                } else {
                    AudioFormat.CHANNEL_OUT_MONO
                }

                val minBuf = AudioTrack.getMinBufferSize(pcm.sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
                val bufferSize = if (minBuf > 0 && pcm.pcmBytes.size < minBuf) minBuf else pcm.pcmBytes.size

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(pcm.sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.setVolume(narrationVolume)
                val written = track.write(pcm.pcmBytes, 0, pcm.pcmBytes.size)
                if (written <= 0) {
                    Log.w(TAG, "AudioTrack.write failed ($written), falling back to MediaPlayer")
                    track.release()
                    playAudioFile(file, seq, onResult)
                    return@execute
                }

                val bytesPerFrame = pcm.channelCount * 2
                val totalFrames = pcm.pcmBytes.size / bytesPerFrame
                val durationMs = (totalFrames * 1000L) / pcm.sampleRate

                val watchdog = Runnable {
                    synchronized(trackLock) {
                        if (activeTrack === track) {
                            activeTrack = null
                            isSpeaking.set(false)
                            mainHandler.post { onSpeechEnd() }
                            runCatching {
                                track.stop()
                                track.release()
                            }
                        }
                    }
                }

                track.setNotificationMarkerPosition(totalFrames)
                track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(t: AudioTrack) {
                        currentWatchdog?.let { mainHandler.removeCallbacks(it) }
                        isSpeaking.set(false)
                        mainHandler.post { onSpeechEnd() }
                        synchronized(trackLock) {
                            if (activeTrack === t) activeTrack = null
                        }
                        runCatching {
                            t.stop()
                            t.release()
                        }
                    }

                    override fun onPeriodicNotification(t: AudioTrack) {}
                })

                synchronized(trackLock) {
                    if (released || (seq > 0 && seq != reqSeq.get())) {
                        runCatching { track.release() }
                        return@execute
                    }
                    activeTrack = track
                }

                currentWatchdog = watchdog
                mainHandler.postDelayed(watchdog, durationMs + 250L)
                isSpeaking.set(true)
                mainHandler.post { onSpeechStart() }
                track.play()
                onResult?.invoke(true, "playing_pcm")
            } catch (t: Throwable) {
                Log.w(TAG, "AudioTrack play failed (${t.message}), falling back to MediaPlayer", t)
                playAudioFile(file, seq, onResult)
            }
        }
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

            stopPlayback()

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
                    onResult?.invoke(true, "playing_mp3")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start MediaPlayer", t)
                NarrationDiag.log(appContext, "edge: player failed ${t.javaClass.simpleName}")
                onResult?.invoke(false, "player_failed: ${t.message}")
            }
        }
    }

    private fun stopPlayback() {
        currentWatchdog?.let {
            mainHandler.removeCallbacks(it)
            currentWatchdog = null
        }
        synchronized(trackLock) {
            val t = activeTrack
            activeTrack = null
            if (t != null) {
                runCatching {
                    if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        t.pause()
                        t.flush()
                    }
                    t.stop()
                    t.release()
                }
            }
        }
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
