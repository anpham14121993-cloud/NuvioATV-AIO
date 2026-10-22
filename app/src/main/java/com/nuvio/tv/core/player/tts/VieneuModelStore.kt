package com.nuvio.tv.core.player.tts

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Copies the bundled int8 ONNX models out of APK assets into `filesDir/vieneu`
 * exactly once. Extraction is idempotent and guarded so concurrent warm-up and
 * prefetch workers never race.
 */
object VieneuModelStore {
    private const val TAG = "VieneuModel"
    private val FILES = listOf("heads.bin", "tokenizer.json", "sea_g2p.bin", "voices_test.json")
    private val DIRS = listOf("onnx_int8", "codec")

    private val lock = Any()

    fun modelDir(context: Context): File {
        val base = File(context.filesDir, "vieneu")
        val marker = File(base, ".ok")
        if (marker.exists()) return base
        synchronized(lock) {
            if (marker.exists()) return base
            Log.i(TAG, "model: extract start")
            Log.i(TAG, "extracting bundled models -> $base")
            base.mkdirs()
            val am = context.assets
            for (dir in DIRS) copyDir(am, dir, File(base, dir))
            for (file in FILES) copyFile(am, file, File(base, file))
            runCatching { marker.createNewFile() }
            Log.i(TAG, "model: extract done")
            Log.i(TAG, "extraction done: ${base.list()?.joinToString(",") ?: "?"}")
            return base
        }
    }

    private fun copyDir(am: android.content.res.AssetManager, assetDir: String, outDir: File) {
        outDir.mkdirs()
        val names = am.list(assetDir) ?: return
        for (name in names) copyFile(am, "$assetDir/$name", File(outDir, name))
    }

    private fun copyFile(am: android.content.res.AssetManager, asset: String, out: File) {
        am.open(asset).use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(1 shl 20)
                var read = input.read(buf)
                while (read > 0) {
                    output.write(buf, 0, read)
                    read = input.read(buf)
                }
            }
        }
    }
}
