package com.puzzlefoco.schnittstelle.media

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.media3.effect.CanvasOverlay
import androidx.media3.common.util.Size
import com.puzzlefoco.schnittstelle.model.TextAlign
import com.puzzlefoco.schnittstelle.model.TextClip
import com.puzzlefoco.schnittstelle.model.TextPosition
import kotlin.math.max

/**
 * Zeichnet alle Textclips, die zum jeweiligen Frame-Zeitpunkt aktiv sind, in das Videobild.
 *
 * Wird als kompositionsweiter Effekt ([androidx.media3.effect.OverlayEffect]) verwendet und
 * damit identisch in Vorschau und Export angewendet. Da [CanvasOverlay] pro Frame neu
 * gezeichnet wird, sind Ein-/Ausblendungen möglich, obwohl `OverlaySettings` statisch sind.
 */
class TimelineTextOverlay(
    private val clipsProvider: () -> List<TextClip>,
    // Achtung: Der Parameter heißt `useInputFrameSize` (nicht „HDR"). Bei `false` müsste
    // `setCanvasSize` vor dem ersten `onDraw` gesetzt werden – passiert das nicht, bleibt die
    // Bitmap 0×0 und die Wiedergabe bricht mit „width and height must be > 0" ab.
) : CanvasOverlay(/* useInputFrameSize= */ true) {

    private var width = 1080
    private var height = 1920

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun configure(outputSize: Size) {
        super.configure(outputSize)
        if (outputSize.width > 0 && outputSize.height > 0) {
            width = outputSize.width
            height = outputSize.height
        }
    }

    override fun setCanvasSize(canvasWidth: Int, canvasHeight: Int) {
        super.setCanvasSize(canvasWidth, canvasHeight)
        if (canvasWidth > 0 && canvasHeight > 0) {
            width = canvasWidth
            height = canvasHeight
        }
    }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        val timeMs = presentationTimeUs / 1000L
        // CanvasOverlay zeichnet jeden Frame auf DIESELBE Bitmap. Ohne vorheriges Leeren
        // bleibt der Text des letzten Frames stehen und Overlays "kleben" bis zum Ende
        // des Films, auch wenn ihr Zeitfenster längst abgelaufen ist.
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        val clips = clipsProvider().filter { timeMs >= it.startMs && timeMs < it.endMs }
        if (clips.isEmpty()) return

        for (clip in clips) {
            val alpha = clip.alphaAt(timeMs)
            if (alpha <= 0.01f) continue
            drawClip(canvas, clip, alpha)
        }
    }

    private fun drawClip(canvas: Canvas, clip: TextClip, alpha: Float) {
        // Textgröße relativ zur Bildhöhe: 1 sp ≈ 3 px bei 1080p Referenz
        val textSize = clip.sizeSp * 3f * (height / 1080f)
        paint.textSize = textSize
        paint.typeface = if (clip.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.color = clip.colorArgb
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)

        val lines = clip.text.split("\n").map { it.trim() }
        val lineHeight = textSize * 1.25f
        val blockHeight = lineHeight * lines.size
        val marginX = width * 0.08f
        val marginY = height * 0.06f

        val blockTop = when (clip.position) {
            TextPosition.TOP -> marginY
            TextPosition.CENTER -> (height - blockHeight) / 2f
            TextPosition.BOTTOM -> height - marginY - blockHeight
        }

        val strokePad = textSize * 0.25f
        lines.forEachIndexed { index, line ->
            val baseline = blockTop + lineHeight * index + textSize
            val textWidth = paint.measureText(line)
            val left = when (clip.align) {
                TextAlign.LEFT -> marginX
                TextAlign.CENTER -> (width - textWidth) / 2f
                TextAlign.RIGHT -> width - marginX - textWidth
            }

            if (Color.alpha(clip.backgroundColorArgb) > 0) {
                backgroundPaint.color = clip.backgroundColorArgb
                backgroundPaint.alpha = (Color.alpha(clip.backgroundColorArgb) * alpha).toInt().coerceIn(0, 255)
                val pad = textSize * 0.2f
                val rect = RectF(
                    left - pad,
                    baseline - textSize - pad * 0.5f,
                    left + textWidth + pad,
                    baseline + textSize * 0.3f + pad * 0.5f,
                )
                val radius = textSize * 0.25f
                canvas.drawRoundRect(rect, radius, radius, backgroundPaint)
            }

            // Lesbarkeit auf hellem Bild: dünne Kontur
            val strokeWidth = paint.strokeWidth
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(2f, textSize * 0.04f)
            val originalColor = paint.color
            paint.color = Color.argb(paint.alpha, 0, 0, 0)
            canvas.drawText(line, left, baseline, paint)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = strokeWidth
            paint.color = originalColor

            canvas.drawText(line, left, baseline, paint)
        }
    }
}
