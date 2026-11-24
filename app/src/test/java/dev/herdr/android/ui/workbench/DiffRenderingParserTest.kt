package dev.herdr.android.ui.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffRenderingParserTest {

    @Test
    fun parseCleanSnippetDiffPairsDeletionsBeforeAdditionsWithIntraLineHighlights() {
        val diff = listOf(
            "-        versionCode = 19",
            "-        versionName = \"0.5.1\"",
            "+        versionCode = 20",
            "+        versionName = \"0.5.2\""
        ).joinToString("\n")

        val parsed = parseUnifiedDiffLines(diff)
        assertEquals(4, parsed.size)
        assertEquals(listOf(DiffLineKind.DEL, DiffLineKind.DEL, DiffLineKind.ADD, DiffLineKind.ADD), parsed.map { it.kind })
        assertTrue(parsed.all { it.oldLineNo == null && it.newLineNo == null })
        assertEquals("        versionCode = 19", parsed[0].code)
        assertEquals("        versionCode = 20", parsed[2].code)

        assertNotNull(parsed[0].strongRange)
        assertEquals("19", parsed[0].code.substring(parsed[0].strongRange!!))
        assertNotNull(parsed[2].strongRange)
        assertEquals("20", parsed[2].code.substring(parsed[2].strongRange!!))

        assertNotNull(parsed[1].strongRange)
        assertEquals("1", parsed[1].code.substring(parsed[1].strongRange!!))
        assertNotNull(parsed[3].strongRange)
        assertEquals("2", parsed[3].code.substring(parsed[3].strongRange!!))
    }

    @Test
    fun parseLegacySyntheticReversedDiffDropsFakeHunkReordersAndHighlights() {
        val diff = listOf(
            "@@ -1,2 +1,2 @@",
            "+versionCode = 20",
            "-versionCode = 19"
        ).joinToString("\n")

        val parsed = parseUnifiedDiffLines(diff)
        assertEquals(2, parsed.size)
        assertEquals(DiffLineKind.DEL, parsed[0].kind)
        assertEquals("versionCode = 19", parsed[0].code)
        assertNull(parsed[0].oldLineNo)
        assertNull(parsed[0].newLineNo)
        assertEquals("19", parsed[0].code.substring(parsed[0].strongRange!!))

        assertEquals(DiffLineKind.ADD, parsed[1].kind)
        assertEquals("versionCode = 20", parsed[1].code)
        assertNull(parsed[1].oldLineNo)
        assertNull(parsed[1].newLineNo)
        assertEquals("20", parsed[1].code.substring(parsed[1].strongRange!!))
    }

    @Test
    fun parseRealUnifiedHunkPreservesHeaderAndLineNumbers() {
        val diff = listOf(
            "@@ -15,2 +15,2 @@",
            "-        versionCode = 19",
            "+        versionCode = 20"
        ).joinToString("\n")

        val parsed = parseUnifiedDiffLines(diff)
        assertEquals(3, parsed.size)
        assertEquals(DiffLineKind.HUNK, parsed[0].kind)
        assertEquals("@@ -15,2 +15,2 @@", parsed[0].code)

        assertEquals(DiffLineKind.DEL, parsed[1].kind)
        assertEquals(15, parsed[1].oldLineNo)
        assertNull(parsed[1].newLineNo)

        assertEquals(DiffLineKind.ADD, parsed[2].kind)
        assertNull(parsed[2].oldLineNo)
        assertEquals(15, parsed[2].newLineNo)
    }
}
