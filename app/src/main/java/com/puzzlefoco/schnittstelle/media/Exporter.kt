package com.puzzlefoco.schnittstelle.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.puzzlefoco.schnittstelle.model.ExportQuality
import java.io.File

/**
 * Exportiert eine [Composition] als MP4 und legt das Ergebnis in der Galerie ab
 * (MediaStore, Ordner „Movies/Schnittstelle\"). Kein Speicher-Sonderrecht nötig, da die
 * Datei über MediaStore geschrieben wird.
 */
class Exporter(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data class Progress(val percent: Int) : State
        data class Success(val uri: Uri, val file: File, val sizeBytes: Long, val durationMs: Long) : State
        data class Failure(val message: String) : State
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var progress: ProgressHolder? = null
    private var poller: Runnable? = null

    val isRunning: Boolean get() = transformer != null

    fun export(
        composition: Composition,
        quality: ExportQuality,
        displayName: String,
        onState: (State) -> Unit,
    ) {
        cancel()
        val outFile = File(context.cacheDir, "schnittstelle-export-${System.currentTimeMillis()}.mp4")

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(quality.bitrate).build()
            )
            .setRequestedAudioEncoderSettings(
                AudioEncoderSettings.Builder().setBitrate(AUDIO_BITRATE).build()
            )
            .build()

        val listener = object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                stopPolling()
                transformer = null
                val published = runCatching { publishToGallery(outFile, displayName) }.getOrNull()
                onState(
                    State.Success(
                        uri = published ?: Uri.fromFile(outFile),
                        file = outFile,
                        sizeBytes = exportResult.fileSizeBytes.takeIf { it > 0 } ?: outFile.length(),
                        durationMs = exportResult.approximateDurationMs,
                    )
                )
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException,
            ) {
                stopPolling()
                transformer = null
                outFile.delete()
                onState(State.Failure(describe(exportException)))
            }
        }

        val t = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(listener)
            .build()

        transformer = t
        onState(State.Progress(0))
        t.start(composition, outFile.absolutePath)
        startPolling(t, onState)
    }

    fun cancel() {
        stopPolling()
        transformer?.cancel()
        transformer = null
    }

    // ------------------------------------------------------------------ intern

    private fun startPolling(t: Transformer, onState: (State) -> Unit) {
        val holder = ProgressHolder().also { progress = it }
        val runnable = object : Runnable {
            private var lastPercent = -1
            override fun run() {
                val state = t.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE && holder.progress != lastPercent) {
                    lastPercent = holder.progress
                    onState(State.Progress(holder.progress))
                }
                if (transformer === t) {
                    mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
                }
            }
        }
        poller = runnable
        mainHandler.postDelayed(runnable, PROGRESS_INTERVAL_MS)
    }

    private fun stopPolling() {
        poller?.let { mainHandler.removeCallbacks(it) }
        poller = null
        progress = null
    }

    /** Legt die fertige Datei in der Galerie ab und gibt deren Uri zurück. */
    private fun publishToGallery(source: File, displayName: String): Uri {
        val name = if (displayName.endsWith(".mp4", ignoreCase = true)) displayName else "$displayName.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Schnittstelle")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return Uri.fromFile(source)
            resolver.openOutputStream(uri, "w")?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return Uri.fromFile(source)
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            // Ältere Systeme: App-eigener Ordner, kein Speicher-Sonderrecht nötig.
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "Schnittstelle")
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, name)
            source.copyTo(target, overwrite = true)
            Uri.fromFile(target)
        }
    }

    private fun describe(exception: ExportException): String {
        val codeName = runCatching { ExportException.getErrorCodeName(exception.errorCode) }.getOrNull()
        val detail = exception.message ?: exception.cause?.message ?: "unbekannter Fehler"
        return if (codeName.isNullOrBlank()) detail else "$codeName – $detail"
    }

    private companion object {
        const val AUDIO_BITRATE = 192_000
        const val PROGRESS_INTERVAL_MS = 400L
        @Suppress("unused")
        val unusedClock = SystemClock::class
    }
}
