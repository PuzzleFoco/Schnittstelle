package com.puzzlefoco.schnittstelle.media

import androidx.media3.common.Effect
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import com.puzzlefoco.schnittstelle.model.EffectPreset

/**
 * Bildlook-Presets als Media3-GL-Effektketten.
 *
 * Wichtig: dieselbe Kette wird für Vorschau (CompositionPlayer) und Export
 * (Transformer) verwendet, damit die Vorschau dem Export entspricht.
 */
object EffectPresets {

    fun effectsFor(preset: EffectPreset): List<Effect> = when (preset) {
        EffectPreset.NONE -> emptyList()

        EffectPreset.WARM -> listOf(
            RgbAdjustment.Builder().setRedScale(1.10f).setGreenScale(1.02f).setBlueScale(0.90f).build(),
            Contrast(1.04f),
        )

        EffectPreset.COOL -> listOf(
            RgbAdjustment.Builder().setRedScale(0.90f).setGreenScale(1.00f).setBlueScale(1.14f).build(),
            Contrast(1.02f),
        )

        EffectPreset.CINEMATIC -> listOf(
            Contrast(1.18f),
            RgbAdjustment.Builder().setRedScale(0.98f).setGreenScale(1.00f).setBlueScale(1.07f).build(),
            HslAdjustment.Builder().adjustSaturation(-8f).build(),
        )

        EffectPreset.VIVID -> listOf(
            Contrast(1.08f),
            HslAdjustment.Builder().adjustSaturation(18f).build(),
        )

        EffectPreset.MONO -> listOf(
            RgbFilter.createGrayscaleFilter(),
            Contrast(1.10f),
        )

        EffectPreset.FADE -> listOf(
            Contrast(0.86f),
            Brightness(0.05f),
            HslAdjustment.Builder().adjustSaturation(-12f).build(),
        )

        EffectPreset.INVERT -> listOf(RgbFilter.createInvertedFilter())
    }

    /** Für Vorschaubilder in der Oberfläche: ungefähre Farbmatrix-Faktoren je Preset. */
    fun swatchArgb(preset: EffectPreset): List<Long> = when (preset) {
        EffectPreset.NONE -> listOf(0xFF7A7A7AL, 0xFF9A9A9AL)
        EffectPreset.WARM -> listOf(0xFFE8A65C)
        EffectPreset.COOL -> listOf(0xFF5C8FE8)
        EffectPreset.CINEMATIC -> listOf(0xFF2E4A7D, 0xFFE8C07A)
        EffectPreset.VIVID -> listOf(0xFFE85C8F, 0xFF5CC9E8)
        EffectPreset.MONO -> listOf(0xFFDADADA, 0xFF4A4A4A)
        EffectPreset.FADE -> listOf(0xFFB8AFA5)
        EffectPreset.INVERT -> listOf(0xFF111111)
    }
}
