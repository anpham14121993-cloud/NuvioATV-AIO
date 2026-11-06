package com.nuvio.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.player.tts.piper.PiperVoiceCatalog
import com.nuvio.tv.data.local.NarrationSubtitleMode
import com.nuvio.tv.data.local.PlayerSettings

/**
 * Offline Vietnamese narration (VieNeu TTS): reads the subtitle line that is
 * currently on screen and speaks it over the movie audio.
 */
@Composable
internal fun PlaybackNarrationSection(
    settings: PlayerSettings,
    onUpdate: PlaybackSettingsUpdate
) {
    var showVoiceDialog by remember { mutableStateOf(false) }

    SettingsNote(text = stringResource(R.string.playback_narration_info))

    SettingsToggleRow(
        title = stringResource(R.string.playback_narration_enabled),
        subtitle = stringResource(R.string.playback_narration_enabled_sub),
        checked = settings.ttsNarrationEnabled,
        onToggle = { onUpdate { setTtsNarrationEnabled(!settings.ttsNarrationEnabled) } }
    )

    if (settings.ttsNarrationEnabled) {
        SliderSettingsItem(
            title = stringResource(R.string.playback_narration_volume),
            subtitle = stringResource(R.string.playback_narration_volume_sub),
            value = settings.ttsNarrationVolume,
            valueText = "${settings.ttsNarrationVolume}%",
            minValue = 0,
            maxValue = 100,
            step = 5,
            onValueChange = { value -> onUpdate { setTtsNarrationVolume(value) } }
        )

        SliderSettingsItem(
            title = stringResource(R.string.playback_narration_duck),
            subtitle = stringResource(R.string.playback_narration_duck_sub),
            value = settings.ttsDuckVolume,
            valueText = "${settings.ttsDuckVolume}%",
            minValue = 0,
            maxValue = 100,
            step = 5,
            onValueChange = { value -> onUpdate { setTtsDuckVolume(value) } }
        )

        val activeVoice = PiperVoiceCatalog.findVoice(settings.ttsNarrationVoice)
        SettingsActionRow(
            title = stringResource(R.string.playback_narration_voice),
            subtitle = activeVoice.description.takeIf { it.isNotBlank() },
            value = activeVoice.displayName,
            onClick = { showVoiceDialog = true }
        )

        if (showVoiceDialog) {
            PiperVoiceSelectionDialog(
                currentVoice = settings.ttsNarrationVoice,
                onVoiceSelected = { voiceName ->
                    onUpdate { setTtsNarrationVoice(voiceName) }
                },
                onDismiss = { showVoiceDialog = false }
            )
        }

        SettingsActionRow(
            title = stringResource(R.string.playback_narration_subtitle_mode),
            subtitle = stringResource(
                if (settings.ttsNarrationSubtitleMode == NarrationSubtitleMode.HIDE) {
                    R.string.player_narration_hide_subtitles
                } else {
                    R.string.player_narration_show_subtitles
                }
            ),
            value = null,
            onClick = {
                val next = if (settings.ttsNarrationSubtitleMode == NarrationSubtitleMode.HIDE) {
                    NarrationSubtitleMode.SHOW
                } else {
                    NarrationSubtitleMode.HIDE
                }
                onUpdate { setTtsNarrationSubtitleMode(next) }
            }
        )
    }
}
