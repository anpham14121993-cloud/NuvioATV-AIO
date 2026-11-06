package com.nuvio.tv.core.player.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nuvio.tv.core.player.tts.piper.PiperPhonemizer
import com.nuvio.tv.core.player.tts.piper.PiperVoiceCatalog
import com.nuvio.tv.core.player.tts.piper.PiperVoiceConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline Piper TTS narration engine powered by ONNX Runtime.
 *
 * Runs lightweight, high-fidelity neural Vietnamese voice models (VITS ONNX, ~60MB)
 * completely locally with zero internet latency, no WebSocket roundtrips, and zero
 * missed sentences. Uses a rolling RAM cache (30 sentences stored in memory as WAV bytes
 * for instant <5ms playback and crisp sub-millisecond cuts), persistent disk cache,
 * and background lookahead prefetching for continuous movie subtitle synchronization.
 */
class PiperNarrationEngine(
    private val appContext: Context,
    private val onSpeechStart: () -> Unit = {},
    private val onSpeechEnd: () -> Unit = {},
    private val onError: (text: String, rate: Int) -> Unit = { _, _ -> },
    private val onStatus: (NarrationEngineStatus) -> Unit = {},
    private val onStage: (String) -> Unit = {},
) : NarrationTtsEngine {

    companion object {
        private const val TAG = "PiperNarration"
        private const val MAX_DISK_CACHE_BYTES = 128L * 1024L * 1024L // 128 MB
        private const val MAX_RAM_CACHE_ENTRIES = 30
    }

    private class InFlightJob {
        val latch = CountDownLatch(1)
        @Volatile var audioBytes: ByteArray? = null
        @Volatile var failed: Boolean = false
    }

    private val inFlightJobs = ConcurrentHashMap<String, InFlightJob>()
    private val prefetchInFlight = ConcurrentHashMap.newKeySet<String>()

    // Dedicated single-thread worker for live synthesis to avoid UI stutter
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-piper-worker").apply { isDaemon = true }
    }

    // 2 background prefetch workers with clearable queue
    private val prefetchQueue = LinkedBlockingQueue<Runnable>()
    private val prefetchExecutor = ThreadPoolExecutor(
        2, 2, 0L, TimeUnit.MILLISECONDS, prefetchQueue
    ) { runnable ->
        Thread(runnable, "nuvio-piper-prefetch").apply { isDaemon = true }
    }

    // Dedicated single-thread playback worker for MediaPlayer
    private val playbackWorker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-piper-player").apply { isDaemon = true }
    }

    // Rolling RAM cache for 30 sentences (LRU) holding raw WAV bytes
    private val ramCache = object : LinkedHashMap<String, ByteArray>(MAX_RAM_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > MAX_RAM_CACHE_ENTRIES
        }
    }
    private val ramLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val modelsDir = File(appContext.filesDir, "piper_models").apply { mkdirs() }
    private val cacheDir = File(appContext.cacheDir, "piper_tts_cache").apply { mkdirs() }

    @Volatile
    private var narrationVolume = 1f

    @Volatile
    private var activePlayer: MediaPlayer? = null
    private val playerLock = Any()

    private val isSpeaking = AtomicBoolean(false)
    private val isDownloading = AtomicBoolean(false)
    private val reqSeq = AtomicInteger(0)

    @Volatile
    private var currentVoiceId: String = ""
    @Volatile
    private var ortSession: OrtSession? = null
    @Volatile
    private var voiceConfig: PiperVoiceConfig? = null
    private val sessionLock = Any()

    @Volatile
    private var released = false

    private val env: OrtEnvironment? by lazy {
        try {
            OrtEnvironment.getEnvironment()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize OrtEnvironment: ${t.message}", t)
            NarrationDiag.log(appContext, "piper: env init failed ${t.javaClass.simpleName}")
            null
        }
    }

    override fun warmUp() {
        if (released) return
        worker.execute {
            runCatching {
                ensureModelLoaded(PiperVoiceCatalog.DEFAULT_VOICE.id)
            }.onFailure { t ->
                Log.e(TAG, "warmUp failed: ${t.message}", t)
                NarrationDiag.log(appContext, "piper: warmup error ${t.javaClass.simpleName}")
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

        val voice = PiperVoiceCatalog.findVoice(voiceApiName)
        val clean = PiperPhonemizer.cleanSubtitleText(text)
        if (clean.isBlank()) {
            onResult?.invoke(false, "empty_text")
            return
        }

        val seq = reqSeq.incrementAndGet()
        val cacheKey = buildCacheKey(clean, voice.id, ratePercent)

        // 1. Check RAM Cache first (< 5ms response)
        val ramAudio = synchronized(ramLock) { ramCache[cacheKey] }
        if (ramAudio != null) {
            playAudioBytes(ramAudio, seq, cutOnNewer, onResult)
            return
        }

        // 2. Check Disk Cache
        val diskFile = File(cacheDir, "$cacheKey.wav")
        if (diskFile.exists() && diskFile.length() > 44L) {
            val bytes = runCatching { diskFile.readBytes() }.getOrNull()
            if (bytes != null && bytes.size > 44) {
                synchronized(ramLock) { ramCache[cacheKey] = bytes }
                playAudioBytes(bytes, seq, cutOnNewer, onResult)
                return
            }
        }

        // 3. Synthesize via Worker
        worker.execute {
            if (released || seq != reqSeq.get()) {
                onResult?.invoke(false, "superseded")
                return@execute
            }

            // Deduplicate with in-flight synthesis
            var job = inFlightJobs[cacheKey]
            var isOriginator = false
            if (job == null) {
                job = InFlightJob()
                val prev = inFlightJobs.putIfAbsent(cacheKey, job)
                if (prev == null) {
                    isOriginator = true
                } else {
                    job = prev
                }
            }

            if (isOriginator) {
                try {
                    val bytes = doSynthesize(clean, voice.id, ratePercent)
                    if (bytes != null && bytes.size > 44) {
                        job.audioBytes = bytes
                        synchronized(ramLock) { ramCache[cacheKey] = bytes }
                        saveToDiskCache(diskFile, bytes)
                    } else {
                        job.failed = true
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Piper synthesis error: ${t.message}", t)
                    job.failed = true
                } finally {
                    job.latch.countDown()
                    inFlightJobs.remove(cacheKey)
                }
            } else {
                job.latch.await(5, TimeUnit.SECONDS)
            }

            if (released || seq != reqSeq.get()) {
                onResult?.invoke(false, "superseded")
                return@execute
            }

            val audio = job.audioBytes
            if (audio != null && audio.size > 44) {
                playAudioBytes(audio, seq, cutOnNewer, onResult)
            } else {
                NarrationDiag.log(appContext, "piper: synth failed for len=${clean.length}")
                mainHandler.post { onError(clean, ratePercent) }
                onResult?.invoke(false, "synth_failed")
            }
        }
    }

    override fun prefetch(text: String, voiceApiName: String, ratePercent: Int, targetRam: Boolean) {
        if (released) return
        val voice = PiperVoiceCatalog.findVoice(voiceApiName)
        val clean = PiperPhonemizer.cleanSubtitleText(text)
        if (clean.isBlank()) return

        val cacheKey = buildCacheKey(clean, voice.id, ratePercent)
        val inRam = synchronized(ramLock) { ramCache.containsKey(cacheKey) }
        val diskFile = File(cacheDir, "$cacheKey.wav")
        val onDisk = diskFile.exists() && diskFile.length() > 44L

        if (targetRam && inRam) return
        if (!targetRam && onDisk) return

        if (!prefetchInFlight.add(cacheKey)) return

        prefetchExecutor.execute {
            try {
                if (released) return@execute
                if (onDisk && targetRam) {
                    val bytes = runCatching { diskFile.readBytes() }.getOrNull()
                    if (bytes != null && bytes.size > 44) {
                        synchronized(ramLock) { ramCache[cacheKey] = bytes }
                        return@execute
                    }
                }

                val bytes = doSynthesize(clean, voice.id, ratePercent)
                if (bytes != null && bytes.size > 44) {
                    if (targetRam) {
                        synchronized(ramLock) { ramCache[cacheKey] = bytes }
                    }
                    saveToDiskCache(diskFile, bytes)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Prefetch failed for $cacheKey: ${t.message}")
            } finally {
                prefetchInFlight.remove(cacheKey)
            }
        }
    }

    override fun stop() {
        playbackWorker.execute {
            stopPlayerInternal()
        }
    }

    override fun release() {
        released = true
        reqSeq.incrementAndGet()
        stop()

        worker.execute {
            synchronized(sessionLock) {
                runCatching { ortSession?.close() }
                ortSession = null
                voiceConfig = null
            }
        }

        runCatching { prefetchQueue.clear() }
        runCatching { prefetchExecutor.shutdownNow() }
        runCatching { worker.shutdownNow() }
        runCatching { playbackWorker.shutdownNow() }
        synchronized(ramLock) { ramCache.clear() }
    }

    override fun clearPrefetchQueue() {
        prefetchQueue.clear()
        prefetchInFlight.clear()
    }

    /**
     * Executes Piper ONNX inference and returns 16-bit PCM RIFF WAV audio bytes.
     */
    private fun doSynthesize(text: String, voiceId: String, ratePercent: Int): ByteArray? {
        val (session, config) = ensureModelLoaded(voiceId) ?: return null

        val phonemes = PiperPhonemizer.phonemize(text)
        if (phonemes.isBlank()) return null

        val ids = config.textToIds(phonemes)
        if (ids.isEmpty()) return null

        // Calculate speed multiplier from rate percent: 0% -> 1.0, 30% -> 1.3
        val speedMultiplier = (1.0f + ratePercent / 100f).coerceIn(0.6f, 2.2f)

        return synchronized(sessionLock) {
            val curSession = ortSession ?: return@synchronized null
            val curEnv = env ?: return@synchronized null
            var inputTensor: OnnxTensor? = null
            var lengthsTensor: OnnxTensor? = null
            var scalesTensor: OnnxTensor? = null
            var sidTensor: OnnxTensor? = null
            var result: OrtSession.Result? = null

            try {
                inputTensor = OnnxTensor.createTensor(
                    curEnv,
                    LongBuffer.wrap(ids),
                    longArrayOf(1, ids.size.toLong())
                )
                lengthsTensor = OnnxTensor.createTensor(
                    curEnv,
                    LongBuffer.wrap(longArrayOf(ids.size.toLong())),
                    longArrayOf(1)
                )
                scalesTensor = OnnxTensor.createTensor(
                    curEnv,
                    FloatBuffer.wrap(floatArrayOf(config.noiseScale, config.lengthScale / speedMultiplier, config.noiseW)),
                    longArrayOf(3)
                )

                val inputs = mutableMapOf<String, OnnxTensor>(
                    "input" to inputTensor,
                    "input_lengths" to lengthsTensor,
                    "scales" to scalesTensor
                )

                if (config.numSpeakers > 1) {
                    sidTensor = OnnxTensor.createTensor(
                        curEnv,
                        LongBuffer.wrap(longArrayOf(0)),
                        longArrayOf(1)
                    )
                    inputs["sid"] = sidTensor
                }

                result = curSession.run(inputs)
                val outTensor = result.get(0) as? OnnxTensor ?: return@synchronized null
                val fb = outTensor.floatBuffer
                val pcmFloat = FloatArray(fb.remaining())
                fb.get(pcmFloat)

                pcmToWav(pcmFloat, config.sampleRate)
            } finally {
                runCatching { inputTensor?.close() }
                runCatching { lengthsTensor?.close() }
                runCatching { scalesTensor?.close() }
                runCatching { sidTensor?.close() }
                runCatching { result?.close() }
            }
        }
    }

    /**
     * Converts raw float audio samples [-1.0f .. 1.0f] to standard 16-bit PCM WAV.
     */
    private fun pcmToWav(floatPcm: FloatArray, sampleRate: Int): ByteArray {
        val numSamples = floatPcm.size
        val dataSize = numSamples * 2
        val totalSize = 36 + dataSize

        val out = ByteArrayOutputStream(44 + dataSize)

        // RIFF header
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(intToLittleEndian(totalSize))
        out.write("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt subchunk
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        out.write(intToLittleEndian(16)) // subchunk1 size
        out.write(shortToLittleEndian(1)) // audio format 1 = PCM
        out.write(shortToLittleEndian(1)) // num channels = 1 (mono)
        out.write(intToLittleEndian(sampleRate))
        out.write(intToLittleEndian(sampleRate * 2)) // byte rate (sampleRate * numChannels * bitsPerSample/8)
        out.write(shortToLittleEndian(2)) // block align (numChannels * bitsPerSample/8)
        out.write(shortToLittleEndian(16)) // bits per sample = 16

        // data subchunk
        out.write("data".toByteArray(Charsets.US_ASCII))
        out.write(intToLittleEndian(dataSize))

        // Samples
        val pcmData = ByteArray(dataSize)
        var byteIdx = 0
        for (i in 0 until numSamples) {
            val clamped = floatPcm[i].coerceIn(-1.0f, 1.0f)
            val sampleInt = (clamped * 32767f).toInt().coerceIn(-32768, 32767)
            pcmData[byteIdx++] = (sampleInt and 0xFF).toByte()
            pcmData[byteIdx++] = ((sampleInt shr 8) and 0xFF).toByte()
        }
        out.write(pcmData)

        return out.toByteArray()
    }

    private fun intToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun shortToLittleEndian(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )

    private fun playAudioBytes(
        wavBytes: ByteArray,
        seq: Int,
        cutOnNewer: Boolean,
        onResult: ((ok: Boolean, detail: String) -> Unit)?
    ) {
        playbackWorker.execute {
            if (released || seq != reqSeq.get()) {
                onResult?.invoke(false, "superseded")
                return@execute
            }

            try {
                if (cutOnNewer) {
                    stopPlayerInternal()
                }

                val tempFile = File.createTempFile("piper_play_", ".wav", appContext.cacheDir)
                FileOutputStream(tempFile).use { it.write(wavBytes) }

                val player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .build()
                    )
                    FileInputStream(tempFile).use { fis ->
                        setDataSource(fis.fd)
                    }
                    setVolume(narrationVolume, narrationVolume)
                    setOnCompletionListener { mp ->
                        playbackWorker.execute {
                            synchronized(playerLock) {
                                if (activePlayer == mp) {
                                    activePlayer = null
                                    if (isSpeaking.getAndSet(false)) {
                                        mainHandler.post { onSpeechEnd() }
                                    }
                                }
                            }
                            runCatching { mp.release() }
                            runCatching { tempFile.delete() }
                        }
                    }
                    setOnErrorListener { mp, what, extra ->
                        Log.w(TAG, "MediaPlayer error: what=$what extra=$extra")
                        playbackWorker.execute {
                            synchronized(playerLock) {
                                if (activePlayer == mp) {
                                    activePlayer = null
                                    if (isSpeaking.getAndSet(false)) {
                                        mainHandler.post { onSpeechEnd() }
                                    }
                                }
                            }
                            runCatching { mp.release() }
                            runCatching { tempFile.delete() }
                        }
                        true
                    }
                    prepare()
                }

                synchronized(playerLock) {
                    if (released || seq != reqSeq.get()) {
                        runCatching { player.release() }
                        runCatching { tempFile.delete() }
                        onResult?.invoke(false, "superseded")
                        return@execute
                    }
                    activePlayer = player
                    player.start()
                }

                if (!isSpeaking.getAndSet(true)) {
                    mainHandler.post { onSpeechStart() }
                }
                onResult?.invoke(true, "playing")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start MediaPlayer: ${t.message}", t)
                stopPlayerInternal()
                onResult?.invoke(false, "playback_failed")
            }
        }
    }

    private fun stopPlayerInternal() {
        synchronized(playerLock) {
            val player = activePlayer ?: return
            activePlayer = null
            runCatching {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            if (isSpeaking.getAndSet(false)) {
                mainHandler.post { onSpeechEnd() }
            }
        }
    }

    private val httpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun ensureModelLoaded(voiceId: String): Pair<OrtSession, PiperVoiceConfig>? {
        synchronized(sessionLock) {
            if (ortSession != null && voiceConfig != null && currentVoiceId == voiceId) {
                return Pair(ortSession!!, voiceConfig!!)
            }

            val ortEnv = env ?: run {
                NarrationDiag.log(appContext, "piper: env is null")
                onStatus(NarrationEngineStatus.FAILED)
                return null
            }

            val modelFile = File(modelsDir, "$voiceId.onnx")
            val configFile = File(modelsDir, "$voiceId.onnx.json")

            if (!modelFile.exists() || !configFile.exists() || modelFile.length() < 10_000_000L || configFile.length() < 100L) {
                if (!downloadVoiceModel(voiceId, modelFile, configFile)) {
                    return null
                }
            }

            return try {
                NarrationDiag.log(appContext, "piper: load model $voiceId")
                onStage("piper: load $voiceId")

                val config = PiperVoiceConfig.fromFile(configFile)
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setInterOpNumThreads(1)
                    setMemoryPatternOptimization(false)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                    addConfigEntry("session.disable_prepacking", "1")
                }

                ortSession?.close()
                val session = ortEnv.createSession(modelFile.absolutePath, sessionOptions)

                ortSession = session
                voiceConfig = config
                currentVoiceId = voiceId

                onStatus(NarrationEngineStatus.READY)
                NarrationDiag.log(appContext, "piper: ready ($voiceId)")
                Pair(session, config)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load Piper model $voiceId: ${t.message}", t)
                NarrationDiag.log(appContext, "piper: load failed ${t.javaClass.simpleName}")
                onStatus(NarrationEngineStatus.FAILED)
                null
            }
        }
    }

    private fun downloadVoiceModel(voiceId: String, modelFile: File, configFile: File): Boolean {
        if (!isDownloading.compareAndSet(false, true)) {
            // Already downloading, wait up to 60 seconds
            for (i in 0 until 120) {
                Thread.sleep(500)
                if (modelFile.exists() && modelFile.length() >= 10_000_000L && configFile.exists() && configFile.length() >= 100L) return true
                if (!isDownloading.get()) break
            }
            return modelFile.exists() && modelFile.length() >= 10_000_000L && configFile.exists() && configFile.length() >= 100L
        }

        try {
            onStatus(NarrationEngineStatus.LOADING)
            onStage("piper: downloading $voiceId")
            NarrationDiag.log(appContext, "piper: start download $voiceId")

            val modelUrl = PiperVoiceCatalog.getModelUrl(voiceId)
            val configUrl = PiperVoiceCatalog.getConfigUrl(voiceId)

            downloadHttp(configUrl, configFile)
            downloadHttp(modelUrl, modelFile)

            NarrationDiag.log(appContext, "piper: downloaded $voiceId (${modelFile.length()} bytes)")
            return modelFile.exists() && modelFile.length() >= 10_000_000L && configFile.exists() && configFile.length() >= 100L
        } catch (t: Throwable) {
            Log.e(TAG, "Download failed for voice $voiceId: ${t.message}", t)
            NarrationDiag.log(appContext, "piper: download failed ${t.javaClass.simpleName}")
            runCatching { modelFile.delete() }
            runCatching { configFile.delete() }
            return false
        } finally {
            isDownloading.set(false)
        }
    }

    private fun downloadHttp(urlStr: String, dest: File) {
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        val req = okhttp3.Request.Builder()
            .url(urlStr)
            .header("User-Agent", "NuvioATV/1.1.0")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw java.io.IOException("HTTP ${resp.code}: ${resp.message}")
            }
            val body = resp.body ?: throw java.io.IOException("Empty response body from $urlStr")
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output)
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
            throw java.io.IOException("Empty download from $urlStr")
        }
    }

    private fun saveToDiskCache(file: File, bytes: ByteArray) {
        try {
            ensureDiskCacheBudget(bytes.size.toLong())
            val tmp = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(tmp).use { it.write(bytes) }
            tmp.renameTo(file)
        } catch (_: Exception) {}
    }

    private fun ensureDiskCacheBudget(newBytes: Long) {
        val files = cacheDir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total + newBytes <= MAX_DISK_CACHE_BYTES) return

        files.sortBy { it.lastModified() }
        for (f in files) {
            if (total + newBytes <= MAX_DISK_CACHE_BYTES) break
            val len = f.length()
            if (f.delete()) {
                total -= len
            }
        }
    }

    private fun buildCacheKey(text: String, voiceId: String, ratePercent: Int): String {
        val md = MessageDigest.getInstance("MD5")
        val hash = md.digest("$voiceId:$ratePercent:$text".toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
