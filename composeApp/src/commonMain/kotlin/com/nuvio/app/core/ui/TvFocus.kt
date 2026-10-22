package com.nuvio.app.core.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Device type of the current composition. Hosts override it so previews and
 * tests can force a form factor.
 */
val LocalDeviceType = compositionLocalOf { DeviceType.PHONE }

/** True when the current composition runs on a TV style device. */
val isTvComposition: Boolean
    @Composable get() = LocalDeviceType.current.isTv

/**
 * Makes the element reachable with a D-pad and highlights it while focused.
 *
 * Chain it on top of an element that already handles clicks; the remote
 * activation is provided by [tvDpadActivate].
 */
@Composable
fun Modifier.tvFocusTarget(
    enabled: Boolean = true,
    shape: Shape? = null,
    scaleOnFocus: Float = 1.06f,
): Modifier {
    if (!enabled || !LocalDeviceType.current.isTv) return this
    var focused by remember { mutableStateOf(false) }
    val focusRing = MaterialTheme.nuvio.colors.focusRing
    return this
        .focusable()
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused && shape != null) {
                Modifier.border(2.dp, focusRing, shape)
            } else {
                Modifier
            },
        )
        .then(if (focused) Modifier.scale(scaleOnFocus) else Modifier)
}

/**
 * Activates [onClick] when the D-pad centre button (or Enter) is pressed.
 *
 * Compose's `clickable` only reacts to Enter/Space, so Android TV remotes
 * would otherwise do nothing.
 */
@Composable
fun Modifier.tvDpadActivate(onClick: () -> Unit): Modifier {
    if (!LocalDeviceType.current.isTv) return this
    return this.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown &&
            (event.key == Key.DirectionCenter || event.key == Key.Enter)
        ) {
            onClick()
            true
        } else {
            false
        }
    }
}

/**
 * Clickable element that also works with an Android TV remote: it takes focus,
 * shows a focus ring and reacts to the D-pad centre button.
 */
@Composable
fun Modifier.tvFocusClickable(
    enabled: Boolean = true,
    shape: Shape? = null,
    onClick: () -> Unit,
): Modifier {
    if (!enabled) return this
    if (!LocalDeviceType.current.isTv) {
        return this.clickable(onClick = onClick)
    }
    return this
        .tvFocusTarget(enabled = true, shape = shape)
        .tvDpadActivate(onClick)
        .clickable(onClick = onClick)
}

/**
 * Throttles D-pad auto-repeat so holding a direction does not flood the focus
 * system, and moves the focus manually at a controlled rate.
 */
@Composable
expect fun Modifier.tvDpadRepeatThrottle(
    horizontalGateMs: Long = 80L,
    verticalGateMs: Long = 112L,
): Modifier

/**
 * Gives a TV screen an initial focus target.
 *
 * Without a focused element the D-pad has nothing to move from, so the remote
 * appears to be dead. The host places an invisible sentinel in front of the
 * content; as soon as it takes the focus it hands it over to the first real
 * focusable element, and it repeats that whenever the focus gets lost (for
 * example after navigating to another screen).
 */
@Composable
fun TvFocusHost(content: @Composable () -> Unit) {
    if (!LocalDeviceType.current.isTv) {
        content()
        return
    }
    val focusManager = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var handedOver by remember { mutableStateOf(false) }
    var descendantHasFocus by remember { mutableStateOf(false) }
    var lastFocusSeenAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(requester) {
        while (true) {
            delay(700)
            val idleFor = System.currentTimeMillis() - lastFocusSeenAt
            if (!descendantHasFocus && idleFor > 1200L) {
                handedOver = false
                runCatching { requester.requestFocus() }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .tvDpadRepeatThrottle()
            .onFocusChanged { state ->
                val focused = state.hasFocus
                descendantHasFocus = focused
                if (focused) lastFocusSeenAt = System.currentTimeMillis()
            },
    ) {
        Box(
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(requester)
                .focusable()
                .onFocusChanged { state ->
                    if (state.isFocused && !handedOver) {
                        handedOver = true
                        // Move to the first focusable element of the screen.
                        if (!focusManager.moveFocus(FocusDirection.Next)) {
                            focusManager.moveFocus(FocusDirection.Down)
                        }
                    }
                },
        )
        content()
    }
}
