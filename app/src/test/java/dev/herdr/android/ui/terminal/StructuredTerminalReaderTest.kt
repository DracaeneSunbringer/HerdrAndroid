package dev.herdr.android.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredTerminalReaderTest {

    @Test
    fun parseBlocks_unwrapsHardWrappedCjkAndGroupsToolsAndExtractsPrompt() {
        val sampleRawScreen = """
            ──────────────────────────────────────────────────────────
            ❯ 你让这个新助手审查一下现在APP的交互逻辑和显示效果
            • Thought for 3s
            • Read(app/src/main/java/dev/herdr/android/ui/HerdrAppScreen.kt)
            从第一性原理来看，移动端的最核心目标是分诊和批示，而不是去模拟一个
            200列的桌面原始终端画布。
            Do you want to proceed? (y/n)
            claude-opus-4-6 · 64.2k tokens · esc to interrupt
        """.trimIndent()

        val blocks = StructuredTerminalReader.parseBlocks(sampleRawScreen)
        assertTrue(blocks.any { it is TerminalSemanticBlock.UserPromptBlock })
        assertTrue(blocks.any { it is TerminalSemanticBlock.ToolGroupBlock })
        val proseBlocks = blocks.filterIsInstance<TerminalSemanticBlock.AgentProseBlock>()
        assertTrue(proseBlocks.isNotEmpty())
        val mergedParagraph = proseBlocks.joinToString(" ") { it.text }
        assertTrue(mergedParagraph.contains("从第一性原理来看，移动端的最核心目标是分诊和批示，而不是去模拟一个200列的桌面原始终端画布。"))
        assertTrue(blocks.any { it is TerminalSemanticBlock.UrgentAlertBlock })
    }

    @Test
    fun extractQuickActions_returnsApprovalPillsForBlockedYesNoPrompt() {
        val rawScreen = """
            Allow Write(HerdrAppScreen.kt)? (y/n)
        """.trimIndent()
        val actions = StructuredTerminalReader.extractQuickActions(rawScreen, null)
        assertTrue(actions.isNotEmpty())
        assertEquals("y", actions.first().payload)
        assertTrue(actions.first().isPrimary)
    }
}
