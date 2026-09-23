package com.puzzlefoco.schnittstelle.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineOpsTest {

    private fun videoTrack(vararg durations: Long): Track {
        var track = Track(id = "trk_video", kind = TrackKind.VIDEO)
        durations.forEachIndexed { i, d ->
            track = TimelineOps.append(
                track,
                VideoClip(
                    id = "vid_$i",
                    durationMs = d,
                    sourcePath = "media/$i.mp4",
                    sourceDurationMs = 60_000,
                    sourceStartMs = 0,
                ),
            )
        }
        return track
    }

    // ------------------------------------------------------------- Layout

    @Test
    fun `sequenzielle Spur legt Elemente lueckenlos hintereinander`() {
        val track = videoTrack(1_000, 2_000, 500)
        val layout = track.layout()

        assertEquals(listOf(0L, 1_000L, 3_000L), layout.map { it.startMs })
        assertEquals(3_500L, track.endMs)
    }

    @Test
    fun `Overlay-Spur behaelt freie Startzeiten`() {
        val track = Track(
            id = "trk_text",
            kind = TrackKind.TEXT,
            items = listOf(
                TextClip(id = "t1", startMs = 2_000, durationMs = 1_000),
                TextClip(id = "t2", startMs = 500, durationMs = 2_000),
            ),
        )
        val layout = track.layout().associateBy { it.item.id }

        assertEquals(2_000L, layout.getValue("t1").startMs)
        assertEquals(500L, layout.getValue("t2").startMs)
        assertTrue(track.hasOverlaps())
    }

    // ------------------------------------------------------------- Schneiden

    @Test
    fun `split teilt Clip und verschiebt Quell-In-Punkt korrekt`() {
        var track = videoTrack(10_000)
        // Quellmaterial bei 5 s antrimmen
        track = TimelineOps.trim(track, "vid_0", 5_000, 4_000)
        // 1,5 s nach Clip-Beginn schneiden
        track = TimelineOps.split(track, "vid_0", 1_500)

        assertEquals(2, track.items.size)
        val left = track.items[0] as VideoClip
        val right = track.items[1] as VideoClip

        assertEquals(1_500L, left.durationMs)
        assertEquals(5_000L, left.sourceStartMs)
        assertEquals(2_500L, right.durationMs)
        assertEquals(6_500L, right.sourceStartMs)   // 5 s Trim + 1,5 s Schnitt
        assertEquals(9_000L, right.sourceEndMs)     // Ende unverändert (5 s + 4 s)
        assertNotEquals(left.id, right.id)
    }

    @Test
    fun `split ignoriert Schnitte die zu kurze Teile erzeugen wuerden`() {
        val track = videoTrack(1_000)
        assertSame(track.items, TimelineOps.split(track, "vid_0", 50).items)
        assertSame(
            track.items,
            TimelineOps.split(track, "vid_0", 1_000 - 50).items,
        )
        assertEquals(2, TimelineOps.split(track, "vid_0", 500).items.size)
    }

    @Test
    fun `split am Playhead trifft das richtige Element`() {
        val project = Project(id = "p", name = "t", tracks = listOf(
            videoTrack(2_000, 3_000),
            Track(id = "trk_audio", kind = TrackKind.AUDIO),
            Track(id = "trk_text", kind = TrackKind.TEXT),
        ))
        // Playhead bei 3 s liegt im zweiten Clip (2 s–5 s), 1 s nach dessen Start
        val result = TimelineOps.splitAtPlayhead(project, project.track(TrackKind.VIDEO), 3_000)
        val items = result.track(TrackKind.VIDEO).items

        assertEquals(3, items.size)
        assertEquals(listOf(2_000L, 1_000L, 2_000L), items.map { it.durationMs })
    }

    // ------------------------------------------------------------- Trimmen

    @Test
    fun `trim begrenzt auf Quelllaenge`() {
        var track = Track(
            id = "trk_audio",
            kind = TrackKind.AUDIO,
            items = listOf(AudioClip(id = "a1", durationMs = 3_000, sourcePath = "m.mp3", sourceDurationMs = 3_000)),
        )
        track = TimelineOps.trim(track, "a1", newSourceStartMs = 2_500, newDurationMs = 5_000)
        val clip = track.items[0] as AudioClip

        assertEquals(2_500L, clip.sourceStartMs)
        assertEquals(500L, clip.durationMs) // nur 500 ms Material übrig → nie länger
    }

    @Test
    fun `trim bei Schleifen-Audio erlaubt beliebige Laenge`() {
        var track = Track(
            id = "trk_audio",
            kind = TrackKind.AUDIO,
            items = listOf(AudioClip(id = "a1", durationMs = 3_000, sourcePath = "m.mp3", sourceDurationMs = 3_000, loop = true)),
        )
        track = TimelineOps.trim(track, "a1", newSourceStartMs = 0, newDurationMs = 60_000)
        assertEquals(60_000L, (track.items[0] as AudioClip).durationMs)
    }

    // ------------------------------------------------------------- Verschieben

    @Test
    fun `move tauscht die Reihenfolge`() {
        var track = videoTrack(1_000, 2_000, 3_000)
        track = TimelineOps.move(track, "vid_2", -1)
        assertEquals(listOf("vid_0", "vid_2", "vid_1"), track.items.map { it.id })

        // Grenzen werden eingehalten
        assertEquals(listOf("vid_0", "vid_2", "vid_1"), TimelineOps.move(track, "vid_0", -1).items.map { it.id })
        assertEquals(listOf("vid_0", "vid_2", "vid_1"), TimelineOps.move(track, "vid_1", 5).items.map { it.id })
    }

    @Test
    fun `shiftInTime bewegt nur Overlays und nie ins Negative`() {
        val text = Track(id = "trk_text", kind = TrackKind.TEXT, items = listOf(
            TextClip(id = "t1", startMs = 1_000, durationMs = 500),
        ))
        assertEquals(1_400L, (TimelineOps.shiftInTime(text, "t1", 400).items[0] as TextClip).startMs)
        assertEquals(0L, (TimelineOps.shiftInTime(text, "t1", -5_000).items[0] as TextClip).startMs)

        val video = videoTrack(1_000)
        assertSame(video.items, TimelineOps.shiftInTime(video, "vid_0", 500).items)
    }

    // ------------------------------------------------------------- Löschen / Kopieren

    @Test
    fun `remove und duplicate auf Projektebene`() {
        val project = Project(id = "p", name = "t", tracks = listOf(
            videoTrack(1_000, 2_000),
            Track(id = "trk_audio", kind = TrackKind.AUDIO),
            Track(id = "trk_text", kind = TrackKind.TEXT),
        ))

        val removed = TimelineOps.removeItem(project, "vid_0")
        assertEquals(listOf("vid_1"), removed.track(TrackKind.VIDEO).items.map { it.id })
        assertEquals(2_000L, removed.durationMs)

        val duplicated = TimelineOps.duplicateItem(project, "vid_1")
        assertEquals(3, duplicated.track(TrackKind.VIDEO).items.size)
        assertEquals(5_000L, duplicated.durationMs)
    }

    // ------------------------------------------------------------- Normalisierung

    @Test
    fun `normalize korrigiert ungueltige Werte`() {
        val project = Project(id = "p", name = "t", tracks = listOf(
            Track(id = "trk_video", kind = TrackKind.VIDEO, items = listOf(
                VideoClip(id = "v", durationMs = 99_000, sourcePath = "m.mp4", sourceDurationMs = 4_000, volume = 5f),
            )),
            Track(id = "trk_audio", kind = TrackKind.AUDIO),
            Track(id = "trk_text", kind = TrackKind.TEXT, items = listOf(
                TextClip(id = "t", startMs = -500, durationMs = 0, fadeMs = 9_000),
                TextClip(id = "leer", startMs = 0, durationMs = 1_000, text = ""),
            )),
        ))

        val normalized = TimelineOps.normalize(project)
        val video = normalized.track(TrackKind.VIDEO).items.single() as VideoClip
        assertEquals(4_000L, video.durationMs)
        assertEquals(2f, video.volume, 0.0001f)

        val texts = normalized.track(TrackKind.TEXT).items
        assertEquals(1, texts.size)                     // leerer Text entfällt
        val text = texts.single() as TextClip
        assertEquals(0L, text.startMs)
        assertEquals(TimelineOps.MIN_CLIP_MS, text.durationMs)
        assertEquals(50L, text.fadeMs)
    }

    @Test
    fun `alphaAt blendet ein und aus`() {
        val clip = TextClip(id = "t", startMs = 1_000, durationMs = 2_000, fadeMs = 500)

        assertEquals(0f, clip.alphaAt(1_000))
        assertEquals(0.5f, clip.alphaAt(1_250), 0.001f)
        assertEquals(1f, clip.alphaAt(2_000), 0.001f)
        assertEquals(0.5f, clip.alphaAt(2_750), 0.001f)
        assertEquals(0f, clip.alphaAt(3_000))
        assertEquals(0f, clip.alphaAt(500))
    }

    @Test
    fun `Laenge des Projekts ist die laengste Spur`() {
        val project = Project(id = "p", name = "t", tracks = listOf(
            videoTrack(2_000),
            Track(id = "trk_audio", kind = TrackKind.AUDIO, items = listOf(
                AudioClip(id = "a", durationMs = 9_000, sourcePath = "m.mp3", sourceDurationMs = 9_000),
            )),
            Track(id = "trk_text", kind = TrackKind.TEXT, items = listOf(
                TextClip(id = "t", startMs = 5_000, durationMs = 1_000),
            )),
        ))
        assertEquals(9_000L, project.durationMs)
    }
}
