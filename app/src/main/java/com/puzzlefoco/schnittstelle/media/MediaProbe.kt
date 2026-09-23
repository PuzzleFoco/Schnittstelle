package com.puzzlefoco.schnittstelle.media

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File

/** Metadaten eines importierten Mediums. */
data class MediaInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
) {
    val aspect: Float get() = if (height == 0) 0f else width.toFloat() / height
}

object MediaProbe {

    fun probe(context: Context, file: File): MediaInfo {
        if (!file.exists()) return MediaInfo(0, 0, 0, 0, false, false)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, android.net.Uri.fromFile(file))
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null ||
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE) == null
            MediaInfo(
                durationMs = duration,
                width = width,
                height = height,
                rotationDegrees = rotation,
                hasVideo = width > 0 && height > 0,
                hasAudio = hasAudio,
            )
        } finally {
            retriever.release()
        }
    }

    /** Anzeigedauer als „1:23" bzw. „1:02:03". */
    fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        val ms3 = (ms % 1000) / 100
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d.%d", m, s, ms3)
    }
}
