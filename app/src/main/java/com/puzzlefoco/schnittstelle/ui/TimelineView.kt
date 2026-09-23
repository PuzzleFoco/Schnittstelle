package com.puzzlefoco.schnittstelle.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.puzzlefoco.schnittstelle.model.Project
import com.puzzlefoco.schnittstelle.model.TextClip
import com.puzzlefoco.schnittstelle.model.TimelineItem
import com.puzzlefoco.schnittstelle.model.TrackKind
import com.puzzlefoco.schnittstelle.media.MediaProbe
import com.puzzlefoco.schnittstelle.ui.theme.SchnittstelleColors
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Timeline: drei Spuren auf einer gemeinsamen Zeitachse, Playhead, Ziehen zum
 * Trimmen/Verschieben. Die Zeitachse wird über [pixelsPerSecond] gezoomt.
 */
@Composable
fun TimelineView(
    project: Project,
    selectedItemId: String?,
    playheadMs: Long,
    pixelsPerSecond: Float,
    onSelect: (String?) -> Unit,
    onSeek: (Long) -> Unit,
    onTrimEdge: (itemId: String, startEdge: Boolean, deltaMs: Long) -> Unit,
    onMoveItem: (itemId: String, deltaMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val contentMs = (project.durationMs + 4_000L).coerceAtLeast(8_000L)
    val contentWidth: Dp = with(density) { (contentMs / 1000f * pixelsPerSecond).toDp() }

    fun msFromPx(px: Float): Long = (px / pixelsPerSecond * 1000f).roundToLong()

    Column(modifier = modifier.background(SchnittstelleColors.Background)) {
        // ---------------------------------------------------------------- Lineal mit Playhead
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(26.dp)
                .background(SchnittstelleColors.Surface)
                .pointerInput(pixelsPerSecond) {
                    detectDragGestures(
                        onDragStart = { offset -> onSeek(msFromPx(offset.x + scroll.value)) },
                    ) { change, _ ->
                        onSeek(msFromPx(change.position.x + scroll.value))
                        change.consume()
                    }
                }
                .pointerInput(pixelsPerSecond) {
                    detectTapGestures { offset -> onSeek(msFromPx(offset.x + scroll.value)) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(modifier = Modifier.horizontalScroll(scroll, enabled = false)) {
                Box(modifier = Modifier.width(contentWidth).height(26.dp)) {
                    // Sekundenmarken
                    val seconds = (contentMs / 1000L).toInt()
                    for (s in 0..seconds) {
                        if (s % 5 != 0) continue
                        Box(
                            modifier = Modifier
                                .offset(x = with(density) { (s * pixelsPerSecond).toDp() })
                                .width(1.dp)
                                .height(10.dp)
                                .background(SchnittstelleColors.Outline),
                        )
                        Text(
                            text = MediaProbe.formatTime(s * 1000L),
                            color = SchnittstelleColors.TextDim,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .offset(x = with(density) { (s * pixelsPerSecond + 4f).toDp() })
                                .padding(top = 10.dp),
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------------------- Spuren
        Box(modifier = Modifier.fillMaxWidth().height(168.dp)) {
            Row(modifier = Modifier.horizontalScroll(scroll)) {
                Column(modifier = Modifier.width(if (contentWidth < 320.dp) 320.dp else contentWidth)) {
                    TrackRow(project, TrackKind.VIDEO, selectedItemId, pixelsPerSecond, { onSelect(it) }, onTrimEdge, onMoveItem)
                    TrackRow(project, TrackKind.AUDIO, selectedItemId, pixelsPerSecond, { onSelect(it) }, onTrimEdge, onMoveItem)
                    TrackRow(project, TrackKind.TEXT, selectedItemId, pixelsPerSecond, { onSelect(it) }, onTrimEdge, onMoveItem)
                }
            }

            // Playhead-Linie (über allen Spuren)
            Playhead(
                playheadMs = playheadMs,
                pixelsPerSecond = pixelsPerSecond,
                scrollOffsetPx = scroll.value,
                contentWidth = contentWidth,
                onDrag = { px -> onSeek(msFromPx(px)) },
            )
        }
    }
}

@Composable
private fun Playhead(
    playheadMs: Long,
    pixelsPerSecond: Float,
    scrollOffsetPx: Int,
    contentWidth: Dp,
    onDrag: (Float) -> Unit,
) {
    val density = LocalDensity.current
    val x = with(density) { (playheadMs / 1000f * pixelsPerSecond - scrollOffsetPx).toDp() }
    Box(modifier = Modifier.width(contentWidth).fillMaxHeight()) {
        Box(
            modifier = Modifier
                .offset(x = x)
                .width(14.dp)
                .fillMaxHeight()
                .pointerInput(pixelsPerSecond, scrollOffsetPx) {
                    detectDragGestures { change, _ ->
                        onDrag(change.position.x + scrollOffsetPx)
                        change.consume()
                    }
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(SchnittstelleColors.Accent),
            )
        }
    }
}

@Composable
private fun TrackRow(
    project: Project,
    kind: TrackKind,
    selectedItemId: String?,
    pixelsPerSecond: Float,
    onSelect: (String?) -> Unit,
    onTrimEdge: (String, Boolean, Long) -> Unit,
    onMoveItem: (String, Long) -> Unit,
) {
    val density = LocalDensity.current
    val track = project.track(kind)
    val placed = track.layout()
    val rowHeight = 52.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight + 4.dp)
            .background(SchnittstelleColors.Surface.copy(alpha = 0.35f))
            .pointerInput(kind) { detectTapGestures { onSelect(null) } },
    ) {
        placed.forEach { p ->
            val startDp = with(density) { (p.startMs / 1000f * pixelsPerSecond).toDp() }
            val widthDp = with(density) {
                ((p.item.durationMs / 1000f) * pixelsPerSecond).coerceAtLeast(56f).toDp()
            }
            ClipBox(
                item = p.item,
                kind = kind,
                selected = p.item.id == selectedItemId,
                width = widthDp,
                height = rowHeight,
                modifier = Modifier.offset(x = startDp).padding(top = 2.dp),
                onClick = { onSelect(p.item.id) },
                onDragMove = { dxPx -> onMoveItem(p.item.id, (dxPx / pixelsPerSecond * 1000f).roundToLong()) },
                onTrimStart = { px -> onTrimEdge(p.item.id, true, (px / pixelsPerSecond * 1000f).roundToLong()) },
                onTrimEnd = { px -> onTrimEdge(p.item.id, false, (px / pixelsPerSecond * 1000f).roundToLong()) },
            )
        }

        if (placed.isEmpty()) {
            Text(
                text = when (kind) {
                    TrackKind.VIDEO -> "Videospur leer – Medien importieren"
                    TrackKind.AUDIO -> "Tonspur leer – Musik hinzufügen"
                    TrackKind.TEXT -> "Textspur leer – Text hinzufügen"
                },
                color = SchnittstelleColors.TextDim,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 12.dp, top = 18.dp),
            )
        }
    }
}

@Composable
private fun ClipBox(
    item: TimelineItem,
    kind: TrackKind,
    selected: Boolean,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDragMove: (Float) -> Unit,
    onTrimStart: (Long) -> Unit,
    onTrimEnd: (Long) -> Unit,
) {
    val baseColor = when {
        item is TextClip -> SchnittstelleColors.TextTrack
        kind == TrackKind.AUDIO -> SchnittstelleColors.AudioTrack
        else -> SchnittstelleColors.VideoTrack
    }
    val label = when (item) {
        is TextClip -> item.text.replace("\n", " ").ifBlank { "Text" }
        else -> item.kindLabel
    }

    Box(
        modifier = modifier
            .height(height)
            .width(width)
            .clip(RoundedCornerShape(8.dp))
            .background(baseColor.copy(alpha = if (selected) 1f else 0.78f))
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) SchnittstelleColors.Accent else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .pointerInput(item.id) {
                detectTapGestures(onTap = { onClick() })
            }
            .pointerInput(item.id, selected) {
                if (!selected) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDrag = { change, drag ->
                        onDragMove(drag.x)
                        change.consume()
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(modifier = Modifier.padding(horizontal = 6.dp)) {
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = MediaProbe.formatTime(item.durationMs),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }

        if (selected) {
            TrimHandle(Alignment.CenterStart) { deltaMs -> onTrimStart(deltaMs) }
            TrimHandle(Alignment.CenterEnd) { deltaMs -> onTrimEnd(deltaMs) }
        }
    }
}

@Composable
private fun BoxScope.TrimHandle(
    alignment: Alignment,
    onTrim: (Long) -> Unit,
) {
    var accumulated = remember { 0f }
    Box(
        modifier = Modifier
            .align(alignment)
            .width(14.dp)
            .height(44.dp)
            .background(SchnittstelleColors.Accent.copy(alpha = 0.9f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { accumulated = 0f },
                    onDragCancel = { accumulated = 0f },
                ) { change, drag ->
                    accumulated += drag.x
                    if (abs(accumulated) >= 2f) {
                        onTrim(accumulated.toLong())
                        accumulated = 0f
                    }
                    change.consume()
                }
            },
    )
}
