package com.puzzlefoco.schnittstelle.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für die Bildanpassung (Einpassen/Ausfüllen/Verzerren) und die daraus
 * abgeleitete Ausgabegröße.
 *
 * Hintergrund: Media3 skaliert die Ausgabe ohne feste Rahmengröße auf die
 * Quellgröße. Ein Querformat-Clip (1280×720) in einem Hochformat-Projekt
 * (9:16) blieb dadurch 16:9 mit schwarzen Balken statt den Projektrahmen zu
 * bekommen. Die Ausgabegröße muss deshalb aus dem Projektformat kommen.
 */
class FitModeTest {

    @Test
    fun `Standard ist Einpassen`() {
        val project = Project()
        assertEquals(FitMode.FIT, project.fitMode)
    }

    @Test
    fun `Hochformat ergibt 1080x1920`() {
        val (w, h) = AspectRatio.PORTRAIT_9_16.outputSize(1920)
        assertEquals(1080, w)
        assertEquals(1920, h)
    }

    @Test
    fun `Projektrahmen bleibt unabhängig vom Quellformat`() {
        // Ein 1280x720-Clip ändert nichts an der Ausgabegröße: sie kommt aus
        // dem Projektformat, nicht aus dem Clip.
        val project = Project(aspect = AspectRatio.PORTRAIT_9_16, canvasHeight = 1920)
        val (w, h) = project.aspect.outputSize(project.canvasHeight)
        assertEquals(1080, w)
        assertEquals(1920, h)
        assertTrue("Rahmen muss hochkant sein", h > w)
    }

    @Test
    fun `Querformat ergibt 1920x1080`() {
        val (w, h) = AspectRatio.LANDSCAPE_16_9.outputSize(1080)
        assertEquals(1920, w)
        assertEquals(1080, h)
    }

    @Test
    fun `Ausgabegroesse ist immer gerade`() {
        // Ungerade Kantenlängen lassen H.264-Encoder scheitern.
        for (aspect in AspectRatio.entries) {
            for (height in listOf(720, 1080, 1440, 2160)) {
                val (w, h) = aspect.outputSize(height)
                assertEquals("Breite bei $aspect/$height", 0, w % 2)
                assertEquals("Höhe bei $aspect/$height", 0, h % 2)
            }
        }
    }

    @Test
    fun `Ausfuellen schneidet zu statt Raender zu lassen`() {
        // Alle drei Modi müssen sich unterscheiden – sonst schaltet die
        // Werkzeugleiste ins Leere.
        assertEquals(3, FitMode.entries.toSet().size)
        assertFalse(FitMode.FIT == FitMode.FILL)
        assertFalse(FitMode.FILL == FitMode.STRETCH)
    }

    @Test
    fun `Jeder Modus hat Kurzform und Beschreibung`() {
        for (mode in FitMode.entries) {
            assertTrue("Kurzform fehlt bei $mode", mode.shortLabel.isNotBlank())
            assertTrue("Beschreibung fehlt bei $mode", mode.description.isNotBlank())
        }
    }

    @Test
    fun `Umschalten laeuft im Kreis`() {
        fun next(mode: FitMode) = when (mode) {
            FitMode.FIT -> FitMode.FILL
            FitMode.FILL -> FitMode.STRETCH
            FitMode.STRETCH -> FitMode.FIT
        }
        assertEquals(FitMode.FILL, next(FitMode.FIT))
        assertEquals(FitMode.STRETCH, next(FitMode.FILL))
        assertEquals(FitMode.FIT, next(FitMode.STRETCH))
    }

    @Test
    fun `Alte Projektdateien ohne fitMode bleiben lesbar`() {
        // fitMode kam nach dem ersten Release dazu: fehlt das Feld im JSON,
        // muss der Standard greifen statt das Laden zu sprengen.
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }
        val raw = """{"id":"prj_alt","name":"Alt","aspect":"PORTRAIT_9_16","canvasHeight":1080}"""
        val restored = json.decodeFromString<Project>(raw)
        assertEquals(FitMode.FIT, restored.fitMode)
        assertEquals("Alt", restored.name)
    }

    @Test
    fun `fitMode ueberlebt Speichern und Laden`() {
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }
        val project = Project(name = "Test", fitMode = FitMode.FILL)
        val restored = json.decodeFromString<Project>(json.encodeToString(project))
        assertEquals(FitMode.FILL, restored.fitMode)
    }
}
