package com.nuvio.tv.core.player.tts

import android.content.Context
import android.os.Debug
import java.io.File

/**
 * Narration diagnostics that survive a process death.
 *
 * Every model-load and playback step is appended to a small file in filesDir
 * (plus the JVM/native heap sizes), so if the offline engine takes the process
 * down the last lines still describe where it died. The narration dialog reads
 * the tail back, so no adb is needed to find out what happened.
 */
object NarrationDiag {
    private const val FILE_NAME = "narration_diag.log"
    private const val MAX_BYTES = 64L * 1024L
    private const val KEEP_LINES = 200

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun log(context: Context, message: String) {
        runCatching {
            val f = file(context)
            if (f.length() > MAX_BYTES) {
                val keep = f.readLines().takeLast(KEEP_LINES)
                f.writeText(keep.joinToString("\n", postfix = "\n"))
            }
            val rt = Runtime.getRuntime()
            val heap = "${(rt.totalMemory() - rt.freeMemory()) / 1048576}M/${rt.maxMemory() / 1048576}M"
            val native = "${Debug.getNativeHeapAllocatedSize() / 1048576}M"
            val stamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
            f.appendText("$stamp $heap j/$native n $message\n")
        }
    }

    fun tail(context: Context, lines: Int = 10): String = runCatching {
        val f = file(context)
        if (!f.exists()) "" else f.readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
