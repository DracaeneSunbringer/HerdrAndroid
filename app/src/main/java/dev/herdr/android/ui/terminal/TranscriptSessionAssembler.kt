package dev.herdr.android.ui.terminal

import dev.herdr.android.protocol.AgentStateWord
import dev.herdr.android.protocol.TerminalSummary
import dev.herdr.android.protocol.TranscriptAgentKind
import dev.herdr.android.protocol.TranscriptRawEvent
import dev.herdr.android.protocol.TranscriptToolCallSummary
import java.util.Locale

data class TerminalTranscriptUiState(
    val address: String,
    val agentKind: TranscriptAgentKind,
    val sessionId: String = "",
    val path: String = "",
    val headOffset: Long = 0L,
    val tailOffset: Long = 0L,
    val fileSize: Long = 0L,
    val hasEarlier: Boolean = false,
    val isLoadingEarlier: Boolean = false,
    val locateAttempted: Boolean = false,
    val events: List<TranscriptRawEvent> = emptyList(),
    val stepOutputs: Map<Int, String> = emptyMap(),
    val loadingSteps: Set<Int> = emptySet(),
    val activeModel: String? = null
)

object TranscriptSessionAssembler {

    private val boldHeadingRegex = Regex("\\*\\*([^*\\n]{2,64})\\*\\*")
    private val whitespaceRegex = Regex("\\s+")
    private val hcWrapperRegex = Regex("^<hc\\b[^>]*>([\\s\\S]*?)(?:</hc>)?\\s*$")
    private val isoUtcRegex = Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})")
    private val orderedMarkerRegex = Regex("^\\d+\\.$")

    fun assemble(
        events: List<TranscriptRawEvent>,
        agentKind: TranscriptAgentKind,
        isLatestRunning: Boolean,
        initialModel: String? = null
    ): Pair<List<TerminalSemanticBlock>, List<ConversationTurn>> {
        if (events.isEmpty()) return emptyList<TerminalSemanticBlock>() to emptyList()

        val sorted = deduplicateAndSort(events)
        val errorCallIds = HashSet<String>()
        for (ev in sorted) {
            if (ev is TranscriptRawEvent.ToolStatusUpdate && ev.hasError) {
                errorCallIds.add(ev.callId)
            }
        }

        data class MutableTurnSection(
            val userPrompt: TerminalSemanticBlock.UserPromptBlock?,
            var startIso: String?,
            var endIso: String?,
            var model: String?,
            var inputTokens: Long = 0L,
            var cacheReadTokens: Long = 0L,
            var outputTokens: Long = 0L,
            val events: MutableList<TranscriptRawEvent.AssistantStep> = ArrayList()
        )

        var runningModel = initialModel
        val sections = ArrayList<MutableTurnSection>()
        var current: MutableTurnSection? = null

        for (ev in sorted) {
            when (ev) {
                is TranscriptRawEvent.UserMessage -> {
                    if (!ev.modelChange.isNullOrBlank()) {
                        runningModel = ev.modelChange
                    }
                    if (ev.text.isNotBlank()) {
                        val sec = MutableTurnSection(
                            userPrompt = TerminalSemanticBlock.UserPromptBlock(ev.text),
                            startIso = ev.createdAt,
                            endIso = ev.createdAt,
                            model = runningModel
                        )
                        sections.add(sec)
                        current = sec
                    } else if (current != null && !runningModel.isNullOrBlank()) {
                        current.model = runningModel
                    }
                }
                is TranscriptRawEvent.TokenUpdate -> {
                    if (!ev.model.isNullOrBlank()) {
                        runningModel = if (!ev.effort.isNullOrBlank()) {
                            "${ev.model} (${ev.effort})"
                        } else {
                            ev.model
                        }
                        if (current != null && current.model.isNullOrBlank()) {
                            current.model = runningModel
                        }
                    }
                    if (current != null && (ev.inputTokens > 0L || ev.outputTokens > 0L || ev.cachedInputTokens > 0L)) {
                        current.inputTokens = ev.inputTokens
                        current.cacheReadTokens = ev.cachedInputTokens
                        current.outputTokens = maxOf(current.outputTokens, ev.outputTokens)
                    }
                }
                is TranscriptRawEvent.AssistantStep -> {
                    val target = current ?: MutableTurnSection(
                        userPrompt = null,
                        startIso = ev.createdAt,
                        endIso = ev.createdAt,
                        model = runningModel
                    ).also {
                        sections.add(it)
                        current = it
                    }
                    if (!ev.model.isNullOrBlank()) {
                        runningModel = ev.model
                        target.model = ev.model
                    } else if (target.model.isNullOrBlank() && !runningModel.isNullOrBlank()) {
                        target.model = runningModel
                    }
                    if (!ev.createdAt.isNullOrBlank()) {
                        if (target.startIso.isNullOrBlank()) target.startIso = ev.createdAt
                        target.endIso = ev.createdAt
                    }
                    if (ev.inputTokens > 0L || ev.cacheReadTokens > 0L) {
                        target.inputTokens = ev.inputTokens
                        target.cacheReadTokens = ev.cacheReadTokens
                    }
                    if (ev.outputTokens > 0L) {
                        target.outputTokens += ev.outputTokens
                    }
                    target.events.add(ev)
                }
                is TranscriptRawEvent.ToolStatusUpdate -> Unit
            }
        }

        val allBlocks = ArrayList<TerminalSemanticBlock>()
        val turns = ArrayList<ConversationTurn>(sections.size)

        sections.forEachIndexed { idx, sec ->
            val isLatest = idx == sections.lastIndex
            val turnRunning = isLatest && isLatestRunning
            val bodyBlocks = buildTurnResponseBlocks(
                steps = sec.events,
                errorCallIds = errorCallIds,
                isTurnRunning = turnRunning
            )
            if (sec.userPrompt != null) allBlocks.add(sec.userPrompt)
            allBlocks.addAll(bodyBlocks)

            val durLabel = formatDurationBetween(sec.startIso, sec.endIso)
            val tokLabel = formatTokenSummary(sec.inputTokens, sec.cacheReadTokens, sec.outputTokens)
            val modelLabel = sec.model?.let(::formatCompactModelName)

            val turn = assembleTurnStats(
                turnIndex = idx + 1,
                prompt = sec.userPrompt,
                body = bodyBlocks,
                isLatestRunning = turnRunning,
                durationOverride = durLabel,
                modelLabel = modelLabel,
                tokenSummaryLabel = tokLabel
            )
            turns.add(turn)
        }

        return allBlocks to turns
    }

    fun fuseWithLiveWorkbench(
        transcriptState: TerminalTranscriptUiState?,
        liveSnapshot: WorkbenchSnapshot,
        summary: TerminalSummary?
    ): WorkbenchSnapshot {
        if (transcriptState == null || transcriptState.events.isEmpty()) {
            return liveSnapshot
        }
        val isWorking = liveSnapshot.liveStatus == AgentStateWord.WORKING
        val (rawBlocks, rawTurns) = assemble(
            events = transcriptState.events,
            agentKind = transcriptState.agentKind,
            isLatestRunning = isWorking,
            initialModel = transcriptState.activeModel
        )
        if (rawTurns.isEmpty()) return liveSnapshot

        val mergedBlocks = rawBlocks.toMutableList()
        val mergedTurns = rawTurns.toMutableList()
        val liveLastTurn = liveSnapshot.turns.lastOrNull()

        if (liveLastTurn != null) {
            val lastSidePrompt = mergedTurns.last().userPrompt?.text
            val livePrompt = liveLastTurn.userPrompt
            val promptUnflushed = livePrompt != null && !promptsMatch(lastSidePrompt, livePrompt.text)

            if (promptUnflushed) {
                val nextIndex = mergedTurns.size + 1
                mergedBlocks.add(livePrompt!!)
                mergedBlocks.addAll(liveLastTurn.responseBlocks)
                mergedTurns.add(
                    liveLastTurn.copy(
                        turnIndex = nextIndex,
                        isRunning = isWorking || liveLastTurn.isRunning
                    )
                )
            } else {
                val interactiveExtras = liveLastTurn.responseBlocks.filter {
                    it is TerminalSemanticBlock.AskCardBlock || it is TerminalSemanticBlock.UrgentAlertBlock
                }.filter { extra ->
                    if (extra is TerminalSemanticBlock.UrgentAlertBlock) {
                        mergedTurns.last().responseBlocks.none {
                            it is TerminalSemanticBlock.UrgentAlertBlock && it.detail == extra.detail
                        }
                    } else {
                        true
                    }
                }
                if (interactiveExtras.isNotEmpty()) {
                    val lastTurn = mergedTurns.last()
                    val updatedBody = lastTurn.responseBlocks + interactiveExtras
                    mergedBlocks.addAll(interactiveExtras)
                    mergedTurns[mergedTurns.lastIndex] = lastTurn.copy(
                        responseBlocks = updatedBody,
                        hasInteractiveCard = lastTurn.hasInteractiveCard ||
                            interactiveExtras.any { it is TerminalSemanticBlock.AskCardBlock },
                        hasError = lastTurn.hasError ||
                            interactiveExtras.any { it is TerminalSemanticBlock.UrgentAlertBlock && it.isError }
                    )
                }
            }
        }

        val updatedLiveActivity = if (isWorking) {
            val latestSteps = mergedTurns.lastOrNull()?.toolOperationCount ?: 0
            liveSnapshot.liveWorkingActivity?.let { act ->
                act.copy(totalStepsInTurn = maxOf(act.totalStepsInTurn, latestSteps))
            } ?: LiveWorkingActivity(
                title = summary?.agentMessage?.takeIf { it.isNotBlank() } ?: "Agent 正在思考与执行…",
                category = ToolActionCategory.COMMAND,
                totalStepsInTurn = latestSteps
            )
        } else {
            null
        }

        return liveSnapshot.copy(
            blocks = mergedBlocks,
            turns = mergedTurns,
            liveWorkingActivity = updatedLiveActivity,
            isRawMarkdownActive = true
        )
    }

    private fun buildTurnResponseBlocks(
        steps: List<TranscriptRawEvent.AssistantStep>,
        errorCallIds: Set<String>,
        isTurnRunning: Boolean
    ): List<TerminalSemanticBlock> {
        val out = ArrayList<TerminalSemanticBlock>()
        val pendingThoughtEntries = ArrayList<ToolStepEntry>()
        val pendingToolEntries = ArrayList<ToolStepEntry>()
        var pendingHasError = false

        fun flushPendingGroup(isGroupRunning: Boolean) {
            val combinedEntries = pendingThoughtEntries + pendingToolEntries
            if (combinedEntries.isEmpty()) return

            val onlyThought = pendingToolEntries.isEmpty()
            val files = LinkedHashSet<String>()
            val counts = LinkedHashMap<ToolActionCategory, Int>()
            for (e in pendingToolEntries) {
                counts[e.category] = (counts[e.category] ?: 0) + 1
                files.addAll(e.changedFiles)
            }

            if (onlyThought) {
                val allLines = combinedEntries.flatMap { listOf(it.title) + it.detailLines }
                val firstBold = allLines.firstNotNullOfOrNull { ln ->
                    boldHeadingRegex.find(ln)?.groupValues?.get(1)?.trim()
                }
                val preview = firstBold ?: combinedEntries.lastOrNull()?.let { e ->
                    e.detailLines.firstOrNull { it.isNotBlank() } ?: e.title
                }?.replace("**", "")?.trim()?.take(72)
                out.add(
                    TerminalSemanticBlock.ToolGroupBlock(
                        summaryLabel = if (isGroupRunning) "思考中…" else "思考过程 (${combinedEntries.size} 段)",
                        steps = allLines,
                        hasError = pendingHasError,
                        entries = combinedEntries,
                        isPureThought = true,
                        thoughtSummaryLabel = if (isGroupRunning) "思考中…" else "思考过程",
                        thoughtPreview = preview,
                        isRunning = isGroupRunning
                    )
                )
            } else {
                val breakdown = buildActionBreakdown(counts, files.size, pendingHasError, isGroupRunning)
                val thoughtCount = pendingThoughtEntries.size
                val last = pendingToolEntries.last()
                out.add(
                    TerminalSemanticBlock.ToolGroupBlock(
                        summaryLabel = breakdown,
                        steps = combinedEntries.map { it.title },
                        hasError = pendingHasError,
                        entries = combinedEntries,
                        isPureThought = false,
                        thoughtSummaryLabel = if (thoughtCount > 0) "含 $thoughtCount 段思考" else null,
                        actionBreakdownLabel = breakdown,
                        changedFiles = files.toList(),
                        isRunning = isGroupRunning,
                        activeStepTitle = last.title,
                        activeStepTailLines = last.detailLines.takeLast(4)
                    )
                )
            }
            pendingThoughtEntries.clear()
            pendingToolEntries.clear()
            pendingHasError = false
        }

        steps.forEachIndexed { stepIdx, step ->
            val isLastStep = stepIdx == steps.lastIndex
            if (step.thinking.isNotBlank()) {
                val lines = step.thinking.trim().lines().map { it.trimEnd() }.filter { it.isNotBlank() }
                val title = lines.firstOrNull()?.replace("**", "")?.trim()?.take(96) ?: "思考过程"
                pendingThoughtEntries.add(
                    ToolStepEntry(
                        title = title,
                        category = ToolActionCategory.THOUGHT,
                        detailLines = if (lines.size > 1) lines.drop(1).take(120) else emptyList(),
                        isRunning = isTurnRunning && isLastStep && step.toolCalls.isEmpty() && step.markdownContent.isBlank()
                    )
                )
            }

            if (step.toolCalls.isNotEmpty()) {
                step.toolCalls.forEachIndexed { tcIdx, tc ->
                    val failed = tc.hasError ||
                        (tc.callId != null && tc.callId in errorCallIds) ||
                        (step.status == "ERROR" && step.markdownContent.isBlank() && tcIdx == step.toolCalls.lastIndex)
                    if (failed) pendingHasError = true
                    val cat = classifyToolCategory(tc.name)
                    val title = formatToolEntryTitle(tc)
                    val details = listOfNotNull(tc.detailPreview.takeIf { it.isNotBlank() })
                    pendingToolEntries.add(
                        ToolStepEntry(
                            title = title,
                            category = cat,
                            detailLines = details,
                            changedFiles = listOfNotNull(tc.changedFile?.takeIf { it.isNotBlank() }),
                            isRunning = isTurnRunning && isLastStep && step.markdownContent.isBlank() && tcIdx == step.toolCalls.lastIndex,
                            hasError = failed,
                            resultStepIndex = tc.resultStepIndex
                        )
                    )
                }
            }

            val hasMarkdown = step.markdownContent.isNotBlank()
            val hasErrorMsg = !step.errorText.isNullOrBlank()
            if (hasMarkdown || hasErrorMsg) {
                flushPendingGroup(isGroupRunning = false)
                if (hasMarkdown) {
                    out.addAll(markdownToSemanticBlocks(step.markdownContent))
                }
                if (hasErrorMsg) {
                    out.add(
                        TerminalSemanticBlock.UrgentAlertBlock(
                            title = "执行异常",
                            detail = step.errorText.orEmpty(),
                            isError = true
                        )
                    )
                }
            }
        }

        flushPendingGroup(isGroupRunning = isTurnRunning)
        return out
    }

    internal fun markdownToSemanticBlocks(markdown: String): List<TerminalSemanticBlock> {
        val segments = BtwRichContentParser.parse(markdown)
        if (segments.isEmpty()) {
            return if (markdown.isNotBlank()) listOf(TerminalSemanticBlock.AgentProseBlock(markdown.trim())) else emptyList()
        }
        val blocks = ArrayList<TerminalSemanticBlock>(segments.size * 2)
        for (seg in segments) {
            when (seg) {
                is BtwContentSegment.Table -> {
                    blocks.add(TerminalSemanticBlock.CodeOrTableBlock(content = seg.rawText, kindLabel = "表格"))
                }
                is BtwContentSegment.CodeBlock -> {
                    blocks.add(
                        TerminalSemanticBlock.CodeOrTableBlock(
                            content = seg.code,
                            kindLabel = seg.language?.takeIf { it.isNotBlank() } ?: "代码"
                        )
                    )
                }
                is BtwContentSegment.Heading -> {
                    blocks.add(TerminalSemanticBlock.AgentProseBlock(text = seg.text, isHeading = true))
                }
                is BtwContentSegment.BulletList -> {
                    for (item in seg.items) {
                        val isOrd = orderedMarkerRegex.matches(item.marker.trim())
                        val body = if (isOrd) "${item.marker.trim()} ${item.text}" else item.text
                        blocks.add(
                            TerminalSemanticBlock.AgentProseBlock(
                                text = body,
                                isBullet = true,
                                indentLevel = item.indentLevel
                            )
                        )
                    }
                }
                is BtwContentSegment.Blockquote -> {
                    val text = if (seg.alertType != null) {
                        "▎ [!${seg.alertType}] ${seg.text}".trim()
                    } else {
                        "▎ ${seg.text}"
                    }
                    blocks.add(TerminalSemanticBlock.AgentProseBlock(text = text))
                }
                is BtwContentSegment.Paragraph -> {
                    blocks.add(TerminalSemanticBlock.AgentProseBlock(text = seg.text))
                }
            }
        }
        return blocks
    }

    private fun deduplicateAndSort(events: List<TranscriptRawEvent>): List<TranscriptRawEvent> {
        val seen = HashSet<String>(events.size)
        val out = ArrayList<TranscriptRawEvent>(events.size)
        for (ev in events.sortedBy { it.lineByteOffset }) {
            val extra = when (ev) {
                is TranscriptRawEvent.ToolStatusUpdate -> "err:${ev.callId}"
                is TranscriptRawEvent.UserMessage -> "u:${ev.text.hashCode()}"
                is TranscriptRawEvent.AssistantStep -> "a:${ev.stepIndex ?: ev.markdownContent.hashCode()}"
                is TranscriptRawEvent.TokenUpdate -> "t:${ev.model.orEmpty()}:${ev.outputTokens}"
            }
            val key = "${ev.lineByteOffset}#$extra"
            if (seen.add(key)) {
                out.add(ev)
            }
        }
        return out
    }

    private fun assembleTurnStats(
        turnIndex: Int,
        prompt: TerminalSemanticBlock.UserPromptBlock?,
        body: List<TerminalSemanticBlock>,
        isLatestRunning: Boolean,
        durationOverride: String?,
        modelLabel: String?,
        tokenSummaryLabel: String?
    ): ConversationTurn {
        var toolOps = 0
        var thoughtLabel: String? = null
        var breakdownLabel: String? = null
        val files = LinkedHashSet<String>()
        var hasErr = false
        var running = isLatestRunning
        val proseCandidates = ArrayList<String>()
        var codeHint: String? = null
        var alertHint: String? = null

        for (b in body) {
            when (b) {
                is TerminalSemanticBlock.ToolGroupBlock -> {
                    val toolEntriesCount = b.entries.count { it.category != ToolActionCategory.THOUGHT }
                    if (toolEntriesCount > 0) {
                        toolOps += toolEntriesCount
                        if (!b.actionBreakdownLabel.isNullOrBlank()) breakdownLabel = b.actionBreakdownLabel
                    }
                    if (b.thoughtSummaryLabel != null) thoughtLabel = b.thoughtSummaryLabel
                    files.addAll(b.changedFiles)
                    if (b.hasError) hasErr = true
                    if (b.isRunning) running = true
                }
                is TerminalSemanticBlock.AgentProseBlock -> {
                    if (!b.isHeading && b.text.isNotBlank()) proseCandidates.add(b.text)
                }
                is TerminalSemanticBlock.CodeOrTableBlock -> {
                    if (codeHint == null) {
                        codeHint = "[${b.kindLabel ?: "代码"}] ${b.content.lineSequence().firstOrNull().orEmpty().trim()}"
                    }
                }
                is TerminalSemanticBlock.UrgentAlertBlock -> {
                    if (b.isError) hasErr = true
                    if (alertHint == null) alertHint = "${b.title}: ${b.detail}"
                }
                is TerminalSemanticBlock.AskCardBlock,
                is TerminalSemanticBlock.UserPromptBlock,
                is TerminalSemanticBlock.SessionHeaderBlock -> Unit
            }
        }

        val rawPreview = proseCandidates.lastOrNull().orEmpty()
            .ifBlank { alertHint.orEmpty() }
            .ifBlank { codeHint.orEmpty() }
            .replace("**", "")
            .replace("`", "")
            .replace(whitespaceRegex, " ")
            .trim()
        val answerPreview = if (rawPreview.length > 96) rawPreview.take(95).trimEnd() + "…" else rawPreview

        return ConversationTurn(
            turnIndex = turnIndex,
            userPrompt = prompt,
            responseBlocks = body,
            toolOperationCount = toolOps,
            thoughtSummaryLabel = thoughtLabel,
            durationLabel = durationOverride,
            actionBreakdownLabel = breakdownLabel,
            changedFiles = files.toList(),
            answerPreview = answerPreview,
            hasError = hasErr,
            isRunning = running,
            hasInteractiveCard = false,
            modelLabel = modelLabel,
            tokenSummaryLabel = tokenSummaryLabel,
            isRawMarkdownSource = true
        )
    }

    private fun classifyToolCategory(rawName: String): ToolActionCategory =
        when (rawName.lowercase(Locale.ROOT)) {
            "read_file", "view_file", "read", "list_dir", "ls" -> ToolActionCategory.READ
            "edit_file", "replace_file_content", "multi_replace_file_content",
            "write_file", "write_to_file", "apply_patch", "edit", "multiedit", "write" -> ToolActionCategory.EDIT
            "run_command", "exec_command", "bash", "shell", "shell_command", "write_stdin" -> ToolActionCategory.COMMAND
            "grep_search", "find_by_name", "grep", "glob", "search_web", "websearch", "webfetch" -> ToolActionCategory.SEARCH
            "invoke_subagent", "define_subagent", "agent", "task", "swarm", "send_message" -> ToolActionCategory.SUBAGENT
            else -> ToolActionCategory.OTHER
        }

    private fun formatToolEntryTitle(tc: TranscriptToolCallSummary): String {
        val verb = when (tc.name.lowercase(Locale.ROOT)) {
            "read_file", "view_file", "read" -> "读取"
            "list_dir", "ls" -> "列出"
            "edit_file", "replace_file_content", "multi_replace_file_content", "edit", "multiedit", "apply_patch" -> "编辑"
            "write_file", "write_to_file", "write" -> "写入"
            "run_command", "exec_command", "bash", "shell", "shell_command" -> "运行"
            "grep_search", "grep" -> "搜索"
            "find_by_name", "glob" -> "查找"
            "search_web", "websearch" -> "检索"
            "invoke_subagent", "agent", "task" -> "子代理"
            else -> tc.name
        }
        return if (tc.primaryTarget.isNotBlank()) "$verb ${tc.primaryTarget}" else verb
    }

    private fun buildActionBreakdown(
        counts: Map<ToolActionCategory, Int>,
        changedFilesCount: Int,
        hasError: Boolean,
        isRunning: Boolean
    ): String {
        fun priority(cat: ToolActionCategory): Int = when (cat) {
            ToolActionCategory.EDIT, ToolActionCategory.COMMAND -> 0
            ToolActionCategory.READ, ToolActionCategory.SEARCH, ToolActionCategory.SUBAGENT -> 1
            ToolActionCategory.OTHER, ToolActionCategory.THOUGHT -> 2
        }

        fun formatCat(cat: ToolActionCategory, cnt: Int): String = when (cat) {
            ToolActionCategory.EDIT -> if (changedFilesCount > 0) "修改 $changedFilesCount 个文件" else "编辑 $cnt 处"
            ToolActionCategory.COMMAND -> "运行 $cnt 条命令"
            ToolActionCategory.READ -> "读取 $cnt 个文件"
            ToolActionCategory.SEARCH -> "检索 $cnt 次"
            ToolActionCategory.SUBAGENT -> "调用 $cnt 个子代理"
            ToolActionCategory.OTHER -> "执行 $cnt 项工具"
            ToolActionCategory.THOUGHT -> "思考 $cnt"
        }

        val order = counts.keys.toList()
        val prioritized = order.sortedBy { priority(it) }
        val topTwo = prioritized.take(2)
        val restCount = prioritized.drop(2).sumOf { counts[it] ?: 0 }
        return buildString {
            append(topTwo.joinToString(" · ") { formatCat(it, counts[it] ?: 0) })
            if (restCount > 0) append(" · 另 $restCount 项")
            if (hasError) append(" · 含失败")
            if (isRunning) append(" · 进行中")
        }
    }

    private fun promptsMatch(sidePrompt: String?, livePrompt: String): Boolean {
        if (sidePrompt.isNullOrBlank()) return false
        fun norm(s: String): String {
            val unwrapped = hcWrapperRegex.find(s.trim())?.groupValues?.get(1) ?: s
            return unwrapped.replace(whitespaceRegex, "").take(160)
        }
        val a = norm(sidePrompt)
        val b = norm(livePrompt)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || (a.length >= 6 && b.length >= 6 && (a.contains(b) || b.contains(a)))
    }

    private fun formatCompactModelName(raw: String): String {
        val clean = raw.trim()
        return clean
            .removePrefix("models/")
            .replace("claude-", "Claude ")
            .replace("-2025", " ")
            .trim()
            .take(28)
    }

    private fun formatTokenSummary(input: Long, cached: Long, output: Long): String? {
        if (input <= 0L && cached <= 0L && output <= 0L) return null
        fun fmt(v: Long): String = when {
            v >= 1_000_000L -> String.format(Locale.US, "%.1fM", v / 1_000_000.0)
            v >= 1_000L -> String.format(Locale.US, "%.1fk", v / 1_000.0)
            else -> v.toString()
        }
        val totalIn = input + cached
        return buildList {
            if (totalIn > 0L) add("入 ${fmt(totalIn)}")
            if (output > 0L) add("出 ${fmt(output)}")
        }.joinToString(" / ")
    }

    private fun formatDurationBetween(startIso: String?, endIso: String?): String? {
        val t1 = parseIsoEpochSeconds(startIso) ?: return null
        val t2 = parseIsoEpochSeconds(endIso) ?: return null
        val sec = (t2 - t1).coerceAtLeast(0L)
        if (sec <= 0L) return null
        return when {
            sec < 60L -> "${sec}s"
            sec < 3600L -> "${sec / 60L}m ${sec % 60L}s"
            else -> "${sec / 3600L}h ${(sec % 3600L) / 60L}m"
        }
    }

    private fun parseIsoEpochSeconds(iso: String?): Long? {
        val m = iso?.trim()?.let { isoUtcRegex.find(it) } ?: return null
        val year = m.groupValues[1].toLongOrNull() ?: return null
        val month = m.groupValues[2].toIntOrNull() ?: return null
        val day = m.groupValues[3].toIntOrNull() ?: return null
        val hour = m.groupValues[4].toLongOrNull() ?: return null
        val min = m.groupValues[5].toLongOrNull() ?: return null
        val sec = m.groupValues[6].toLongOrNull() ?: return null
        val days = daysSinceEpoch(year, month, day)
        return days * 86_400L + hour * 3_600L + min * 60L + sec
    }

    // Howard Hinnant civil-from/to-days algorithm (pure integer math, works on all API levels)
    private fun daysSinceEpoch(year: Long, month: Int, day: Int): Long {
        val y = year - (if (month <= 2) 1L else 0L)
        val era = (if (y >= 0L) y else y - 399L) / 400L
        val yoe = y - era * 400L
        val doy = (153L * (month + (if (month > 2) -3 else 9)) + 2L) / 5L + day - 1L
        val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
        return era * 146_097L + doe - 719_468L
    }
}
