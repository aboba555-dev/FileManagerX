package app.filemanagerx.data.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafeTargetPathTest {
    @Test
    fun `entries stay below the destination`() {
        assertEquals("/out/a/b.txt", safeTargetPath("/out", "a/b.txt"))
        assertEquals("/out/a/b.txt", safeTargetPath("/out", "/a/./b.txt"))
        assertEquals("/out/win/file.txt", safeTargetPath("/out", "win\\file.txt"))
    }

    @Test
    fun `zip slip attempts are rejected`() {
        assertNull(safeTargetPath("/out", "../evil"))
        assertNull(safeTargetPath("/out", "a/../../evil"))
        assertNull(safeTargetPath("/out", ""))
    }

    @Test
    fun `format detection by name and magic`() {
        assertEquals(ArchiveFormat.TarGzip, ArchiveFormat.fromName("x.TAR.GZ"))
        assertEquals(ArchiveFormat.Zip, ArchiveFormat.fromName("app.apk"))
        assertEquals(ArchiveFormat.SevenZip, ArchiveFormat.fromMagic(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)))
    }
}
