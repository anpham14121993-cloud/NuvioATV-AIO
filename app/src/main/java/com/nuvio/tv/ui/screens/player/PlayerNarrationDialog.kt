package com.nuvio.tv.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.tts.NarrationDiag
import com.nuvio.tv.core.player.tts.NarrationEngineStatus
import com.nuvio.tv.data.local.NarrationSubtitleMode
import com.nuvio.tv.ui.components.PlayerPanelRow
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.NuvioSideSheet

/**
 * Narration options: turn the offline Vietnamese narration on or off and pick
 * what happens to the subtitles while it reads the current line.
 */
@Composable
internal fun PlayerNarrationDialog(
    narrationEnabled: Boolean,
    subtitleMode: String,
    engineStatus: NarrationEngineStatus = NarrationEngineStatus.LOADING,
    lastCue: String? = null,
    lastError: String? = null,
    onEnable: (String) -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val v2 = LocalV2Appearance.current != null
    val options = listOf(
        NarrationSubtitleMode.SHOW to R.string.player_narration_show_subtitles,
        NarrationSubtitleMode.HIDE to R.string.player_narration_hide_subtitles,
    )
    val subtitles = mapOf(
        NarrationSubtitleMode.SHOW to R.string.player_narration_show_subtitles_sub,
        NarrationSubtitleMode.HIDE to R.string.player_narration_hide_subtitles_sub,
    )
    val focusRequesters = remember { options.map { FocusRequester() } }
    val listState = rememberLazyListState()
    val statusText = when (engineStatus) {
        NarrationEngineStatus.LOADING -> stringResource(R.string.player_narration_status_loading)
        NarrationEngineStatus.READY -> stringResource(R.string.player_narration_status_ready)
        NarrationEngineStatus.FAILED -> stringResource(R.string.player_narration_status_failed)
    }
    val cueText = lastCue?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.player_narration_last_cue, it) }
        ?: stringResource(R.string.player_narration_no_cue)
    val errorText = lastError?.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.player_narration_last_error, it) }

    LaunchedEffect(narrationEnabled) {
        if (!v2) {
            repeat(2) { withFrameNanos { } }
            runCatching { focusRequesters.first().requestFocus() }
        }
    }

    if (v2) {
        NuvioSideSheet(onDismiss) {
            Text(
                stringResource(R.string.player_narration_title),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White, modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                stringResource(R.string.player_narration_info),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = .75f), modifier = Modifier.padding(bottom = 6.dp)
            )
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = .55f), modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                cueText,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = .55f), modifier = Modifier.padding(bottom = 4.dp)
            )
            if (errorText != null) {
                Text(
                    errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFB4A9), modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            NarrationDiagText()
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item {
                    PlayerPanelRow(
                        title = stringResource(
                            if (narrationEnabled) R.string.player_narration_disable
                            else R.string.player_narration_enable
                        ),
                        selected = narrationEnabled,
                        onClick = { if (narrationEnabled) onDisable() else onEnable(subtitleMode) },
                        modifier = Modifier.focusRequester(focusRequesters.first()),
                    )
                }
                items(options) { (mode, label) ->
                    PlayerPanelRow(
                        title = stringResource(label),
                        subtitle = stringResource(subtitles.getValue(mode)),
                        selected = narrationEnabled && subtitleMode == mode,
                        onClick = { onEnable(mode) },
                    )
                }
            }
        }
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .width(340.dp)
                .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(NuvioTheme.radii.xxl))
        ) {
            Column(modifier = Modifier.padding(NuvioTheme.spacing.xl)) {
                Text(
                    text = stringResource(R.string.player_narration_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
                )
                Text(
                    text = stringResource(R.string.player_narration_info),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextPrimary.copy(alpha = .75f),
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.xs)
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextPrimary.copy(alpha = .55f),
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.xs)
                )
                Text(
                    text = cueText,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextPrimary.copy(alpha = .55f),
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.xs)
                )
                if (errorText != null) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFFB4A9),
                        modifier = Modifier.padding(bottom = NuvioTheme.spacing.sm)
                    )
                }
                NarrationDiagText()
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(top = NuvioTheme.spacing.xs)
                ) {
                    item {
                        PlayerPanelRow(
                            title = stringResource(
                                if (narrationEnabled) R.string.player_narration_disable
                                else R.string.player_narration_enable
                            ),
                            selected = narrationEnabled,
                            onClick = { if (narrationEnabled) onDisable() else onEnable(subtitleMode) },
                            modifier = Modifier.focusRequester(focusRequesters.first()),
                        )
                    }
                    items(options) { (mode, label) ->
                        PlayerPanelRow(
                            title = stringResource(label),
                            subtitle = stringResource(subtitles.getValue(mode)),
                            selected = narrationEnabled && subtitleMode == mode,
                            onClick = { onEnable(mode) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shows the last recorded narration steps. The log lives in filesDir, so it
 * keeps the lines written just before a crash: after the box restarts the very
 * same lines tell which native step took the process down.
 */
@Composable
private fun NarrationDiagText() {
    val context = LocalContext.current
    val text by produceState(initialValue = "") {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            NarrationDiag.captureLogcatBlocking(context)
            val stages = NarrationDiag.tail(context, 8)
            val crash = NarrationDiag.logcatTail(context, 20)
            if (crash.isBlank()) stages else "$stages\n--- logcat ---\n$crash"
        }
    }
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 8.sp, fontFamily = FontFamily.Monospace),
        color = Color(0xFF8FC7FF),
        modifier = Modifier
            .heightIn(max = 170.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
    )
}
