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
 */

@SuppressLint("ViewConstructor")
class InkView(context: Context, private val onInkChanged: (thaiInk: Boolean) -> Unit) :
    SurfaceView(context), SurfaceHolder.Callback {

    private val strokes = mutableListOf<Stroke>()
    private var current = mutableListOf<InkPoint>()
    private var touchHelper: TouchHelper? = null
    private var active = true
    val strokeWidth = 5f

    private val inkPaint = InkRenderer.paint(strokeWidth)
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
        redraw()
        onInkChanged(false)
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
            post { add(Stroke(points)) }
        }
        override fun onBeginRawErasing(b: Boolean, p: TouchPoint?) = Unit
        override fun onEndRawErasing(b: Boolean, p: TouchPoint?) = Unit
        override fun onRawErasingTouchPointMoveReceived(p: TouchPoint?) = Unit
        override fun onRawErasingTouchPointListReceived(list: TouchPointList?) = Unit
    }

    private fun startOnyx() {
        touchHelper = try {
            TouchHelper.create(this, callback).apply {
                setStrokeWidth(strokeWidth)
                setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL)
                setLimitRect(Rect(0, 0, width, height), emptyList())
                openRawDrawing()
                setRawDrawingEnabled(active)
            }
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> current = mutableListOf(InkPoint(event.x, event.y))
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.historySize) current += InkPoint(event.getHistoricalX(i), event.getHistoricalY(i))
                current += InkPoint(event.x, event.y)
                redraw(Stroke(current.toList()))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                add(Stroke(current.toList()))
                current = mutableListOf()
                redraw()
            }
        }
        return true
    }

    /** Draws the zone lines, the labels and all strokes on the surface. */
    private fun redraw(extra: Stroke? = null) {
        val raw = touchHelper != null && active
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
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
        if (raw) touchHelper?.setRawDrawingEnabled(true)
    }
}
