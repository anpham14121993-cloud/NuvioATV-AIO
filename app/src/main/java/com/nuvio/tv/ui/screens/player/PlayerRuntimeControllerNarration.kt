package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.data.local.NarrationSubtitleMode
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Applies the offline narration settings to the narration controller.
 *
 * The controller is created lazily, so enabling the feature only pays for the
 * ONNX model extraction when the user actually turns it on.
 */
internal fun PlayerRuntimeController.applyNarrationSettings(settings: com.nuvio.tv.data.local.PlayerSettings) {
    val controller = narration() ?: return
    controller.setEnabled(settings.ttsNarrationEnabled)
    controller.setVolumePercent(settings.ttsNarrationVolume)
    controller.setVoice(settings.ttsNarrationVoice)
    controller.setDuckLevelPercent(settings.ttsDuckVolume)
    _uiState.update {
        it.copy(
            narrationEnabled = settings.ttsNarrationEnabled,
            narrationSubtitleMode = settings.ttsNarrationSubtitleMode,
        )
    }
    applyNarrationSubtitleVisibility(settings.ttsNarrationEnabled, settings.ttsNarrationSubtitleMode)
}

/**
 * Chooses what happens to the on-screen subtitles while the narration speaks.
 *
 * The subtitle track keeps running so the narration always has text to read;
 * only the rendering is suppressed when the user picks "ẩn phụ đề".
 */
internal fun PlayerRuntimeController.applyNarrationSubtitleVisibility(enabled: Boolean, mode: String) {
    val hide = enabled && mode == NarrationSubtitleMode.HIDE
    if (narrationHidesSubtitles == hide) return
    narrationHidesSubtitles = hide
    Log.i(PlayerRuntimeController.TAG, "narration subtitle visibility: hide=$hide")
    // The sidecar path paints its own cues, so it has to be told as well.
    clearSidecarSubtitleIfHidden()
}

/**
 * Mirrors the offline model progress so the player can explain a silent
 * narration instead of leaving the user guessing.
 */
internal fun PlayerRuntimeController.observeNarrationStatus() {
    scope.launch {
        narration()?.status?.collect { state ->
            _uiState.update { it.copy(narrationEngineStatus = state) }
        }
    }
    scope.launch {
        narration()?.lastCue?.collect { cue ->
            _uiState.update { it.copy(narrationLastCue = cue) }
        }
    }
    scope.launch {
        narration()?.lastError?.collect { error ->
            _uiState.update { it.copy(narrationLastError = error) }
        }
    }
    scope.launch {
        narration()?.stage?.collect { step ->
            _uiState.update { it.copy(narrationStage = step) }
        }
    }
}

/**
 * Turns the narration on and remembers what should happen to the subtitles.
 */
internal fun PlayerRuntimeController.enableNarration(subtitleMode: String) {
    val mode = if (subtitleMode == NarrationSubtitleMode.HIDE) NarrationSubtitleMode.HIDE else NarrationSubtitleMode.SHOW
    Log.i(PlayerRuntimeController.TAG, "enableNarration: subtitleMode=$mode")
    scope.launch {
        playerSettingsDataStore.setTtsNarrationSubtitleMode(mode)
        playerSettingsDataStore.setTtsNarrationEnabled(true)
    }
    narration()?.setEnabled(true)
    _uiState.update {
        it.copy(narrationEnabled = true, narrationSubtitleMode = mode, showNarrationDialog = false)
    }
    applyNarrationSubtitleVisibility(true, mode)
    ensureNarrationSubtitleSelected()
}

/** Turns the narration off and brings the subtitles back. */
internal fun PlayerRuntimeController.disableNarration() {
    Log.i(PlayerRuntimeController.TAG, "disableNarration")
    scope.launch {
        playerSettingsDataStore.setTtsNarrationEnabled(false)
    }
    narration()?.setEnabled(false)
    _uiState.update { it.copy(narrationEnabled = false) }
    applyNarrationSubtitleVisibility(false, NarrationSubtitleMode.SHOW)
}

/**
 * The narration reads the subtitle line that is on screen, so a subtitle track
 * has to be selected. When none is active the first Vietnamese track is picked
 * automatically, otherwise the narration would stay silent.
 */
internal fun PlayerRuntimeController.ensureNarrationSubtitleSelected() {
    val state = _uiState.value
    val hasSelectedSubtitle = state.selectedSubtitleTrackIndex >= 0 || state.selectedAddonSubtitle != null
    if (hasSelectedSubtitle) return
    val tracks = state.subtitleTracks
    if (tracks.isEmpty()) {
        Log.i(PlayerRuntimeController.TAG, "ensureNarrationSubtitleSelected: no subtitle track available")
        return
    }
    val preferred = tracks.indexOfFirst { track ->
        val language = track.language?.lowercase().orEmpty()
        language.contains("vie") || language.contains("viet")
    }
    val index = if (preferred >= 0) preferred else 0
    Log.i(
        PlayerRuntimeController.TAG,
        "ensureNarrationSubtitleSelected: selecting track $index " +
            "(${tracks[index].language}/${tracks[index].name})"
    )
    selectSubtitleTrack(index)
}
