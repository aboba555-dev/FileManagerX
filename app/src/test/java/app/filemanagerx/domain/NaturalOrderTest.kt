package app.filemanagerx.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalOrderTest {
    @Test
    fun `numbers inside names sort by value`() {
        val sorted = listOf("file10", "file2", "file1").sortedWith { a, b -> NaturalOrder.compare(a, b) }
        assertEquals(listOf("file1", "file2", "file10"), sorted)
    }

    @Test
    fun `comparison ignores case`() {
        val sorted = listOf("b", "A", "c").sortedWith { a, b -> NaturalOrder.compare(a, b) }
        assertEquals(listOf("A", "b", "c"), sorted)
    }
}
