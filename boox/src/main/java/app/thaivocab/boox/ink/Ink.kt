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

/** Width of the eraser, as the distance in pixels between the eraser path and the ink. */
const val ERASER_RADIUS = 12f

/**
 * Two eraser points in sequence that are farther apart than this are not joined by a line.
 * A pen moves less than this between two samples, so a larger jump is a faulty sample.
 */
const val ERASER_MAX_JUMP = 120f

private fun pointToSegment(p: InkPoint, a: InkPoint, b: InkPoint): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val length2 = dx * dx + dy * dy
    val t = if (length2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / length2).coerceIn(0f, 1f)
    return kotlin.math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/** The lines of the eraser path. A point without a near neighbour gives a line of length 0. */
private fun eraserLines(eraser: List<InkPoint>): List<Pair<InkPoint, InkPoint>> =
    eraser.map { it to it } + eraser.zipWithNext().filter { (a, b) ->
        kotlin.math.hypot(b.x - a.x, b.y - a.y) <= ERASER_MAX_JUMP
    }

/**
 * Removes the ink that the eraser path [eraser] touches: each stroke point nearer than [radius]
 * to the path. A stroke that loses points in its middle becomes two or more shorter strokes.
 * Strokes without a touched point are returned unchanged, in the same order.
 */
fun erase(strokes: List<Stroke>, eraser: List<InkPoint>, radius: Float = ERASER_RADIUS): List<Stroke> {
    if (eraser.isEmpty()) return strokes
    val lines = eraserLines(eraser)
    return strokes.flatMap { stroke ->
        val touched = stroke.points.map { p -> lines.any { (a, b) -> pointToSegment(p, a, b) <= radius } }
        if (touched.none { it }) return@flatMap listOf(stroke)
        val pieces = mutableListOf<Stroke>()
        var piece = mutableListOf<InkPoint>()
        stroke.points.forEachIndexed { index, point ->
            if (!touched[index]) piece += point
            else if (piece.isNotEmpty()) { pieces += Stroke(piece); piece = mutableListOf() }
        }
        if (piece.isNotEmpty()) pieces += Stroke(piece)
        // A piece of one point is a remainder at the edge of the eraser, not a written dot.
        pieces.filter { it.points.size > 1 }
    }
}
