package com.nuvio.app.core.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

@Composable
actual fun Modifier.tvDpadRepeatThrottle(
    horizontalGateMs: Long,
    verticalGateMs: Long,
): Modifier {
    if (!LocalDeviceType.current.isTv) return this
    val focusManager = LocalFocusManager.current
    val lastRepeat = remember { longArrayOf(0L) }
    return this.onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        if (event.type != KeyEventType.KeyDown || native.repeatCount <= 0) {
            return@onPreviewKeyEvent false
        }
        val direction = when (native.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
            KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
            KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
            else -> null
        } ?: return@onPreviewKeyEvent false
        val gate = when (direction) {
            FocusDirection.Up, FocusDirection.Down -> verticalGateMs
            else -> horizontalGateMs
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastRepeat[0] < gate) return@onPreviewKeyEvent true
        lastRepeat[0] = now
        runCatching { focusManager.moveFocus(direction) }
        true
    }
}
