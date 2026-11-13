package app.thaivocab.boox.ink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InkTest {
    private fun stroke(vararg xy: Float) = Stroke(xy.toList().chunked(2).map { InkPoint(it[0], it[1]) })

    @Test fun `a stroke belongs to the zone where it starts`() {
        // Box height 100: the split line is at y = 64.
        assertEquals(Zone.THAI, zoneOf(stroke(10f, 60f, 10f, 90f), height = 100f)) // tail crosses the line
        assertEquals(Zone.ENGLISH, zoneOf(stroke(10f, 70f, 10f, 30f), height = 100f))
    }

    @Test fun `split puts each stroke in one list`() {
        val a = stroke(0f, 10f, 5f, 10f)
        val b = stroke(0f, 80f, 5f, 80f)
        val (thai, english) = splitZones(listOf(a, b), height = 100f)
        assertEquals(listOf(a), thai)
        assertEquals(listOf(b), english)
    }

    @Test fun `bounds add the margin and stay inside the box`() {
        val box = boundsOf(listOf(stroke(10f, 20f, 50f, 40f)), margin = 15f, width = 200f, height = 100f)!!
        assertEquals(Bounds(0f, 5f, 65f, 55f), box)
    }

    @Test fun `bounds of an empty list are null`() {
        assertNull(boundsOf(emptyList(), margin = 15f, width = 200f, height = 100f))
    }

    @Test fun `scale keeps small images and shrinks wide images`() {
        assertEquals(1f, scaleFor(width = 800f, maxWidth = 1000), 0.0001f)
        assertEquals(0.5f, scaleFor(width = 2000f, maxWidth = 1000), 0.0001f)
    }
}
