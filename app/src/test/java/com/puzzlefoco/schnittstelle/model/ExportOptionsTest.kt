package com.puzzlefoco.schnittstelle.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Exportprofile und Codecs bestimmen Auflösung, Bitrate und Dateigröße.
 * Fehler hier kosten den Nutzer Zeit (falsche Schätzung) oder Qualität
 * (zu niedrige Bitrate), darum sind die Profile festgenagelt.
 */
class ExportOptionsTest {

    @Test
    fun `4K ist enthalten und hat mehr Pixel als 1080p`() {
        assertTrue(ExportQuality.entries.any { it.is4K })
        assertTrue(ExportQuality.UHD4K.height > ExportQuality.P1080.height)
    }

    @Test
    fun `4K hat die höchste Bitrate`() {
        val hoechste = ExportQuality.entries.maxBy { it.bitrate }
        assertTrue(hoechste.is4K)
    }

    @Test
    fun `4K60 hat höhere Bitrate als 4K30`() {
        assertTrue(ExportQuality.UHD4K60.bitrate > ExportQuality.UHD4K.bitrate)
    }

    /** H.264 ist der Codec, der überall laufen muss – er ist der Startwert. */
    @Test
    fun `H264 ist als universell markiert`() {
        assertEquals(1.0, ExportCodec.H264.bitrateFactor, 0.0001)
        assertTrue(ExportCodec.H264.isUniversallySupported)
        assertTrue(ExportCodec.entries.none { it != ExportCodec.H264 && it.isUniversallySupported })
    }

    @Test
    fun `jeder Codec bringt einen eigenen Mime-Typ mit`() {
        val typen = ExportCodec.entries.map { it.mimeType }
        assertEquals(typen.size, typen.distinct().size)
        assertTrue(typen.all { it.startsWith("video/") })
    }

    /** Effizientere Codecs brauchen weniger Bitrate – sonst wäre der Faktor sinnlos. */
    @Test
    fun `sparsamere Codecs haben kleinere Faktoren`() {
        assertTrue(ExportCodec.H265.bitrateFactor < ExportCodec.H264.bitrateFactor)
        assertTrue(ExportCodec.AV1.bitrateFactor < ExportCodec.H265.bitrateFactor)
    }

    @Test
    fun `AV1 schätzt kleinere Datei als H264 bei gleichem Profil`() {
        val dauer = 60_000L
        val h264 = ExportQuality.P1080.estimatedSizeMb(dauer, ExportCodec.H264)
        val av1 = ExportQuality.P1080.estimatedSizeMb(dauer, ExportCodec.AV1)
        assertTrue("AV1 ($av1 MB) muss kleiner sein als H264 ($h264 MB)", av1 < h264)
    }

    @Test
    fun `4K schätzt größere Datei als 1080p bei gleichem Codec`() {
        val dauer = 60_000L
        val klein = ExportQuality.P1080.estimatedSizeMb(dauer, ExportCodec.H264)
        val gross = ExportQuality.UHD4K.estimatedSizeMb(dauer, ExportCodec.H264)
        assertTrue("4K ($gross MB) muss größer sein als 1080p ($klein MB)", gross > klein)
    }

    /** 60 Sekunden 1080p H.264 ≈ 12 Mbit/s + 192 kbit/s Ton ≈ 91 MB. */
    @Test
    fun `Groessenordnung der Schaetzung stimmt`() {
        val mb = ExportQuality.P1080.estimatedSizeMb(60_000L, ExportCodec.H264)
        assertTrue("Erwartet rund 90 MB, war $mb", mb in 80.0..100.0)
    }

    @Test
    fun `leere Timeline schaetzt null`() {
        assertEquals(0.0, ExportQuality.P1080.estimatedSizeMb(0L, ExportCodec.H264), 0.0001)
    }
}
