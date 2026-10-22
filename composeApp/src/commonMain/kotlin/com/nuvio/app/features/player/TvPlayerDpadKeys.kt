package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.nuvio.app.core.ui.LocalDeviceType

/**
 * Maps an Android TV remote to the player while the playback controls are
 * hidden:
 * - centre / play-pause toggles playback,
 * - left / right seek backwards or forwards,
 * - up / down reveal the playback controls.
 *
 * While the controls are visible the keys are left alone so the remote can
 * move the focus between the control buttons.
 */
@Composable
internal fun Modifier.tvPlayerDpadKeys(
    intercept: Boolean,
    onTogglePlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onRevealControls: () -> Unit,
): Modifier {
    if (!LocalDeviceType.current.isTv || !intercept) return this
    return this.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                onTogglePlayback()
                true
            }

            Key.DirectionLeft -> {
                onSeekBack()
                true
            }

            Key.DirectionRight -> {
                onSeekForward()
                true
            }

            Key.DirectionUp, Key.DirectionDown -> {
                onRevealControls()
                true
            }

            Key.MediaPlayPause -> {
                onTogglePlayback()
                true
            }

            else -> false
        }
    }
}
