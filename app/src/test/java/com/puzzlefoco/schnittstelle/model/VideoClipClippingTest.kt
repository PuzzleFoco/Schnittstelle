package com.puzzlefoco.schnittstelle.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sichert die Regel ab, deren Verletzung die Vorschau komplett schwarz machte:
 *
 * Media3 prüft beim Aufbau der Composition
 * `endPositionUs <= setDurationUs()` in `EditedMediaItem.getClippedDuration`.
 * Die Clipping-Endposition ist [VideoClip.sourceEndMs]; als Dauer wird
 * derselbe Wert übergeben. Wurde stattdessen die Clip-Länge gesetzt und begann
 * der Clip nicht bei 0, schlug die Prüfung fehl und Media3 brach mit
 * `IllegalArgumentException` ab — die ganze Vorschau blieb schwarz.
 *
 * [VideoClip.sourceEndMs] hält zusätzlich [VideoClip.END_MARGIN_MS] Abstand zur
 * Quelldauer, weil die gespeicherte Dauer in ganzen Millisekunden gerundet ist
 * und minimal unter der echten Dateidauer liegen kann.
 */
class VideoClipClippingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun clip(
        startMs: Long,
        durationMs: Long,
        sourceDurationMs: Long = 30_052,
    ) = VideoClip(
        id = "test",
        sourcePath = "test.mp4",
        durationMs = durationMs,
        sourceDurationMs = sourceDurationMs,
        sourceStartMs = startMs,
    )

    @Test
    fun `Clip ohne Verschiebung endet knapp vor der Quelldauer`() {
        // Clip deckt die ganze Datei ab -> nur hier greift der Sicherheitsabstand.
        val c = clip(startMs = 0, durationMs = 12_000, sourceDurationMs = 12_000)
        assertEquals(12_000 - VideoClip.END_MARGIN_MS, c.sourceEndMs)
    }

    @Test
    fun `Clip mit Verschiebung endet knapp vor der Quelldauer`() {
        // Der reale Fall: 28,465 s Clip, der bei 1,587 s beginnt und am Dateiende endet.
        val c = clip(startMs = 1_587, durationMs = 28_465)
        assertEquals(30_052 - VideoClip.END_MARGIN_MS, c.sourceEndMs)
    }

    @Test
    fun `Endposition bleibt immer unter der Quelldauer`() {
        // Genau die Zusicherung, die Media3 verlangt.
        val c = clip(startMs = 1_587, durationMs = 28_465)
        assertTrue("Ende muss unter der Quelldauer liegen", c.sourceEndMs < c.sourceDurationMs)
    }

    @Test
    fun `ohne Verschiebung ist die Endposition gleich der Clip-Laenge`() {
        // Bei Start 0 entspricht das Ende der Clip-Länge — nur so ging der
        // Testclip durch, obwohl der Fehler schon vorhanden war.
        val c = clip(startMs = 0, durationMs = 5_000, sourceDurationMs = 60_000)
        assertEquals(5_000L, c.sourceEndMs)
    }

    @Test
    fun `Schnitt weit vor dem Dateiende wird nicht verkuerzt`() {
        // Regression: Ein pauschaler Sicherheitsabstand kürzte JEDEN Clip um
        // END_MARGIN_MS. Bei mehreren Clips in einer Sequenz summiert sich das
        // zu Lücken an den Clip-Grenzen, die Media3 mit "blank frames"
        // überbrückt — die Vorschau blieb dort sekundenlang stehen.
        val c = clip(startMs = 0, durationMs = 9_164, sourceDurationMs = 9_164)
        // Dateiende-Fall: Abstand greift.
        assertEquals(9_164 - VideoClip.END_MARGIN_MS, c.sourceEndMs)

        val weitDavor = clip(startMs = 0, durationMs = 5_000, sourceDurationMs = 60_000)
        assertEquals("Schnitt weit vor dem Ende bleibt exakt", 5_000L, weitDavor.sourceEndMs)
    }

    @Test
    fun `mehrere Clips behalten ihre volle Laenge`() {
        // Drei Clips wie im realen Projekt: nur der letzte endet am Dateiende.
        val clips = listOf(
            clip(startMs = 0, durationMs = 5_000, sourceDurationMs = 60_000),
            clip(startMs = 0, durationMs = 9_164, sourceDurationMs = 9_164),
            clip(startMs = 0, durationMs = 12_000, sourceDurationMs = 12_000),
        )
        val gespielt = clips.sumOf { c ->
            val start = c.sourceStartMs.coerceIn(0L, c.sourceDurationMs)
            c.sourceEndMs - start
        }
        // Clip 1 bleibt voll, die beiden Dateiende-Clips verlieren je den Abstand.
        assertEquals(5_000L + (9_164 - VideoClip.END_MARGIN_MS) + (12_000 - VideoClip.END_MARGIN_MS), gespielt)
    }

    @Test
    fun `mit Verschiebung ist die Endposition groesser als die Clip-Laenge`() {
        // Der Fehlerfall: Endposition > als die Clip-Länge, die früher als
        // setDurationUs übergeben wurde.
        val c = clip(startMs = 1_587, durationMs = 28_465)
        assertTrue(
            "Endposition muss über der reinen Clip-Länge liegen",
            c.sourceEndMs > c.durationMs,
        )
    }

    @Test
    fun `Endposition wird auf die Quelldauer begrenzt`() {
        // Kaputte oder zu großzügige Werte dürfen nicht nach oben durchschlagen.
        val c = clip(startMs = 500, durationMs = 99_999, sourceDurationMs = 10_000)
        assertTrue("Ende darf Quelldauer nicht überschreiten", c.sourceEndMs <= 10_000)
    }

    @Test
    fun `Start hinter der Quelldauer liefert kein negatives Fenster`() {
        // Wirksamer Start wird geklemmt; das Fenster darf nie negativ werden.
        val c = clip(startMs = 99_999, durationMs = 1_000, sourceDurationMs = 10_000)
        val wirksam = c.sourceStartMs.coerceIn(0L, c.sourceDurationMs - VideoClip.END_MARGIN_MS)
        assertTrue("Ende darf nicht vor dem wirksamen Start liegen", c.sourceEndMs >= wirksam)
        assertTrue("Ende muss im gültigen Bereich liegen", c.sourceEndMs >= 0L)
    }

    @Test
    fun `alte Projektdatei ohne neue Felder laedt weiter`() {
        // Abwärtskompatibilität: das früher fehlende Feld hat einen Default.
        val alt = """{"id":"a","sourcePath":"x.mp4","durationMs":5000,"sourceDurationMs":5000}"""
        val c = json.decodeFromString<VideoClip>(alt)
        assertEquals(5_000L, c.durationMs)
        assertEquals(0L, c.sourceStartMs)
    }
}
