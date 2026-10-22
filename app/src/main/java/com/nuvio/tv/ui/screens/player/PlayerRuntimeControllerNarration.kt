package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.PlayerSettings

/**
 * Applies the offline narration settings to the narration controller.
 *
 * The controller is created lazily, so enabling the feature only pays for the
 * ONNX model extraction when the user actually turns it on.
 */
internal fun PlayerRuntimeController.applyNarrationSettings(settings: PlayerSettings) {
    val controller = narration() ?: return
    controller.setEnabled(settings.ttsNarrationEnabled)
    controller.setVolumePercent(settings.ttsNarrationVolume)
    controller.setVoice(settings.ttsNarrationVoice)
    controller.setDuckLevelPercent(settings.ttsDuckVolume)
}
