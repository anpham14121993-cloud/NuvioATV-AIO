package com.nuvio.tv.ui.screens.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.player.thumbnail.SeekThumbnails
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.v2.appearance.LocalV2Appearance
import com.nuvio.tv.ui.v2.components.GlassRole
import com.nuvio.tv.ui.v2.components.nuvioGlass
import com.nuvio.tv.ui.v2.components.nuvioV2Focus
import kotlin.math.abs

/** Root bounds of the seek bar on screen, reported by the bar; thumbnails sit just above it. */
internal object SeekBarAnchor {
    var bounds by mutableStateOf<Rect?>(null)
}

private val BarGap = 12.dp

/** True while thumbnails are drawn over the seek bar; the title block above the bar steps aside meanwhile. */
internal object SeekThumbnailOverlay {
    var visible by mutableStateOf(false)
}

/** Fades the title block out while thumbnails cover it. */
internal fun Modifier.hideUnderSeekThumbnails(): Modifier = composed {
    val alpha by animateFloatAsState(if (SeekThumbnailOverlay.visible) 0f else 1f, tween(150), label = "titleUnderThumbs")
    graphicsLayer { this.alpha = alpha }
}

/** Seek time and, for a film with chapters, the chapter at that time; sits just above the picture. */
@Composable
private fun SeekThumbnailLabel(chapters: List<PlayerChapter>, positionMs: Long, modifier: Modifier = Modifier) {
    val chapter = chapters.getOrNull(PlayerChapters.indexAt(chapters, positionMs))
    val chapterText = chapter?.let { chapterLabel(it) }
    val accent = NuvioTheme.colors.Secondary
    val shape = RoundedCornerShape(if (LocalV2Appearance.current != null) 12.dp else NuvioTheme.radii.sm)
    Text(
        text = buildAnnotatedString {
            append(formatSeekTime(positionMs))
            if (chapterText != null) {
                append(" · ")
                withStyle(SpanStyle(color = accent)) { append(chapterText) }
            }
        },
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = 560.dp)
            .thumbnailSurface(shape)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
    )
}

/** The paused hint keeps the screen on only while its percentage still moves. */
private const val KEEP_SCREEN_ON_STALL_MS = 5L * 60 * 1000

/** Lays [content] out over the seek bar's width, bottom edge [BarGap] above the bar. */
@Composable
private fun AboveSeekBar(
    modifier: Modifier,
    anchored: Boolean = true,
    contentAlignment: Alignment = Alignment.BottomStart,
    content: @Composable BoxScope.(width: Dp) -> Unit
) {
    val bar = SeekBarAnchor.bounds?.takeIf { anchored && it.width > 0f }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val start: Dp
            val end: Dp
            val bottom: Dp
            if (bar != null) {
                with(density) {
                    start = bar.left.coerceAtLeast(0f).toDp()
                    end = (constraints.maxWidth - bar.right).coerceAtLeast(0f).toDp()
                    bottom = (constraints.maxHeight - bar.top).coerceAtLeast(0f).toDp() + BarGap
                }
            } else {
                start = NuvioTheme.spacing.xxl
                end = NuvioTheme.spacing.xxl
                bottom = 140.dp
            }
            val width = (maxWidth - start - end).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier.fillMaxSize().padding(PaddingValues(start = start, end = end, bottom = bottom))
                    .clipToBounds(),
                contentAlignment = contentAlignment
            ) {
                content(width)
            }
        }
    }
}

@Composable
private fun Modifier.thumbnailSurface(shape: Shape): Modifier =
    if (LocalV2Appearance.current != null) clip(shape).nuvioGlass(GlassRole.HUD, shape = shape)
    else clip(shape).background(Color.Black.copy(alpha = 0.85f))

@Composable
private fun Modifier.landingFrame(shape: Shape): Modifier =
    if (LocalV2Appearance.current != null) nuvioV2Focus(focused = true, shape = shape, stationary = true)
    else border(NuvioTheme.focusRing.border(2.dp), shape)

internal fun seekThumbnailPainter(shown: SeekThumbnails.Shown?): BitmapPainter? = croppedPainter(shown)

private fun croppedPainter(shown: SeekThumbnails.Shown?): BitmapPainter? {
    val bitmap = shown?.bitmap ?: return null
    val crop = shown.crop?.takeIf { it.fits(bitmap.width, bitmap.height) }
    val size = IntSize(
        (bitmap.width - (crop?.let { it.left + it.right } ?: 0)).coerceAtLeast(1),
        (bitmap.height - (crop?.let { it.top + it.bottom } ?: 0)).coerceAtLeast(1)
    )
    return BitmapPainter(bitmap.asImageBitmap(), IntOffset(crop?.left ?: 0, crop?.top ?: 0), size)
}

/**
 * Thumbnails above the seek bar while a seek key is held: a strip once the title's coverage is stored, a single
 * picture until then. While paused, the picture of the last tap stays up, so the viewer can step back and forth
 * and look. A tap during playback shows nothing. The centre picture follows the glide's published position (a few
 * times a second); the strip slides with the cursor every frame.
 */
@Composable
fun SeekThumbnailOverlayHost(
    uiState: PlayerUiState,
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val timeline by viewModel.playbackTimeline.collectAsState()
    val tick by SeekThumbnails.tick
    val gliding = ScrubGlide.active
    val seekPositionMs = PauseOnTimeline.thumbnailPositionMs(
        thumbPositionMs = uiState.previewThumbPositionMs,
        pendingPositionMs = uiState.pendingPreviewSeekPosition,
        gliding = gliding,
        playing = uiState.isPlaying,
        barShown = uiState.showControls || uiState.showSeekOverlay
    )
    val durationMs = timeline.duration
    DisposableEffect(Unit) { onDispose { SeekThumbnailOverlay.visible = false } }
    val restingMs = seekPositionMs ?: timeline.currentPosition
    val fraction by animateFloatAsState(
        targetValue = if (durationMs > 0L) (restingMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(PlayerScrubRates.GLIDE_PUBLISH_MS.toInt(), easing = LinearEasing),
        label = "thumbFraction"
    )
    if (seekPositionMs == null || durationMs <= 0L) {
        SideEffect { SeekThumbnailOverlay.visible = false }
        return
    }

    val stripSpacing = remember(tick) { SeekThumbnails.stripSpacingMs() }
    if (stripSpacing != null && gliding) {
        val aspect = SeekThumbnails.frameAspect() ?: (16f / 9f)
        // Re-spaced only when the speed crosses a step, and never back down while the glide stands still.
        val lastMultiple = remember { longArrayOf(1L) }
        val multiple by remember(stripSpacing) {
            derivedStateOf {
                val speed = ScrubGlide.speed
                if (speed > 0f) lastMultiple[0] = SeekStripLayout.multipleForSpeed(speed, stripSpacing)
                lastMultiple[0]
            }
        }
        SideEffect { SeekThumbnailOverlay.visible = true }
        SeekThumbnailStrip({ ScrubGlide.positionMs.toFloat() }, seekPositionMs, durationMs, stripSpacing, multiple,
            aspect, tick, modifier, uiState.chapters)
        return
    }

    val painter = remember(seekPositionMs, tick) { croppedPainter(SeekThumbnails.shownFor(seekPositionMs)) }
    val aspect = painter?.intrinsicSize?.let { it.width / it.height }
        ?: SeekThumbnails.frameAspect()
    if (aspect == null) {
        SideEffect { SeekThumbnailOverlay.visible = false }
        return
    }
    SideEffect { SeekThumbnailOverlay.visible = true }
    val height = 108.dp
    val shape = RoundedCornerShape(if (LocalV2Appearance.current != null) 12.dp else NuvioTheme.radii.sm)
    AboveSeekBar(modifier, contentAlignment = BiasAlignment(horizontalBias = fraction * 2f - 1f, verticalBias = 1f)) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SeekThumbnailLabel(uiState.chapters, seekPositionMs, Modifier.padding(bottom = NuvioTheme.spacing.sm))
            Box(Modifier.height(height).width(height * aspect).landingFrame(shape).thumbnailSurface(shape)) {
                if (painter != null) {
                    Image(painter, contentDescription = null, contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Stored coverage thumbnails slide past a fixed centre frame that shows where the seek will land. */
@Composable
private fun SeekThumbnailStrip(
    glidePosMs: () -> Float,
    exactPosMs: Long,
    durationMs: Long,
    baseSpacingMs: Long,
    multiple: Long,
    aspect: Float,
    tick: Int,
    modifier: Modifier,
    chapters: List<PlayerChapter>
) {
    val spacingMs = (baseSpacingMs * multiple).toFloat()
    // [0] anchor time, [1] spacing it was set for. A new spacing keeps the tiles where they are and refills them.
    val anchor = remember { doubleArrayOf(0.0, 0.0) }
    if (anchor[1] != spacingMs.toDouble()) {
        if (anchor[1] > 0.0) {
            val glide = Snapshot.withoutReadObservation { glidePosMs() }.toDouble()
            anchor[0] = SeekStripLayout.respacedAnchor(anchor[0], glide, anchor[1], spacingMs.toDouble())
        }
        anchor[1] = spacingMs.toDouble()
    }
    val anchorMs = anchor[0]
    val v2 = LocalV2Appearance.current != null
    val centreH = 127.dp
    val tileH = 95.dp
    val gap = 10.dp
    val centreW = centreH * aspect
    val tileW = tileH * aspect
    val tileShape = RoundedCornerShape(NuvioTheme.radii.sm)
    val centreShape = RoundedCornerShape(if (v2) 12.dp else NuvioTheme.radii.sm)
    val density = LocalDensity.current
    val tileWPx = with(density) { tileW.toPx() }
    val stridePx = with(density) { (tileW + gap).toPx() }
    val liftPx = with(density) { ((centreH - tileH) / 2).roundToPx() }
    val pxPerMs = stridePx / spacingMs
    // Resolved before the tiles so their disk loads cannot starve it.
    val centre = remember(exactPosMs, tick) { croppedPainter(SeekThumbnails.shownFor(exactPosMs)) }
    val posMs = exactPosMs.toFloat()
    AboveSeekBar(modifier) { width ->
        val widthPx = with(density) { width.toPx() }
        val centreX = widthPx / 2f
        val reachMs = (centreX / pxPerMs) + spacingMs
        for (i in SeekStripLayout.tileIndices(anchorMs, posMs.toDouble(), reachMs.toDouble(), spacingMs.toDouble())) {
            val tileMs = anchorMs + i * spacingMs.toDouble()
            val t = SeekStripLayout.pictureMs(tileMs, baseSpacingMs)
            if (t < 0L || t > durationMs) continue
            val rel = (tileMs - posMs).toFloat()
            val xAtTarget = centreX + rel * pxPerMs - tileWPx / 2f
            if (xAtTarget + tileWPx < -stridePx || xAtTarget > widthPx + stridePx) continue
            key(t) {
                // A slot whose thumbnail is not made yet stays an empty frame.
                val painter = remember(t, tick) { croppedPainter(SeekThumbnails.tileFor(t)?.takeIf { it.exact }) }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset {
                            val x = centreX + (tileMs - glidePosMs()).toFloat() * pxPerMs - tileWPx / 2f
                            IntOffset(x.toInt(), -liftPx)
                        }
                        .graphicsLayer { alpha = if (abs(tileMs - glidePosMs()) < spacingMs * 0.5) 0f else 1f }
                        .height(tileH)
                        .width(tileW)
                        .thumbnailSurface(tileShape)
                ) {
                    if (painter != null) {
                        Image(painter, contentDescription = null, contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        SeekThumbnailLabel(
            chapters, exactPosMs,
            Modifier.align(Alignment.BottomCenter).padding(bottom = centreH + NuvioTheme.spacing.sm)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .height(centreH)
                .width(centreW)
                .landingFrame(centreShape)
                .thumbnailSurface(centreShape)
        ) {
            if (centre != null) {
                Image(centre, contentDescription = null, contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** True while a side panel, menu or the details overlay covers the screen the progress hint would sit on. */
internal fun seekThumbnailHintCovered(state: PlayerUiState): Boolean =
    state.showPauseOverlay || state.showSourcesPanel || state.showEpisodesPanel || state.showChaptersPanel ||
        state.showAudioOverlay || state.showSubtitleOverlay || state.showSubtitleStylePanel ||
        state.showSubtitleTimingDialog || state.showSubtitleDelayOverlay || state.showSpeedDialog ||
            state.showNarrationDialog ||
        state.showMoreDialog || state.showStreamInfoOverlay || state.showPartyPanel ||
        state.postPlayMode != null

/** While paused on a small box, shows how far a 4K title's thumbnails are. */
@Composable
fun SeekThumbnailProgressHint(
    uiState: PlayerUiState,
    modifier: Modifier = Modifier,
    covered: Boolean = false
) {
    val tick by SeekThumbnails.tick
    val paused = !uiState.isPlaying && !uiState.isBuffering && uiState.pendingPreviewSeekPosition == null &&
        uiState.error == null
    val percent = remember(tick, paused) { if (paused) SeekThumbnails.progressPercent() else null } ?: return
    // The screensaver counts as away and would stop the paused pass; the screen stays on while it still counts.
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(percent) {
        stalled = false
        delay(KEEP_SCREEN_ON_STALL_MS)
        stalled = true
    }
    if (!stalled) {
        val view = LocalView.current
        DisposableEffect(view) {
            val before = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = before }
        }
    }
    if (covered || seekThumbnailHintCovered(uiState)) return
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    AboveSeekBar(modifier, anchored = uiState.showControls, contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .then(
                    if (LocalV2Appearance.current != null) Modifier.clip(shape).nuvioGlass(GlassRole.HUD, shape = shape)
                    else Modifier.clip(shape).background(Color.Black.copy(alpha = 0.7f))
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = stringResource(R.string.seek_thumbnails_progress_chip, percent),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}
