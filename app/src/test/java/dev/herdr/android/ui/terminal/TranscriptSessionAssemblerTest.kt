package dev.herdr.android.ui.terminal

import dev.herdr.android.protocol.AgentStateWord
import dev.herdr.android.protocol.TerminalSummary
import dev.herdr.android.protocol.TranscriptAgentKind
import dev.herdr.android.protocol.TranscriptRawEvent
import dev.herdr.android.protocol.TranscriptToolCallSummary
import dev.herdr.android.ui.workbench.CodeHighlightPalette
import dev.herdr.android.ui.workbench.CodeSyntaxHighlighter
import dev.herdr.android.ui.workbench.DiffLineKind
import dev.herdr.android.ui.workbench.HERDR_LINK_TAG
import dev.herdr.android.ui.workbench.buildRichMarkdownAnnotatedString
import dev.herdr.android.ui.workbench.parseUnifiedDiffLines
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.*
import org.junit.Test

class TranscriptSessionAssemblerTest {

    @Test
    fun richMarkdownParserHandlesFourBacktickFencesAndGitHubAlerts() {
        val md = """
            ### 核心方案

            > [!IMPORTANT]
            > 请勿使用 WSL；仅调用 [RemoteTranscriptSidecar.kt](file:///D:/Code/RemoteTranscriptSidecar.kt)。

            ````markdown
            外层包裹内层代码块：
            ```kotlin
            val ok = true
            ```
            ````

            | 模块 | 状态 |
            | :--- | :---: |
            | `:connection` | 就绪 |
        """.trimIndent()

        val segs = BtwRichContentParser.parse(md)
        assertTrue(segs.any { it is BtwContentSegment.Heading && it.text == "核心方案" })
        val quote = segs.filterIsInstance<BtwContentSegment.Blockquote>().single()
        assertEquals("IMPORTANT", quote.alertType)
        assertTrue(quote.text.contains("RemoteTranscriptSidecar.kt"))

        val code = segs.filterIsInstance<BtwContentSegment.CodeBlock>().single()
        assertEquals("markdown", code.language)
        assertTrue(code.code.contains("```kotlin"))
        assertTrue(code.code.contains("val ok = true"))

        val table = segs.filterIsInstance<BtwContentSegment.Table>().single()
        assertEquals(listOf("模块", "状态"), table.headers)
        assertEquals(listOf(listOf("`:connection`", "就绪")), table.rows)
    }

    @Test
    fun assembleBuildsMultiTurnConversationWithThoughtsToolsErrorsTokensAndDuration() {
        val events = listOf(
            TranscriptRawEvent.UserMessage(
                lineByteOffset = 0L,
                stepIndex = 0,
                createdAt = "2026-11-23T01:00:00Z",
                text = "请检查协议并修改 Sidecar",
                modelChange = "Gemini 4 Argon (High)"
            ),
            TranscriptRawEvent.AssistantStep(
                lineByteOffset = 200L,
                stepIndex = 1,
                createdAt = "2026-11-23T01:00:05Z",
                thinking = "**Investigating Sidecar**\nChecking byte seek alignment.",
                toolCalls = listOf(
                    TranscriptToolCallSummary(
                        callId = "step_2",
                        name = "read_file",
                        primaryTarget = "RemoteTranscriptSidecar.kt",
                        detailPreview = "L1..100",
                        resultStepIndex = 2
                    ),
                    TranscriptToolCallSummary(
                        callId = "step_3",
                        name = "edit_file",
                        primaryTarget = "RemoteTranscriptSidecar.kt",
                        detailPreview = "+12 -3 行",
                        changedFile = "RemoteTranscriptSidecar.kt",
                        diffPreview = "@@ -1,1 +1,1 @@\n-oldCall()\n+newCall()",
                        resultStepIndex = 3
                    )
                ),
                inputTokens = 1500L,
                cacheReadTokens = 500L,
                outputTokens = 180L
            ),
            TranscriptRawEvent.ToolStatusUpdate(
                lineByteOffset = 350L,
                callId = "step_3",
                hasError = true
            ),
            TranscriptRawEvent.AssistantStep(
                lineByteOffset = 500L,
                stepIndex = 4,
                createdAt = "2026-11-23T01:01:05Z",
                status = "DONE",
                markdownContent = "已完成改动。\n\n```kotlin\nfun sync() = Unit\n```",
                inputTokens = 2100L,
                cacheReadTokens = 900L,
                outputTokens = 320L
            )
        )

        val (blocks, turns) = TranscriptSessionAssembler.assemble(
            events = events,
            agentKind = TranscriptAgentKind.ANTIGRAVITY,
            isLatestRunning = false
        )

        assertTrue(blocks.isNotEmpty())
        assertEquals(1, turns.size)
        val t = turns.single()
        assertEquals("请检查协议并修改 Sidecar", t.userPrompt?.text)
        assertEquals(2, t.toolOperationCount)
        assertTrue(t.hasError)
        assertEquals("1m 5s", t.durationLabel)
        assertEquals("Gemini 4 Argon (High)", t.modelLabel)
        assertEquals("入 3.0k / 出 500", t.tokenSummaryLabel)
        assertEquals(listOf("RemoteTranscriptSidecar.kt"), t.changedFiles)
        assertTrue(t.isRawMarkdownSource)

        val toolGroup = t.responseBlocks.filterIsInstance<TerminalSemanticBlock.ToolGroupBlock>().single()
        assertTrue(toolGroup.hasError)
        assertEquals(2, toolGroup.entries.first { it.title.startsWith("读取") }.resultStepIndex)
        val editEntry = toolGroup.entries.first { it.title.startsWith("编辑") }
        assertTrue(editEntry.hasError)
        assertTrue(editEntry.diffPreview.orEmpty().contains("+newCall()"))

        val mdDoc = t.responseBlocks.filterIsInstance<TerminalSemanticBlock.MarkdownDocumentBlock>().single()
        val codeBlock = mdDoc.nodes.filterIsInstance<MarkdownBlockNode.CodeBlock>().single()
        assertEquals("kotlin", codeBlock.language)
        assertEquals("fun sync() = Unit", codeBlock.code)
    }

    @Test
    fun fuseWithLiveWorkbenchKeepsRawMarkdownHistoryWhileAppendingInflightScreenTurnAndAskCard() {
        val summary = TerminalSummary(
            address = "t1q",
            agentApp = "antigravity",
            agentStatus = AgentStateWord.BLOCKED,
            cwd = "D:\\Work",
            turn = 2
        )
        val liveAsk = AgyAskCardState(
            questionIndex = 1,
            totalQuestions = 1,
            questionText = "是否允许执行 Gradle 测试？",
            isMultiSelect = false,
            options = listOf(AgyAskOption(1, "允许", isCursor = true))
        )
        val liveSnap = WorkbenchSnapshot(
            blocks = listOf(
                TerminalSemanticBlock.UserPromptBlock("请修复并跑测试"),
                TerminalSemanticBlock.AskCardBlock(liveAsk)
            ),
            quickActions = listOf(QuickReplyAction("允许", "1", isPrimary = true)),
            liveStatus = AgentStateWord.BLOCKED,
            isExpandedMode = false,
            dialect = TerminalDialect.ANTIGRAVITY,
            turns = listOf(
                ConversationTurn(
                    turnIndex = 1,
                    userPrompt = TerminalSemanticBlock.UserPromptBlock("请修复并跑测试"),
                    responseBlocks = listOf(TerminalSemanticBlock.AskCardBlock(liveAsk)),
                    hasInteractiveCard = true
                )
            )
        )

        // Fallback cleanly when transcript has no events yet
        val fallback = TranscriptSessionAssembler.fuseWithLiveWorkbench(null, liveSnap, summary)
        assertFalse(fallback.isRawMarkdownActive)
        assertSame(liveSnap, fallback)

        // Side-channel has Turn 1, screen matches Turn 1 prompt + active AskCard + another unflushed prompt scenario
        val txState = TerminalTranscriptUiState(
            address = "t1q",
            agentKind = TranscriptAgentKind.ANTIGRAVITY,
            path = "C:\\transcript_full.jsonl",
            events = listOf(
                TranscriptRawEvent.UserMessage(0L, stepIndex = 0, text = "请修复并跑测试"),
                TranscriptRawEvent.AssistantStep(
                    lineByteOffset = 100L,
                    stepIndex = 1,
                    markdownContent = "请先确认以下问题："
                )
            )
        )
        val fused = TranscriptSessionAssembler.fuseWithLiveWorkbench(txState, liveSnap, summary)
        assertTrue(fused.isRawMarkdownActive)
        assertEquals(AgentStateWord.BLOCKED, fused.liveStatus)
        assertEquals(1, fused.quickActions.size)
        assertEquals(1, fused.turns.size)
        assertTrue(fused.turns.single().hasInteractiveCard)
        assertTrue(fused.turns.single().responseBlocks.any { it is TerminalSemanticBlock.AskCardBlock })
        assertTrue(
            fused.turns.single().responseBlocks.any { blk ->
                blk is TerminalSemanticBlock.MarkdownDocumentBlock &&
                    blk.nodes.any { node -> node is MarkdownBlockNode.Paragraph && node.text == "请先确认以下问题：" }
            }
        )

        // Unflushed new screen turn while WORKING merges Turn 2 after raw Markdown Turn 1
        val liveTurn2Snapshot = WorkbenchSnapshot(
            blocks = listOf(
                TerminalSemanticBlock.UserPromptBlock("继续推进第二阶段"),
                TerminalSemanticBlock.AgentProseBlock("正在同步...")
            ),
            quickActions = emptyList(),
            liveStatus = AgentStateWord.WORKING,
            isExpandedMode = false,
            dialect = TerminalDialect.ANTIGRAVITY,
            turns = listOf(
                ConversationTurn(
                    turnIndex = 2,
                    userPrompt = TerminalSemanticBlock.UserPromptBlock("继续推进第二阶段"),
                    responseBlocks = listOf(TerminalSemanticBlock.AgentProseBlock("正在同步...")),
                    isRunning = true
                )
            ),
            liveWorkingActivity = LiveWorkingActivity(title = "执行增量读取", category = ToolActionCategory.COMMAND)
        )
        val fusedWithUnflushedTurn = TranscriptSessionAssembler.fuseWithLiveWorkbench(
            txState,
            liveTurn2Snapshot,
            summary.copy(agentStatus = AgentStateWord.WORKING)
        )
        assertTrue(fusedWithUnflushedTurn.isRawMarkdownActive)
        assertEquals(2, fusedWithUnflushedTurn.turns.size)
        assertEquals("请修复并跑测试", fusedWithUnflushedTurn.turns[0].userPrompt?.text)
        assertEquals("继续推进第二阶段", fusedWithUnflushedTurn.turns[1].userPrompt?.text)
        assertTrue(fusedWithUnflushedTurn.turns[1].isRunning)
        assertNotNull(fusedWithUnflushedTurn.liveWorkingActivity)
    }

    @Test
    fun markdownDocumentParserHandlesHeadingsRulesCheckboxesNestedListsAlertsAndFourBackticks() {
        val raw = """
            # 总览标题
            ## 二级模块
            ### 三级细节
            #### 四级标签

            ---

            > [!WARNING]
            > 请保留 Windows 原生命令链路。

            - [x] 已接入旁路解析
            - [ ] 待真机联调
              - 嵌套子项 1
              - 嵌套子项 2
            1. 第一步
            2. 第二步

            ````markdown
            外层说明：
            ```kotlin
            val ok = true
            ```
            ````

            | 组件 | 结果 |
            | :--- | ---: |
            | Sidecar | 100% |
        """.trimIndent()

        val nodes = MarkdownDocumentParser.parse(raw)
        val headings = nodes.filterIsInstance<MarkdownBlockNode.Heading>()
        assertEquals(listOf(1, 2, 3, 4), headings.map { it.level })
        assertEquals("总览标题", headings[0].text)
        assertTrue(nodes.any { it === MarkdownBlockNode.HorizontalRule })

        val quote = nodes.filterIsInstance<MarkdownBlockNode.QuoteBlock>().single()
        assertEquals("WARNING", quote.alertType)
        assertEquals(listOf("请保留 Windows 原生命令链路。"), quote.lines)

        val listBlock = nodes.filterIsInstance<MarkdownBlockNode.ListBlock>().single()
        assertEquals(6, listBlock.items.size)
        assertEquals(true, listBlock.items[0].checked)
        assertEquals("已接入旁路解析", listBlock.items[0].text)
        assertEquals(false, listBlock.items[1].checked)
        assertEquals(1, listBlock.items[2].indentLevel)
        assertTrue(listBlock.items[4].ordered)
        assertEquals(1, listBlock.items[4].ordinal)

        val code = nodes.filterIsInstance<MarkdownBlockNode.CodeBlock>().single()
        assertEquals("markdown", code.language)
        assertTrue(code.code.contains("```kotlin"))

        val table = nodes.filterIsInstance<MarkdownBlockNode.TableBlock>().single()
        assertEquals(listOf("组件", "结果"), table.table.headers)
        assertEquals(listOf(listOf("Sidecar", "100%")), table.table.rows)
    }

    @Test
    fun codeSyntaxHighlighterStylesKotlinJsonAndDiff() {
        val palette = CodeHighlightPalette(
            keyword = Color(0xFF0000FF),
            string = Color(0xFF008800),
            comment = Color(0xFF888888),
            number = Color(0xFFFF8800),
            type = Color(0xFF00AAAA),
            annotation = Color(0xFFAA00AA),
            diffAddFg = Color(0xFF00AA00),
            diffAddBg = Color(0x3300AA00),
            diffDelFg = Color(0xFFAA0000),
            diffDelBg = Color(0x33AA0000),
            diffHunkFg = Color(0xFF0000AA),
            diffMetaFg = Color(0xFF666666)
        )

        val ktOut = CodeSyntaxHighlighter.highlight(
            code = "@Composable\nfun Demo(): String {\n    // comment\n    return \"hi\" + 42\n}",
            language = "kt",
            palette = palette
        )
        assertTrue(ktOut.spanStyles.any { it.item.color == palette.annotation })
        assertTrue(ktOut.spanStyles.any { it.item.color == palette.keyword })
        assertTrue(ktOut.spanStyles.any { it.item.color == palette.type })
        assertTrue(ktOut.spanStyles.any { it.item.color == palette.comment })
        assertTrue(ktOut.spanStyles.any { it.item.color == palette.string })
        assertTrue(ktOut.spanStyles.any { it.item.color == palette.number })

        val diffOut = CodeSyntaxHighlighter.highlight(
            code = "@@ -1,2 +1,2 @@\n-val a = 1\n+val a = 2",
            language = "diff",
            palette = palette
        )
        assertTrue(diffOut.spanStyles.any { it.item.color == palette.diffHunkFg })
        assertTrue(diffOut.spanStyles.any { it.item.color == palette.diffDelFg })
        assertTrue(diffOut.spanStyles.any { it.item.color == palette.diffAddFg })
    }

    @Test
    fun incrementalAssemblyReusesCompletedTurnsWithoutReparsing() {
        val u1 = TranscriptRawEvent.UserMessage(lineByteOffset = 1000L, stepIndex = 2, text = "第一轮任务")
        val a1 = TranscriptRawEvent.AssistantStep(
            lineByteOffset = 1100L,
            stepIndex = 3,
            status = "DONE",
            markdownContent = "### 第一轮完成\n\n```kotlin\nval one = 1\n```"
        )
        val u2 = TranscriptRawEvent.UserMessage(lineByteOffset = 1250L, stepIndex = 4, text = "第二轮任务")
        val a2First = TranscriptRawEvent.AssistantStep(
            lineByteOffset = 1350L,
            stepIndex = 5,
            status = "PENDING",
            markdownContent = "正在处理"
        )
        val state1 = TerminalTranscriptUiState(
            address = "t1q",
            agentKind = TranscriptAgentKind.ANTIGRAVITY,
            sessionId = "sess-1",
            path = "/tmp/transcript.jsonl",
            headOffset = 1000L,
            tailOffset = 1450L,
            events = listOf(u1, a1, u2, a2First)
        )
        val cache = IncrementalTranscriptAssemblyCache()
        val (_, turns1) = TranscriptSessionAssembler.assembleIncremental(state1, isLatestRunning = true, cache = cache)
        assertEquals(2, turns1.size)

        // Append delta to Turn 2: Turn 1 instance MUST be reused verbatim (zero re-parse)
        val a2Second = TranscriptRawEvent.AssistantStep(
            lineByteOffset = 1500L,
            stepIndex = 6,
            status = "DONE",
            markdownContent = "正在处理\n\n已完成全部优化"
        )
        val state2 = state1.copy(tailOffset = 1650L, events = listOf(u1, a1, u2, a2First, a2Second))
        val (_, turns2) = TranscriptSessionAssembler.assembleIncremental(state2, isLatestRunning = false, cache = cache)
        assertEquals(2, turns2.size)
        assertSame(turns1[0], turns2[0])
        assertSame(turns1[0].responseBlocks, turns2[0].responseBlocks)

        // Prepend earlier Turn 0 (shifting turnIndex from 1 -> 2): Turn 1 responseBlocks STILL reused verbatim!
        val u0 = TranscriptRawEvent.UserMessage(lineByteOffset = 0L, stepIndex = 0, text = "前置历史轮次")
        val a0 = TranscriptRawEvent.AssistantStep(lineByteOffset = 100L, stepIndex = 1, status = "DONE", markdownContent = "历史结果")
        val stateShifted = state2.copy(
            headOffset = 0L,
            events = listOf(u0, a0, u1, a1, u2, a2First, a2Second)
        )
        val (_, turns3) = TranscriptSessionAssembler.assembleIncremental(stateShifted, isLatestRunning = false, cache = cache)
        assertEquals(3, turns3.size)
        assertEquals(2, turns3[1].turnIndex)
        assertSame(turns1[0].responseBlocks, turns3[1].responseBlocks)
    }

    @Test
    fun recursiveInlineMarkdownHandlesCodeInsideBoldAndFileLinks() {
        val codeBg = Color(0xFFEEEEEE)
        val codeFg = Color(0xFF0055AA)
        val linkFg = Color(0xFF0077CC)
        val strikeFg = Color(0xFF888888)

        val input = "已改造为 **SSHJ `SFTPClient` + Kotlin 本地解析**，详见 [RemoteTranscriptSidecar.kt](file:///D:/Code/RemoteTranscriptSidecar.kt#L179)。"
        val out = buildRichMarkdownAnnotatedString(input, codeBg, codeFg, linkFg, strikeFg)
        val rendered = out.text

        assertFalse("Raw backticks must not appear: $rendered", rendered.contains('`'))
        assertFalse("Raw asterisks must not appear: $rendered", rendered.contains("**"))
        assertTrue(rendered.contains("SSHJ "))
        assertTrue(rendered.contains("SFTPClient"))
        assertTrue("File pill with line hint expected in: $rendered", rendered.contains("RemoteTranscriptSidecar.kt:179"))

        val codeStart = rendered.indexOf("SFTPClient")
        assertTrue(codeStart >= 0)
        val stylesAtCode = out.spanStyles.filter { it.start <= codeStart && it.end >= codeStart + "SFTPClient".length }
        assertTrue(stylesAtCode.any { it.item.fontWeight == FontWeight.SemiBold })
        assertTrue(stylesAtCode.any { it.item.fontFamily == FontFamily.Monospace })

        val links = out.getStringAnnotations(HERDR_LINK_TAG, 0, out.length)
        assertEquals(1, links.size)
        assertEquals("D:/Code/RemoteTranscriptSidecar.kt#L179", links.single().item)
    }

    @Test
    fun unifiedDiffParserTracksLineNumbersAndIntraLineWordDiff() {
        val diff = """
            @@ -10,3 +10,3 @@ fun sync() {
             val keep = true
            -val timeout = 1500
            +val timeout = 12000
        """.trimIndent()

        val parsed = parseUnifiedDiffLines(diff)
        assertEquals(4, parsed.size)
        assertEquals(DiffLineKind.HUNK, parsed[0].kind)

        assertEquals(DiffLineKind.CONTEXT, parsed[1].kind)
        assertEquals(10, parsed[1].oldLineNo)
        assertEquals(10, parsed[1].newLineNo)

        val delLine = parsed[2]
        assertEquals(DiffLineKind.DEL, delLine.kind)
        assertEquals(11, delLine.oldLineNo)
        assertNull(delLine.newLineNo)
        assertEquals("1500", delLine.code.substring(delLine.strongRange!!))

        val addLine = parsed[3]
        assertEquals(DiffLineKind.ADD, addLine.kind)
        assertNull(addLine.oldLineNo)
        assertEquals(11, addLine.newLineNo)
        assertEquals("12000", addLine.code.substring(addLine.strongRange!!))
    }
}
