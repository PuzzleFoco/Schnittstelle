package com.puzzlefoco.schnittstelle.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.transformer.CompositionPlayer
import com.puzzlefoco.schnittstelle.data.ProjectStore
import com.puzzlefoco.schnittstelle.media.CompositionFactory
import com.puzzlefoco.schnittstelle.media.Exporter
import com.puzzlefoco.schnittstelle.media.MediaProbe
import com.puzzlefoco.schnittstelle.model.AudioClip
import com.puzzlefoco.schnittstelle.model.EffectPreset
import com.puzzlefoco.schnittstelle.model.ExportCodec
import com.puzzlefoco.schnittstelle.model.ExportQuality
import com.puzzlefoco.schnittstelle.model.FitMode
import com.puzzlefoco.schnittstelle.model.Project
import com.puzzlefoco.schnittstelle.model.TextClip
import com.puzzlefoco.schnittstelle.model.TimelineItem
import com.puzzlefoco.schnittstelle.model.TimelineOps
import com.puzzlefoco.schnittstelle.model.TrackKind
import com.puzzlefoco.schnittstelle.model.VideoClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "Schnittstelle"

/**
 * Zustand und Aktionen des Editors. Bindet Modell (JSON-Projekt), Vorschau
 * (CompositionPlayer) und Export (Transformer) zusammen.
 */
class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ProjectStore(app)
    private val exporter = Exporter(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var previewJob: Job? = null

    val mediaDir: File get() = store.mediaDir

    /** Vorschau-Player; dieselbe Media3-Pipeline wie der Export. */
    val player: CompositionPlayer = CompositionPlayer.Builder(app)
        .experimentalSetEnableReplayableCache(true)
        .build()

    var projects by mutableStateOf<List<Project>>(emptyList())
        private set
    var project by mutableStateOf<Project?>(null)
        private set
    var selectedItemId by mutableStateOf<String?>(null)
    var editingTextId by mutableStateOf<String?>(null)
    var showExportDialog by mutableStateOf(false)
    var playheadMs by mutableStateOf(0L)
    var pixelsPerSecond by mutableStateOf(48f)
    var isPlaying by mutableStateOf(false)
        private set
    var exportState by mutableStateOf<Exporter.State>(Exporter.State.Idle)
        private set
    var notice by mutableStateOf<String?>(null)
    var mediaUsageBytes by mutableStateOf(0L)
        private set

    val selectedItem: TimelineItem?
        get() = selectedItemId?.let { id -> project?.itemById(id) }

    init {
        // Ohne Listener bleibt ein fehlgeschlagener Vorschau-Aufbau stumm: der
        // Player steht dann einfach still. Fehler deshalb sichtbar machen.
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Vorschau-Fehler", error)
                notice = "Wiedergabe nicht möglich: ${error.errorCodeName}"
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.i(TAG, "Player-Zustand: $playbackState")
            }
        })
        refreshProjects()
        scope.launch {
            while (isActive) {
                val p = project
                if (p != null) {
                    isPlaying = player.isPlaying
                    if (player.isPlaying) {
                        playheadMs = player.currentPosition.coerceAtLeast(0L)
                    }
                }
                delay(80)
            }
        }
    }

    // ---------------------------------------------------------------- Projekte

    fun refreshProjects() {
        // Plattenarbeit gehört nicht auf den Hauptthread: Projektdateien lesen und
        // die Medienbelegung berechnen laufen im Hintergrund.
        scope.launch {
            val (loadedProjects, usage) = withContext(Dispatchers.IO) {
                store.listProjects() to runCatching { store.mediaUsageBytes() }.getOrDefault(0L)
            }
            projects = loadedProjects
            mediaUsageBytes = usage
        }
    }

    fun newProject() {
        val project = Project(name = "Projekt ${projects.size + 1}")
        store.save(project)
        open(project)
        refreshProjects()
    }

    fun open(project: Project) {
        this.project = project
        selectedItemId = null
        editingTextId = null
        playheadMs = 0L
        exportState = Exporter.State.Idle
        rebuildPreview()
    }

    fun closeProject() {
        player.stop()
        previewJob?.cancel()
        project?.let { store.save(it) }
        project = null
        selectedItemId = null
        refreshProjects()
    }

    fun deleteProject(projectId: String) {
        store.delete(projectId)
        refreshProjects()
    }

    fun renameProject(name: String) {
        update { it.copy(name = name) }
    }

    /**
     * Schaltet die Einpassung weiter: Einpassen → Ausfüllen → Verzerren → Einpassen.
     *
     * Ändert die Ausgabegröße der Vorschau: Ohne festen Rahmen übernimmt Media3 die
     * Quellgröße, wodurch ein Querformat-Clip in einem Hochformat-Projekt schwarze
     * Balken behält. Die Vorschau wird neu aufgebaut, damit das sofort sichtbar ist.
     */
    fun cycleFitMode() {
        val current = project ?: return
        val next = when (current.fitMode) {
            FitMode.FIT -> FitMode.FILL
            FitMode.FILL -> FitMode.STRETCH
            FitMode.STRETCH -> FitMode.FIT
        }
        update { it.copy(fitMode = next) }
        rebuildPreview()
        notice = "Bildanpassung: ${next.label} – ${next.description}"
    }

    /** Wendet eine Änderung an, normalisiert die Invarianten und speichert. */
    private fun update(transform: (Project) -> Project) {
        val current = project ?: return
        val next = TimelineOps.normalize(transform(current))
        project = next
        store.save(next)
    }

    // ---------------------------------------------------------------- Import

    fun importMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (project == null) return
        scope.launch {
            var imported = 0
            var failed = 0
            for (uri in uris) {
                val result = runCatching {
                    // Kopieren und Auslesen der Metadaten dauert bei großen Dateien
                    // Sekunden – deshalb im Hintergrund, nicht auf dem Hauptthread.
                    withContext(Dispatchers.IO) {
                        val relative = store.importMedia(uri)
                        val info = MediaProbe.probe(getApplication(), store.mediaFile(relative))
                        relative to info
                    }
                }
                val (relative, info) = result.getOrElse {
                    failed++
                    continue
                }
                if (info.hasVideo && info.durationMs > 0) {
                    update { p ->
                        val clip = VideoClip(
                            durationMs = info.durationMs,
                            sourcePath = relative,
                            sourceDurationMs = info.durationMs,
                            rotationDegrees = info.rotationDegrees,
                            muted = !info.hasAudio,
                        )
                        p.withTrack(TrackKind.VIDEO) { TimelineOps.append(it, clip) }
                    }
                    imported++
                } else if (info.durationMs > 0) {
                    val clip = AudioClip(
                        durationMs = info.durationMs,
                        sourcePath = relative,
                        sourceDurationMs = info.durationMs,
                        label = store.displayName(uri)?.substringBeforeLast('.') ?: "Audio",
                    )
                    update { p -> p.withTrack(TrackKind.AUDIO) { TimelineOps.append(it, clip) } }
                    imported++
                } else {
                    store.mediaFile(relative).delete()
                    failed++
                }
            }
            refreshProjects()
            notice = when {
                failed == 0 && imported > 0 -> "$imported Medium/Medien importiert"
                imported > 0 -> "$imported importiert, $failed nicht lesbar"
                else -> "Import fehlgeschlagen – Datei nicht lesbar"
            }
        }
    }

    /** Hängt Musik an die Tonspur (Schleife, damit sie unter dem ganzen Film liegt). */
    fun addMusic(uri: Uri) {
        if (project == null) return
        scope.launch {
            runCatching {
                val relative = store.importMedia(uri)
                val info = MediaProbe.probe(getApplication(), store.mediaFile(relative))
                relative to info
            }.onSuccess { (relative, info) ->
                val needed = project?.durationMs?.coerceAtLeast(1000L) ?: 1000L
                val clip = AudioClip(
                    durationMs = info.durationMs.coerceAtLeast(needed),
                    sourcePath = relative,
                    sourceDurationMs = info.durationMs,
                    loop = info.durationMs < needed,
                    label = "Musik",
                )
                update { p -> p.withTrack(TrackKind.AUDIO) { TimelineOps.append(it, clip) } }
                notice = "Musik hinzugefügt"
            }.onFailure {
                notice = "Audiodatei nicht lesbar"
            }
        }
    }

    // ---------------------------------------------------------------- Text

    fun addTextClip(text: String = "Text") {
        val clip = TextClip(startMs = playheadMs, durationMs = 3_000L, text = text)
        update { p -> p.withTrack(TrackKind.TEXT) { TimelineOps.append(it, clip) } }
        selectedItemId = clip.id
        editingTextId = clip.id
    }

    fun updateText(itemId: String, transform: (TextClip) -> TextClip) {
        update { p ->
            p.withTrack(TrackKind.TEXT) { track ->
                track.copy(items = track.items.map { if (it.id == itemId) transform(it as TextClip) else it })
            }
        }
    }

    // ---------------------------------------------------------------- Schnitt

    fun splitSelectedAtPlayhead() {
        val id = selectedItemId ?: return
        val p = project ?: return
        val track = p.trackOf(id) ?: return
        val placed = track.layout().firstOrNull { it.item.id == id } ?: return
        val offset = playheadMs - placed.startMs
        val updated = TimelineOps.applyOnItem(p, id, { t, _ -> TimelineOps.split(t, id, offset) })
        if (updated == p) {
            notice = "Schnitt nicht möglich – Playhead muss im Clip liegen (min. 0,1 s Abstand)"
            return
        }
        project = TimelineOps.normalize(updated)
        project?.let { store.save(it) }
        rebuildPreview()
    }

    fun deleteSelected() {
        val id = selectedItemId ?: return
        update { TimelineOps.removeItem(it, id) }
        selectedItemId = null
        notice = "Clip gelöscht"
    }

    fun duplicateSelected() {
        val id = selectedItemId ?: return
        update { TimelineOps.duplicateItem(it, id) }
    }

    /** Verschiebt einen Clip innerhalb der Spur nach vorne/hinten (Reihenfolge). */
    fun moveSelected(delta: Int) {
        val id = selectedItemId ?: return
        update { TimelineOps.moveItem(it, id, delta) }
    }

    /** Zieht eine Clip-Kante (Finger-Drag auf der Timeline). */
    fun trimSelected(startEdge: Boolean, deltaMs: Long) {
        val id = selectedItemId ?: return
        val item = selectedItem ?: return
        when (item) {
            is VideoClip -> {
                if (startEdge) {
                    val newStart = (item.sourceStartMs + deltaMs).coerceAtLeast(0L)
                    val newDuration = (item.durationMs - deltaMs).coerceAtLeast(TimelineOps.MIN_CLIP_MS)
                    update { p ->
                        TimelineOps.applyOnItem(p, id, { t, _ ->
                            TimelineOps.trim(t, id, newStart, newDuration)
                        })
                    }
                } else {
                    val newDuration = (item.durationMs + deltaMs)
                        .coerceIn(TimelineOps.MIN_CLIP_MS, item.sourceDurationMs - item.sourceStartMs)
                    update { p ->
                        TimelineOps.applyOnItem(p, id, { t, _ ->
                            TimelineOps.trim(t, id, item.sourceStartMs, newDuration)
                        })
                    }
                }
            }
            is AudioClip -> {
                val maxDuration = if (item.loop) Long.MAX_VALUE
                    else (item.sourceDurationMs - item.sourceStartMs).coerceAtLeast(TimelineOps.MIN_CLIP_MS)
                if (startEdge) {
                    val newStart = (item.sourceStartMs + deltaMs).coerceAtLeast(0L)
                    val newDuration = (item.durationMs - deltaMs).coerceAtLeast(TimelineOps.MIN_CLIP_MS)
                    update { p ->
                        TimelineOps.applyOnItem(p, id, { t, _ -> TimelineOps.trim(t, id, newStart, newDuration) })
                    }
                } else {
                    val newDuration = (item.durationMs + deltaMs)
                        .coerceIn(TimelineOps.MIN_CLIP_MS, maxDuration)
                    update { p ->
                        TimelineOps.applyOnItem(p, id, { t, _ -> TimelineOps.trim(t, id, item.sourceStartMs, newDuration) })
                    }
                }
            }
            is TextClip -> {
                val newDuration = (item.durationMs + deltaMs).coerceAtLeast(TimelineOps.MIN_CLIP_MS)
                updateText(id) { it.copy(durationMs = newDuration) }
            }
        }
    }

    /** Verschiebt ein Text-Overlay auf der Zeitachse. */
    fun shiftSelected(deltaMs: Long) {
        val id = selectedItemId ?: return
        val track = project?.trackOf(id) ?: return
        if (track.kind.isSequential) return
        update { p -> TimelineOps.applyOnItem(p, id, { t, _ -> TimelineOps.shiftInTime(t, id, deltaMs) }) }
    }

    // ---------------------------------------------------------------- Eigenschaften

    fun setPreset(preset: EffectPreset) {
        val id = selectedItemId ?: return
        update { p ->
            p.withTrack(TrackKind.VIDEO) { track ->
                track.copy(items = track.items.map { item ->
                    if (item.id == id && item is VideoClip) item.copy(preset = preset) else item
                })
            }
        }
    }

    fun setVolume(volume: Float) {
        val id = selectedItemId ?: return
        update { p -> applyVolume(p, id, volume.coerceIn(0f, 2f)) }
    }

    fun toggleMute() {
        val id = selectedItemId ?: return
        update { p -> applyMute(p, id) }
    }

    private fun applyVolume(project: Project, itemId: String, volume: Float): Project =
        project.copy(tracks = project.tracks.map { track ->
            track.copy(items = track.items.map { item ->
                when {
                    item.id != itemId -> item
                    item is VideoClip -> item.copy(volume = volume)
                    item is AudioClip -> item.copy(volume = volume)
                    else -> item
                }
            })
        })

    private fun applyMute(project: Project, itemId: String): Project =
        project.copy(tracks = project.tracks.map { track ->
            track.copy(items = track.items.map { item ->
                when {
                    item.id != itemId -> item
                    item is VideoClip -> item.copy(muted = !item.muted)
                    item is AudioClip -> item.copy(volume = if (item.volume > 0f) 0f else 1f)
                    else -> item
                }
            })
        })

    // ---------------------------------------------------------------- Wiedergabe

    fun seek(ms: Long) {
        val target = ms.coerceIn(0L, (project?.durationMs ?: 0L).coerceAtLeast(0L))
        playheadMs = target
        runCatching { player.seekTo(target) }
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (project == null) return
            if (project?.durationMs == 0L) {
                notice = "Erst Medien importieren"
                return
            }
            if (playheadMs >= (project?.durationMs ?: 0L)) {
                runCatching { player.seekTo(0L) }
                playheadMs = 0L
            }
            player.play()
        }
        isPlaying = player.isPlaying
        Log.i(TAG, "Play-Toggle: isPlaying=${player.isPlaying} zustand=${player.playbackState} " +
            "position=${player.currentPosition} dauer=${project?.durationMs}")
    }

    fun select(itemId: String?) {
        selectedItemId = itemId
    }

    // ---------------------------------------------------------------- Vorschau

    private fun rebuildPreview() {
        previewJob?.cancel()
        previewJob = scope.launch {
            delay(120) // Tippen/Draggen nicht bei jedem Zwischenschritt neu aufbauen
            val p = project ?: return@launch
            val position = playheadMs
            val composition = runCatching {
                CompositionFactory.build(getApplication(), p, store.mediaDir, PREVIEW_HEIGHT, PREVIEW_FPS)
            }.getOrElse { error ->
                notice = "Vorschau nicht möglich: ${error.message ?: "unbekannt"}"
                return@launch
            }
            runCatching {
                player.setComposition(composition, position)
                // setComposition bereitet den Player nicht vor: ohne prepare()
                // bleibt er im Zustand IDLE und play() bewirkt nichts.
                player.prepare()
            }
                .onSuccess { Log.i(TAG, "Vorschau bereit: pos=$position dauer=${p.durationMs}") }
                .onFailure { Log.w(TAG, "Vorschau-Aufbau fehlgeschlagen", it) }
        }
    }

    // ---------------------------------------------------------------- Export

    fun startExport(quality: ExportQuality, codec: ExportCodec) {
        val p = project ?: return
        if (p.durationMs == 0L) {
            notice = "Nichts zu exportieren – Timeline ist leer"
            return
        }
        player.pause()
        val composition = runCatching {
            CompositionFactory.build(getApplication(), p, store.mediaDir, quality.height, quality.fps)
        }.getOrElse { error ->
            exportState = Exporter.State.Failure(error.message ?: "Composition nicht baubar")
            return
        }
        exportState = Exporter.State.Progress(0)
        exporter.export(composition, quality, codec, p.baseName()) { state -> exportState = state }
    }

    /** Öffnet das fertige Video in einem Player der Wahl (Galerie, VLC, was installiert ist). */
    fun openExport(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Der Application-Kontext hat keinen eigenen Task – ohne dieses Flag
            // verweigert Android den Start (AndroidRuntimeException).
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { getApplication<Application>().startActivity(intent) }
            .onFailure { error ->
                Log.w(TAG, "openExport fehlgeschlagen", error)
                notice = "Kein Videoplayer auf diesem Gerät gefunden"
            }
    }

    fun cancelExport() {
        exporter.cancel()
        exportState = Exporter.State.Idle
    }

    fun dismissExport() {
        exportState = Exporter.State.Idle
        showExportDialog = false
        refreshProjects()
    }

    override fun onCleared() {
        exporter.cancel()
        runCatching { player.release() }
        scope.cancel()
        super.onCleared()
    }

    private companion object {
        /** Vorschau bewusst in 720p – flüssiger und identisch zum 720p-Export. */
        const val PREVIEW_HEIGHT = 720
        const val PREVIEW_FPS = 30
    }
}
