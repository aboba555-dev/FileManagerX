package app.filemanagerx.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TreemapTest {
    private fun area(rect: TreemapRect) = rect.width * rect.height

    @Test
    fun `areas are proportional to values and fill the box`() {
        val values = listOf(60.0, 30.0, 6.0, 3.0, 1.0)
        val rects = Treemap.layout(values, 400.0, 300.0)
        assertEquals(values.size, rects.size)
        assertTrue(abs(rects.sumOf(::area) - 400.0 * 300.0) < 1e-6)
        values.indices.forEach { index ->
            val expected = values[index] / values.sum() * 400.0 * 300.0
            assertTrue(abs(area(rects[index]) - expected) < 1e-6)
        }
    }

    @Test
    fun `every tile stays inside the box`() {
        val rects = Treemap.layout(List(25) { (25 - it).toDouble() }, 320.0, 480.0)
        rects.forEach { rect ->
            assertTrue(rect.x >= -1e-9 && rect.y >= -1e-9)
            assertTrue(rect.x + rect.width <= 320.0 + 1e-6)
            assertTrue(rect.y + rect.height <= 480.0 + 1e-6)
        }
    }

    @Test
    fun `empty input gives no tiles`() {
        assertEquals(emptyList<TreemapRect>(), Treemap.layout(emptyList(), 10.0, 10.0))
    }
}
