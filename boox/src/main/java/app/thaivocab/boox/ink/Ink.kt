package app.thaivocab.boox.ink

data class InkPoint(val x: Float, val y: Float)

/** One pen movement from pen-down to pen-up, in pixels of the writing box. */
data class Stroke(val points: List<InkPoint>)

enum class Zone { THAI, ENGLISH }

/** The split line between the two zones, as a part of the box height. */
const val SPLIT = 0.64f

/** A stroke belongs to the zone where it starts, so a long Thai tail may cross the line. */
fun zoneOf(stroke: Stroke, height: Float): Zone =
    if (stroke.points.first().y > height * SPLIT) Zone.ENGLISH else Zone.THAI

/** Returns the strokes of the upper zone and the strokes of the lower zone. */
fun splitZones(strokes: List<Stroke>, height: Float): Pair<List<Stroke>, List<Stroke>> =
    strokes.filter { it.points.isNotEmpty() }.partition { zoneOf(it, height) == Zone.THAI }

data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** The smallest rectangle around the ink plus [margin], limited to the box. Null without ink. */
fun boundsOf(strokes: List<Stroke>, margin: Float, width: Float, height: Float): Bounds? {
    val points = strokes.flatMap { it.points }
    if (points.isEmpty()) return null
    return Bounds(
        left = (points.minOf { it.x } - margin).coerceAtLeast(0f),
        top = (points.minOf { it.y } - margin).coerceAtLeast(0f),
        right = (points.maxOf { it.x } + margin).coerceAtMost(width),
        bottom = (points.maxOf { it.y } + margin).coerceAtMost(height),
    )
}

/** Factor that makes an image at most [maxWidth] pixels wide. Small images keep their size. */
fun scaleFor(width: Float, maxWidth: Int): Float = if (width <= maxWidth) 1f else maxWidth / width
