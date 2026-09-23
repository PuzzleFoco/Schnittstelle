package com.puzzlefoco.schnittstelle.media

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstantGainProviderTest {

    private val rate = 48_000

    /**
     * Regression: `isUnityUntil` lieferte bei unveränderter Verstärkung `Long.MAX_VALUE`.
     * `GainProcessor` rechnet daraus einen Byte-Offset und castet ihn auf `int` – der Wert
     * lief über, das Buffer-Limit wurde negativ und die Vorschau brach ab
     * (`IllegalArgumentException: newLimit < 0`). Vertrag: Hier gehört `C.TIME_END_OF_SOURCE` hin.
     */
    @Test
    fun `unveraenderte Verstaerkung meldet Einheitsbereich bis Streamende`() {
        val provider = ConstantGainProvider(1f)

        assertEquals(C.TIME_END_OF_SOURCE, provider.isUnityUntil(0L, rate))
        assertEquals(1f, provider.getGainFactorAtSamplePosition(0L, rate), 0f)
        assertNotEquals(Long.MAX_VALUE, provider.isUnityUntil(0L, rate))
    }

    @Test
    fun `abweichende Verstaerkung meldet keine Einheitszone`() {
        val leise = ConstantGainProvider(0.5f)

        assertEquals(C.TIME_UNSET, leise.isUnityUntil(0L, rate))
        assertEquals(0.5f, leise.getGainFactorAtSamplePosition(0L, rate), 0f)
        assertNotEquals(C.TIME_END_OF_SOURCE, leise.isUnityUntil(1_000L, rate))
    }

    @Test
    fun `Verstaerkung wird auf null bis zwei begrenzt`() {
        assertEquals(0f, ConstantGainProvider(-1f).getGainFactorAtSamplePosition(0L, rate), 0f)
        assertEquals(2f, ConstantGainProvider(3f).getGainFactorAtSamplePosition(0L, rate), 0f)
        assertEquals(C.TIME_UNSET, ConstantGainProvider(3f).isUnityUntil(0L, rate))
    }

    /** Der Byte-Offset, den GainProcessor bildet, darf nie negativ werden. */
    @Test
    fun `Einheitsbereich erzeugt keinen negativen Byte-Offset`() {
        val bytesPerFrame = 4
        val readFrames = 12_345L
        val regionEnd = ConstantGainProvider(1f).isUnityUntil(readFrames, rate)

        // Der Zweig wird nur betreten, wenn das Einheitsband nicht bis zum Streamende reicht.
        if (regionEnd != C.TIME_END_OF_SOURCE) {
            val limitOffsetBytes = (regionEnd - readFrames) * bytesPerFrame
            assertTrue("Offset wäre negativ: $limitOffsetBytes", limitOffsetBytes >= 0)
        }
        assertEquals(C.TIME_END_OF_SOURCE, regionEnd)
    }

    @Test
    fun `bei hundert Prozent wird kein GainProcessor angehaengt`() {
        assertTrue(ConstantGainProvider.processorsFor(1f).isEmpty())
        assertEquals(1, ConstantGainProvider.processorsFor(0.5f).size)
        assertEquals(1, ConstantGainProvider.processorsFor(0f).size)
        assertEquals(1, ConstantGainProvider.processorsFor(2f).size)
    }
}
