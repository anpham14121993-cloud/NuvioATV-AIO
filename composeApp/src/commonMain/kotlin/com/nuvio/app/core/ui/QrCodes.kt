package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Renders a QR code for [content].
 *
 * The implementation is platform specific (zxing on Android) and installed
 * once at startup, so the shared UI can show a QR code without pulling a
 * barcode library into the common source set.
 */
interface QrCodeRenderer {
    fun render(content: String, sizePx: Int): ImageBitmap?
}

object QrCodes {
    private var renderer: QrCodeRenderer? = null

    fun install(renderer: QrCodeRenderer) {
        this.renderer = renderer
    }

    fun render(content: String, sizePx: Int = 512): ImageBitmap? {
        if (content.isBlank()) return null
        return renderer?.render(content, sizePx)
    }
}

/** Renders [content] as a QR code, or null when no renderer is installed. */
@Composable
fun rememberQrCode(content: String, sizePx: Int = 512): ImageBitmap? =
    remember(content, sizePx) { QrCodes.render(content, sizePx) }
