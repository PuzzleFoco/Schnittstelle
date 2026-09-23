package com.puzzlefoco.schnittstelle.model

/**
 * Reine Timeline-Operationen – bewusst ohne Android-Abhängigkeiten,
 * damit sie in JVM-Unit-Tests verifizierbar sind.
 *
 * Invarianten:
 *  - Sequenzielle Spuren (VIDEO, AUDIO) haben keine Lücken: Startzeiten ergeben
 *    sich aus der Reihenfolge (siehe [Track.layout]).
 *  - Overlay-Spuren (TEXT) dürfen überlappen und frei positioniert sein.
 *  - Ein Element hat immer `durationMs > 0`.
 */
object TimelineOps {

    const val MIN_CLIP_MS = 100L

    // ---------------------------------------------------------------- hinzufügen

    fun append(track: Track, item: TimelineItem): Track =
        track.copy(items = track.items + item)

    /** Fügt einen Clip an der Position ein, an der er zeitlich hingehört (nur sequenzielle Spuren). */
    fun insertAt(track: Track, item: TimelineItem, atMs: Long): Track {
        if (track.kind == TrackKind.TEXT) return track.copy(items = track.items + item)
        var cursor = 0L
        var index = track.items.size
        track.items.forEachIndexed { i, existing ->
            if (atMs <= cursor + existing.durationMs / 2) {
                index = i
                return@forEachIndexed
            }
            cursor += existing.durationMs
        }
        return track.copy(items = track.items.toMutableList().also { it.add(index, item) })
    }

    // ---------------------------------------------------------------- schneiden

    /**
     * Teilt ein Element an der relativen Position [offsetMs] (bezogen auf den
     * Element-Anfang) in zwei Elemente.
     *
     * @return unveränderter Track, wenn der Schnitt außerhalb des Elements liegt
     *         oder eines der Teile kürzer als [MIN_CLIP_MS] wäre.
     */
    fun split(track: Track, itemId: String, offsetMs: Long): Track {
        val index = track.items.indexOfFirst { it.id == itemId }
        if (index < 0) return track
        val item = track.items[index]
        if (offsetMs < MIN_CLIP_MS || item.durationMs - offsetMs < MIN_CLIP_MS) return track

        val left: TimelineItem
        val right: TimelineItem
        when (item) {
            is VideoClip -> {
                left = item.copy(id = newId("vid"), durationMs = offsetMs)
                right = item.copy(
                    id = newId("vid"),
                    sourceStartMs = item.sourceStartMs + offsetMs,
                    durationMs = item.durationMs - offsetMs,
                )
            }
            is AudioClip -> {
                left = item.copy(id = newId("aud"), durationMs = offsetMs)
                right = item.copy(
                    id = newId("aud"),
                    sourceStartMs = item.sourceStartMs + offsetMs,
                    durationMs = item.durationMs - offsetMs,
                )
            }
            is TextClip -> {
                left = item.copy(id = newId("txt"), durationMs = offsetMs)
                right = item.copy(
                    id = newId("txt"),
                    startMs = item.startMs + offsetMs,
                    durationMs = item.durationMs - offsetMs,
                )
            }
        }
        val items = track.items.toMutableList()
        items[index] = left
        items.add(index + 1, right)
        return track.copy(items = items)
    }

    /** Teilt das Element, das [timelineMs] überdeckt (Schnitt am Playhead). */
    fun splitAtPlayhead(project: Project, track: Track, timelineMs: Long): Project {
        val placed = track.layout().firstOrNull { timelineMs > it.startMs && timelineMs < it.endMs } ?: return project
        val offset = timelineMs - placed.startMs
        return project.withTrack(track.kind) { split(it, placed.item.id, offset) }
    }

    // ---------------------------------------------------------------- trimmen

    /**
     * Setzt Anfang und Ende eines Elements (relativ zum Quellmaterial).
     * Bei sequenziellen Spuren bleibt der Quell-In-Punkt erhalten, die Länge ändert sich.
     */
    fun trim(track: Track, itemId: String, newSourceStartMs: Long, newDurationMs: Long): Track {
        val index = track.items.indexOfFirst { it.id == itemId }
        if (index < 0) return track
        val item = track.items[index]
        val duration = newDurationMs.coerceAtLeast(MIN_CLIP_MS)
        val newItem = when (item) {
            is VideoClip -> {
                val maxStart = (item.sourceDurationMs - MIN_CLIP_MS).coerceAtLeast(0L)
                val start = newSourceStartMs.coerceIn(0L, maxStart)
                val available = (item.sourceDurationMs - start).coerceAtLeast(MIN_CLIP_MS)
                item.copy(sourceStartMs = start, durationMs = duration.coerceIn(MIN_CLIP_MS, available))
            }
            is AudioClip -> {
                val maxStart = if (item.loop) Long.MAX_VALUE / 2
                    else (item.sourceDurationMs - MIN_CLIP_MS).coerceAtLeast(0L)
                val start = newSourceStartMs.coerceIn(0L, maxStart)
                // Nur schleifende Tonspuren dürfen über das Material hinausragen.
                val maxDuration = if (item.loop) Long.MAX_VALUE
                    else (item.sourceDurationMs - start).coerceAtLeast(MIN_CLIP_MS)
                item.copy(sourceStartMs = start, durationMs = duration.coerceIn(MIN_CLIP_MS, maxDuration))
            }
            is TextClip -> item.copy(durationMs = duration)
        }
        return track.copy(items = track.items.toMutableList().also { it[index] = newItem })
    }

    // ---------------------------------------------------------------- verschieben

    fun move(track: Track, itemId: String, delta: Int): Track {
        val index = track.items.indexOfFirst { it.id == itemId }
        if (index < 0) return track
        val target = (index + delta).coerceIn(0, track.items.size - 1)
        if (target == index) return track
        val items = track.items.toMutableList()
        val moved = items.removeAt(index)
        items.add(target, moved)
        return track.copy(items = items)
    }

    /** Verschiebt ein Overlay-Element auf der Zeitachse (nur TEXT). */
    fun shiftInTime(track: Track, itemId: String, deltaMs: Long): Track {
        if (track.kind.isSequential) return track
        return track.copy(items = track.items.map { item ->
            if (item.id != itemId) item
            else (item as TextClip).copy(startMs = (item.startMs + deltaMs).coerceAtLeast(0L))
        })
    }

    // ---------------------------------------------------------------- löschen / duplizieren

    fun remove(track: Track, itemId: String): Track =
        track.copy(items = track.items.filterNot { it.id == itemId })

    fun duplicate(track: Track, itemId: String): Track {
        val index = track.items.indexOfFirst { it.id == itemId }
        if (index < 0) return track
        val copy = when (val item = track.items[index]) {
            is VideoClip -> item.copy(id = newId("vid"))
            is AudioClip -> item.copy(id = newId("aud"))
            is TextClip -> item.copy(id = newId("txt"), startMs = item.endMs)
        }
        val items = track.items.toMutableList()
        items.add(index + 1, copy)
        return track.copy(items = items)
    }

    // ---------------------------------------------------------------- Projekt-Ebene

    fun applyOnItem(project: Project, itemId: String, block: (Track, String) -> Track): Project {
        val track = project.trackOf(itemId) ?: return project
        return project.withTrack(track.kind) { block(it, itemId) }
    }

    fun splitAt(project: Project, itemId: String, offsetMs: Long): Project =
        applyOnItem(project, itemId, { track, id -> split(track, id, offsetMs) })

    fun removeItem(project: Project, itemId: String): Project =
        applyOnItem(project, itemId, { track, id -> remove(track, id) })

    fun duplicateItem(project: Project, itemId: String): Project =
        applyOnItem(project, itemId, { track, id -> duplicate(track, id) })

    fun moveItem(project: Project, itemId: String, delta: Int): Project =
        applyOnItem(project, itemId, { track, id -> move(track, id, delta) })

    /** Löscht ein Element und schließt die Lücke (nur sequenzielle Spuren). */
    fun removeAndClose(project: Project, itemId: String): Project = removeItem(project, itemId)

    /**
     * Stellt die Invarianten her: Längen > 0, Trim-Grenzen innerhalb des Quellmaterials,
     * Overlay-Zeiten >= 0.
     */
    fun normalize(project: Project): Project = project.copy(
        tracks = project.tracks.map { track ->
            track.copy(items = track.items.mapNotNull { item ->
                when (item) {
                    is VideoClip -> {
                        val maxDuration = (item.sourceDurationMs - item.sourceStartMs).coerceAtLeast(MIN_CLIP_MS)
                        item.copy(
                            durationMs = item.durationMs.coerceIn(MIN_CLIP_MS, maxDuration),
                            volume = item.volume.coerceIn(0f, 2f),
                        )
                    }
                    is AudioClip -> {
                        // Wie beim Video darf die Länge das Material nicht überschreiten –
                        // außer die Spur ist als Schleife markiert (Hintergrundmusik).
                        val maxDuration = if (item.loop) Long.MAX_VALUE
                            else (item.sourceDurationMs - item.sourceStartMs).coerceAtLeast(MIN_CLIP_MS)
                        item.copy(
                            durationMs = item.durationMs.coerceIn(MIN_CLIP_MS, maxDuration),
                            volume = item.volume.coerceIn(0f, 2f),
                        )
                    }
                    is TextClip -> {
                        // Fade erst nach der Korrektur der Dauer begrenzen, sonst
                        // würde eine ungültige Dauer den Fade auf 0 ziehen.
                        val duration = item.durationMs.coerceAtLeast(MIN_CLIP_MS)
                        item.copy(
                            durationMs = duration,
                            startMs = item.startMs.coerceAtLeast(0L),
                            fadeMs = item.fadeMs.coerceIn(0L, duration / 2),
                        ).takeIf { it.text.isNotEmpty() }
                    }
                }
            })
        },
        modifiedAt = System.currentTimeMillis(),
    )
}
