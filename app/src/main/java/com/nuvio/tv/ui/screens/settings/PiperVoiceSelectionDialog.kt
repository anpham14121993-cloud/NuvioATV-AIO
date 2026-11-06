package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.tts.piper.PiperVoice
import com.nuvio.tv.core.player.tts.piper.PiperVoiceCatalog
import com.nuvio.tv.core.player.tts.piper.PiperVoiceManager
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.launch

/**
 * TV dialog to browse, preview (listen before downloading), download with live progress,
 * and select Vietnamese offline Piper TTS voice models.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PiperVoiceSelectionDialog(
    currentVoice: String,
    onVoiceSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val downloadedStates = remember { mutableStateMapOf<String, Boolean>() }
    val fileSizes = remember { mutableStateMapOf<String, Float>() }
    val downloadingProgress = remember { mutableStateMapOf<String, Float>() }
    var playingPreviewId by remember { mutableStateOf<String?>(null) }

    val initialIndex = remember {
        PiperVoiceCatalog.VOICES.indexOfFirst {
            it.displayName.equals(currentVoice, ignoreCase = true) || it.id.equals(currentVoice, ignoreCase = true)
        }.let { if (it >= 0) it else 0 }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val firstFocusRequester = remember { FocusRequester() }

    DisposableEffect(Unit) {
        onDispose {
            PiperVoiceManager.stopVoicePreview()
        }
    }

    LaunchedEffect(Unit) {
        PiperVoiceCatalog.VOICES.forEach { voice ->
            val downloaded = PiperVoiceManager.isVoiceDownloaded(context, voice.id)
            downloadedStates[voice.id] = downloaded
            if (downloaded) {
                fileSizes[voice.id] = PiperVoiceManager.getVoiceFileSizeMb(context, voice.id)
            }
        }
        firstFocusRequester.requestFocusAfterFrames()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.piper_voice_dialog_title),
        subtitle = stringResource(R.string.piper_voice_dialog_subtitle),
        width = 620.dp,
        suppressFirstKeyUp = false
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
        ) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                itemsIndexed(
                    items = PiperVoiceCatalog.VOICES,
                    key = { _, voice -> voice.id }
                ) { index, voice ->
                    val isSelected = voice.displayName.equals(currentVoice, ignoreCase = true) ||
                            voice.id.equals(currentVoice, ignoreCase = true)
                    val isDownloaded = downloadedStates[voice.id] == true
                    val progress = downloadingProgress[voice.id]
                    val isDownloading = progress != null
                    val isPlayingPreview = playingPreviewId == voice.id
                    val sizeMb = fileSizes[voice.id] ?: 0f

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Main Action Card: Select or Download & Apply
                        Card(
                            onClick = {
                                if (isDownloaded) {
                                    onVoiceSelected(voice.displayName)
                                    onDismiss()
                                } else if (!isDownloading) {
                                    coroutineScope.launch {
                                        downloadingProgress[voice.id] = 0.01f
                                        val ok = PiperVoiceManager.downloadVoice(context, voice.id) { p ->
                                            downloadingProgress[voice.id] = p
                                        }
                                        downloadingProgress.remove(voice.id)
                                        if (ok) {
                                            downloadedStates[voice.id] = true
                                            fileSizes[voice.id] = PiperVoiceManager.getVoiceFileSizeMb(context, voice.id)
                                            // Automatically select voice upon download ("tải về nạp luôn")
                                            onVoiceSelected(voice.displayName)
                                            onDismiss()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .then(if (index == initialIndex) Modifier.focusRequester(firstFocusRequester) else Modifier),
                            colors = CardDefaults.colors(
                                containerColor = if (isSelected) NuvioTheme.colors.Secondary.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.45f),
                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(1.5.dp, NuvioTheme.colors.FocusRing),
                                    shape = RoundedCornerShape(10.dp)
                                )
                            ),
                            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                            scale = CardDefaults.scale(focusedScale = 1f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = voice.displayName,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = if (isSelected) NuvioTheme.colors.Primary else NuvioTheme.colors.TextPrimary
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "✓ " + stringResource(R.string.piper_voice_selected),
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = NuvioTheme.colors.Primary
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = voice.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NuvioTheme.colors.TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    if (isDownloading) {
                                        val percent = ((progress ?: 0f) * 100).toInt()
                                        Text(
                                            text = stringResource(R.string.piper_voice_downloading, percent),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = Color(0xFF64B5F6)
                                        )
                                    } else if (!isDownloaded) {
                                        Text(
                                            text = stringResource(R.string.piper_voice_not_downloaded),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = NuvioTheme.colors.TextTertiary
                                        )
                                    } else {
                                        val mbInt = if (sizeMb > 0) sizeMb.toInt() else 60
                                        Text(
                                            text = stringResource(R.string.piper_voice_downloaded, mbInt),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF81C784)
                                        )
                                    }
                                }

                                if (!isDownloaded && !isDownloading) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = stringResource(R.string.piper_voice_download),
                                        tint = NuvioTheme.colors.TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = NuvioTheme.colors.Primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // 2. Button "Nghe thử" / "Dừng" (Dedicated sample preview)
                        Card(
                            onClick = {
                                if (isPlayingPreview) {
                                    PiperVoiceManager.stopVoicePreview()
                                    playingPreviewId = null
                                } else {
                                    playingPreviewId = voice.id
                                    val played = PiperVoiceManager.playVoicePreview(
                                        context = context,
                                        voiceId = voice.id,
                                        onPlaybackStarted = {
                                            playingPreviewId = voice.id
                                        },
                                        onPlaybackEnded = {
                                            if (playingPreviewId == voice.id) {
                                                playingPreviewId = null
                                            }
                                        }
                                    )
                                    if (!played) {
                                        playingPreviewId = null
                                    }
                                }
                            },
                            colors = CardDefaults.colors(
                                containerColor = if (isPlayingPreview) Color(0x33FF5252) else Color.Black.copy(alpha = 0.45f),
                                focusedContainerColor = NuvioTheme.colors.FocusBackground
                            ),
                            border = CardDefaults.border(
                                focusedBorder = Border(
                                    border = BorderStroke(1.5.dp, NuvioTheme.colors.FocusRing),
                                    shape = RoundedCornerShape(10.dp)
                                )
                            ),
                            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                            scale = CardDefaults.scale(focusedScale = 1f),
                            modifier = Modifier.height(58.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                    .heightIn(min = 42.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isPlayingPreview) Icons.Default.Stop else Icons.Default.VolumeUp,
                                    contentDescription = if (isPlayingPreview) stringResource(R.string.piper_voice_stop) else stringResource(R.string.piper_voice_preview),
                                    tint = if (isPlayingPreview) Color(0xFFFF5252) else NuvioTheme.colors.TextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isPlayingPreview) stringResource(R.string.piper_voice_stop) else stringResource(R.string.piper_voice_preview),
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                                    color = if (isPlayingPreview) Color(0xFFFF5252) else NuvioTheme.colors.TextPrimary
                                )
                            }
                        }

                        // 3. Button "Xóa" (Only available when downloaded and not currently active)
                        if (isDownloaded && !isSelected) {
                            Card(
                                onClick = {
                                    PiperVoiceManager.deleteVoice(context, voice.id)
                                    downloadedStates[voice.id] = false
                                    fileSizes.remove(voice.id)
                                },
                                colors = CardDefaults.colors(
                                    containerColor = Color.Black.copy(alpha = 0.45f),
                                    focusedContainerColor = Color(0x55FF5252)
                                ),
                                border = CardDefaults.border(
                                    focusedBorder = Border(
                                        border = BorderStroke(1.5.dp, Color(0xFFFF5252)),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                ),
                                shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
                                scale = CardDefaults.scale(focusedScale = 1f),
                                modifier = Modifier.height(58.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 10.dp)
                                        .heightIn(min = 42.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.piper_voice_delete),
                                        tint = NuvioTheme.colors.TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
