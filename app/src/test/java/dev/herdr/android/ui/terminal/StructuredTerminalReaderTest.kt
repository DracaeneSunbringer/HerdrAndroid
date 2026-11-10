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

    @Test
    fun codexSourceBijection_parsesUnfencedCodeSubagentsPlansRecapErrorsAndMarkdownConstructs() {
        val codexScreen = """
            ╭──────────────────────────────────────────────────╮
            │ ✨ Update available! 0.165.0 -> 0.167.0          │
            │ Run npm install -g @openai/codex to update.      │
            │ See full release notes:                          │
            │ https://github.com/openai/codex/releases/latest  │
            ╰──────────────────────────────────────────────────╯

            ╭──────────────────────────────────────────────────╮
            │ >_ OpenAI Codex (v0.167.0)                       │
            │                                                  │
            │ model:     gpt-5-codex   /model to change        │
            │ directory: ~/Code/HerdrAndroid                   │
            ╰──────────────────────────────────────────────────╯

              [Image #1]

            › 请结合截图修复进程退出竞态并做多代理复核

            • Updated Plan · 2/3 complete
              └ ✔ 审计 codex-rs/tui 渲染路径
                ✔ 识别无围栏代码块与单文件多 hunk diff
                □ 完成真机回归测试

            • Questions 2/2 answered
              • 是否保留原 UI 主题？
                answer: 保持不变
              • 是否需要覆盖子代理条目？
                answer: 全部覆盖

            • Spawned auditor [Explorer] (gpt-5 high)
            • Waiting for auditor
            • Finished waiting
            • Browsing the web
            • Opened https://github.com/openai/codex
            • Called
              └ github.get_pull_request({"number": 42})
                … 4 earlier lines hidden
            ✔ You approved codex to run ./gradlew test
            ✘ Failed to apply patch
              └ context mismatch at line 18
            • Edited app/src/main/Foo.kt (+4 -2)
                12 -    val ready = false
                12 +    val ready = true

                44 -    return oldState
                44 +    return newState

            • Sent by Codex from task task-auditor
              子代理已完成独立静态核验。

            • ## 根因与修复结论

              > 子进程退出事件必须在流关闭前完成注册，
              > 否则极端情况下会漏掉退出码。

              If you want to verify this locally on Windows, inspect the session state
              for each active connection and confirm that reconnect timers reset cleanly.

              const procExited = new Promise((resolve) => {
                child.once("exit", resolve);
              });

              ———

              ☑ 已收敛 unfenced 代码块解析
              ☐ 待推送远端分支
                  • 二级列表项
                      • 三级列表项

              Worked for <1s • 10:05 • Local tools: 4 calls (120ms) • Inference: 2 calls
              (680ms) • TTFT: 210ms

              ↳ Recap: 已完成无围栏代码块与多代理条目映射修复。
                Next: 运行单元测试并汇报。

            • You ran git status -sb
              └ ## main...origin/main
                M app/src/main/Foo.kt
                + 3 lines (ctrl+t to expand)

            ■ Stream disconnected before completion: transport reset by peer while reading
            SSE frame
        """.trimIndent()

        val blocks = StructuredTerminalReader.parseBlocks(codexScreen, agentApp = "codex")

        // 1. Update banner & startup header stripped; image badge attached to prompt + `! git status -sb` extracted
        val prompts = blocks.filterIsInstance<TerminalSemanticBlock.UserPromptBlock>()
        assertEquals(2, prompts.size)
        assertEquals("[Image #1] 请结合截图修复进程退出竞态并做多代理复核", prompts[0].text)
        assertEquals("! git status -sb", prompts[1].text)

        // 2. PlanUpdateCell, QuestionsResultCell, and unified Activity/Collab/Single-file-multi-hunk-diff group
        val toolGroups = blocks.filterIsInstance<TerminalSemanticBlock.ToolGroupBlock>()
        assertEquals(3, toolGroups.size)
        assertEquals("任务计划 (2/3)", toolGroups[0].summaryLabel)
        assertEquals("问答确认 (2/2)", toolGroups[1].summaryLabel)
        assertTrue(toolGroups[2].summaryLabel.contains("耗时 <1s"))
        assertTrue(toolGroups[2].hasError)

        // 3. Unfenced 2-space JS code block & `!cmd` shell output preserved as CodeOrTableBlock,
        //    while single-file diff hunks did NOT leak out of ToolGroupBlock!
        val codeBlocks = blocks.filterIsInstance<TerminalSemanticBlock.CodeOrTableBlock>()
        assertEquals(2, codeBlocks.size)
        assertEquals("代码", codeBlocks[0].kindLabel)
        assertTrue(codeBlocks[0].content.contains("const procExited = new Promise"))
        assertFalse(codeBlocks[0].content.contains("return newState"))
        assertEquals("终端输出", codeBlocks[1].kindLabel)
        assertTrue(codeBlocks[1].content.contains("## main...origin/main"))

        // 4. Headings, blockquote, wrapped English prose (with `for`!), task list, 3-level nesting, recap
        val prose = blocks.filterIsInstance<TerminalSemanticBlock.AgentProseBlock>()
        assertTrue(prose.any { it.isHeading && it.text == "来自子任务 task-auditor" })
        assertTrue(prose.any { it.isHeading && it.text == "根因与修复结论" })
        assertTrue(prose.any { it.text.startsWith("▎ 子进程退出事件必须在流关闭前完成注册") && it.text.contains("漏掉退出码") })
        // Wrapped English prose starting with `for` on line 2 stayed prose (NOT misclassified as code!)
        assertTrue(prose.any { it.text.contains("inspect the session state for each active connection") })
        // `———` rule and `Worked for <1s` / `TTFT:` / `Update available!` never leaked into prose
        assertFalse(prose.any {
            it.text.contains("———") || it.text.contains("TTFT:") ||
                it.text.contains("Update available") || it.text.contains("OpenAI Codex")
        })
        val checkedTask = prose.first { it.text.startsWith("☑") }
        assertTrue(checkedTask.isBullet && checkedTask.indentLevel == 0)
        val level2 = prose.first { it.text == "二级列表项" }
        val level3 = prose.first { it.text == "三级列表项" }
        assertEquals(1, level2.indentLevel)
        assertEquals(2, level3.indentLevel)
        assertTrue(prose.any { it.text.startsWith("会话摘要：已完成无围栏代码块") && it.text.contains("Next: 运行单元测试并汇报") })

        // 5. `■ ` error cell parsed as UrgentAlertBlock("Error", ..., isError = true)
        val alerts = blocks.filterIsInstance<TerminalSemanticBlock.UrgentAlertBlock>()
        assertEquals(1, alerts.size)
        assertEquals("Error", alerts.single().title)
        assertTrue(alerts.single().isError)
        assertTrue(alerts.single().detail.contains("Stream disconnected before completion"))
        assertTrue(alerts.single().detail.contains("SSE frame"))
    }

    @Test
    fun codexApprovalOverlay_preservesSelectedBottomOptionAndGeneratesNumberedQuickActions() {
        val blockedCodexScreen = """
            • Running git push origin main

              Would you like to run the following command?

              Reason: Pushing release tag to remote repository
              ${'$'} git push origin main

              1. Yes, proceed (y)
              2. Yes, and don't ask again for commands starting with `git push` (a)
            › 3. No, and tell Codex what to do differently (esc)

              Press enter to confirm or esc to cancel
              GPT-5-codex high · D:\luoyu\Documents\Code\HerdrAndroid
        """.trimIndent()

        val blocks = StructuredTerminalReader.parseBlocks(blockedCodexScreen, agentApp = "codex")
        // Option 3 (`› 3. No...`) must NOT be mistaken for a UserPromptBlock or stripped by bottom-dock cleaner!
        assertTrue(blocks.none { it is TerminalSemanticBlock.UserPromptBlock })
        val alert = blocks.filterIsInstance<TerminalSemanticBlock.UrgentAlertBlock>().single()
        assertEquals("需要你的授权", alert.title)
        assertFalse(alert.isError)
        assertTrue(alert.detail.contains("${'$'} git push origin main"))
        assertTrue(alert.detail.contains("3. No, and tell Codex"))

        val actions = StructuredTerminalReader.extractQuickActions(blockedCodexScreen, null)
        assertEquals("1", actions[0].payload)
        assertEquals("2", actions[1].payload)
        assertEquals("3", actions[2].payload)
        assertEquals("enter", actions[3].payload)
        assertEquals("esc", actions[4].payload)
    }

    @Test
    fun codexLiveSnapshots_handlesBareTreeConnectorStackedAndTruncatedHcPrompts() {
        val liveCodex = """
            › <hc from="t1j">请立即读取 codex_final_audit.md 并对 acp-session.mjs 做最后一遍…

            • 我先读取这次的签核要求。

            › <hc from="t19">请立即读取 codex_policy_verify_r2_prompt.md 仅核对本轮 25 行增量修复，请在 2 分钟
              内返回是否准予上线！</hc>
              <hc from="t19">开始</hc>

            • Ran Get-Content -LiteralPath 'codex_final_signoff_prompt.md' -Encoding UTF8
              └
                ## 输出要求
                请直接给出最终验收结论（**终审通过** 或 **终审不通过**）。
                + 28 lines (ctrl+t to expand)

            • 终审通过。本轮限定增量未发现新的阻断项。
        """.trimIndent()

        val blocks = StructuredTerminalReader.parseBlocks(liveCodex, agentApp = "codex")

        // Truncated `<hc ...…` (without closing `</hc>`) must NEVER swallow subsequent assistant/tool cells!
        val prompts = blocks.filterIsInstance<TerminalSemanticBlock.UserPromptBlock>()
        assertEquals(2, prompts.size)
        assertFalse(prompts[0].text.contains("我先读取"))
        assertTrue(prompts[1].text.contains("内返回是否准予上线"))
        assertTrue(prompts[1].text.contains("开始"))

        // Stdout starting with an empty line (`  └` alone) must NEVER leak `## 输出要求` into assistant prose!
        val prose = blocks.filterIsInstance<TerminalSemanticBlock.AgentProseBlock>()
        assertEquals(2, prose.size)
        assertTrue(prose[0].text.startsWith("我先读取这次的签核要求"))
        assertFalse(prose.any { it.text.contains("输出要求") || it.text.contains("<hc") })
        assertTrue(prose[1].text.startsWith("终审通过。"))
    }

    @Test
    fun antigravityBijection_parsesAttachmentsSlashOutcomesSubagentToolsCalloutsLoneMarkersAndCustomDock() {
        val agyScreen = """
            > 我这里有这个情况吗
              ▸ 📎 1 image(s) (96 KB)  (ctrl+o to expand)

            > /agents
              ⎿  Exited /agents command

            > /artifact
              ⎿  Review submitted

            ────────────────────────────────────────────────────────────
            > [Approved] ui_v030_redesign.md
              [Approved] herdr_ui_review.md

            ▾ Thought for 12s, 180 tokens (ctrl+o to collapse)
              Inspecting subagent reports and verifying task logs before replying.

            ● Spawned 2 subagents
            ● Messaged 1 subagent
            ● Scheduled 1 timer (30s: Check subagent output.)
            ● Ran 6 commands, checked 1 task, killed 1 task (Select-String -Pattern "failed" build.log) (ctrl+o to expand)
            ● Listed tasks
            └ Tip: View and review artifacts with /artifact.

              已完成连接至 192.
              168.1.249，复核了 policy.test.
              mjs:535（共 1,
              458 轮思考样本）：

              1.
              两处小修：把返回按钮上的数字徽章挪开，避免贴着箭头。
              •
              在 03:12 修改策略时写了：
                  const isCheapRation = cid === 'tide_snack' || (def && def.price / def.eat <= 234)
                  • 二级说明项
                      • 三级说明项
              │ 补充一个容易引起误判的细节：当某一步思考较短时，服务端只返回加密的
              │ thought_signature 签名块，不代表 0 思考。

            ⚠ The Canva plugin needs you to sign in. Open /plugin to sign in.

            Resume with -c (or command below):
            agy --conversation=1dc59769-31f6-4d79-8043-033d5803e5df
                                                                               1 artifact · /artifact to review
            ───────────────────────────────────────────────────────────────────────────────────────────────────
            > draft not sent yet
            ───────────────────────────────────────────────────────────────────────────────────────────────────
              ● helper · Claude Opus 5.5 · Running...
              ● [10:02:46] cron (every hour at minute 55) running
            ───────────────────────────────────────────────────────────────────────────────────────────────────
            ╭─ ● READY ╱ Gemini 4 Argon (High) ╱ main*
            ╰─ ctx ███············ 18.4% · artifacts 1 · subagents 1 · tasks 1 · sandbox off
        """.trimIndent()

        val blocks = StructuredTerminalReader.parseBlocks(agyScreen, agentApp = "antigravity")

        // 1. Prompts: `/agents -> Exited` dropped; attachment formatted; `/artifact -> Review submitted` combined;
        //    multi-artifact approvals kept on separate lines; bottom live draft stripped!
        val prompts = blocks.filterIsInstance<TerminalSemanticBlock.UserPromptBlock>()
        assertEquals(3, prompts.size)
        assertEquals("我这里有这个情况吗\n[📎 1 image(s) (96 KB)]", prompts[0].text)
        assertEquals("/artifact → Review submitted", prompts[1].text)
        assertEquals("[Approved] ui_v030_redesign.md\n[Approved] herdr_ui_review.md", prompts[2].text)

        // 2. ToolGroupBlock: expanded thought body folded, Spawned(2)+Messaged(1)+Scheduled(1)+Compound(8)+Listed(1)+Thought(1) = 14,
        //    and `"failed"` inside Select-String argument did NOT falsely set hasError!
        val tools = blocks.filterIsInstance<TerminalSemanticBlock.ToolGroupBlock>()
        assertEquals(1, tools.size)
        assertTrue(tools.single().summaryLabel.contains("14"))
        assertFalse(tools.single().hasError)
        assertFalse(tools.single().steps.any { it.contains("Tip:") })

        // 3. Inline unfenced code line extracted cleanly as CodeOrTableBlock("代码")
        val codes = blocks.filterIsInstance<TerminalSemanticBlock.CodeOrTableBlock>()
        assertEquals(1, codes.size)
        assertEquals("代码", codes.single().kindLabel)
        assertTrue(codes.single().content.startsWith("const isCheapRation"))

        // 4. Prose: wrapped IPv4 (`192.168.1.249`), filename (`policy.test.mjs:535`), comma digits (`1,458`),
        //    lone `1.` and `•` markers, 3-level nesting (`0, 1, 2`), and `│` callout separated from preceding bullet!
        val prose = blocks.filterIsInstance<TerminalSemanticBlock.AgentProseBlock>()
        val intro = prose.first()
        assertTrue(intro.text.contains("192.168.1.249"))
        assertTrue(intro.text.contains("policy.test.mjs:535"))
        assertTrue(intro.text.contains("1,458"))

        val loneOrd = prose.first { it.text.startsWith("1. 两处小修") }
        assertTrue(loneOrd.isBullet && loneOrd.indentLevel == 0)
        val loneUnord = prose.first { it.text.startsWith("在 03:12 修改策略时写了") }
        assertTrue(loneUnord.isBullet && loneUnord.indentLevel == 0)
        assertEquals(1, prose.first { it.text == "二级说明项" }.indentLevel)
        assertEquals(2, prose.first { it.text == "三级说明项" }.indentLevel)

        val callout = prose.first { it.text.startsWith("▎ 补充一个容易引起误判的细节") }
        assertFalse(callout.isBullet)
        assertTrue(callout.text.contains("thought_signature"))

        // Exit resume hint, subagent tray, artifact review hint & custom statusline never leaked!
        assertFalse(prose.any {
            it.text.contains("Resume with") || it.text.contains("--conversation") ||
                it.text.contains("sandbox off") || it.text.contains("draft not sent")
        })

        // 5. Plugin sign-in warning extracted as UrgentAlertBlock
        val alerts = blocks.filterIsInstance<TerminalSemanticBlock.UrgentAlertBlock>()
        assertEquals(1, alerts.size)
        assertEquals("需要你的授权", alerts.single().title)
        assertTrue(alerts.single().detail.contains("Canva plugin needs you to sign in"))
    }

    @Test
    fun antigravityPermissionDialog_stripsNavigateFooterAndExtractsNumberedActions() {
        val blockedAgy = """
            ● Running (powershell -File deploy.ps1)

              Allow calling this tool?

              powershell -File deploy.ps1

            > 1. Allow once
              2. Always allow for this conversation
              3. Deny

            ↑/↓ Navigate · tab Amend · e edit command
            🔧 TOOL ╱ Gemini 4 Argon (High) │ ctx █·············· 4.6% · artifacts 3 · subagents 1 · tasks 0 · sandbox off
        """.trimIndent()

        val blocks = StructuredTerminalReader.parseBlocks(blockedAgy, agentApp = "antigravity")
        assertTrue(blocks.none { it is TerminalSemanticBlock.UserPromptBlock })
        val alert = blocks.filterIsInstance<TerminalSemanticBlock.UrgentAlertBlock>().single()
        assertEquals("需要你的授权", alert.title)
        assertTrue(alert.detail.contains("Allow calling this tool?"))
        assertTrue(alert.detail.contains("1. Allow once"))
        assertFalse(alert.detail.contains("Navigate"))

        val actions = StructuredTerminalReader.extractQuickActions(blockedAgy, null)
        assertEquals("1", actions[0].payload)
        assertEquals("2", actions[1].payload)
        assertEquals("3", actions[2].payload)
        assertEquals("enter", actions[3].payload)
        assertEquals("esc", actions[4].payload)
    }

    @Test
    fun codexCtrlTTranscriptOverlay_foldsReasoningMultiLineCmdPipesAndLongStdoutWithoutLeakingIntoProse() {
        val ctrlTScreen = """
            / / / / T R A N S C R I P T / / / /

            › 检查一下为什么 Ctrl+T 展开后解析会乱

            ${"\u001B"}[2;3m• **Inspecting transcript_lines in codex-rs/tui**${"\u001B"}[0m
            ${"\u001B"}[2;3m  In compact mode, ReasoningSummaryCell returns an empty Vec from display_lines().${"\u001B"}[0m

            ${"\u001B"}[2;3m  When the user presses Ctrl+T, transcript_lines() includes every ReasoningSummaryCell.${"\u001B"}[0m

            • Ran git log -n 2 --oneline
              │ --decorate
              │ --stat
              └ 083d324 feat(terminal): transcript-verified antigravity (agy) TUI bijection parser
                app/src/main/java/dev/herdr/android/ui/terminal/AntigravityTranscriptSpec.kt | 250 +++

                54dfd38 feat(terminal): source-verified codex-rs/tui transcript bijection parser
            https://raw.githubusercontent.com/openai/codex/main/codex-rs/tui/src/pager_overlay/transcript.rs
              − Hide details

            • **Verifying EXEC_DISPLAY_LAYOUT continuation**
              Multi-line commands use "  │ " which must not be unwrapped into plain prose.

            • 已经定位到 `Ctrl+T` 展开视图下的三处核心差异，并完成了雙模式解析兼容。

              Worked for 12s • 16:40

            ~
            ~
            Line 1-24 of 24 · PgUp/PgDn scroll · / find · q or Esc close
        """.trimIndent()

        val blocks = StructuredTerminalReader.parseBlocks(ctrlTScreen, agentApp = "codex")
        val prompts = blocks.filterIsInstance<TerminalSemanticBlock.UserPromptBlock>()
        assertEquals(1, prompts.size)
        assertEquals("检查一下为什么 Ctrl+T 展开后解析会乱", prompts.single().text)

        val tools = blocks.filterIsInstance<TerminalSemanticBlock.ToolGroupBlock>()
        assertEquals(1, tools.size)
        val group = tools.single()
        // 2 ReasoningSummaryCells + 1 Ran command = 3 steps, plus Worked for 12s
        assertTrue(group.summaryLabel.contains("3"))
        assertTrue(group.summaryLabel.contains("12s"))
        assertTrue(group.steps.any { it.startsWith("思考：Inspecting transcript_lines") })
        assertTrue(group.steps.any { it.contains("│ --decorate") })
        assertTrue(group.steps.any { it.startsWith("思考：Verifying EXEC_DISPLAY_LAYOUT") })

        // Formal assistant prose has ONLY the final answer—no reasoning, tilde rows, or pager chrome!
        val prose = blocks.filterIsInstance<TerminalSemanticBlock.AgentProseBlock>()
        assertEquals(1, prose.size)
        assertTrue(prose.single().text.startsWith("已经定位到"))
        assertFalse(prose.single().text.contains("T R A N S C R I P T"))
        assertFalse(prose.single().text.contains("PgUp/PgDn"))

        // Quick actions offer "收起视图 Ctrl+T" as primary action when expanded
        val actions = StructuredTerminalReader.extractQuickActions(ctrlTScreen, null)
        assertEquals("收起视图 Ctrl+T", actions.first().label)
        assertEquals("ctrl+t", actions.first().payload)
        assertTrue(actions.first().isSpecialKey)
    }

    @Test
    fun antigravityCtrlOExpandedMode_foldsMultiParagraphThoughtsAndExpandedToolOutputsWhilePreservingFinalResponse() {
        val ctrlOAgyScreen = """
            > 看看按了 Ctrl+O 之后解析对不对

              ▾ Thought for 5s (ctrl+o to collapse)
                I need to check how Antigravity renders expanded trajectory blocks when Ctrl+O is active.

                First, expanded thoughts can have multiple paragraphs separated by blank lines.
                Second, expanded tool calls show full stdout and diffs with ↕ ··· fold dividers.

              ● Ran ./gradlew :app:testDebugUnitTest (ctrl+o to collapse)
                > Task :hc-protocol:test UP-TO-DATE
                > Task :app:testDebugUnitTest

                ${"\u001B"}[2mBUILD SUCCESSFUL in 2s${"\u001B"}[0m

              ● Edited StructuredTerminalReader.kt (ctrl+o to collapse)
                1689 - private fun consumeCodexToolGroup(orphanStart: Boolean) {
                1689 + private fun isCodexReasoningCellStart(idx: Int): Boolean {
                ↕ ···
                1820 + val isExpandedAgyTool = dialect == TerminalDialect.ANTIGRAVITY

              现在按 `Ctrl+O` 展开后的多段思考内容、命令输出以及带折叠分隔符的 Diff 都已经收敛进工具组，不会再污染正式回复正文。

            ────────────────────────────────────────────────────────────────────────────────
            >
            ────────────────────────────────────────────────────────────────────────────────
              Gemini 3.1 Pro (High) · 4.2k tokens · 94% context left · ? for help
        """.trimIndent()

        val blocks = StructuredTerminalReader.parseBlocks(ctrlOAgyScreen, agentApp = "antigravity")
        val prompts = blocks.filterIsInstance<TerminalSemanticBlock.UserPromptBlock>()
        assertEquals(1, prompts.size)
        assertEquals("看看按了 Ctrl+O 之后解析对不对", prompts.single().text)

        val tools = blocks.filterIsInstance<TerminalSemanticBlock.ToolGroupBlock>()
        assertEquals(1, tools.size)
        val group = tools.single()
        assertTrue(group.summaryLabel.contains("3"))
        assertFalse(group.hasError)
        assertTrue(group.steps.any { it.contains("First, expanded thoughts") })
        assertTrue(group.steps.any { it.contains("Task :app:testDebugUnitTest") })
        assertTrue(group.steps.any { it.contains("↕ ···") })

        val prose = blocks.filterIsInstance<TerminalSemanticBlock.AgentProseBlock>()
        assertEquals(1, prose.size)
        assertEquals(
            "现在按 `Ctrl+O` 展开后的多段思考内容、命令输出以及带折叠分隔符的 Diff 都已经收敛进工具组，不会再污染正式回复正文。",
            prose.single().text
        )

        val actions = StructuredTerminalReader.extractQuickActions(ctrlOAgyScreen, null)
        assertEquals("收起详情 Ctrl+O", actions.first().label)
        assertEquals("ctrl+o", actions.first().payload)
        assertTrue(actions.first().isSpecialKey)
    }
}
