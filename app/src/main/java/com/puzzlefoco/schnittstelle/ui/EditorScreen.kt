package com.puzzlefoco.schnittstelle.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.ui.compose.PlayerSurface
import com.puzzlefoco.schnittstelle.media.Exporter
import com.puzzlefoco.schnittstelle.media.MediaProbe
import com.puzzlefoco.schnittstelle.model.AudioClip
import com.puzzlefoco.schnittstelle.model.EffectPreset
import com.puzzlefoco.schnittstelle.model.ExportQuality
import com.puzzlefoco.schnittstelle.model.Project
import com.puzzlefoco.schnittstelle.model.TextAlign
import com.puzzlefoco.schnittstelle.model.TextClip
import com.puzzlefoco.schnittstelle.model.TextPosition
import com.puzzlefoco.schnittstelle.model.VideoClip
import com.puzzlefoco.schnittstelle.ui.theme.SchnittstelleColors
import kotlin.math.abs
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: EditorViewModel) {
    val project = vm.project ?: return
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val selected = vm.selectedItem

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) vm.importMedia(uris) }

    val pickMusic = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::addMusic) }

    BackHandler { vm.closeProject() }

    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            snackbar.showSnackbar(it)
            vm.notice = null
        }
    }

    Scaffold(
        containerColor = SchnittstelleColors.Background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = project.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "${MediaProbe.formatTime(project.durationMs)} · " +
                                "${project.track(com.puzzlefoco.schnittstelle.model.TrackKind.VIDEO).items.size} Clips",
                            color = SchnittstelleColors.TextDim,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = { vm.closeProject() }) { Text("‹ Projekte") }
                },
                actions = {
                    TextButton(onClick = { vm.showExportDialog = true }) {
                        Text("Export", color = SchnittstelleColors.Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SchnittstelleColors.Surface,
                    titleContentColor = SchnittstelleColors.Text,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            PreviewBlock(vm, project, modifier = Modifier.weight(1f))
            TransportRow(vm, project)
            HorizontalDivider(color = SchnittstelleColors.Outline)
            TimelineView(
                project = project,
                selectedItemId = vm.selectedItemId,
                playheadMs = vm.playheadMs,
                pixelsPerSecond = vm.pixelsPerSecond,
                onSelect = { vm.select(it) },
                onSeek = { vm.seek(it) },
                onTrimEdge = { id, startEdge, deltaMs ->
                    vm.select(id)
                    vm.trimSelected(startEdge, deltaMs)
                },
                onMoveItem = { id, deltaMs ->
                    vm.select(id)
                    val item = project.itemById(id)
                    if (item is TextClip) {
                        vm.shiftSelected(deltaMs)
                    } else if (abs(deltaMs) > 150L) {
                        vm.moveSelected(if (deltaMs > 0) 1 else -1)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(196.dp),
            )
            HorizontalDivider(color = SchnittstelleColors.Outline)
            ActionBar(
                vm = vm,
                onPickMedia = { pickMedia.launch(arrayOf("video/*", "audio/*")) },
                onPickMusic = { pickMusic.launch(arrayOf("audio/*")) },
            )
            HorizontalDivider(color = SchnittstelleColors.Outline)
            Inspector(vm = vm, selected = selected)
        }
    }

    // ------------------------------------------------------------------ Dialoge
    val editing = vm.editingTextId?.let { id -> project.itemById(id) as? TextClip }
    if (editing != null) {
        TextEditorDialog(
            clip = editing,
            onDismiss = { vm.editingTextId = null },
            onApply = { updated ->
                vm.updateText(editing.id) { updated }
                vm.editingTextId = null
            },
        )
    }

    if (vm.showExportDialog) {
        ExportDialog(
            vm = vm,
            project = project,
            onShare = { uri ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                }
                runCatching {
                    context.startActivity(Intent.createChooser(send, "Export teilen"))
                }.onFailure { vm.notice = "Teilen nicht möglich – Datei liegt in Movies/Schnittstelle" }
            },
        )
    }
}

// ---------------------------------------------------------------------- Vorschau

@Composable
private fun PreviewBlock(vm: EditorViewModel, project: Project, modifier: Modifier = Modifier) {
    val ratio = project.aspect.widthRatio.toFloat() / project.aspect.heightRatio.toFloat()
    Box(
        modifier = modifier.fillMaxWidth().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(6.dp)),
            ) {
                PlayerSurface(player = vm.player, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun TransportRow(vm: EditorViewModel, project: Project) {
    val durationMs = max(1_000L, project.durationMs)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SchnittstelleColors.Surface)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { vm.togglePlay() },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Text(if (vm.isPlaying) "⏸ Pause" else "▶ Play")
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = MediaProbe.formatTime(vm.playheadMs),
            color = SchnittstelleColors.Text,
            style = MaterialTheme.typography.labelSmall,
        )
        Slider(
            value = vm.playheadMs.toFloat().coerceIn(0f, durationMs.toFloat()),
            onValueChange = { vm.seek(it.toLong()) },
            valueRange = 0f..durationMs.toFloat(),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(
            text = MediaProbe.formatTime(durationMs),
            color = SchnittstelleColors.TextDim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ---------------------------------------------------------------------- Werkzeuge

@Composable
private fun ActionBar(
    vm: EditorViewModel,
    onPickMedia: () -> Unit,
    onPickMusic: () -> Unit,
) {
    val hasSelection = vm.selectedItemId != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SchnittstelleColors.Surface)
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = onPickMedia, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("＋ Medien")
            }
            OutlinedButton(onClick = onPickMusic, contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text("♪ Musik")
            }
            OutlinedButton(
                onClick = { vm.addTextClip() },
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("T Text")
            }
            OutlinedButton(
                onClick = { vm.splitSelectedAtPlayhead() },
                enabled = hasSelection,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("✂ Teilen")
            }
            OutlinedButton(
                onClick = { vm.duplicateSelected() },
                enabled = hasSelection,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("⧉ Kopie")
            }
            OutlinedButton(
                onClick = { vm.moveSelected(-1) },
                enabled = hasSelection,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("◀")
            }
            OutlinedButton(
                onClick = { vm.moveSelected(1) },
                enabled = hasSelection,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("▶")
            }
            OutlinedButton(
                onClick = { vm.deleteSelected() },
                enabled = hasSelection,
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text("🗑 Löschen")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Zoom", color = SchnittstelleColors.TextDim, style = MaterialTheme.typography.labelSmall)
            Slider(
                value = vm.pixelsPerSecond,
                onValueChange = { vm.pixelsPerSecond = it },
                valueRange = 12f..160f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(
                text = "${vm.pixelsPerSecond.toInt()} px/s",
                color = SchnittstelleColors.TextDim,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ---------------------------------------------------------------------- Eigenschaften

@Composable
private fun Inspector(vm: EditorViewModel, selected: com.puzzlefoco.schnittstelle.model.TimelineItem?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .background(SchnittstelleColors.SurfaceHigh)
            .padding(10.dp),
    ) {
        when (selected) {
            null -> Text(
                text = "Clip antippen, um Filter, Lautstärke oder Text zu bearbeiten.\n" +
                    "Playhead ziehen (Lineal) und dann ✂ Teilen.",
                color = SchnittstelleColors.TextDim,
                style = MaterialTheme.typography.bodySmall,
            )

            is VideoClip -> VideoInspector(vm, selected)

            is AudioClip -> {
                Text(
                    text = "Ton · ${selected.label}",
                    color = SchnittstelleColors.Text,
                    style = MaterialTheme.typography.bodyMedium,
                )
                VolumeSlider(vm, selected.volume)
            }

            is TextClip -> {
                Text(
                    text = "Text · „${selected.text.take(28)}\"",
                    color = SchnittstelleColors.Text,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.editingTextId = selected.id }) {
                        Text("Text bearbeiten")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Start ${MediaProbe.formatTime(selected.startMs)} · " +
                            "Dauer ${MediaProbe.formatTime(selected.durationMs)}",
                        color = SchnittstelleColors.TextDim,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoInspector(vm: EditorViewModel, clip: VideoClip) {
    Text(
        text = "Filter · Lautstärke ${(clip.volume * 100).toInt()}%" +
            if (clip.muted) " · stumm" else "",
        color = SchnittstelleColors.Text,
        style = MaterialTheme.typography.bodySmall,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EffectPreset.entries.forEach { preset ->
            FilterChip(
                selected = clip.preset == preset,
                onClick = { vm.setPreset(preset) },
                label = { Text(preset.label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
    VolumeSlider(vm, clip.volume)
}

@Composable
private fun VolumeSlider(vm: EditorViewModel, volume: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Lautstärke", color = SchnittstelleColors.TextDim, style = MaterialTheme.typography.labelSmall)
        Slider(
            value = volume,
            onValueChange = { vm.setVolume(it) },
            valueRange = 0f..2f,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(
            text = "${(volume * 100).toInt()}%",
            color = SchnittstelleColors.Text,
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(modifier = Modifier.width(6.dp))
        TextButton(onClick = { vm.toggleMute() }) { Text("Stumm") }
    }
}

// ---------------------------------------------------------------------- Text-Dialog

@Composable
private fun TextEditorDialog(
    clip: TextClip,
    onDismiss: () -> Unit,
    onApply: (TextClip) -> Unit,
) {
    var text by remember { mutableStateOf(clip.text) }
    var size by remember { mutableStateOf(clip.sizeSp) }
    var bold by remember { mutableStateOf(clip.bold) }
    var position by remember { mutableStateOf(clip.position) }
    var align by remember { mutableStateOf(clip.align) }
    var durationMs by remember { mutableStateOf(clip.durationMs) }
    var colorArgb by remember { mutableStateOf(clip.colorArgb) }

    val swatches = listOf(
        0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFD400.toInt(),
        0xFFFF5A5F.toInt(), 0xFF4CD964.toInt(), 0xFF5B8CFF.toInt(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SchnittstelleColors.SurfaceHigh,
        title = { Text("Text", color = SchnittstelleColors.Text) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Text") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Größe ${size.toInt()}", color = SchnittstelleColors.TextDim, style = MaterialTheme.typography.labelSmall)
                Slider(value = size, onValueChange = { size = it }, valueRange = 14f..90f)
                Text(
                    "Dauer ${MediaProbe.formatTime(durationMs)}",
                    color = SchnittstelleColors.TextDim,
                    style = MaterialTheme.typography.labelSmall,
                )
                Slider(
                    value = durationMs.toFloat(),
                    onValueChange = { durationMs = it.toLong() },
                    valueRange = 500f..15_000f,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextPosition.entries.forEach { p ->
                        FilterChip(
                            selected = position == p,
                            onClick = { position = p },
                            label = {
                                Text(
                                    when (p) {
                                        TextPosition.TOP -> "oben"
                                        TextPosition.CENTER -> "mitte"
                                        TextPosition.BOTTOM -> "unten"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                    TextAlign.entries.forEach { a ->
                        FilterChip(
                            selected = align == a,
                            onClick = { align = a },
                            label = {
                                Text(
                                    when (a) {
                                        TextAlign.LEFT -> "links"
                                        TextAlign.CENTER -> "zentriert"
                                        TextAlign.RIGHT -> "rechts"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    swatches.forEach { swatch ->
                        Box(
                            modifier = Modifier
                                .width(26.dp)
                                .height(26.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(Color(swatch))
                                .border(
                                    width = if (colorArgb == swatch) 3.dp else 1.dp,
                                    color = if (colorArgb == swatch) SchnittstelleColors.Accent else SchnittstelleColors.Outline,
                                    shape = RoundedCornerShape(13.dp),
                                )
                                .clickable { colorArgb = swatch },
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Fett", color = SchnittstelleColors.TextDim, style = MaterialTheme.typography.labelSmall)
                    Switch(checked = bold, onCheckedChange = { bold = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(
                        clip.copy(
                            text = text,
                            sizeSp = size,
                            bold = bold,
                            position = position,
                            align = align,
                            durationMs = durationMs,
                            colorArgb = colorArgb,
                        )
                    )
                },
            ) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

// ---------------------------------------------------------------------- Export-Dialog

@Composable
private fun ExportDialog(
    vm: EditorViewModel,
    project: Project,
    onShare: (android.net.Uri) -> Unit,
) {
    var quality by remember { mutableStateOf(ExportQuality.P1080) }
    val state = vm.exportState
    val busy = state is Exporter.State.Progress

    AlertDialog(
        onDismissRequest = { if (!busy) vm.dismissExport() },
        containerColor = SchnittstelleColors.SurfaceHigh,
        title = { Text("Exportieren", color = SchnittstelleColors.Text) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (state) {
                    is Exporter.State.Success -> {
                        Text(
                            text = "Fertig: ${formatBytes(state.sizeBytes)}\n" +
                                "Länge ${MediaProbe.formatTime(state.durationMs)}\n" +
                                "Ablage: Filme/Schnittstelle",
                            color = SchnittstelleColors.Text,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    is Exporter.State.Failure -> {
                        Text(
                            text = "Export fehlgeschlagen:\n${state.message}",
                            color = SchnittstelleColors.Danger,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    else -> {
                        ExportQuality.entries.forEach { q ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) { quality = q },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = quality == q, onClick = { quality = q }, enabled = !busy)
                                Column {
                                    Text(q.label, color = SchnittstelleColors.Text, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "≈ ${formatBytes((q.estimatedSizeMb(project.durationMs) * 1024 * 1024).toLong())}",
                                        color = SchnittstelleColors.TextDim,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                        if (state is Exporter.State.Progress) {
                            Spacer(modifier = Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { state.percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "${state.percent}%",
                                color = SchnittstelleColors.TextDim,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            text = "Format: H.264 + AAC, ${quality.height}p, ${quality.fps} fps",
                            color = SchnittstelleColors.TextDim,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is Exporter.State.Success -> Button(onClick = { onShare(s.uri) }) { Text("Teilen") }
                is Exporter.State.Progress -> TextButton(onClick = { vm.cancelExport() }) { Text("Abbrechen") }
                else -> Button(onClick = { vm.startExport(quality) }, enabled = !busy) { Text("Starten") }
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissExport() }, enabled = !busy) {
                Text(if (state is Exporter.State.Success) "Schließen" else "Zurück")
            }
        },
    )
}
