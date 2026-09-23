package com.puzzlefoco.schnittstelle.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectJsonTest {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    private fun sample(): Project = Project(
        id = "prj_test",
        name = "Urlaub & Meer",
        aspect = AspectRatio.SQUARE_1_1,
        tracks = listOf(
            Track(id = "trk_v", kind = TrackKind.VIDEO, items = listOf(
                VideoClip(
                    id = "v1",
                    durationMs = 4_500,
                    sourcePath = "media/v1.mp4",
                    sourceDurationMs = 12_000,
                    sourceStartMs = 1_200,
                    preset = EffectPreset.CINEMATIC,
                    volume = 0.8f,
                ),
            )),
            Track(id = "trk_a", kind = TrackKind.AUDIO, items = listOf(
                AudioClip(id = "a1", durationMs = 4_500, sourcePath = "media/song.mp3", sourceDurationMs = 180_000, loop = true, label = "Musik"),
            )),
            Track(id = "trk_t", kind = TrackKind.TEXT, items = listOf(
                TextClip(id = "t1", startMs = 500, durationMs = 2_000, text = "Hallo Welt", position = TextPosition.TOP),
            )),
        ),
    )

    @Test
    fun `Projekt ueberlebt Speichern und Laden unveraendert`() {
        val original = sample()
        val text = json.encodeToString(Project.serializer(), original)
        val restored = json.decodeFromString(Project.serializer(), text)

        assertEquals(original, restored)
        assertEquals(EffectPreset.CINEMATIC, (restored.track(TrackKind.VIDEO).items[0] as VideoClip).preset)
        assertEquals("Musik", (restored.track(TrackKind.AUDIO).items[0] as AudioClip).label)
        assertEquals(TextPosition.TOP, (restored.track(TrackKind.TEXT).items[0] as TextClip).position)
    }

    @Test
    fun `elementtypen werden als lesbare Diskriminatoren geschrieben`() {
        val text = json.encodeToString(Project.serializer(), sample())
        assertTrue(text.contains("\"type\": \"video\""))
        assertTrue(text.contains("\"type\": \"audio\""))
        assertTrue(text.contains("\"type\": \"text\""))
    }

    @Test
    fun `unbekannte Felder brechen das Laden nicht ab`() {
        val text = json.encodeToString(Project.serializer(), sample())
            .replaceFirst("{", "{\n  \"zukunftsfeld\": 42,")
        val restored = json.decodeFromString(Project.serializer(), text)
        assertEquals(4_500L, restored.track(TrackKind.VIDEO).items[0].durationMs)
    }

    @Test
    fun `Exportgroessen sind gerade Zahlen im richtigen Seitenverhaeltnis`() {
        assertEquals(1080 to 1920, AspectRatio.PORTRAIT_9_16.outputSize(1920))
        assertEquals(1920 to 1080, AspectRatio.LANDSCAPE_16_9.outputSize(1080))
        assertEquals(1080 to 1080, AspectRatio.SQUARE_1_1.outputSize(1080))
        // 21:9 bei Höhe 1000 → 2333,3 → auf gerade Zahl aufgerundet
        val (w, h) = AspectRatio.CINEMA_21_9.outputSize(1_000)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
        assertTrue(w > h)
    }

    @Test
    fun `Bitratenschaetzung ist plausibel`() {
        // 60 s bei 12 Mbit/s Video + 192 kbit/s Audio ≈ 91 MB
        val mb = ExportQuality.P1080.estimatedSizeMb(60_000)
        assertTrue("erwartet ~91 MB, war $mb", mb in 85.0..97.0)
    }
}
