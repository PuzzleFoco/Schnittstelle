package com.puzzlefoco.schnittstelle.media

import androidx.media3.common.C
import androidx.media3.common.audio.GainProcessor

/**
 * Feste Verstärkung (0–2) für eine Audiospur, damit Vorschau und Export gleich klingen.
 *
 * Wichtig: `isUnityUntil` muss den Vertrag von [GainProcessor.GainProvider] exakt erfüllen.
 * Bei „Verstärkung ist 1" (unverändert) **muss** [C.TIME_END_OF_SOURCE] zurückkommen und bei
 * abweichender Verstärkung [C.TIME_UNSET] – nicht etwa ein Positionswert oder `Long.MAX_VALUE`.
 * `GainProcessor` rechnet aus dem Rückgabewert einen Byte-Offset und castet ihn auf `int`:
 * ein anderer großer Wert läuft dabei über, das Buffer-Limit wird negativ und die Wiedergabe
 * bricht mit `IllegalArgumentException: newLimit < 0` ab (getroffen: Lautstärke = 100 %).
 */
internal class ConstantGainProvider(gain: Float) : GainProcessor.GainProvider {

    /** Auf den zulässigen Bereich begrenzt; negative Werte würden die Phase umkehren. */
    private val applied: Float = gain.coerceIn(MIN_GAIN, MAX_GAIN)

    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float = applied

    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long =
        if (applied == 1f) {
            C.TIME_END_OF_SOURCE // durchgehend 100 % → Einheitsbereich bis Streamende
        } else {
            C.TIME_UNSET // hier ist die Verstärkung nicht 1 → keine Einheitszone
        }

    companion object {
        const val MIN_GAIN = 0f
        const val MAX_GAIN = 2f

        /** Ohne Verstärkungsänderung ist ein `GainProcessor` reine Arbeit ohne Wirkung. */
        fun processorsFor(volume: Float): List<GainProcessor> =
            if (volume.coerceIn(MIN_GAIN, MAX_GAIN) == 1f) {
                emptyList()
            } else {
                listOf(GainProcessor(ConstantGainProvider(volume)))
            }
    }
}
