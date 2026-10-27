package com.nuvio.tv.core.player.tts

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
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
import java.util.function.BooleanSupplier

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
    private val onStatus: (NarrationEngineStatus) -> Unit = {},
    private val onStage: (String) -> Unit = {},
) : NarrationTtsEngine {

    private companion object {
        const val TAG = "VieneuNarration"
        const val THREADS = 4
        const val GAIN = 2.2f
        const val MAX_CACHE = 32
        const val PREFETCH_PACE_MS = 300L
        const val MAX_DISK_BYTES = 256L * 1024L * 1024L
        // The engine's measured peak resident footprint is ~170 MB, so that is
        // what the guard has to protect. It used to ask for 300 MB free, which a
        // 2 GB box playing a film rarely has - the engine then refused to load
        // and fell back to the system voice for no good reason.
        const val OFFLINE_MIN_AVAIL_MB = 180L
        // 32-bit processes get a much smaller heap; the mapped heads file and the
        // ONNX weights are native, but the KV cache and audio buffers still land
        // in the Java heap, so require a sane memory class before loading.
        const val OFFLINE_MIN_MEMORY_CLASS_MB = 128
        // Small boxes: keep the PCM cache and the on-disk cache short so the
        // engine is not competing with the player for memory or storage.
        const val LOW_RAM_MAX_CACHE = 6
        const val LOW_RAM_MAX_DISK_BYTES = 48L * 1024L * 1024L
        const val LOW_RAM_TOTAL_MB = 2560L
        const val MALE = "Adam"
        const val FEMALE = "Thùy Dung"
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        // ORT builds its graph on this thread; 32-bit boxes need a deeper stack
        // than the platform default or the load dies in a native stack overflow.
        Thread(null, runnable, "nuvio-vieneu-tts", 8L * 1024L * 1024L).apply { isDaemon = true }
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

    /** True on boxes with little RAM, where the caches must stay short. */
    private val lowRam: Boolean by lazy {
        val totalMb = runCatching {
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            info.totalMem / 1048576L
        }.getOrDefault(Long.MAX_VALUE)
        totalMb < LOW_RAM_TOTAL_MB
    }

    private val maxCacheEntries: Int get() = if (lowRam) LOW_RAM_MAX_CACHE else MAX_CACHE
    private val maxDiskBytes: Long get() = if (lowRam) LOW_RAM_MAX_DISK_BYTES else MAX_DISK_BYTES

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
        // Loads the engine (heads + G2P) on the worker while the player opens.
        // It must not open any ONNX graph here: 7.5.41 opened all four and the
        // app died on the 32-bit box before the first subtitle. Each graph is
        // opened on its first real use and then kept for the session.
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
        Log.i(TAG, "release: begin")
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
            Log.i(TAG, "release: done")
        }, "nuvio-vieneu-close").apply { isDaemon = true }.start()
    }

    // ── worker ─────────────────────────────────────────────────────────────────

    private fun runSpeak(seq: Int) {
        // Keep the request counted until it is fully handled. It used to be
        // decremented on entry, before the worker blocked on synthLock, so a
        // running prefetch saw "nobody waiting" and kept the engine for a whole
        // line while the on-screen subtitle waited behind it.
        try {
            runSpeakInner(seq)
        } finally {
            speakWaiting.decrementAndGet()
        }
    }

    private fun runSpeakInner(seq: Int) {
        val r = req ?: return
        if (r.seq != seq || released) {
            staleDrop.incrementAndGet()
            r.onResult?.invoke(false, "stale")
            return
        }
        // A stop or a newer cue while this line is synthesizing must not fall
        // through to the system voice: that is what made stale sentences play
        // long after the subtitle had moved on.
        val myStop = stopToken.get()
        val e = ensureEngine()
        // Engine load can take seconds; a stop during it must not lead to a
        // stale line being synthesized or handed to the system voice.
        if (released || stopToken.get() != myStop) {
            staleDrop.incrementAndGet()
            r.onResult?.invoke(false, "stale")
            return
        }
        if (e == null) {
            synthFail.incrementAndGet()
            r.onResult?.invoke(false, "model:$loadError")
            onError(r.text, r.rate)
            return
        }
        val pcm = synth(e, r.text, r.voiceApi, seq)
        if (pcm == null) {
            if (isStale(seq, myStop)) {
                staleDrop.incrementAndGet()
                r.onResult?.invoke(false, "stale")
                return
            }
            synthFail.incrementAndGet()
            r.onResult?.invoke(false, "synth-failed")
            onError(r.text, r.rate)
            return
        }
        // Only a stop/release discards finished audio. A superseded line still
        // plays (the newer one cuts it when ready): dropping it as well would
        // turn a synth that is merely one cue behind into total silence.
        if (released || stopToken.get() != myStop) {
            staleDrop.incrementAndGet()
            r.onResult?.invoke(false, "stale")
            return
        }
        play(pcm, r)
    }

    private fun isStale(seq: Int, myStop: Int): Boolean =
        released || stopToken.get() != myStop || (req?.seq ?: 0) > seq

    /**
     * CPU features from /proc/cpuinfo. The int8 ONNX kernels (MatMulInteger) are
     * the first thing that executes a vector instruction, so an old ARMv7 core
     * without NEON dies there with SIGILL - right after "prefill session open"
     * and with no Java trace. Logging the features tells that apart from an
     * out-of-memory kill, which looks identical from the outside.
     */
    private val cpuFeatures: String by lazy {
        runCatching {
            val line = File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }
                ?: return@runCatching "no-Features-line"
            val f = line.substringAfter(":").trim()
            val want = listOf("neon", "vfpv3", "vfpv4", "idiva", "asimd", "dotprod", "i8mm")
            val have = want.filter { f.contains(it) }
            (if (have.isEmpty()) "none-of:" else "") + have.joinToString(",")
        }.getOrDefault("?")
    }

    private fun ensureEngine(): VieneuEngine? {
        engine?.let { return it }
        synchronized(loadLock) {
            engine?.let { return it }
            try {
                onStatus(NarrationEngineStatus.LOADING)
                stage(
                    "abi: ${Build.SUPPORTED_ABIS.joinToString(",")} " +
                        "64bit=${Process.is64Bit()} sdk=${Build.VERSION.SDK_INT}"
                )
                VieneuEngine.CPU = cpuFeatures
                stage("cpu: $cpuFeatures")
                val mem = runCatching {
                    val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                    info
                }.getOrNull()
                if (mem != null) {
                    val availMb = mem.availMem / 1048576L
                    val totalMb = mem.totalMem / 1048576L
                    val classMb = runCatching {
                        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
                    }.getOrDefault(0).toLong()
                    stage("mem: avail=${availMb}M total=${totalMb}M class=${classMb}M low=${mem.lowMemory}")
                    stage("mem: lowRam=$lowRam cache=${maxCacheEntries} disk=${maxDiskBytes / 1048576L}M")
                    if (availMb < OFFLINE_MIN_AVAIL_MB) {
                        loadError = "low-memory(${availMb}M)"
                        stage("load: SKIP $loadError")
                        onStatus(NarrationEngineStatus.FAILED)
                        return null
                    }
                    if (classMb in 1 until OFFLINE_MIN_MEMORY_CLASS_MB) {
                        loadError = "small-heap(${classMb}M)"
                        stage("load: SKIP $loadError")
                        onStatus(NarrationEngineStatus.FAILED)
                        return null
                    }
                }
                stage("extract: start")
                val base = VieneuModelStore.modelDir(appContext)
                stage("extract: ok free=${base.usableSpace / 1048576L}M")
                val heads = File(base, "heads.bin")
                stage("read: tokenizer")
                val tokJson = readText(File(base, "tokenizer.json"))
                val g2p = File(base, "sea_g2p.bin").absolutePath
                Log.i(TAG, "engine: load start")
                runCatching {
                    val dir = File(appContext.applicationInfo.nativeLibraryDir)
                    val libs = listOf("libonnxruntime.so", "libonnxruntime4j_jni.so", "libsea_g2p_rs.so", "libg2pjni.so")
                        .joinToString(" ") { n -> "$n=${File(dir, n).let { if (it.exists()) it.length() / 1024 else -1 }}k" }
                    stage("libs: $libs")
                }
                stage("load: sessions")
                VieneuEngine.TRACE = java.util.function.Consumer { msg ->
                    Log.i(TAG, msg)
                    stage("ort: ${msg.take(90)}")
                }
                val t0 = System.nanoTime()
                val loader = Thread.currentThread()
                val hbDone = java.util.concurrent.atomic.AtomicBoolean(false)
                val hb = Thread {
                    var beats = 0
                    while (!hbDone.get() && beats < 25) {
                        try {
                            Thread.sleep(1200)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        beats++
                        val frames = loader.stackTrace.take(4)
                            .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                        stage("hb${beats} ${loader.state} $frames")
                    }
                }.apply { isDaemon = true; name = "nuvio-vieneu-hb"; start() }
                try {
                    engine = VieneuEngine(base, heads, tokJson, g2p, if (Process.is64Bit()) THREADS else 2)
                } finally {
                    hbDone.set(true)
                    hb.interrupt()
                }
                loadError = ""
                Log.i(TAG, "engine: load ok")
                stage("load: ok ${"%.1f".format((System.nanoTime() - t0) / 1e9)}s")
                loadVoices(File(base, "voices_test.json"))
                stage("voices: ${voices.size}")
                onStatus(NarrationEngineStatus.READY)
            } catch (t: Throwable) {
                loadError = t.javaClass.simpleName + ":" + (t.message?.take(60) ?: "")
                Log.i(TAG, "engine: load FAIL $loadError")
                Log.e(TAG, "engine load failed", t)
                stage("load: FAIL $loadError")
                onStatus(NarrationEngineStatus.FAILED)
            }
            return engine
        }
    }

    private fun stage(text: String) {
        runCatching { onStage(text) }
        NarrationDiag.log(appContext, text)
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
            if (total <= maxDiskBytes) return
            files.sortBy { it.lastModified() }
            for (f in files) {
                if (total <= maxDiskBytes) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        }
    }

    private fun logMemory(tag: String) {
        try {
            val am = appContext.getSystemService(android.app.ActivityManager::class.java)
            val mi = am?.let { ActivityManager.MemoryInfo() }?.also { am.getMemoryInfo(it) }
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            val maxMb = runtime.maxMemory() / (1024 * 1024)
            val availMb = (mi?.availMem ?: 0L) / (1024 * 1024)
            val low = mi?.lowMemory ?: false
            val flags = appContext.applicationInfo.flags
            val largeHeap = (flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0
            val memoryClassMb = am?.memoryClass ?: 0
            val largeMemoryClassMb = am?.largeMemoryClass ?: 0
            Log.i(TAG, 
                "$tag mem: heap=${usedMb}/${maxMb}MB avail=${availMb}MB lowRam=$low " +
                    "largeHeap=$largeHeap memClass=${memoryClassMb}MB largeMemClass=${largeMemoryClassMb}MB",
            )
        } catch (t: Throwable) {
            Log.i(TAG, "$tag mem: unavailable " + t.javaClass.simpleName)
        }
    }

    /**
     * Synthesizes PCM (or a cached copy) for [text] in the voice behind [api].
     *
     * [seq] ties the work to a playback request so a newer cue can cancel it;
     * background prefetch keeps the default -1 and is only cancelled on release.
     */
    private fun synth(e: VieneuEngine, text: String, api: String, seq: Int = -1): FloatArray? {
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
        val myStop = stopToken.get()
        val cancelled: BooleanSupplier?
        val superseded: BooleanSupplier?
        if (seq < 0) {
            // Background prefetch: yield the engine as soon as a real cue
            // arrives, so warming the cache never delays a subtitle line.
            cancelled = BooleanSupplier { released || speakWaiting.get() > 0 }
            superseded = null
        } else {
            cancelled = BooleanSupplier { released || stopToken.get() != myStop }
            // A newer cue must not abort a line that has already started: one
            // line takes longer than several subtitle cues on this box, so
            // aborting on "superseded" dropped every line during a dialogue
            // and left the narration silent. The pre-lock check above still
            // skips a cue that never started; a finished line plays and is cut
            // by the next one's audio.
            superseded = null
        }
        return try {
            val p = VieneuEngine.Params()
            // ORT sessions are not re-entrant: serialize synthesis so prefetch and
            // on-demand calls can never corrupt each other's audio. Never touch the
            // engine after release() closed its native sessions.
            Log.i(TAG, "synth: start len=" + text.length)
            stage("synth: start ${text.length}")
            logMemory("synth: start")
            val wav = synchronized(synthLock) {
                if (released) return null
                // The request may have been replaced while this worker waited
                // behind an earlier line: do not pay for a prefill whose audio
                // can never play.
                if (seq >= 0 && req?.seq != seq) return null
                e.synthesize(text, voice.emb, voice.codes, p, cancelled, superseded)
            }
            Log.i(TAG, "synth: ok samples=" + wav.size)
            stage("synth: ok ${wav.size}")
            logMemory("synth: ok")
            synthOk.incrementAndGet()
            putCache(key, wav)
            writeDisk(name, text, wav)
            wav
        } catch (c: VieneuEngine.Cancelled) {
            // Stopped or replaced mid-synthesis: drop the line silently. The
            // caller checks isStale() and must not fall back to system TTS.
            stage("synth: drop " + (c.message ?: "cancelled"))
            Log.i(TAG, "synth: drop " + (c.message ?: "cancelled"))
            null
        } catch (t: Throwable) {
            synthFail.incrementAndGet()
            Log.i(TAG, "synth: FAIL " + (t.javaClass.simpleName) + ":" + (t.message?.take(80) ?: ""))
            stage("synth: FAIL ${t.javaClass.simpleName}")
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
        Log.i(TAG, "play: track create size=" + maxOf(boosted.size * 2, minBuffer))
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
            Log.i(TAG, "play: writing")
            stage("play: write ${boosted.size}")
            val written = track.write(boosted, 0, boosted.size, AudioTrack.WRITE_BLOCKING)
            Log.i(TAG, "play: wrote=" + written)
            stage("play: wrote=$written")
            if (written < 0) {
                r.onResult?.invoke(false, "audiotrack-write:$written")
                return
            }
            onSpeechStart()
            Log.i(TAG, "play: start audio")
            track.play()
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                r.onResult?.invoke(false, "audiotrack-not-playing")
                return
            }
            Log.i(TAG, "play: start speed=" + speed)
            stage("play: speaking")
            playStart.incrementAndGet()
            val frames = boosted.size.coerceAtLeast(written)
            var durationMs = frames.toLong() * 1000L / sr
            if (speed > 1.001f) durationMs = (durationMs / speed).toLong()
            // Play the whole line. It used to be clamped to one subtitle window
            // (budgetMs), but synthesis on this box is slower than playback, so
            // the next line's audio never arrives in time: the clamp only cut
            // the sentence's tail and left silence in the gap. A newer line's
            // play() still stops this track the moment its audio is ready.
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
            while (cache.size > maxCacheEntries) {
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
