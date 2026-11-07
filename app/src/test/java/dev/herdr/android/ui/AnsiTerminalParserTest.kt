package dev.herdr.android.ui

import dev.herdr.android.ui.terminal.AnsiTerminalParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTerminalParserTest {

    @Test
    fun stripsOsc7501AndParsesSgrColors() {
        val raw = "\u001B]7501;{\"app\":\"codex\",\"state\":\"working\"}\u0007\u001B[1;32m✓ Connected\u001B[0m to hc 0.1.18"
        val parsed = AnsiTerminalParser.parseToAnnotatedString(raw)
        assertEquals("✓ Connected to hc 0.1.18", parsed.text)
        assertTrue(parsed.spanStyles.isNotEmpty())
    }

    @Test
    fun normalizesCarriageReturnProgressOverwrite() {
        val raw = "Loading 10%\rLoading 50%\rLoading 100%\nDone"
        val parsed = AnsiTerminalParser.parseToAnnotatedString(raw)
        assertEquals("Loading 100%\nDone", parsed.text)
    }

    @Test
    fun preservesNativeTuiTablesCodeBlocksAndIndentationOneToOne() {
        val raw = listOf(
            "┌──────────┬───────────────────────┐",
            "│ 模式     │ unlimited / quota 1e9 │",
            "├──────────┼───────────────────────┤",
            "│ 今日卖单 │ 松露 68 + 草莓 191    │",
            "└──────────┴───────────────────────┘",
            "  .daemon.pid          3337639  worker-alpha",
            "  .daemon.sub.pid      3339480  worker-beta",
            "● Todos (0/2)",
            "├─ ○ 同步 site 前端",
            "└─ ○ 补测试并重启"
        ).joinToString("\n")
        val parsed = AnsiTerminalParser.parseToAnnotatedString(raw)
        assertEquals(raw, parsed.text)
        assertTrue(parsed.spanStyles.isNotEmpty())
    }
}
