package app.filemanagerx.domain

import app.filemanagerx.domain.model.FileItem
import app.filemanagerx.domain.model.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchRenameTest {
    private fun file(name: String, directory: Boolean = false) =
        FileItem("/x/$name", name, directory, false, 1L, 0L, if (directory) FileKind.Folder else FileKind.Other)

    @Test
    fun `pattern numbers files and keeps the extension`() {
        val items = listOf(file("a.jpg"), file("b.jpg"), file("c.png"))
        val result = BatchRename.preview(items, RenameRule.Pattern("Photo_{n}", start = 1, padding = 3), setOf("a.jpg", "b.jpg", "c.png"))
        assertEquals(listOf("Photo_001.jpg", "Photo_002.jpg", "Photo_003.png"), result.map { it.newName })
        assertEquals(listOf(null, null, null), result.map { it.problem })
    }

    @Test
    fun `folders do not get an extension`() {
        val result = BatchRename.preview(listOf(file("old.v1", directory = true)), RenameRule.Pattern("new"), setOf("old.v1"))
        assertEquals("new", result.single().newName)
    }

    @Test
    fun `find and replace works literally and with regex groups`() {
        val items = listOf(file("IMG_2024_01.jpg"))
        val literal = BatchRename.preview(items, RenameRule.Replace("IMG", "Trip"), setOf("IMG_2024_01.jpg"))
        assertEquals("Trip_2024_01.jpg", literal.single().newName)
        val regex = BatchRename.preview(items, RenameRule.Replace("IMG_(\\d+)_(\\d+)", "$2-$1", useRegex = true), setOf("IMG_2024_01.jpg"))
        assertEquals("01-2024.jpg", regex.single().newName)
    }

    @Test
    fun `broken regex is reported for every item`() {
        val result = BatchRename.preview(listOf(file("a"), file("b")), RenameRule.Replace("(", "x", useRegex = true), setOf("a", "b"))
        assertEquals(listOf(RenameProblem.BadPattern, RenameProblem.BadPattern), result.map { it.problem })
    }

    @Test
    fun `duplicates and existing names are flagged`() {
        val items = listOf(file("a.txt"), file("b.txt"))
        val same = BatchRename.preview(items, RenameRule.Pattern("same", padding = 0, start = 0).copy(template = "same"), setOf("a.txt", "b.txt"))
        assertEquals(listOf(RenameProblem.Duplicate, RenameProblem.Duplicate), same.map { it.problem })
        val clash = BatchRename.preview(listOf(file("a.txt")), RenameRule.Replace("a", "z"), setOf("a.txt", "z.txt"))
        assertEquals(RenameProblem.Exists, clash.single().problem)
    }

    @Test
    fun `unchanged names are not a problem`() {
        val result = BatchRename.preview(listOf(file("a.txt")), RenameRule.Replace("zzz", "y"), setOf("a.txt"))
        assertNull(result.single().problem)
        assertEquals(false, result.single().changed)
    }

    @Test
    fun `empty and invalid results are flagged`() {
        val empty = BatchRename.preview(listOf(file("a")), RenameRule.Pattern("", keepExtension = false), setOf("a"))
        assertEquals(RenameProblem.Empty, empty.single().problem)
        val invalid = BatchRename.preview(listOf(file("a")), RenameRule.Pattern("x/y", keepExtension = false), setOf("a"))
        assertEquals(RenameProblem.InvalidName, invalid.single().problem)
    }
}
