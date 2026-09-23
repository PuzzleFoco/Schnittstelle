package com.puzzlefoco.schnittstelle.model

import androidx.media3.common.MimeTypes
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
    val fitMode: FitMode = FitMode.FIT,
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

/**
 * Wie ein Clip in den Projektrahmen eingepasst wird.
 *
 * Wichtig: Ohne feste Vorgabe skaliert Media3 die Ausgabe auf die **Quellgröße**
 * (ein 16:9-Clip in einem 9:16-Projekt bleibt damit 16:9 mit schwarzen Balken).
 * Deshalb wird beim Bauen der Komposition die feste Rahmengröße mitgegeben.
 */
@Serializable
enum class FitMode(val label: String) {
    /** Ganzer Clip sichtbar, notfalls mit schwarzen Rändern. */
    FIT("Einpassen (Ränder)"),

    /** Rahmen komplett gefüllt, dafür wird seitlich/oben beschnitten. */
    FILL("Ausfüllen (Zuschnitt)"),

    /** Rahmen komplett gefüllt, Bild wird verzerrt. */
    STRETCH("Verzerren (füllt)");

    val description: String
        get() = when (this) {
            FIT -> "Nichts geht verloren, es können schwarze Ränder bleiben."
            FILL -> "Bildschirm komplett gefüllt, Ränder werden weggeschnitten."
            STRETCH -> "Bildschirm komplett gefüllt, das Bild wird in die Länge gezogen."
        }

    /** Kurzform für die Werkzeugleiste. */
    val shortLabel: String
        get() = when (this) {
            FIT -> "Einpassen"
            FILL -> "Ausfüllen"
            STRETCH -> "Verzerren"
        }
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

    /**
     * Endposition in der Quelldatei für Media3.
     *
     * Wichtig: Die gespeicherte [sourceDurationMs] kommt aus einer Abfrage in
     * ganzen Millisekunden und kann **kürzer** sein als die Dauer, die Media3
     * selbst aus der Datei liest (z. B. Datei 30052,300 ms → gespeichert
     * 30052 ms). Media3 prüft in `EditedMediaItem.getClippedDuration`
     * `endPositionUs <= durationUs` und wirft sonst eine
     * `IllegalArgumentException` – die gesamte Vorschau bleibt dann schwarz.
     *
     * Deshalb liegt das Ende immer um [END_MARGIN_MS] unter der gespeicherten
     * Quelldauer. Der Abstand ist eine gute Zehntelsekunde und damit in der
     * Vorschau nicht sichtbar, verhindert aber den harten Abbruch.
     */
    val sourceEndMs: Long
        get() {
            // Nur das ECHTE Dateiende braucht den Abstand. Wird ein Clip ohnehin weit vor
            // dem Ende geschnitten (Normalfall), darf nichts abgezogen werden: Ein
            // pauschaler Abzug kürzt jeden Clip um 100 ms, die Sequenz rechnet aber mit
            // der vollen Länge — an der Clip-Grenze entsteht dann eine Lücke, die Media3
            // mit "blank frames" überbrückt und die Wiedergabe für Sekunden anhält.
            val hartesEnde = sourceDurationMs
            val maxEnd = (hartesEnde - END_MARGIN_MS).coerceAtLeast(0L)
            val start = sourceStartMs.coerceIn(0L, maxEnd)
            val gewuenscht = start + durationMs
            // Solange der gewünschte Schnitt nicht am Dateiende klebt, exakt schneiden.
            return if (gewuenscht < hartesEnde) gewuenscht else maxEnd
        }

    companion object {
        /**
         * Sicherheitsabstand zum Dateiende in Millisekunden.
         *
         * Deckt die Rundung der gespeicherten Dauer ab und hält Abstand zu
         * einem möglicherweise später liegenden echten Dateiende.
         */
        const val END_MARGIN_MS = 100L
    }
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

/** Video-Codecs, die der Export anbieten kann. */
@Serializable
enum class ExportCodec(val label: String, val mimeType: String, val fileSuffix: String) {
    H264("H.264 (kompatibel)", MimeTypes.VIDEO_H264, "h264"),
    H265("H.265 / HEVC (kleiner)", MimeTypes.VIDEO_H265, "h265"),
    VP9("VP9 (offen)", MimeTypes.VIDEO_VP9, "vp9"),
    AV1("AV1 (modern)", MimeTypes.VIDEO_AV1, "av1");

    /** HEVC und AV1 kodieren effizienter, brauchen also weniger Bitrate als H.264. */
    val bitrateFactor: Double
        get() = when (this) {
            H264 -> 1.0
            H265 -> 0.65
            VP9 -> 0.8
            AV1 -> 0.55
        }

    /** Nicht jedes Gerät hat für jeden Codec einen Encoder – lässt sich nicht vorab wissen. */
    val isUniversallySupported: Boolean get() = this == H264
}

/** Exportprofile für 720p bis 4K. */
@Serializable
enum class ExportQuality(val label: String, val height: Int, val fps: Int, val bitrate: Int) {
    P720("720p · 30 fps", 720, 30, 6_000_000),
    P1080("1080p · 30 fps", 1080, 30, 12_000_000),
    P1080HQ("1080p · 60 fps hoch", 1080, 60, 20_000_000),
    UHD4K("4K · 30 fps", 2160, 30, 45_000_000),
    UHD4K60("4K · 60 fps hoch", 2160, 60, 70_000_000);

    val is4K: Boolean get() = height >= 2160

    /** Geschätzte Dateigröße in MB für [durationMs] und den gewählten [codec]. */
    fun estimatedSizeMb(durationMs: Long, codec: ExportCodec = ExportCodec.H264): Double {
        val videoBitrate = bitrate * codec.bitrateFactor
        return durationMs / 1000.0 * (videoBitrate + AUDIO_BITRATE) / 8.0 / 1024.0 / 1024.0
    }

    private companion object {
        const val AUDIO_BITRATE = 192_000.0
    }
}

fun newId(prefix: String): String = "${prefix}_" + UUID.randomUUID().toString().take(8)
