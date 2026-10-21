package com.nuvio.app.features.player

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny append-only trace in Downloads so on-device crashes can be located. */
object TtsTrace {
    private const val NAME = "vieneu_trace.txt"
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        log("app-start")
    }

    @Synchronized
    fun log(event: String) {
        val ctx = appContext ?: return
        runCatching {
            val line = fmt.format(Date()) + " " + event + "\n"
            if (Build.VERSION.SDK_INT >= 29) {
                val uri = findUri(ctx) ?: createUri(ctx) ?: return
                val out = ctx.contentResolver.openOutputStream(uri, "wa")
                    ?: ctx.contentResolver.openOutputStream(uri)
                out?.use { it.write(line.toByteArray()) }
            } else {
                val dir = ctx.getExternalFilesDir(null) ?: return
                java.io.File(dir, NAME).appendText(line)
            }
        }
    }

    private fun findUri(ctx: Context): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = MediaStore.MediaColumns.DISPLAY_NAME + "=?"
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(NAME),
            MediaStore.MediaColumns.DATE_MODIFIED + " DESC",
        )?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                c.getLong(0),
            )
        }
        return null
    }

    private fun createUri(ctx: Context): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        return ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }
}
