package com.nuvio.app.features.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.nuvio.app.vieneu.VieneuEngine
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Offline VieNeu-TTS v3 narration engine: synthesizes Vietnamese PCM from the
 * bundled int8 ONNX models and plays it through an [AudioTrack] with the same
 * duck/budget semantics used by the narration controller. No network involved.
 *
 * Heavy work (asset extraction, model load, synthesis, playback wait) all runs
 * on a single worker thread, so cue callbacks off the main thread never block UI.
 */
class VieneuNarrationEngine(
    private val appContext: Context,
    private val onSpeechStart: () -> Unit = {},
    private val onSpeechEnd: () -> Unit = {},
    private val onError: (text: String, rate: Int) -> Unit = { _, _ -> },
) : NarrationTtsEngine {

    private companion object {
        const val TAG = "VieneuNarration"
        const val THREADS = 4
        const val GAIN = 2.2f
        const val MAX_CACHE = 32
        const val PREFETCH_PACE_MS = 300L
        const val MAX_DISK_BYTES = 256L * 1024L * 1024L
        const val MALE = "Adam"
        const val FEMALE = "Thùy Dung"
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nuvio-vieneu-tts").apply { isDaemon = true }
    }
    private val prefetchPool = Executors.newFixedThreadPool(1) { runnable ->
        Thread(runnable, "nuvio-vieneu-prefetch").apply { isDaemon = true }
    }
    private val prefetchInFlight = ConcurrentHashMap.newKeySet<String>()
    private val stopHandler = Handler(Looper.getMainLooper())
    private val synthLock = Any()
    private val speakWaiting = AtomicInteger()
    private val diskHit = AtomicInteger()
    private val diskCacheDir = File(appContext.filesDir, "vieneu_cache")

    @Volatile
    private var engine: VieneuEngine? = null
    private val loadLock = Any()
    @Volatile
    private var loadError: String = ""
    @Volatile
    private var released = false

    @Volatile
    private var narrationVolume = 1f
    private val currentTrack = AtomicReference<AudioTrack?>(null)

    /** Monotonic per-request sequence; a larger value always means "newer". */
    private val reqSeq = AtomicInteger(0)
    /** Bumped by stop()/release() to cancel in-flight playback immediately. */
    private val stopToken = AtomicInteger(0)

    @Volatile
    private var req: Req? = null

    private val playStart = AtomicInteger(0)
    private val playDone = AtomicInteger(0)
    private val playCut = AtomicInteger(0)
    private val synthOk = AtomicInteger(0)
    private val synthFail = AtomicInteger(0)
    private val cacheHit = AtomicInteger(0)
    private val staleDrop = AtomicInteger(0)
    private val prefetchCount = AtomicInteger(0)

    private val voices = HashMap<String, Voice>()

    private val cache = LinkedHashMap<String, FloatArray>(64, 0.75f, true)
    private val cacheLock = Any()

    private data class Req(
        val seq: Int,
        val text: String,
        val voiceApi: String,
        val rate: Int,
        val cutOnNewer: Boolean,
        val budgetMs: Long?,
        val onResult: ((Boolean, String) -> Unit)?,
    )

    private data class Voice(val emb: FloatArray, val codes: Array<IntArray>)

    override fun warmUp() {
        if (released) return
        runCatching { worker.execute { ensureEngine() } }
    }

    override fun setNarrationVolume(volume01: Float) {
        narrationVolume = volume01.coerceIn(0f, 1f)
        runCatching { currentTrack.get()?.setVolume(narrationVolume) }
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
        req = Req(seq, text, voiceApiName, ratePercent, cutOnNewer, budgetMs, onResult)
        speakWaiting.incrementAndGet()
        runCatching { worker.execute { runSpeak(seq) } }
    }

    override fun prefetch(text: String, voiceApiName: String, ratePercent: Int) {
        if (released || text.isBlank()) return
        val key = "$voiceApiName|$text"
        if (!prefetchInFlight.add(key)) return
        prefetchCount.incrementAndGet()
        runCatching {
            prefetchPool.execute {
                try {
                    // Never make the line on screen wait for background work: let the
                    // urgent synth finish first, then continue warming the cache.
                    var waited = 0
                    while (speakWaiting.get() > 0 && !released && waited < 2000) {
                        Thread.sleep(25)
                        waited += 25
                    }
                    if (released) return@execute
                    val e = ensureEngine()
                    if (e != null) {
                        synth(e, text, voiceApiName)
                        // Rolling pre-generation must stay light: spread it out so the
                        // CPU never saturates while the movie is playing.
                        if (!released && speakWaiting.get() == 0) Thread.sleep(PREFETCH_PACE_MS)
                    }
                } catch (_: Throwable) {
                } finally {
                    prefetchInFlight.remove(key)
                }
            }
        }
    }

    override fun stop() {
        stopToken.incrementAndGet()
        runCatching { stopTrack() }
        onSpeechEnd()
    }

    override fun release() {
        if (released) return
        TtsTrace.log("release: begin")
        released = true
        stopToken.incrementAndGet()
        runCatching { stopHandler.removeCallbacksAndMessages(null) }
        runCatching { stopTrack() }
        onSpeechEnd()
        // Never tear the engine down on the caller thread (it is the main thread
        // during navigation): waiting for a running synth + closing four ORT
        // sessions can exceed the ANR window and get the app killed.
        Thread({
            runCatching { prefetchPool.shutdownNow() }
            runCatching { prefetchPool.awaitTermination(2000, TimeUnit.MILLISECONDS) }
            runCatching { worker.shutdownNow() }
            // Closing ORT sessions while a native synthesize() call is in flight is
            // a hard native crash; synthLock guarantees the call has finished.
            synchronized(synthLock) {
                runCatching { engine?.close() }
                engine = null
            }
            TtsTrace.log("release: done")
        }, "nuvio-vieneu-close").apply { isDaemon = true }.start()
    }

    // ── worker ─────────────────────────────────────────────────────────────────

    private fun runSpeak(seq: Int) {
        speakWaiting.decrementAndGet()
        val r = req ?: return
        if (r.seq != seq || released) {
            staleDrop.incrementAndGet()
            r.onResult?.invoke(false, "stale")
            return
        }
        val e = ensureEngine()
        if (e == null) {
            synthFail.incrementAndGet()
            r.onResult?.invoke(false, "model:$loadError")
            onError(r.text, r.rate)
            return
        }
        val pcm = synth(e, r.text, r.voiceApi)
        if (pcm == null) {
            synthFail.incrementAndGet()
            r.onResult?.invoke(false, "synth-failed")
            onError(r.text, r.rate)
            return
        }
        play(pcm, r)
    }

    private fun ensureEngine(): VieneuEngine? {
        engine?.let { return it }
        synchronized(loadLock) {
            engine?.let { return it }
            try {
                val base = VieneuModelStore.modelDir(appContext)
                val heads = File(base, "heads.bin")
                val tokJson = readText(File(base, "tokenizer.json"))
                val g2p = File(base, "sea_g2p.bin").absolutePath
                TtsTrace.log("engine: load start")
                VieneuEngine.TRACE = java.util.function.Consumer { msg -> TtsTrace.log(msg) }
                val t0 = System.nanoTime()
                engine = VieneuEngine(base, heads, tokJson, g2p, THREADS)
                loadError = ""
                TtsTrace.log("engine: load ok")
                Log.i(TAG, "engine loaded in ${"%.2f".format((System.nanoTime() - t0) / 1e9)}s")
                loadVoices(File(base, "voices_test.json"))
            } catch (t: Throwable) {
                loadError = t.javaClass.simpleName + ":" + (t.message?.take(60) ?: "")
                TtsTrace.log("engine: load FAIL $loadError")
                Log.e(TAG, "engine load failed", t)
            }
            return engine
        }
    }

    private fun loadVoices(f: File) {
        runCatching {
            val obj = JSONObject(readText(f))
            val names = obj.keys()
            while (names.hasNext()) {
                val name = names.next()
                val vo = obj.getJSONObject(name)
                val embArr = vo.getJSONArray("speaker_emb")
                val emb = FloatArray(embArr.length()) { embArr.getDouble(it).toFloat() }
                val codesArr = vo.getJSONArray("codes")
                val codes = Array(codesArr.length()) { i ->
                    val row = codesArr.getJSONArray(i)
                    IntArray(row.length()) { row.getInt(it) }
                }
                voices[name] = Voice(emb, codes)
            }
            Log.i(TAG, "voices: ${voices.keys.joinToString(",")}")
        }.onFailure { Log.w(TAG, "loadVoices failed", it) }
    }

    private fun diskFile(name: String, text: String): File {
        val md = java.security.MessageDigest.getInstance("MD5")
        val digest = md.digest("$name|$text".toByteArray())
        val hex = buildString { for (b in digest) append("%02x".format(b)) }
        return File(diskCacheDir, "$hex.pcm")
    }

    private fun readDisk(name: String, text: String): FloatArray? = runCatching {
        val f = diskFile(name, text)
        if (!f.isFile) return null
        val bytes = f.readBytes()
        if (bytes.size < 4) return null
        val out = FloatArray(bytes.size / 4)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer().get(out)
        f.setLastModified(System.currentTimeMillis())
        out
    }.getOrNull()

    private fun writeDisk(name: String, text: String, pcm: FloatArray) {
        runCatching {
            if (!diskCacheDir.isDirectory && !diskCacheDir.mkdirs()) return
            val f = diskFile(name, text)
            if (f.isFile) {
                f.setLastModified(System.currentTimeMillis())
                return
            }
            val bytes = ByteArray(pcm.size * 4)
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().put(pcm)
            val tmp = File(diskCacheDir, f.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
            pruneDisk()
        }
    }

    private fun pruneDisk() {
        runCatching {
            val files = diskCacheDir.listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_DISK_BYTES) return
            files.sortBy { it.lastModified() }
            for (f in files) {
                if (total <= MAX_DISK_BYTES) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        }
    }

    /** Synthesizes PCM (or a cached copy) for [text] in the voice behind [api]. */
    private fun synth(e: VieneuEngine, text: String, api: String): FloatArray? {
        val name = voiceFor(api) ?: return null
        val voice = voices[name] ?: return null
        val key = "$name|$text"
        synchronized(cacheLock) { cache[key] }?.let {
            cacheHit.incrementAndGet()
            return it
        }
        readDisk(name, text)?.let {
            diskHit.incrementAndGet()
            putCache(key, it)
            return it
        }
        return try {
            val p = VieneuEngine.Params()
            // ORT sessions are not re-entrant: serialize synthesis so prefetch and
            // on-demand calls can never corrupt each other's audio. Never touch the
            // engine after release() closed its native sessions.
            TtsTrace.log("synth: start len=" + text.length)
            val wav = synchronized(synthLock) {
                if (released) return null
                e.synthesize(text, voice.emb, voice.codes, p)
            }
            TtsTrace.log("synth: ok samples=" + wav.size)
            synthOk.incrementAndGet()
            putCache(key, wav)
            writeDisk(name, text, wav)
            wav
        } catch (t: Throwable) {
            synthFail.incrementAndGet()
            TtsTrace.log("synth: FAIL " + (t.javaClass.simpleName) + ":" + (t.message?.take(80) ?: ""))
            Log.w(TAG, "synth failed", t)
            null
        }
    }

    private fun voiceFor(api: String): String? = when {
        api.contains(MALE, true) || api.contains("Adam", true) -> MALE
        api.contains(FEMALE, true) || api.contains("Thuy", true) || api.contains("Thùy", true) -> FEMALE
        else -> null
    }

    // ── playback ───────────────────────────────────────────────────────────────

    private fun play(pcm: FloatArray, r: Req) {
        val speed = (1f + r.rate.coerceIn(0, 150) / 100f).coerceIn(1f, 1.6f)
        val samples = floatToShort(pcm)
        val boosted = applyGain(samples)
        val sr = VieneuEngine.SR
        val minBuffer = AudioTrack.getMinBufferSize(
            sr,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(0)
        TtsTrace.log("play: track create size=" + maxOf(boosted.size * 2, minBuffer))
        val track = try {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                maxOf(boosted.size * 2, minBuffer),
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack create failed", t)
            r.onResult?.invoke(false, "audiotrack-create")
            return
        }
        if (released) {
            runCatching { track.release() }
            return
        }
        // New audio is ready: cut the previous line only now so the gap is silent-free.
        stopTrack()
        currentTrack.set(track)
        val myStop = stopToken.get()
        try {
            runCatching { track.setVolume(narrationVolume) }
            if (speed > 1.001f) {
                runCatching {
                    track.playbackParams = android.media.PlaybackParams()
                        .setSpeed(speed)
                        .setPitch(1f)
                }
            }
            TtsTrace.log("play: writing")
            val written = track.write(boosted, 0, boosted.size, AudioTrack.WRITE_BLOCKING)
            TtsTrace.log("play: wrote=" + written)
            if (written < 0) {
                r.onResult?.invoke(false, "audiotrack-write:$written")
                return
            }
            onSpeechStart()
            TtsTrace.log("play: start audio")
            track.play()
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                r.onResult?.invoke(false, "audiotrack-not-playing")
                return
            }
            TtsTrace.log("play: start speed=" + speed)
            playStart.incrementAndGet()
            val frames = boosted.size.coerceAtLeast(written)
            var durationMs = frames.toLong() * 1000L / sr
            if (speed > 1.001f) durationMs = (durationMs / speed).toLong()
            if (r.budgetMs != null && r.budgetMs in 1 until durationMs) durationMs = r.budgetMs
            // Do NOT block the worker here: return immediately so the next cue can be
            // synthesized/played on time. The track is stopped by a scheduled callback.
            val finish = Runnable {
                if (currentTrack.compareAndSet(track, null)) {
                    runCatching { track.stop() }
                    runCatching { track.release() }
                    playDone.incrementAndGet()
                    r.onResult?.invoke(true, "ok")
                    if (stopToken.get() == myStop) onSpeechEnd()
                }
            }
            stopHandler.postDelayed(finish, durationMs + 50L)
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack play failed", t)
            r.onResult?.invoke(false, "audiotrack-play")
            currentTrack.compareAndSet(track, null)
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun stopTrack() {
        val track = currentTrack.getAndSet(null) ?: return
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    private fun applyGain(samples: ShortArray): ShortArray {
        if (GAIN <= 1f) return samples
        val norm = kotlin.math.tanh(GAIN)
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val x = samples[i] / 32768f
            out[i] = (32767f * kotlin.math.tanh(GAIN * x) / norm)
                .toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun floatToShort(pcm: FloatArray): ShortArray {
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            val v = Math.round(pcm[i] * 32767f)
            out[i] = v.coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun putCache(key: String, value: FloatArray) {
        synchronized(cacheLock) {
            cache[key] = value
            while (cache.size > MAX_CACHE) {
                val it = cache.keys.iterator()
                it.next()
                it.remove()
            }
        }
    }

    private fun hasCache(key: String): Boolean = synchronized(cacheLock) { cache.containsKey(key) }

    private fun readText(f: File): String {
        val bo = java.io.ByteArrayOutputStream()
        java.io.FileInputStream(f).use { input ->
            val buf = ByteArray(1 shl 16)
            var read = input.read(buf)
            while (read > 0) {
                bo.write(buf, 0, read)
                read = input.read(buf)
            }
        }
        return bo.toString("UTF-8")
    }
}
