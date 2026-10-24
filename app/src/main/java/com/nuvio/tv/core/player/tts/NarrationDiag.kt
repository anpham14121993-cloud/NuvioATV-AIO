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
    private const val LOGCAT_FILE = "narration_logcat.log"
    private const val MAX_BYTES = 64L * 1024L
    private const val KEEP_LINES = 200

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun logcatFile(context: Context) = File(context.filesDir, LOGCAT_FILE)

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

    fun logcatTail(context: Context, lines: Int = 12): String = runCatching {
        val f = logcatFile(context)
        if (!f.exists()) "" else f.readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    @Synchronized
    private fun append(context: Context, f: File, message: String) {
        runCatching {
            if (f.length() > MAX_BYTES) {
                val keep = f.readLines().takeLast(KEEP_LINES)
                f.writeText(keep.joinToString("\n", postfix = "\n"))
            }
            val stamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
            f.appendText("$stamp $message\n")
        }
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /**
     * Dumps this app's own logcat window (the only part Android lets an app
     * read) and keeps the lines that explain a native death: the fatal signal,
     * the backtrace frames and any ONNX runtime complaint. Runs at app start so
     * the trace of the previous crash is still in the ring buffer.
     */
    fun captureLogcatBlocking(context: Context) {
        runCatching {
            val process = ProcessBuilder("/system/bin/logcat", "-d", "-v", "threadtime", "-t", "3000")
                .redirectErrorStream(true)
                .start()
            val lines = process.inputStream.bufferedReader().use { it.readLines() }
            runCatching { process.waitFor() }
            val keys = listOf(
                "Fatal signal", "SIGSEGV", "SIGILL", "SIGABRT", "SIGBUS", "backtrace",
                "onnxruntime", "vieneu", "Vieneu", "libsea_g2p", "libg2p",
                "cannot locate", "dlopen", "UnsatisfiedLink", "abort message", "DEBUG",
            )
            val hits = lines.filter { line -> keys.any { line.contains(it, ignoreCase = true) } }
            append(context, logcatFile(context), "logcat: ${lines.size} lines, ${hits.size} hits")
            hits.takeLast(90).forEach { append(context, logcatFile(context), it.take(190)) }
        }.onFailure {
            log(context, "logcat: failed ${it.javaClass.simpleName}")
        }
    }

    fun captureLogcat(context: Context) {
        Thread({ captureLogcatBlocking(context) }, "nuvio-diag-logcat").apply {
            isDaemon = true
            start()
        }
    }
}
