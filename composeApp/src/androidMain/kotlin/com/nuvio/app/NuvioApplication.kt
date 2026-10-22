package com.nuvio.app

import android.app.Application
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor

/**
 * Custom Application class that implements [SingletonImageLoader.Factory] to guarantee
 * the Coil ImageLoader (with [CustomPosterFallbackInterceptor]) is created before any
 * image request. Without this, Coil's auto-init ContentProvider creates the singleton
 * before MainActivity.onCreate, and interceptors registered later are ignored.
 */
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        com.nuvio.app.features.player.TtsTrace.init(this)
        com.nuvio.app.core.ui.initDeviceTypeContext(this)
        com.nuvio.app.core.ui.QrCodes.install(com.nuvio.app.core.ui.AndroidQrCodeRenderer())
        com.nuvio.app.features.player.TtsTrace.log("app: cpu=" + cpuFeatures())
        // Persist TTS narration settings immediately, from any screen. The shared
        // repository cannot touch platform storage, so Android installs this hook
        // once at startup (player open is NOT required).
        val appContext = applicationContext
        com.nuvio.app.features.player.PlayerSettingsRepository.ttsPersistHook = { field, state ->
            runCatching {
                val prefs = com.nuvio.app.features.player.TtsNarrationPrefs
                val fields = com.nuvio.app.features.player.PlayerSettingsRepository.TtsPersistField
                when (field) {
                    fields.ENABLED -> prefs.save(appContext, state.ttsNarrationEnabled)
                    fields.VOLUME -> prefs.saveVolume(appContext, state.ttsNarrationVolume)
                    fields.VOICE -> prefs.saveVoice(appContext, state.ttsNarrationVoice)
                    fields.DUCK -> prefs.saveDuckVolume(appContext, state.ttsDuckVolume)
                }
                prefs.stamp(appContext)
            }
        }
        // Hydrate immediately so the settings UI shows persisted values even
        // before any player opens (disk reads are tiny; each field isolated so
        // one bad entry cannot block the others).
        val repo = com.nuvio.app.features.player.PlayerSettingsRepository
        val prefs = com.nuvio.app.features.player.TtsNarrationPrefs
        runCatching { repo.ensureLoaded() }
        runCatching {
            prefs.load(appContext)?.let { v ->
                if (v != repo.uiState.value.ttsNarrationEnabled) repo.setTtsNarrationEnabled(v)
            }
        }
        runCatching {
            prefs.loadVolume(appContext)?.let { v ->
                if (v != repo.uiState.value.ttsNarrationVolume) repo.setTtsNarrationVolume(v)
            }
        }
        runCatching {
            prefs.loadVoice(appContext)?.let { v ->
                if (v != repo.uiState.value.ttsNarrationVoice) repo.setTtsNarrationVoice(v, "app-hydration")
            }
        }
        runCatching {
            prefs.loadDuckVolume(appContext)?.let { v ->
                if (v != repo.uiState.value.ttsDuckVolume) repo.setTtsDuckVolume(v)
            }
        }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .components {
                add(CustomPosterFallbackInterceptor())
                add(SvgDecoder.Factory())
                add(
                    KtorNetworkFetcherFactory(
                        cacheStrategy = { CacheControlCacheStrategy() },
                    ),
                )
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }


    /** CPU feature flags (e.g. asimddp) to spot unsupported dotprod instructions. */
    private fun cpuFeatures(): String = runCatching {
        val text = java.io.File("/proc/cpuinfo").readText()
        val line = text.lineSequence().firstOrNull { it.startsWith("Features") || it.startsWith("flags") }
        val flags = line?.substringAfter(':')?.trim().orEmpty().split(' ').filter { it.isNotBlank() }
        val interesting = flags.filter {
            it in setOf(
                "asimd", "asimddp", "fphp", "sve", "sve2", "i8mm", "bf16", "atomics", "aes", "sha2",
            )
        }
        "features=" + interesting.joinToString(",") + " abis=" + android.os.Build.SUPPORTED_ABIS.joinToString(",")
    }.getOrElse { "unavailable" }

    /** Writes the Java stack trace to Downloads so crashes can be diagnosed on-device. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(throwable: Throwable) {
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        val body = buildString {
            appendLine("time=" + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            appendLine("device=" + Build.MANUFACTURER + " " + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT)
            append(sw.toString())
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "vieneu_crash.txt")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) }
                }
            } else {
                val dir = getExternalFilesDir(null) ?: filesDir
                java.io.File(dir, "vieneu_crash.txt").writeText(body)
            }
        }
    }

}
