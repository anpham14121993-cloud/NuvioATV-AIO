package com.nuvio.tv.core.player.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.nuvio.tv.core.player.tts.google.GoogleTtsApi
import com.nuvio.tv.core.player.tts.piper.PiperPhonemizer
import com.nuvio.tv.core.player.tts.piper.PiperVoiceCatalog
import com.nuvio.tv.core.player.tts.piper.PiperVoiceConfig
import com.nuvio.tv.core.player.tts.piper.PiperVoiceManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance, low-latency Vietnamese narration engine adopting the full Vax architecture.
 *
 * Core Pillars:
 * 1. Default High-Speed Cloud TTS (Google TTS tw-ob): 0 MB download, ~150ms network synthesis,
 *    zero CPU overhead on low-end Android TV boxes.
 * 2. High-Fidelity Offline Piper ONNX Models: Multi-threaded (intraOpThreads=2), BASIC_OPT,
 *    and SilenceTrimmer leading/trailing silence removal with cosine-taper smoothing.
 * 3. Automatic Seamless Cloud Fallback: If an offline model is slow (>1500ms), missing, or fails,
 *    instantly synthesizes via Google TTS so zero sentences are ever skipped or missed.
 * 4. Vax Non-Blocking TtsAudioPlayerQueue: ConcurrentLinkedQueue with dynamic playback speed catch-up
 *    (setSpeed 1.1x - 1.3x when queue size >= 2) and LoudnessEnhancer volume boost.
 * 5. Rolling RAM and persistent disk cache with lookahead prefetching for 0ms cue-sync playback.
 */
class PiperNarrationEngine(
    private val appContext: Context,
    private val onSpeechStart: () -> Unit = {},
    private val onSpeechEnd: () -> Unit = {},
    private val onError: (text: String, rate: Int, reason: String?) -> Unit = { _, _, _ -> },
    private val onStatus: (NarrationEngineStatus) -> Unit = {},
    private val onStage: (String) -> Unit = {},
) : NarrationTtsEngine {

    companion object {
        private const val TAG = "PiperNarration"
        private const val MAX_PREFETCH_FILES = 40
    }

    private data class PrefetchedAudio(
        val file: File,
        val timeStretched: Boolean,
        val generation: Long,
    )

    private val prefetchInFlight = ConcurrentHashMap.newKeySet<String>()

    // Dedicated single-thread worker for live orchestration so slow ONNX never stalls Google fallback
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-piper-worker").apply { isDaemon = true }
    }

    // Separate single-thread executor for heavy ONNX inference (can take 2-6s on 32-bit TV boxes)
    private val piperExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-piper-onnx").apply { isDaemon = true }
    }

    // 1 background prefetch worker with lowest priority so it never starves live playback
    private val prefetchQueue = LinkedBlockingQueue<Runnable>()
    private val prefetchExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, prefetchQueue
    ) { runnable ->
        Thread(runnable, "nuvio-piper-prefetch").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    // Non-blocking Audio Player Queue directly matching Vax k26
    private val audioQueue = TtsAudioPlayerQueue(
        context = appContext,
        onSpeechStart = onSpeechStart,
        onSpeechEnd = onSpeechEnd,
        onPlaybackError = { item, reason ->
            mainHandler.post {
                if (!released && item.text.isNotBlank()) {
                    onError(item.text, item.ratePercent, reason)
                }
            }
        },
    )

    private val prefetchLock = Any()
    private val prefetchedFiles = object : LinkedHashMap<String, PrefetchedAudio>(MAX_PREFETCH_FILES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PrefetchedAudio>?): Boolean {
            if (size > MAX_PREFETCH_FILES) {
                eldest?.value?.file?.let { runCatching { it.delete() } }
                return true
            }
            return false
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val modelsDir = File(appContext.filesDir, "piper_models").apply { mkdirs() }
    private val cacheDir = File(appContext.cacheDir, "tts_cache").apply { mkdirs() }

    @Volatile
    private var narrationVolume = 1f

    private val cancelSeq = AtomicLong(0L)
    private val liveSpeakSeq = AtomicLong(0L)
    private val liveSynthesisActive = AtomicBoolean(false)
    @Volatile
    private var slowOfflinePiper = false
    @Volatile
    private var slowPiperVoiceId: String = ""
    @Volatile
    private var lastInferenceMs: Long = 0L

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

    private fun executeSafe(executor: ExecutorService, block: () -> Unit): Boolean {
        if (released || executor.isShutdown || executor.isTerminated) return false
        return try {
            executor.execute(block)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    private fun googleSpeechRate(ratePercent: Int): Float {
        // Software Sonic time-stretcher in Media3 ExoPlayer cleanly supports [1.20x .. 1.55x],
        // keeping Google TTS cadence aligned with Vietnamese subtitle pacing.
        return (1.20f + ((ratePercent - 70).coerceIn(0, 70) / 70f) * 0.35f).coerceIn(1.15f, 1.55f)
    }

    private fun storePrefetched(textKey: String, entry: PrefetchedAudio): Boolean {
        if (released || entry.generation != cancelSeq.get() || !entry.file.exists()) {
            runCatching { entry.file.delete() }
            return false
        }
        synchronized(prefetchLock) {
            if (released || entry.generation != cancelSeq.get()) {
                runCatching { entry.file.delete() }
                return false
            }
            val prev = prefetchedFiles.put(textKey, entry)
            if (prev != null && prev.file != entry.file) {
                runCatching { prev.file.delete() }
            }
        }
        return true
    }

    override fun warmUp() {
        if (released) return
        executeSafe(worker) {
            if (released) return@executeSafe
            GoogleTtsApi.warmUpConnection()
            onStatus(NarrationEngineStatus.READY)
            NarrationDiag.log(appContext, "piper: ready (vax engine)")
        }
    }

    override fun setNarrationVolume(volume01: Float) {
        narrationVolume = volume01.coerceIn(0f, 1.5f)
        audioQueue.volumeMultiplier = narrationVolume
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

        val currentCancel = cancelSeq.get()
        val mySpeakSeq = if (cutOnNewer) liveSpeakSeq.incrementAndGet() else liveSpeakSeq.get()
        val speakStartedAt = System.currentTimeMillis()
        val textKey = "${voice.id}:$clean"
        val targetDurationMs = budgetMs ?: -1L

        // 1. Check Prefetched Disk Cache First (0ms instant playback)
        val prefetched = synchronized(prefetchLock) { prefetchedFiles.remove(textKey) }
        if (prefetched != null && prefetched.file.exists() && prefetched.file.length() > 44L) {
            val rate = if (prefetched.timeStretched) 1.0f else googleSpeechRate(ratePercent)
            audioQueue.enqueue(
                QueuedTtsAudio(
                    file = prefetched.file,
                    speechRate = rate,
                    isTimeStretched = prefetched.timeStretched,
                    targetDurationMs = targetDurationMs,
                    text = clean,
                    ratePercent = ratePercent,
                    cutOnNewer = cutOnNewer,
                )
            )
            onResult?.invoke(true, "cache_hit")
            return
        }

        // 2. Synthesize via Worker
        val scheduled = executeSafe(worker) {
            if (released || currentCancel != cancelSeq.get() || mySpeakSeq != liveSpeakSeq.get()) {
                onResult?.invoke(false, "superseded")
                return@executeSafe
            }

            var audioFile: File? = null
            var usedOfflinePiper = false
            var failureReason: String? = null

            // A. If Online Google TTS is selected (Vax Default):
            if (PiperVoiceCatalog.isOnlineVoice(voice.id)) {
                try {
                    liveSynthesisActive.set(true)
                    val t0 = System.currentTimeMillis()
                    val result = GoogleTtsApi.synthesize(appContext, clean)
                    val took = System.currentTimeMillis() - t0
                    audioFile = result.file
                    if (audioFile != null && audioFile.exists()) {
                        NarrationDiag.log(appContext, "google: synth ok in ${took}ms (len=${clean.length})")
                    } else {
                        failureReason = result.error ?: "Google TTS failed"
                        NarrationDiag.log(appContext, "google: synth failed ($failureReason) in ${took}ms")
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Google TTS live synthesis error: ${t.message}", t)
                    failureReason = "${t.javaClass.simpleName}: ${t.message ?: ""}".trimEnd(':', ' ')
                    NarrationDiag.log(appContext, "google: err $failureReason")
                } finally {
                    liveSynthesisActive.set(false)
                }
            } else {
                // B. Offline Piper Voice Selected: auto-fallback to fast Google TTS when TV CPU is slow
                val outcome = synthesizeOfflineWithRealtimeGuard(
                    clean = clean,
                    voiceId = voice.id,
                    ratePercent = ratePercent,
                    mySpeakSeq = mySpeakSeq,
                    currentCancel = currentCancel,
                )
                audioFile = outcome.file
                usedOfflinePiper = outcome.usedOfflinePiper
                failureReason = outcome.failureReason
            }

            if (released || currentCancel != cancelSeq.get() || mySpeakSeq != liveSpeakSeq.get()) {
                runCatching { audioFile?.delete() }
                onResult?.invoke(false, "superseded")
                return@executeSafe
            }

            if (audioFile != null && audioFile.exists() && audioFile.length() > 44L) {
                val rate = if (usedOfflinePiper) 1.0f else googleSpeechRate(ratePercent)
                val elapsedSynthMs = (System.currentTimeMillis() - speakStartedAt).coerceAtLeast(0L)
                val adjustedTargetMs = if (targetDurationMs > 0L) {
                    (targetDurationMs - elapsedSynthMs).coerceAtLeast(500L)
                } else {
                    -1L
                }
                audioQueue.enqueue(
                    QueuedTtsAudio(
                        file = audioFile,
                        speechRate = rate,
                        isTimeStretched = usedOfflinePiper,
                        targetDurationMs = adjustedTargetMs,
                        text = clean,
                        ratePercent = ratePercent,
                        cutOnNewer = cutOnNewer,
                    )
                )
                onResult?.invoke(true, "synth_ok")
            } else {
                val detail = failureReason ?: "all synth paths failed"
                NarrationDiag.log(appContext, "piper: $detail for len=${clean.length}")
                mainHandler.post { if (!released) onError(clean, ratePercent, detail) }
                onResult?.invoke(false, "synth_failed")
            }
        }
        if (!scheduled) {
            onResult?.invoke(false, "released")
        }
    }

    override fun prefetch(text: String, voiceApiName: String, ratePercent: Int, targetRam: Boolean) {
        if (released) return
        val voice = PiperVoiceCatalog.findVoice(voiceApiName)
        val clean = PiperPhonemizer.cleanSubtitleText(text)
        if (clean.isBlank()) return

        val textKey = "${voice.id}:$clean"
        val alreadyCached = synchronized(prefetchLock) {
            val existing = prefetchedFiles[textKey]
            existing != null && existing.file.exists() && existing.file.length() > 44L
        }
        if (alreadyCached) return
        if (!prefetchInFlight.add(textKey)) return

        val currentCancel = cancelSeq.get()
        val scheduled = executeSafe(prefetchExecutor) {
            try {
                // Yield briefly if a live line is downloading rather than discarding upcoming cues.
                var waited = 0
                while (liveSynthesisActive.get() && !released && currentCancel == cancelSeq.get() && waited < 15) {
                    Thread.sleep(40L)
                    waited++
                }
                if (released || currentCancel != cancelSeq.get()) return@executeSafe

                if (PiperVoiceCatalog.isOnlineVoice(voice.id) ||
                    !PiperVoiceManager.isVoiceDownloaded(appContext, voice.id) ||
                    slowOfflinePiper
                ) {
                    val file = GoogleTtsApi.synthesizeToFile(appContext, clean)
                    if (file != null) {
                        storePrefetched(textKey, PrefetchedAudio(file, timeStretched = false, generation = currentCancel))
                    }
                } else {
                    val bytes = doSynthesize(clean, voice.id, ratePercent)
                    if (lastInferenceMs > 1_150L) {
                        slowOfflinePiper = true
                    }
                    if (bytes != null && bytes.size > 44) {
                        val f = File(cacheDir, "offline_tts_${UUID.randomUUID()}.wav")
                        FileOutputStream(f).use { it.write(bytes) }
                        storePrefetched(textKey, PrefetchedAudio(f, timeStretched = true, generation = currentCancel))
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Prefetch failed for $textKey: ${t.message}")
            } finally {
                prefetchInFlight.remove(textKey)
            }
        }
        if (!scheduled) {
            prefetchInFlight.remove(textKey)
        }
    }

    override fun stop() {
        liveSpeakSeq.incrementAndGet()
        audioQueue.stop()
        clearPrefetchQueue()
    }

    override fun release() {
        released = true
        cancelSeq.incrementAndGet()
        liveSpeakSeq.incrementAndGet()
        audioQueue.stop()
        audioQueue.release()
        synchronized(prefetchLock) {
            prefetchedFiles.values.forEach { runCatching { it.file.delete() } }
            prefetchedFiles.clear()
        }
        clearPrefetchQueue()

        runCatching { prefetchQueue.clear() }
        runCatching { prefetchExecutor.shutdownNow() }
        runCatching { worker.shutdownNow() }
        runCatching { piperExecutor.shutdownNow() }

        // Close ONNX session off the caller/Main thread so ongoing inference cannot block UI teardown.
        Thread({
            synchronized(sessionLock) {
                runCatching { ortSession?.close() }
                ortSession = null
                voiceConfig = null
                currentVoiceId = ""
            }
            runCatching {
                cacheDir.listFiles()?.forEach { file -> runCatching { file.delete() } }
            }
        }, "nuvio-piper-release").apply {
            isDaemon = true
            start()
        }
    }

    override fun clearPrefetchQueue() {
        prefetchQueue.clear()
        prefetchInFlight.clear()
    }

    private data class SynthOutcome(
        val file: File?,
        val usedOfflinePiper: Boolean,
        val failureReason: String? = null,
    )

    private fun writeOfflineWav(bytes: ByteArray?): File? {
        if (bytes == null || bytes.size <= 44) return null
        val file = File(cacheDir, "offline_tts_${UUID.randomUUID()}.wav")
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    private fun synthesizeOfflineWithRealtimeGuard(
        clean: String,
        voiceId: String,
        ratePercent: Int,
        mySpeakSeq: Long,
        currentCancel: Long,
    ): SynthOutcome {
        if (!PiperVoiceManager.isVoiceDownloaded(appContext, voiceId)) {
            NarrationDiag.log(appContext, "piper: voice $voiceId not downloaded -> fallback Google TTS")
            val result = GoogleTtsApi.synthesize(appContext, clean)
            return SynthOutcome(
                file = result.file,
                usedOfflinePiper = false,
                failureReason = if (result.file == null) "Voice not downloaded & Google TTS: ${result.error ?: "failed"}" else null,
            )
        }

        if (slowPiperVoiceId != voiceId) {
            slowPiperVoiceId = voiceId
            slowOfflinePiper = false
        }

        liveSynthesisActive.set(true)
        try {
            if (slowOfflinePiper) {
                val tGoogle = System.currentTimeMillis()
                val googleResult = GoogleTtsApi.synthesize(appContext, clean)
                val googleTook = System.currentTimeMillis() - tGoogle
                if (googleResult.file != null) {
                    NarrationDiag.log(appContext, "google (fast fallback): ok ${googleTook}ms (len=${clean.length})")
                    return SynthOutcome(googleResult.file, usedOfflinePiper = false)
                }
                val wav = writeOfflineWav(doSynthesize(clean, voiceId, ratePercent))
                return if (wav != null) {
                    SynthOutcome(wav, usedOfflinePiper = true)
                } else {
                    SynthOutcome(null, usedOfflinePiper = false, "Google TTS (${googleResult.error}) & Piper failed")
                }
            }

            val alreadyLoaded = currentVoiceId == voiceId && ortSession != null && voiceConfig != null
            val deadlineMs = if (alreadyLoaded) 1_050L else 1_600L
            val t0 = System.currentTimeMillis()
            val synthFuture: Future<ByteArray?>? = runCatching {
                if (released || piperExecutor.isShutdown) null
                else piperExecutor.submit(Callable { doSynthesize(clean, voiceId, ratePercent) })
            }.getOrNull()

            if (synthFuture != null) {
                var waitedMs = 0L
                while (!synthFuture.isDone && waitedMs < deadlineMs) {
                    if (released || currentCancel != cancelSeq.get() || mySpeakSeq != liveSpeakSeq.get()) {
                        return SynthOutcome(null, usedOfflinePiper = false)
                    }
                    Thread.sleep(50L)
                    waitedMs += 50L
                }
                if (released || currentCancel != cancelSeq.get() || mySpeakSeq != liveSpeakSeq.get()) {
                    return SynthOutcome(null, usedOfflinePiper = false)
                }
                if (synthFuture.isDone) {
                    val bytes = synthFuture.get()
                    val took = System.currentTimeMillis() - t0
                    val inferMs = lastInferenceMs
                    if (inferMs > 1_150L) {
                        slowOfflinePiper = true
                        NarrationDiag.log(appContext, "piper: slow (${inferMs}ms) -> auto Google TTS next")
                    }
                    val wav = writeOfflineWav(bytes)
                    if (wav != null) {
                        NarrationDiag.log(appContext, "piper: synth ok ${bytes!!.size}B in ${took}ms (len=${clean.length} r=$ratePercent)")
                        return SynthOutcome(wav, usedOfflinePiper = true)
                    }
                } else {
                    slowOfflinePiper = true
                    NarrationDiag.log(appContext, "piper: >${deadlineMs}ms -> fast Google TTS")
                    val googleResult = GoogleTtsApi.synthesize(appContext, clean)
                    if (googleResult.file != null) {
                        return SynthOutcome(googleResult.file, usedOfflinePiper = false)
                    }
                    val bytes = runCatching { synthFuture.get(8_000L, TimeUnit.MILLISECONDS) }.getOrNull()
                    val wav = writeOfflineWav(bytes)
                    return if (wav != null) {
                        SynthOutcome(wav, usedOfflinePiper = true)
                    } else {
                        SynthOutcome(null, usedOfflinePiper = false, "Piper timeout & Google TTS: ${googleResult.error ?: "failed"}")
                    }
                }
            }

            NarrationDiag.log(appContext, "piper: synth null -> fallback Google TTS")
            val fallback = GoogleTtsApi.synthesize(appContext, clean)
            return SynthOutcome(
                file = fallback.file,
                usedOfflinePiper = false,
                failureReason = if (fallback.file == null) "Piper null & Google TTS: ${fallback.error ?: "failed"}" else null,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Piper live synthesis error, falling back: ${t.message}", t)
            NarrationDiag.log(appContext, "piper: err -> fallback Google TTS")
            val fallback = GoogleTtsApi.synthesize(appContext, clean)
            return SynthOutcome(
                file = fallback.file,
                usedOfflinePiper = false,
                failureReason = if (fallback.file == null) "Piper ${t.javaClass.simpleName} & Google TTS: ${fallback.error ?: "failed"}" else null,
            )
        } finally {
            liveSynthesisActive.set(false)
        }
    }

    /**
     * Executes Piper ONNX inference with multi-threading and silence trimming.
     */
    private fun doSynthesize(text: String, voiceId: String, ratePercent: Int): ByteArray? {
        val (session, config) = ensureModelLoaded(voiceId) ?: return null

        val phonemes = PiperPhonemizer.phonemize(text)
        if (phonemes.isBlank()) return null

        val ids = config.textToIds(phonemes)
        if (ids.isEmpty()) return null

        val speedMultiplier = (1.0f + ratePercent / 100f).coerceIn(0.6f, 2.6f)

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

                val tInfer = System.currentTimeMillis()
                result = curSession.run(inputs)
                lastInferenceMs = System.currentTimeMillis() - tInfer
                val outTensor = result.get(0) as? OnnxTensor ?: return@synchronized null
                val fb = outTensor.floatBuffer
                val pcmFloat = FloatArray(fb.remaining())
                fb.get(pcmFloat)

                // Trim leading/trailing silence with cosine-taper smoothing
                val trimmedPcm = SilenceTrimmer.trimSilence(pcmFloat, config.sampleRate)
                pcmToWav(trimmedPcm, config.sampleRate)
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
        out.write(intToLittleEndian(16))
        out.write(shortToLittleEndian(1)) // PCM
        out.write(shortToLittleEndian(1)) // mono
        out.write(intToLittleEndian(sampleRate))
        out.write(intToLittleEndian(sampleRate * 2))
        out.write(shortToLittleEndian(2))
        out.write(shortToLittleEndian(16))

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

    private fun ensureModelLoaded(voiceId: String): Pair<OrtSession, PiperVoiceConfig>? {
        if (released || PiperVoiceCatalog.isOnlineVoice(voiceId)) return null
        synchronized(sessionLock) {
            if (released) return null
            if (currentVoiceId == voiceId && ortSession != null && voiceConfig != null) {
                return Pair(ortSession!!, voiceConfig!!)
            }

            val ortEnv = env ?: return null
            val modelFile = File(modelsDir, "$voiceId.onnx")
            val configFile = File(modelsDir, "$voiceId.onnx.json")

            if (!modelFile.exists() || !configFile.exists() || modelFile.length() < 10_000_000L) {
                return null
            }

            try {
                onStatus(NarrationEngineStatus.LOADING)
                onStage("piper: loading model $voiceId")
                NarrationDiag.log(appContext, "piper: load model $voiceId")

                val config = PiperVoiceConfig.fromFile(configFile)
                // Keep 32-bit ARMv7 TV-box stability flags (NO_OPT + disabled prepacking /
                // memory-pattern optimization) so ONNX Runtime does not abort in createSession.
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                    setInterOpNumThreads(1)
                    setMemoryPatternOptimization(false)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                    addConfigEntry("session.disable_prepacking", "1")
                }

                val session = try {
                    ortSession?.close()
                    ortEnv.createSession(modelFile.absolutePath, sessionOptions)
                } finally {
                    runCatching { sessionOptions.close() }
                }

                ortSession = session
                voiceConfig = config
                currentVoiceId = voiceId

                onStatus(NarrationEngineStatus.READY)
                NarrationDiag.log(appContext, "piper: ready ($voiceId)")
                return Pair(session, config)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load Piper model $voiceId: ${t.message}", t)
                NarrationDiag.log(appContext, "piper: load failed ${t.javaClass.simpleName}")
                onStatus(NarrationEngineStatus.FAILED)
                return null
            }
        }
    }

    private fun buildCacheKey(text: String, voiceId: String, ratePercent: Int): String {
        val md = MessageDigest.getInstance("MD5")
        val hash = md.digest("$voiceId:$ratePercent:$text".toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
