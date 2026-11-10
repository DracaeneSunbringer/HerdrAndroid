package dev.herdr.android.ui.terminal

import dev.herdr.android.protocol.AgentStateWord
import dev.herdr.android.protocol.TerminalSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun antigravityDialect_preservesIndentedProseAfterThoughtAndRanAndFoldsMoreActions() {
        val agyScreen = """
            ────────────────────────────────────────────────────────────
            > 最后做一遍审核

            ○ Thought for 40.7s (Final audit prompt requires generating a markdown document.)
            ● Explored 1 file (codex_final_audit_response.txt)
            … 53 more actions (18 reads, 11 edits, 24 commands: 10 failed) …
            ● Checked 1 task (task-7177) (ctrl+o to expand)
              搭档 t26 正在完成最后的模拟核验，完成后立即汇总终审结果。

            ▸ Thought for 9s, 360 tokens
              已完成四维度自查、与 Codex 搭档的 5 轮独立交叉复核：
              1. 地图页面卷轴上的节点图标消失
                  • 原因：按 SVG 命名空间（http://www.
                  w3.org/2000/svg）创建导致标签失效。

             指标   │ 结果
            ────────┼──────────────
             零残留 │ 全部通过

            ⚠ UNAUTHENTICATED (code 401): Request had invalid authentication credentials.
            Error ID: 843344fd-5005-4b7f-bbaf-0ca2fbfa76fb

            ───────────────────────────────────────────────────────────────────────────
            >
            ───────────────────────────────────────────────────────────────────────────
              ● [19:27:41] while (${'$'}true) { ssh matebook "node watch.mjs" } running
            ───────────────────────────────────────────────────────────────────────────
            esc to cancel                                  Gemini 4 Argon · high · 1 task(s) · /tasks
        """.trimIndent()

        val blocks = StructuredTerminalReader.parseBlocks(agyScreen, agentApp = "antigravity")

        val prompts = blocks.filterIsInstance<TerminalSemanticBlock.UserPromptBlock>()
        assertEquals(1, prompts.size)
        assertEquals("最后做一遍审核", prompts.single().text)

        val tools = blocks.filterIsInstance<TerminalSemanticBlock.ToolGroupBlock>()
        assertTrue(tools.isNotEmpty())
        assertTrue(tools.first().summaryLabel.contains("56"))
        assertTrue(tools.first().hasError)

        val prose = blocks.filterIsInstance<TerminalSemanticBlock.AgentProseBlock>()
        // Critical: 2-space indented prose immediately after `● Checked` and `▸ Thought` must NEVER be swallowed!
        assertTrue(prose.any { it.text.contains("搭档 t26 正在完成最后的模拟核验") })
        assertTrue(prose.any { it.text.contains("已完成四维度自查") })
        val nestedBullet = prose.firstOrNull { it.text.contains("http://www.w3.org/2000/svg") }
        assertTrue(nestedBullet != null && nestedBullet.isBullet && nestedBullet.indentLevel == 1)
        // Daemon strip & Gemini footer must be stripped
        assertFalse(prose.any { it.text.contains("watch.mjs") || it.text.contains("Gemini 4 Argon") })

        val tables = blocks.filterIsInstance<TerminalSemanticBlock.CodeOrTableBlock>()
        assertEquals(1, tables.size)
        assertEquals("表格", tables.single().kindLabel)
        assertTrue(tables.single().content.contains("零残留"))

        val alerts = blocks.filterIsInstance<TerminalSemanticBlock.UrgentAlertBlock>()
        assertEquals(1, alerts.size)
        assertTrue(alerts.single().detail.contains("UNAUTHENTICATED"))
        assertTrue(alerts.single().detail.contains("Error ID:"))
    }

    @Test
    fun codexDialect_distinguishesLeadProseFromToolsStripsPlaceholderAndPreservesSpacedTables() {
        val codexScreen = """
            › <hc from="t19">请读取 codex_round5_prompt.md 做最终收口核验。</hc>

            • Edited 2 files (+46 -10)
              └ hc-protocol\src\main\kotlin\dev\herdr\android\protocol\HcFramedClient.kt (+11 -3)
                103 -    suspend fun readTerminal(address: String): String

              └ hc-protocol\src\main\kotlin\dev\herdr\android\protocol\HcLiveTerminalSession.kt (+35 -7)
                 20 +import java.util.concurrent.atomic.AtomicLong
                + Show details

            • Ran Get-Content prompt.md …
              └ ## 输出要求

                P1 已闭环且无新引入 P0/P1，请明确输出终审通过。
                + 12 lines (ctrl+t to expand)

            • Exited (status 1) Get-Content missing.txt
              └ Cannot find path 'missing.txt'
                + 4 lines (ctrl+t to expand)

            • **准予上线。** 第 5 轮限定的锁回收增量已通过终验，详见 ZeroTierPeerWarmup.kt (/D:/luoyu/Documents/Code/HerdrAndroid/connection/src/main/kotlin/dev/herdr/android/connection/ZeroTierPeerWarmup.kt:14) 实现。

              • 所有锁获取都先以 wx 取得同一 .gate 门闩。
              • 锁释放同样进入 .gate 并核对持有者 token。

               本轮复核项       结果
              ━━━━━━━━━━━━━━━  ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
               1. 取消退出租约  原竞态已修复，新调用正常完成
              ───────────────  ──────────────────────────────
               2. 身份隔离      无显式 ID 的不同实例不再同 key

              Worked for 54s • 03:07

            ⚠ Heads up, you have less than 25% of your weekly limit left. Run /status for a breakdown.
                                                       ⚠ weekly limit: 25% left · resets at 15:50 · /status

            › Ask Codex to do anything

              GPT-6.1-Sol xhigh · D:\luoyu\Documents\Code\HerdrAndroid · 重构审计架构
              ← for agents · ? for shortcuts                                    ⚠ 3 warnings · f2 to view
        """.trimIndent()

        val blocks = StructuredTerminalReader.parseBlocks(codexScreen, agentApp = "codex")

        // Only the real <hc> prompt should be extracted — NOT `› Ask Codex to do anything`!
        val prompts = blocks.filterIsInstance<TerminalSemanticBlock.UserPromptBlock>()
        assertEquals(1, prompts.size)
        assertTrue(prompts.single().text.contains("codex_round5_prompt.md"))

        val tools = blocks.filterIsInstance<TerminalSemanticBlock.ToolGroupBlock>()
        assertEquals(1, tools.size)
        assertTrue(tools.single().hasError)
        assertTrue(tools.single().summaryLabel.contains("耗时 54s"))
        assertFalse(tools.single().summaryLabel.contains("进行中"))

        val prose = blocks.filterIsInstance<TerminalSemanticBlock.AgentProseBlock>()
        // Lead `• 准予上线...` at col 0 must be normal prose (isBullet = false), NOT a bullet!
        val lead = prose.first()
        assertFalse(lead.isBullet)
        assertTrue(lead.text.startsWith("准予上线。"))
        // Redundant file path simplified to `ZeroTierPeerWarmup.kt:14`
        assertTrue(lead.text.contains("ZeroTierPeerWarmup.kt:14"))
        assertFalse(lead.text.contains("(/D:/luoyu"))

        // Indented `  • ...` items ARE bullets
        val bullets = prose.filter { it.isBullet }
        assertEquals(2, bullets.size)
        // `Worked for 54s` and `GPT-6.1-Sol` must NOT appear in prose
        assertFalse(prose.any { it.text.contains("Worked for") || it.text.contains("GPT-6.1-Sol") || it.text.contains("Heads up") })

        // Codex borderless spaced-rule table preserved intact
        val tables = blocks.filterIsInstance<TerminalSemanticBlock.CodeOrTableBlock>()
        assertEquals(1, tables.size)
        assertEquals("表格", tables.single().kindLabel)
        assertTrue(tables.single().content.contains("本轮复核项"))

        // Codex quick actions use Ctrl+T (`ctrl+t`) when working
        val workingSummary = TerminalSummary(address = "t1r", agentApp = "codex", agentStatus = AgentStateWord.WORKING)
        val quickActions = StructuredTerminalReader.extractQuickActions(codexScreen, workingSummary)
        assertEquals("ctrl+t", quickActions.first().payload)
    }

    @Test
    fun piDialect_extractsClickToJumpPromptsFoldsThoughtPreviewsAndTodos() {
        val piScreen = """
             cmd是什么的缩写吗                                                           [click to jump]

             Thought for 2s · 11 more lines · click to show more
             Let me inspect the command enum in ze5.java first.

             ● Bash: 2 done · click to show more
             ├ ✓ Bash grep -n "command=" ze5.java · 120ms
             └ ✓ Bash sed -n '1,20p' ze5.java · 90ms

             ✓ Bash python -c "print('ok')"
               ↳ 1 line returned · 0.2s · click to show more

             我在工具里写成 cmd 只是为了字段短一点，指的是 16 位命令字里低 7 位的操作号。

             │ Ran 3 commands · 2.4s

            ● Todos (1/2)
            ├─ ✓ 解析 GATT 特征值
            └─ ○ 产出调耳机文档

            ───────────────────────────────────────────────────────────────────────────────────────
             Package Updates Available
             Package updates are available. Run pi update --extensions
            ───────────────────────────────────────────────────────────────────────────────────────
             Resumed session
             Thinking level: high
            ───────────────────────────────────────────────────────────────────────────────────────
            ❯
            ───────────────────────────────────────────────────────────────────────────────────────
              cline-pass/deepseek-v4.1 ◉ xhigh · ███░░░ 29%/1.0M · avg 16 tok/s (TTFT: 3.2s) · ${'$'}0.95
              DaliySolve · Cline upstream: deepseek · MCP: 2 servers enabled
            [hc] incomplete: older output may be missing
        """.trimIndent()

        val blocks = StructuredTerminalReader.parseBlocks(piScreen, agentApp = "pi")

        val prompts = blocks.filterIsInstance<TerminalSemanticBlock.UserPromptBlock>()
        assertEquals(1, prompts.size)
        assertEquals("cmd是什么的缩写吗", prompts.single().text)

        val prose = blocks.filterIsInstance<TerminalSemanticBlock.AgentProseBlock>()
        assertEquals(1, prose.size)
        assertTrue(prose.single().text.contains("我在工具里写成 cmd"))
        // Thought preview ("Let me inspect...") was folded into ToolGroupBlock, not prose!
        assertFalse(prose.any { it.text.contains("Let me inspect") || it.text.contains("Package Updates") })

        val toolGroups = blocks.filterIsInstance<TerminalSemanticBlock.ToolGroupBlock>()
        assertTrue(toolGroups.any { it.summaryLabel.startsWith("任务清单 Todos (1/2)") })

        val summary = TerminalSummary(address = "t2", agentApp = "pi", agentStatus = AgentStateWord.DONE)
        val headline = StructuredTerminalReader.extractLatestHeadline(piScreen, summary)
        assertTrue(headline.contains("我在工具里写成 cmd"))
    }

    @Test
    fun shellDialect_parsesPromptsAndPreservesCommandOutput() {
        val bashScreen = """
            luoe@luoe-matebook:~/Documents/Code/DailyNote${'$'} ls
            52dmeme  AGENTS.md  chrome-agent
            luoe@luoe-matebook:~/Documents/Code/DailyNote${'$'}
        """.trimIndent()

        val blocks = StructuredTerminalReader.parseBlocks(bashScreen, agentApp = "bash")
        assertEquals(2, blocks.size)
        assertEquals("${'$'} ls", (blocks[0] as TerminalSemanticBlock.UserPromptBlock).text)
        assertTrue((blocks[1] as TerminalSemanticBlock.CodeOrTableBlock).content.contains("chrome-agent"))
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
