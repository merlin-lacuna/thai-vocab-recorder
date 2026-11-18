package app.thaivocab.boox.ink

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * The writing box: an upper zone (Thai or phonetic) and a lower zone (English).
 *
 * On a Boox device, the Onyx pen library draws the ink while the pen moves and delivers each
 * finished stroke to [callback]. On other devices, [onTouchEvent] collects and draws the strokes.
 *
 * Erasing removes the ink under the eraser path and can cut a stroke into pieces. Two inputs start it: the eraser end of a stylus, or any pen
 * movement while [eraserOn] is true (the "Eraser" button, for a stylus without an eraser end).
 */

@SuppressLint("ViewConstructor")
class InkView(
    context: Context,
    private val onInkChanged: (thaiInk: Boolean) -> Unit,
    private val onEraserChanged: (on: Boolean) -> Unit = {},
) :
    SurfaceView(context), SurfaceHolder.Callback {

    private val strokes = mutableListOf<Stroke>()
    private var current = mutableListOf<InkPoint>()
    private var touchHelper: TouchHelper? = null
    private var active = true

    /** While true, a pen movement removes the ink that it touches and draws no ink. */
    var eraserOn = false
        private set
    val strokeWidth = 5f

    private companion object {
        /** Grey of the trail that the pen library draws while the pen erases. */
        const val TRAIL_COLOR = 0xFF9E9E9E.toInt()
    }

    private val inkPaint = InkRenderer.paint(strokeWidth)
    private val trailPaint = InkRenderer.paint(ERASER_RADIUS * 2).apply { color = TRAIL_COLOR; alpha = 150 }
    private val linePaint = Paint().apply { color = Color.BLACK; strokeWidth = 3f }
    private val basePaint = Paint().apply {
        color = Color.DKGRAY; strokeWidth = 1.5f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 22f }

    init {
        setZOrderOnTop(false)
        holder.addCallback(this)
    }

    /** A copy of the strokes written so far. */
    fun snapshot(): List<Stroke> = strokes.toList()

    fun clear() {
        strokes.clear()
        setEraser(false)
        redraw()
        onInkChanged(false)
    }

    fun setEraser(on: Boolean) {
        if (eraserOn == on) return
        eraserOn = on
        applyPenStyle()
        onEraserChanged(on)
    }

    /**
     * Sets what the pen library draws while the pen moves: thin black ink for writing, or a
     * wide grey trail while [eraserOn] is true. The trail shows where the eraser has been;
     * [eraseAlong] removes it from the screen when the pen is lifted.
     */
    private fun applyPenStyle() {
        val helper = touchHelper ?: return
        // The library reads a new colour and width when raw drawing is switched on.
        if (active) helper.setRawDrawingEnabled(false)
        helper.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
        helper.setStrokeColor(if (eraserOn) TRAIL_COLOR else Color.BLACK)
        helper.setStrokeWidth(if (eraserOn) ERASER_RADIUS * 2 else strokeWidth)
        if (active) helper.setRawDrawingEnabled(true)
    }

    private fun eraseAlong(path: List<InkPoint>) {
        val kept = erase(strokes, path)
        if (kept != strokes) {
            strokes.clear()
            strokes += kept
            onInkChanged(splitZones(strokes, height.toFloat()).first.isNotEmpty())
        }
        // Always redraw: the pen library can leave an eraser trail on the screen.
        redraw()
    }

    /** Switches pen input off while a window covers the writing box. */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        touchHelper?.setRawDrawingEnabled(value)
        if (!value) redraw()
    }

    private fun add(stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        strokes += stroke
        onInkChanged(splitZones(strokes, height.toFloat()).first.isNotEmpty())
    }

    // ---- Onyx pen library ----

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(b: Boolean, p: TouchPoint?) = Unit
        override fun onEndRawDrawing(b: Boolean, p: TouchPoint?) = Unit
        override fun onRawDrawingTouchPointMoveReceived(p: TouchPoint?) = Unit
        override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
            val points = list?.points?.map { InkPoint(it.x, it.y) } ?: return
            post { if (eraserOn) eraseAlong(points) else add(Stroke(points)) }
        }
        // The pen library draws no trail for the eraser end of the stylus on this firmware.
        // So this view draws a grey trail on its own surface while the eraser moves. The
        // screen shows the surface only while the library's own drawing is switched off;
        // the library still delivers the pen positions in that state.
        override fun onBeginRawErasing(b: Boolean, p: TouchPoint?) {
            p ?: return
            touchHelper?.setRawDrawingRenderEnabled(false)
            trailPoints = mutableListOf(InkPoint(p.x, p.y))
        }
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint?) {
            p ?: return
            trailPoints += InkPoint(p.x, p.y)
            // A redraw for each position is too slow for the e-ink screen.
            if (trailPoints.size % 12 == 0) redraw(trail = Stroke(trailPoints.toList()), pauseLibrary = false)
        }
        override fun onEndRawErasing(b: Boolean, p: TouchPoint?) {
            trailPoints = mutableListOf()
            // [eraseAlong] runs next and removes the trail from the screen.
            post { touchHelper?.setRawDrawingRenderEnabled(true) }
        }
        override fun onRawErasingTouchPointListReceived(list: TouchPointList?) {
            val points = list?.points?.map { InkPoint(it.x, it.y) } ?: return
            post { eraseAlong(points) }
        }
    }

    private var trailPoints = mutableListOf<InkPoint>()

    private fun startOnyx() {
        touchHelper = try {
            TouchHelper.create(this, callback).apply {
                setLimitRect(Rect(0, 0, width, height), emptyList())
                openRawDrawing()
                setRawDrawingEnabled(active)
            }.also { touchHelper = it; applyPenStyle() }
        } catch (e: Throwable) {
            Log.i("InkView", "Onyx pen library is not available; using normal touch events", e)
            null
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        touchHelper?.closeRawDrawing()
        touchHelper = null
        redraw()
        startOnyx()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        touchHelper?.closeRawDrawing()
        touchHelper = null
    }

    // ---- Normal touch events (other devices, emulator) ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (touchHelper != null || !active) return false
        val erasing = eraserOn || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> current = mutableListOf(InkPoint(event.x, event.y))
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) current += InkPoint(event.getHistoricalX(i), event.getHistoricalY(i))
                current += InkPoint(event.x, event.y)
                if (!erasing) redraw(Stroke(current.toList()))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val points = current.toList()
                current = mutableListOf()
                if (erasing) eraseAlong(points) else { add(Stroke(points)); redraw() }
            }
        }
        return true
    }

    /** Draws the zone lines, the labels and all strokes on the surface. */
    private fun redraw(extra: Stroke? = null, trail: Stroke? = null, pauseLibrary: Boolean = true) {
        val raw = pauseLibrary && touchHelper != null && active
        if (raw) touchHelper?.setRawDrawingEnabled(false)
        val canvas = holder.lockCanvas()
        if (canvas != null) {
            try {
                canvas.drawColor(Color.WHITE)
                val split = height * SPLIT
                canvas.drawLine(0f, split, width.toFloat(), split, linePaint)
                canvas.drawLine(width * 0.04f, height * 0.44f, width * 0.96f, height * 0.44f, basePaint)
                canvas.drawText("THAI OR PHONETIC", 14f, 30f, labelPaint)
                canvas.drawText("ENGLISH, OPTIONAL", 14f, split + 32f, labelPaint)
                (strokes + listOfNotNull(extra)).forEach { canvas.drawPath(InkRenderer.pathOf(it), inkPaint) }
                trail?.let { canvas.drawPath(InkRenderer.pathOf(it), trailPaint) }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
        if (raw) touchHelper?.setRawDrawingEnabled(true)
    }
}
