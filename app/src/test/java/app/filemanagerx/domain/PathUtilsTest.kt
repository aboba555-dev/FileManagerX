package app.filemanagerx.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathUtilsTest {
    @Test
    fun `normalize collapses separators and dots`() {
        assertEquals("/a/b", PathUtils.normalize("/a//b/./"))
        assertEquals("/a", PathUtils.normalize("/a/b/.."))
        assertEquals("/", PathUtils.normalize("/"))
    }

    @Test
    fun `parent of root is null`() {
        assertNull(PathUtils.parent("/"))
        assertEquals("/", PathUtils.parent("/storage"))
        assertEquals("/storage/emulated", PathUtils.parent("/storage/emulated/0"))
    }

    @Test
    fun `descendant check does not match sibling prefixes`() {
        assertTrue(PathUtils.isSameOrDescendant("/data/app", "/data"))
        assertTrue(PathUtils.isSameOrDescendant("/data", "/data"))
        assertFalse(PathUtils.isSameOrDescendant("/database", "/data"))
    }

    @Test
    fun `splitName keeps compound archive extensions together`() {
        assertEquals("backup" to ".tar.gz", PathUtils.splitName("backup.tar.gz"))
        assertEquals("photo" to ".jpg", PathUtils.splitName("photo.jpg"))
    }

    @Test
    fun `uniqueName appends a counter until free`() {
        val taken = setOf("a.txt", "a (1).txt")
        assertEquals("a (2).txt", PathUtils.uniqueName("a.txt") { it in taken })
        assertEquals("b.txt", PathUtils.uniqueName("b.txt") { it in taken })
    }

    @Test
    fun `validateName rejects separators and reserved names`() {
        assertEquals(PathUtils.NameError.Empty, PathUtils.validateName("  "))
        assertEquals(PathUtils.NameError.Reserved, PathUtils.validateName(".."))
        assertEquals(PathUtils.NameError.InvalidCharacters, PathUtils.validateName("a/b"))
        assertNull(PathUtils.validateName("report.pdf"))
    }
}
