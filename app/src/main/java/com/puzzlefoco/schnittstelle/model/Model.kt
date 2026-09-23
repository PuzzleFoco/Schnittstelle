package com.puzzlefoco.schnittstelle.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Datenmodell des Schnittprojekts.
 *
 * Alle Zeiten sind Millisekunden und beziehen sich entweder auf das Quellmaterial
 * (`sourceStartMs`) oder auf die Projekt-Timeline (`startMs`, `durationMs`).
 */
@Serializable
data class Project(
    val id: String = newId("prj"),
    val name: String = "Neues Projekt",
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val aspect: AspectRatio = AspectRatio.PORTRAIT_9_16,
    val canvasHeight: Int = 1080,
    val tracks: List<Track> = listOf(
        Track(id = newId("trk"), kind = TrackKind.VIDEO),
        Track(id = newId("trk"), kind = TrackKind.AUDIO),
        Track(id = newId("trk"), kind = TrackKind.TEXT),
    ),
) {
    fun track(kind: TrackKind): Track = tracks.first { it.kind == kind }

    fun withTrack(kind: TrackKind, block: (Track) -> Track): Project =
        copy(tracks = tracks.map { if (it.kind == kind) block(it) else it })

    /** Gesamtlänge des Projekts in ms (längste Spur bestimmt). */
    val durationMs: Long
        get() = tracks.maxOfOrNull { it.endMs } ?: 0L

    fun itemById(itemId: String): TimelineItem? =
        tracks.asSequence().flatMap { it.items.asSequence() }.firstOrNull { it.id == itemId }

    fun trackOf(itemId: String): Track? = tracks.firstOrNull { t -> t.items.any { it.id == itemId } }

    fun baseName(): String = name.ifBlank { "projekt" }
}

@Serializable
enum class AspectRatio(val label: String, val widthRatio: Int, val heightRatio: Int) {
    PORTRAIT_9_16("9:16 Hochformat", 9, 16),
    SQUARE_1_1("1:1 Quadrat", 1, 1),
    LANDSCAPE_16_9("16:9 Querformat", 16, 9),
    CINEMA_21_9("21:9 Kino", 21, 9);

    /** Kantenlänge pro Kürzungsstufe (nur Breite/Höhe für die Ausgabe relevant). */
    fun outputSize(height: Int): Pair<Int, Int> {
        val w = (height.toDouble() * widthRatio / heightRatio).toInt()
        // gerade Zahlen sind bei H.264-Encodern Pflicht
        fun even(v: Int) = if (v % 2 == 0) v else v + 1
        return even(w) to even(height)
    }

    val isPortrait: Boolean get() = heightRatio > widthRatio
}

@Serializable
enum class TrackKind(val label: String) {
    /** Hauptspur: Bildclips, streng hintereinander. */
    VIDEO("Video"),

    /** Tonspur: Musik + Voiceover, streng hintereinander (Lücken werden als Pause geführt). */
    AUDIO("Ton"),

    /** Overlay-Spur: Texte, frei positionierbar und überlappend. */
    TEXT("Text");

    /** Sequenzielle Spuren werden immer lückenlos (abzüglich Lücken) hintereinander gelegt. */
    val isSequential: Boolean get() = this != TEXT
}

@Serializable
data class Track(
    val id: String,
    val kind: TrackKind,
    val items: List<TimelineItem> = emptyList(),
    val muted: Boolean = false,
) {
    /** Endzeit des letzten Elements (0 wenn leer). */
    val endMs: Long
        get() = if (kind.isSequential) items.sumOf { it.durationMs } else (items.maxOfOrNull { (it as TextClip).endMs } ?: 0L)

    fun layout(): List<Placed> {
        var cursor = 0L
        return items.map { item ->
            val start = if (kind.isSequential) cursor else (item as TextClip).startMs
            cursor = start + item.durationMs
            Placed(trackId = id, item = item, startMs = start)
        }
    }

    /** Für Overlay-Spuren: liegen Elemente übereinander? */
    fun hasOverlaps(): Boolean {
        if (kind.isSequential) return false
        val sorted = items.map { it as TextClip }.sortedBy { it.startMs }
        return sorted.zipWithNext().any { (a, b) -> b.startMs < a.endMs }
    }
}

/** Ein Element mit berechneter Startzeit auf der Timeline. */
data class Placed(val trackId: String, val item: TimelineItem, val startMs: Long) {
    val endMs: Long get() = startMs + item.durationMs
}

@Serializable
sealed interface TimelineItem {
    val id: String

    /** Länge auf der Timeline in ms. */
    val durationMs: Long

    val kindLabel: String
}

/** Bildclip aus importiertem Material. */
@Serializable
@SerialName("video")
data class VideoClip(
    override val id: String = newId("vid"),
    override val durationMs: Long,
    val sourcePath: String,
    val sourceDurationMs: Long,
    val sourceStartMs: Long = 0L,
    val preset: EffectPreset = EffectPreset.NONE,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val rotationDegrees: Int = 0,
) : TimelineItem {
    override val kindLabel: String get() = "Video"

    val sourceEndMs: Long get() = (sourceStartMs + durationMs).coerceAtMost(sourceDurationMs)
}

/** Tonclip: Musik oder Voiceover. */
@Serializable
@SerialName("audio")
data class AudioClip(
    override val id: String = newId("aud"),
    override val durationMs: Long,
    val sourcePath: String,
    val sourceDurationMs: Long,
    val sourceStartMs: Long = 0L,
    val volume: Float = 1f,
    val loop: Boolean = false,
    val label: String = "Audio",
) : TimelineItem {
    override val kindLabel: String get() = label
}

@Serializable
enum class TextPosition { TOP, CENTER, BOTTOM }

@Serializable
enum class TextAlign { LEFT, CENTER, RIGHT }

/** Text-Overlay mit freier Platzierung auf der Timeline. */
@Serializable
@SerialName("text")
data class TextClip(
    override val id: String = newId("txt"),
    val startMs: Long,
    override val durationMs: Long,
    val text: String = "Text",
    val sizeSp: Float = 28f,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val backgroundColorArgb: Int = 0x66000000,
    val position: TextPosition = TextPosition.BOTTOM,
    val align: TextAlign = TextAlign.CENTER,
    val bold: Boolean = false,
    val fadeMs: Long = 300L,
) : TimelineItem {
    override val kindLabel: String get() = "Text"
    val endMs: Long get() = startMs + durationMs

    /** Deckkraft zum Zeitpunkt [tMs] (mit Ein-/Ausblendung). */
    fun alphaAt(tMs: Long): Float {
        val rel = tMs - startMs
        if (rel < 0 || rel > durationMs) return 0f
        if (fadeMs <= 0L) return 1f
        val fadeIn = (rel.toFloat() / fadeMs).coerceIn(0f, 1f)
        val fadeOut = ((durationMs - rel).toFloat() / fadeMs).coerceIn(0f, 1f)
        return minOf(fadeIn, fadeOut)
    }
}

@Serializable
enum class EffectPreset(val label: String) {
    NONE("Original"),
    WARM("Warm"),
    COOL("Kühl"),
    CINEMATIC("Kino"),
    VIVID("Kräftig"),
    MONO("S/W"),
    FADE("Verblasst"),
    INVERT("Invertiert"),
}

/** Exportprofile für 720p/1080p. */
@Serializable
enum class ExportQuality(val label: String, val height: Int, val fps: Int, val bitrate: Int) {
    P720("720p · 30 fps", 720, 30, 6_000_000),
    P1080("1080p · 30 fps", 1080, 30, 12_000_000),
    P1080HQ("1080p · 60 fps hoch", 1080, 60, 20_000_000);

    /** Geschätzte Dateigröße in MB für [durationMs]. */
    fun estimatedSizeMb(durationMs: Long): Double =
        durationMs / 1000.0 * (bitrate + 192_000) / 8.0 / 1024.0 / 1024.0
}

fun newId(prefix: String): String = "${prefix}_" + UUID.randomUUID().toString().take(8)
