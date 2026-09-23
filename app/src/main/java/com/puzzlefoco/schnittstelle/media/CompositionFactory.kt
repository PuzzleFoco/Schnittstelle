package com.puzzlefoco.schnittstelle.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.puzzlefoco.schnittstelle.model.AudioClip
import com.puzzlefoco.schnittstelle.model.FitMode
import com.puzzlefoco.schnittstelle.model.Project
import com.puzzlefoco.schnittstelle.model.TextClip
import com.puzzlefoco.schnittstelle.model.TrackKind
import com.puzzlefoco.schnittstelle.model.VideoClip
import java.io.File

/**
 * Übersetzt das Projektmodell in eine Media3-[Composition].
 *
 * Dieselbe Composition wird für Vorschau (CompositionPlayer) und Export (Transformer)
 * verwendet. Struktur – von CompositionPlayer gefordert/begrenzt:
 *   Sequenz 1: Videospur (alle Bildclips hintereinander, Presets pro Clip)
 *   Sequenz 2: Audiospur (Musik/Voiceover, per GainProcessor in der Lautstärke regelbar)
 *   Kompositions-Effekte: Text-Overlays + Skalierung auf die Zielauflösung
 */
object CompositionFactory {

    fun build(
        context: Context,
        project: Project,
        mediaRoot: File,
        targetHeight: Int = project.canvasHeight,
        fps: Int = 30,
    ): Composition {
        val sequences = mutableListOf<EditedMediaItemSequence>()
        // Feste Ausgabegröße aus dem Projektformat. Ohne sie skaliert Media3 die Ausgabe
        // auf die Quellgröße – ein 16:9-Clip in einem 9:16-Projekt bliebe dann 16:9 mit
        // schwarzen Balken, statt den Hochformat-Rahmen zu bekommen.
        val (frameWidth, frameHeight) = project.aspect.outputSize(targetHeight)

        val videoClips = project.track(TrackKind.VIDEO).layout().mapNotNull { placed ->
            val clip = placed.item as? VideoClip ?: return@mapNotNull null
            val file = File(mediaRoot, clip.sourcePath)
            if (!file.exists()) return@mapNotNull null
            clip to buildVideoItem(context, clip, file, fps)
        }
        if (videoClips.isNotEmpty()) {
            // Die Spurtypen bestimmen, welche Spuren der Clips übernommen werden. Enthält
            // die Sequenz AUDIO, bekommen Clips ohne eigene Tonspur automatisch eine
            // stille Spur (Media3-Doku zu EditedMediaItemSequence).
            val clipAudio = videoClips.any { (clip, _) -> !clip.muted }
            val trackTypes =
                if (clipAudio) setOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)
                else setOf(C.TRACK_TYPE_VIDEO)
            val builder = EditedMediaItemSequence.Builder(trackTypes)
            videoClips.forEach { (_, item) -> builder.addItem(item) }
            sequences.add(builder.build())
        }

        val audioItems = project.track(TrackKind.AUDIO).items.filterIsInstance<AudioClip>()
            .filter { File(mediaRoot, it.sourcePath).exists() }
        if (audioItems.isNotEmpty()) {
            val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            audioItems.forEach { clip ->
                builder.addItem(
                    EditedMediaItem.Builder(
                        MediaItem.Builder().setUri(Uri.fromFile(File(mediaRoot, clip.sourcePath))).build()
                    )
                        .setRemoveVideo(true)
                        .setDurationUs(clip.durationMs * 1000L)
                        .setEffects(Effects(ConstantGainProvider.processorsFor(clip.volume), emptyList()))
                        .build()
                )
            }
            // Endlosschleife nur bei genau einem Clip (z. B. Hintergrundmusik)
            if (audioItems.size == 1 && audioItems.first().loop) {
                builder.setIsLooping(true)
            }
            sequences.add(builder.build())
        }

        if (sequences.isEmpty()) {
            throw IllegalStateException("Projekt enthält kein abspielbares Material")
        }

        val textClipsProvider: () -> List<TextClip> = {
            project.track(TrackKind.TEXT).items.filterIsInstance<TextClip>()
        }
        val compositionEffects = mutableListOf<Effect>()
        if (textClipsProvider().isNotEmpty()) {
            compositionEffects.add(
                OverlayEffect(listOf(TimelineTextOverlay(textClipsProvider)))
            )
        }
        compositionEffects.add(
            Presentation.createForWidthAndHeight(
                frameWidth,
                frameHeight,
                when (project.fitMode) {
                    FitMode.FIT -> Presentation.LAYOUT_SCALE_TO_FIT
                    FitMode.FILL -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                    FitMode.STRETCH -> Presentation.LAYOUT_STRETCH_TO_FIT
                },
            )
        )

        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), compositionEffects))
            .build()
    }

    private fun buildVideoItem(context: Context, clip: VideoClip, file: File, fps: Int): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.sourceStartMs)
                    .setEndPositionMs(clip.sourceEndMs)
                    .build()
            )
            .build()

        // Stumm heißt NICHT "Tonspur entfernen": Bei mehreren Clips in einer Sequenz, die
        // AUDIO als Spurtyp führt, erwartet Media3 in jedem Item eine Tonspur. Fehlt sie,
        // wird sie zwar automatisch als stille Spur erzeugt, aber `setRemoveAudio(true)`
        // erklärte den Clip zum reinen Video-Item – die Wiedergabe endete dann nach dem
        // ersten Clip. Stattdessen wird die Spur behalten und über die Verstärkung auf 0
        // gesetzt; das klingt identisch stumm, lässt die Sequenz aber weiterlaufen.
        val audioProcessors: List<AudioProcessor> = ConstantGainProvider.processorsFor(
            if (clip.muted) 0f else clip.volume
        )

        // Wichtig: setDurationUs muss die Laenge AB DATEIANFANG sein, nicht die
        // Clip-Laenge. Media3 vergleicht in getPresentationDurationUs() die
        // Clipping-Endposition (sourceEndMs) gegen genau diesen Wert und wirft
        // sonst IllegalArgumentException — die Vorschau bleibt komplett schwarz.
        // Das betrifft jeden Clip, der nicht bei 0 beginnt (Start-Verschiebung).
        return EditedMediaItem.Builder(mediaItem)
            .setDurationUs(clip.sourceEndMs * 1000L)
            .setFrameRate(fps)
            .setEffects(Effects(audioProcessors, EffectPresets.effectsFor(clip.preset)))
            .build()
    }


    /** Overlay-Standardeinstellungen (Vollbild-Overlay, Deckkraft bereits im Overlay gebacken). */
    val defaultOverlaySettings: StaticOverlaySettings = StaticOverlaySettings.Builder().build()
}
