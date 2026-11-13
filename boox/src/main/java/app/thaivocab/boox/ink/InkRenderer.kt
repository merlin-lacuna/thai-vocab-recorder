package app.thaivocab.boox.ink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.File

object InkRenderer {
    const val MAX_WIDTH = 1000
    const val MARGIN = 24f

    fun paint(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun pathOf(stroke: Stroke) = Path().apply {
        val first = stroke.points.first()
        moveTo(first.x, first.y)
        // A single point is drawn as a dot.
        if (stroke.points.size == 1) lineTo(first.x + 0.1f, first.y)
        stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
    }

    /**
     * Draws [strokes] as black ink on white, cropped to the ink, and writes a PNG file.
     * Returns false when [strokes] is empty; the function then writes no file.
     */
    fun writePng(strokes: List<Stroke>, boxWidth: Float, boxHeight: Float, strokeWidth: Float, target: File): Boolean {
        val box = boundsOf(strokes, MARGIN, boxWidth, boxHeight) ?: return false
        val scale = scaleFor(box.width, MAX_WIDTH)
        val bitmap = Bitmap.createBitmap(
            (box.width * scale).toInt().coerceAtLeast(1), (box.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            scale(scale, scale)
            translate(-box.left, -box.top)
            val paint = paint(strokeWidth)
            strokes.forEach { drawPath(pathOf(it), paint) }
        }
        target.parentFile?.mkdirs()
        target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return true
    }
}
