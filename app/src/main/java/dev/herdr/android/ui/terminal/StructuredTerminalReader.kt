package dev.herdr.android.ui.terminal

import dev.herdr.android.protocol.AgentStateWord
import dev.herdr.android.protocol.TerminalSummary

/**
 * Semantic, phone-friendly view of a remote agent TUI screen.
 *
 * The raw PTY snapshot (often 200+ columns of hard-wrapped TUI output) is reduced to a list of
 * blocks a mobile reader actually cares about: what I asked, what the agent said, what it did
 * (folded), code/tables (kept monospace), and anything blocking or failing.
 */
sealed interface TerminalSemanticBlock {
    /** A user instruction (`> ` / `❯ ` / `› ` prompt), including multi-line `<hc ...>...</hc>` messages. */
    data class UserPromptBlock(val text: String) : TerminalSemanticBlock

    /** Consecutive thinking / tool-call lines folded into one collapsible group. */
    data class ToolGroupBlock(
        val summaryLabel: String,
        val steps: List<String>,
        val hasError: Boolean = false
    ) : TerminalSemanticBlock

    /** Monospace content that must keep its layout: fenced/indented code, tables, diffs, JSON. */
    data class CodeOrTableBlock(val content: String) : TerminalSemanticBlock

    /** Errors and blocking prompts (permission / confirmation). */
    data class UrgentAlertBlock(
        val title: String,
        val detail: String,
        val isError: Boolean
    ) : TerminalSemanticBlock

    /** Agent prose with hard wraps re-joined into natural paragraphs. */
    data class AgentProseBlock(
        val text: String,
        val isBullet: Boolean = false,
        val isHeading: Boolean = false
    ) : TerminalSemanticBlock
}

/**
 * A one-tap reply. When [isSpecialKey] is true, [payload] is a special-key wire name
 * (`enter`, `esc`, `tab`, `up`, `down`, `ctrl+o`, `ctrl+t`, `ctrl+c`); otherwise it is literal text
 * to send as a line.
 */
data class QuickReplyAction(
    val label: String,
    val payload: String,
    val isSpecialKey: Boolean = false,
    val isPrimary: Boolean = false,
    val isDanger: Boolean = false
)

object StructuredTerminalReader {

    // ---------------------------------------------------------------------------------------------
    // Limits
    // ---------------------------------------------------------------------------------------------

    private const val MAX_INPUT_LINES = 2000
    private const val MAX_HC_MESSAGE_LINES = 400
    private const val MAX_JSON_LINES = 300
    private const val MAX_GROUP_STEPS = 300
    private const val HEADLINE_MAX_WIDTH = 96

    // ---------------------------------------------------------------------------------------------
    // Control-sequence stripping
    // ---------------------------------------------------------------------------------------------

    private val oscRegex = Regex("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)")
    private val csiRegex = Regex("\u001B\\[[0-9;:?<=>!]*[ -/]*[@-~]")
    private val charsetRegex = Regex("\u001B[()*+][0-9A-Za-z]")
    private val escTwoCharRegex = Regex("\u001B[@-Z\\\\-_=>78]")
    private val controlCharRegex = Regex("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]")

    // ---------------------------------------------------------------------------------------------
    // Noise (TUI chrome / status bars)
    // ---------------------------------------------------------------------------------------------

    private val fullRuleRegex = Regex("^[─━═╌╍┄┅┈┉\\-_=~]{6,}$")
    private val inputBoxBorderRegex = Regex("^[╭╰┌└][─━]{4,}[╮╯┐┘]$")
    private val emptyBoxRowRegex = Regex("^│\\s*(?:[>❯›]\\s*)?│?$")
    private val lonePromptRegex = Regex("^[>❯›]\\s*$")
    private val progressBarRegex = Regex("[█▉▊▋▌▍▎▏░▒▓]{3,}")
    private val footerRegex = Regex(
        "(?i)(\\? for shortcuts|esc to (?:cancel|interrupt)|ctrl\\+c to (?:exit|quit|cancel)|" +
            "shift\\+tab to|auto-accept|bypass(?:ing)? permissions|tok/s|TTFT:|Cline upstream:|" +
            "← for agents|Thinking blocks:|Tool output:|\\[hc] incomplete:|context left|" +
            "Claude (?:Opus|Sonnet|Haiku)|\\bMCP:\\s*\\d+)"
    )
    private val tipRegex = Regex("^(?:[※⎿*]\\s*)?Tip:")

    private val workingHintRegex = Regex("(?i)(esc to interrupt|^\\s*[\\u2800-\\u28FF✻✽✶✳✢]\\s+\\S.*(?:…|\\.\\.\\.|ing\\b))")

    // ---------------------------------------------------------------------------------------------
    // Line classification
    // ---------------------------------------------------------------------------------------------

    private val promptRegex = Regex("^\\s*(?:>|❯|›)\\s+(.*\\S.*)$")
    private val promptMenuOptionRegex = Regex("^\\s*[>❯›]\\s*\\d{1,2}[.)]\\s+\\S.*$")

    private val thoughtRegex = Regex("^\\s*[○◦▸▾▼•●⏺]?\\s*Thought for\\b.*$")
    private val toolVerbRegex = Regex(
        "^\\s*[●⏺•○◦▸▾▼✓✔✗✘]\\s+(?:Thought|Thinking|Ran|Run|Running|Edited|Editing|Explored|Exploring|" +
            "Read|Reading|Wrote|Writing|Searched|Searching|Updated|Updating|Created|Creating|Deleted|" +
            "Listed|Listing|Called|Calling|Executed|Executing|Viewed|Viewing|Analyzed|Fetched|Fetching|" +
            "Applied|Applying|Scheduling|Scheduled|Worked|Waited|Grepped|Globbed|Patched|Inspected)\\b.*$"
    )
    private val toolCallRegex = Regex("^\\s*[●⏺•]\\s+[A-Z][A-Za-z0-9_.:-]*\\(.*$")
    private val bareToolSummaryRegex = Regex(
        "^\\s*(?:Ran|Edited|Explored|Read|Searched|Wrote|Updated|Created)\\s+\\d+\\s+\\S+.*$"
    )
    private val moreActionsRegex = Regex("^\\s*\\.{2,3}\\s+\\d+\\s+more\\s+actions\\b.*$")
    private val spinnerRegex = Regex("^\\s*[\\u2800-\\u28FF✻✽✶✳✢]\\s+\\S.*$")
    private val toolChildRegex = Regex("^\\s*(?:[└├]─*|⎿)\\s*\\S.*$")

    private val errorRegex = Regex(
        "^\\s*(?:[●⏺•✗✘]\\s*)?(Error|ERROR|error|Fatal|FATAL|fatal|panic|Exception|FAILED)" +
            "(?:\\[[^\\]]*])?\\s*[:：]\\s*(.*)$"
    )
    private val tracebackRegex = Regex("^\\s*Traceback \\(most recent call last\\):?\\s*$")
    private val permissionErrorRegex = Regex("(?i)(permission denied|access is denied|拒绝访问)")
    private val blockingPromptRegex = Regex(
        "(?i)(locked \\(permission\\)|permission required|requires? (?:your )?(?:approval|permission)|" +
            "do you want to (?:proceed|make this edit|run|create|allow|continue|apply)|" +
            "allow (?:this|once|always)|waiting for (?:your )?(?:approval|confirmation|input)|" +
            "需要(?:你的)?(?:授权|确认|批准)|是否(?:允许|继续|执行))"
    )

    private val fenceRegex = Regex("^\\s*(```|~~~).*$")
    private val tableBorderRegex = Regex("^\\s*[┌┬┐├┼┤└┴┘─━═╞╪╡╘╧╛╒╤╕╔╦╗╠╬╣╚╩╝║+|:\\-\\s]{3,}$")
    private val boxCharRegex = Regex("[┌┬┐├┼┤└┴┘╞╪╡╘╧╛╒╤╕╔╦╗╠╬╣╚╩╝]")
    private val asciiTableBorderRegex = Regex("^\\s*\\+[-=+]{3,}\\+\\s*$")
    private val markdownTableRegex = Regex("^\\s*\\|.*\\|\\s*$")
    private val diffLineRegex = Regex(
        "^(?:\\s*\\d+\\s*[+-]\\s|@@ .* @@|diff --git |\\+\\+\\+ |--- (?:a/|b/|/dev/null)|index [0-9a-f]+\\.\\.)"
    )

    private val headingRegex = Regex("^\\s*#{1,6}\\s+(.+)$")
    private val boldHeadingRegex = Regex("^\\s*\\*\\*([^*]{1,80})\\*\\*\\s*[:：]?\\s*$")
    private val unorderedBulletRegex = Regex("^(\\s*)[•\\-*·◦▪‣]\\s+(.+)$")
    private val orderedBulletRegex = Regex("^(\\s*)(\\d{1,2}[.)]\\s+|\\d{1,2}、\\s*|[（(]\\d{1,2}[)）]\\s*)(.+)$")
    private val proseBulletRegex = Regex("^(\\s*)[●⏺]\\s+(.+)$")

    private val stepErrorRegex = Regex("(?i)(\\bfailed\\b|\\bfailure\\b|\\berror\\b|exit code [1-9]|\\bexit [1-9]|✗|✘|denied)")

    // Quick-action detection
    private val menuOptionRegex = Regex("^\\s*(?:[❯›>]\\s*)?(\\d{1,2})[.)]\\s+(.{1,80})$")
    private val yesNoRegex = Regex("(?i)(\\(y/n\\)|\\[y/n]|\\(yes/no\\)|\\[yes/no])")
    private val questionTailRegex = Regex("[?？]\\s*$")

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    fun parseBlocks(rawScreen: String): List<TerminalSemanticBlock> {
        if (rawScreen.isBlank()) return emptyList()
        return runCatching { BlockParser(cleanLines(rawScreen)).parse() }.getOrElse {
            // Never crash the UI on unexpected screen content: fall back to a single prose block.
            val fallback = cleanLines(rawScreen).joinToString("\n").trim()
            if (fallback.isEmpty()) emptyList() else listOf(TerminalSemanticBlock.CodeOrTableBlock(fallback))
        }
    }

    fun extractQuickActions(rawScreen: String?, summary: TerminalSummary?): List<QuickReplyAction> {
        val raw = rawScreen.orEmpty()
        val rawTail = stripControl(raw).replace("\r\n", "\n").split('\n')
            .map { it.trimEnd('\r').substringAfterLast('\r').trimEnd() }
            .filter { it.isNotBlank() }
            .takeLast(30)
        val cleanTail = cleanLines(raw).filter { it.isNotBlank() }.takeLast(16)

        val menuOptions = findMenuOptions(cleanTail)
        val hasYesNo = cleanTail.any { yesNoRegex.containsMatchIn(it) }
        val looksWorking = rawTail.takeLast(8).any { workingHintRegex.containsMatchIn(it) }
        val looksBlocked = menuOptions.isNotEmpty() || hasYesNo ||
            cleanTail.takeLast(8).any { blockingPromptRegex.containsMatchIn(it) }
        val looksError = cleanTail.takeLast(6).any { errorRegex.matches(it) }

        val status = when (val s = summary?.agentStatus ?: AgentStateWord.NONE) {
            AgentStateWord.NONE -> when {
                looksBlocked -> AgentStateWord.BLOCKED
                looksWorking -> AgentStateWord.WORKING
                looksError -> AgentStateWord.ERROR
                else -> AgentStateWord.DONE
            }
            else -> s
        }

        val actions = when (status) {
            AgentStateWord.BLOCKED -> blockedActions(menuOptions, hasYesNo)
            AgentStateWord.ERROR -> listOf(
                QuickReplyAction("重试/继续", "继续，重试刚才失败的步骤", isPrimary = true),
                QuickReplyAction("查看报错", "ctrl+o", isSpecialKey = true),
                QuickReplyAction("分析原因", "先分析这个报错的根本原因，给出修复方案，暂不改代码"),
                QuickReplyAction("⏎ 回车", "enter", isSpecialKey = true)
            )
            AgentStateWord.WORKING -> listOf(
                QuickReplyAction("展开详情 Ctrl+O", "ctrl+o", isSpecialKey = true, isPrimary = true),
                QuickReplyAction("⏎ 回车", "enter", isSpecialKey = true),
                QuickReplyAction("■ 中断 Ctrl+C", "ctrl+c", isSpecialKey = true, isDanger = true)
            )
            AgentStateWord.DONE, AgentStateWord.IDLE, AgentStateWord.NONE -> listOf(
                QuickReplyAction("继续推进", "继续", isPrimary = true),
                QuickReplyAction("按这个先做", "按这个先做"),
                QuickReplyAction("跑测试验证", "跑一遍测试，修复所有失败项后汇报结果"),
                QuickReplyAction("提交并推送", "提交当前改动并推送到远端")
            )
        }
        return actions.take(5)
    }

    fun extractLatestHeadline(rawScreen: String?, summary: TerminalSummary): String {
        val blocks = if (rawScreen.isNullOrBlank()) emptyList() else parseBlocks(rawScreen)
        val status = summary.agentStatus

        // 1. Blocked / error: the concrete question or failure is the most actionable line.
        if (status == AgentStateWord.BLOCKED || status == AgentStateWord.ERROR) {
            val cleanTail = if (rawScreen.isNullOrBlank()) emptyList()
            else cleanLines(rawScreen).filter { it.isNotBlank() }.takeLast(16)
            val question = findQuestionLine(cleanTail)
            if (question != null) return polishHeadline(question)
            val alert = blocks.lastOrNull { it is TerminalSemanticBlock.UrgentAlertBlock }
                as? TerminalSemanticBlock.UrgentAlertBlock
            if (alert != null) {
                val text = if (alert.detail.isBlank()) alert.title else "${alert.title}: ${alert.detail.lineSequence().first()}"
                return polishHeadline(text)
            }
        }

        // 2. hc's own agent message.
        summary.agentMessage?.takeIf { it.isNotBlank() }?.let { return polishHeadline(it) }

        // 3. The agent's latest prose (skip headings), first sentence.
        val lastPromptIndex = blocks.indexOfLast { it is TerminalSemanticBlock.UserPromptBlock }
        val prose = blocks.asReversed().firstOrNull {
            it is TerminalSemanticBlock.AgentProseBlock && !it.isHeading && it.text.isNotBlank()
        } as? TerminalSemanticBlock.AgentProseBlock
        if (prose != null && blocks.indexOf(prose) > lastPromptIndex) {
            return polishHeadline(firstSentence(prose.text))
        }

        // 4. Running tools after the latest prompt.
        val latestGroup = blocks.lastOrNull() as? TerminalSemanticBlock.ToolGroupBlock
        if (latestGroup != null && status == AgentStateWord.WORKING) {
            latestGroup.steps.lastOrNull { !it.startsWith(" ") }?.let { return polishHeadline("正在：$it") }
        }

        // 5. The latest instruction.
        (blocks.getOrNull(lastPromptIndex) as? TerminalSemanticBlock.UserPromptBlock)?.let {
            return polishHeadline("最近指令：${stripHcWrapper(it.text)}")
        }
        prose?.let { return polishHeadline(firstSentence(it.text)) }

        // 6. Status fallback.
        return when (status) {
            AgentStateWord.WORKING -> "运行中…"
            AgentStateWord.BLOCKED -> "等待你的授权或确认"
            AgentStateWord.ERROR -> "运行出错，等待处理"
            AgentStateWord.DONE -> "已完成，等待下一轮指令"
            AgentStateWord.IDLE -> "空闲"
            AgentStateWord.NONE -> summary.title.ifBlank { summary.workspaceSubtitle }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Cleaning
    // ---------------------------------------------------------------------------------------------

    private fun stripControl(raw: String): String {
        var s = oscRegex.replace(raw, "")
        s = csiRegex.replace(s, "")
        s = charsetRegex.replace(s, "")
        s = escTwoCharRegex.replace(s, "")
        s = s.replace("\u001B", "")
        s = s.replace("\t", "    ")
        return controlCharRegex.replace(s, "")
    }

    /**
     * Strips control sequences, resolves carriage-return overwrites, unwraps single-column box
     * borders, and drops TUI chrome. Blank lines are preserved (collapsed to one) as paragraph
     * separators.
     */
    private fun cleanLines(raw: String): List<String> {
        val lines = stripControl(raw).replace("\r\n", "\n").split('\n').takeLast(MAX_INPUT_LINES)
        val out = ArrayList<String>(lines.size)
        var lastBlank = true
        for (rawLine in lines) {
            var line = rawLine.trimEnd('\r').substringAfterLast('\r').replace('\u00A0', ' ').trimEnd()
            line = unwrapBoxRow(line)
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (!lastBlank) out.add("")
                lastBlank = true
                continue
            }
            if (isNoise(trimmed)) continue
            out.add(line)
            lastBlank = false
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        return out
    }

    /** `│ text │` or `│ text` (a framed message, not a table) -> `text`, keeping relative indent. */
    private fun unwrapBoxRow(line: String): String {
        val pipeCount = line.count { it == '│' }
        val trimmedStart = line.trimStart()
        if (!trimmedStart.startsWith("│")) return line
        val leading = line.length - trimmedStart.length
        return when {
            pipeCount == 1 -> " ".repeat(leading) + trimmedStart.removePrefix("│").removePrefix(" ")
            pipeCount == 2 && line.trimEnd().endsWith("│") ->
                " ".repeat(leading) + trimmedStart.removePrefix("│").trimEnd().removeSuffix("│").removePrefix(" ").trimEnd()
            else -> line
        }
    }

    private fun isNoise(trimmed: String): Boolean {
        if (fullRuleRegex.matches(trimmed)) return true
        if (inputBoxBorderRegex.matches(trimmed)) return true
        if (emptyBoxRowRegex.matches(trimmed)) return true
        if (lonePromptRegex.matches(trimmed)) return true
        if (tipRegex.containsMatchIn(trimmed)) return true
        if (progressBarRegex.containsMatchIn(trimmed)) return true
        if (footerRegex.containsMatchIn(trimmed)) return true
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // Block parser
    // ---------------------------------------------------------------------------------------------

    private class BlockParser(private val lines: List<String>) {
        private val blocks = ArrayList<TerminalSemanticBlock>()
        private val maxWidth: Int = lines.maxOfOrNull { displayWidth(it) } ?: 0
        private val wrapThreshold: Int = maxOf(40, (maxWidth * 0.72f).toInt())
        private val baseIndent: Int = lines
            .filter { it.isNotBlank() && !isToolHead(it) }
            .minOfOrNull { indentOf(it) }
            ?.coerceAtMost(4) ?: 0

        // Current prose paragraph
        private var paraText: StringBuilder? = null
        private var paraBullet = false
        private var paraIndent = 0
        private var paraLastWidth = 0

        private var i = 0

        fun parse(): List<TerminalSemanticBlock> {
            while (i < lines.size) {
                val line = lines[i]
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> {
                        flushParagraph()
                        i++
                    }
                    fenceRegex.matches(line) -> {
                        flushParagraph()
                        consumeFence()
                    }
                    isPromptLine(line) -> {
                        flushParagraph()
                        consumePrompt()
                    }
                    isToolHead(line) -> {
                        flushParagraph()
                        consumeToolGroup()
                    }
                    isAlertLine(line) -> {
                        flushParagraph()
                        consumeAlerts()
                    }
                    isTableLine(line) || isDiffLine(line) -> {
                        flushParagraph()
                        consumeCodeRun { isTableLine(it) || isDiffLine(it) }
                    }
                    isJsonStart(trimmed) -> {
                        flushParagraph()
                        consumeJson()
                    }
                    isIndentedCode(line) -> {
                        flushParagraph()
                        consumeCodeRun { isIndentedCode(it) }
                    }
                    toolChildRegex.matches(line) -> {
                        // An orphan tree child (its head scrolled off-screen): fold it as a tool step.
                        flushParagraph()
                        consumeToolGroup()
                    }
                    else -> {
                        appendProse(line)
                        i++
                    }
                }
            }
            flushParagraph()
            return mergeAdjacentToolGroups(blocks)
        }

        // ---- prompts ----

        private fun isPromptLine(line: String): Boolean =
            promptRegex.matches(line) && !promptMenuOptionRegex.matches(line)

        private fun consumePrompt() {
            val first = promptRegex.find(lines[i])!!.groupValues[1].trim()
            val promptIndent = indentOf(lines[i])
            i++
            if (first.contains("<hc") && !first.contains("</hc>")) {
                val sb = StringBuilder(first)
                var consumed = 0
                while (i < lines.size && consumed < MAX_HC_MESSAGE_LINES) {
                    val l = lines[i]
                    sb.append('\n').append(l.trim())
                    i++
                    consumed++
                    if (l.contains("</hc>")) break
                }
                blocks.add(TerminalSemanticBlock.UserPromptBlock(sb.toString().trim()))
                return
            }
            var text = first
            var lastWidth = displayWidth(lines[i - 1])
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) break
                if (indentOf(l) <= promptIndent) break
                if (isToolHead(l) || isAlertLine(l) || isPromptLine(l) || toolChildRegex.matches(l)) break
                text = joinWrapped(text, l.trim(), lastWidth >= wrapThreshold)
                lastWidth = displayWidth(l)
                i++
            }
            blocks.add(TerminalSemanticBlock.UserPromptBlock(text))
        }

        // ---- tool groups ----

        private fun consumeToolGroup() {
            val steps = ArrayList<String>()
            var headCount = 0
            var hasError = false
            var running = false
            var headIndent = indentOf(lines[i])

            while (i < lines.size) {
                val line = lines[i]
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> {
                        val next = nextNonBlank(i + 1)
                        if (next != null && (isToolHead(lines[next]) || toolChildRegex.matches(lines[next]))) {
                            i = next
                        } else {
                            break
                        }
                    }
                    isToolHead(line) -> {
                        headCount++
                        headIndent = indentOf(line)
                        val clean = cleanToolHead(trimmed)
                        running = spinnerRegex.matches(line) ||
                            clean.startsWith("Running") || clean.startsWith("Thinking") || clean.endsWith("…")
                        if (stepErrorRegex.containsMatchIn(clean) && !zeroFailures(clean)) hasError = true
                        if (steps.size < MAX_GROUP_STEPS) steps.add(clean)
                        i++
                    }
                    toolChildRegex.matches(line) ||
                        (headCount > 0 && indentOf(line) > headIndent + 1 && !isPromptLine(line) && !isAlertLine(line)) -> {
                        if (headCount == 0) headCount = 1
                        val clean = trimmed.replace(Regex("^(?:[└├]─*|⎿)\\s*"), "")
                        if (stepErrorRegex.containsMatchIn(clean) && !zeroFailures(clean)) hasError = true
                        if (steps.size < MAX_GROUP_STEPS) steps.add("  $clean")
                        i++
                    }
                    else -> break
                }
            }

            if (steps.isEmpty()) {
                // Defensive: guarantee forward progress.
                i++
                return
            }
            val label = buildString {
                append("已执行 ").append(headCount).append(" 项思考与工具操作")
                if (hasError) append(" · 含失败")
                if (running) append(" · 进行中")
            }
            blocks.add(TerminalSemanticBlock.ToolGroupBlock(label, steps, hasError))
        }

        private fun cleanToolHead(trimmed: String): String =
            trimmed.replace(Regex("^[●⏺•○◦▸▾▼✓✔✗✘\\u2800-\\u28FF✻✽✶✳✢]\\s*"), "").trim()

        private fun zeroFailures(text: String): Boolean =
            Regex("(?i)\\b0 (?:failed|errors?)\\b").containsMatchIn(text) &&
                !Regex("(?i)\\b[1-9]\\d* (?:failed|errors?)\\b").containsMatchIn(text)

        // ---- alerts ----

        private fun isAlertLine(line: String): Boolean =
            errorRegex.matches(line) || tracebackRegex.matches(line) ||
                permissionErrorRegex.containsMatchIn(line) || blockingPromptRegex.containsMatchIn(line)

        private fun consumeAlerts() {
            while (i < lines.size && lines[i].isNotBlank() && isAlertLine(lines[i])) {
                val line = lines[i].trim()
                val (title, detail, isError) = classifyAlert(line)
                val last = blocks.lastOrNull() as? TerminalSemanticBlock.UrgentAlertBlock
                if (last != null && last.title == title && last.isError == isError) {
                    blocks[blocks.lastIndex] = last.copy(
                        detail = listOf(last.detail, detail).filter { it.isNotBlank() }.joinToString("\n")
                    )
                } else {
                    blocks.add(TerminalSemanticBlock.UrgentAlertBlock(title, detail, isError))
                }
                i++
            }
        }

        private fun classifyAlert(line: String): Triple<String, String, Boolean> {
            errorRegex.find(line)?.let { m ->
                val kind = m.groupValues[1]
                val title = when (kind.lowercase()) {
                    "fatal" -> "Fatal"
                    "panic" -> "Panic"
                    "exception" -> "Exception"
                    "failed" -> "Failed"
                    else -> "Error"
                }
                return Triple(title, m.groupValues[2].trim(), true)
            }
            if (tracebackRegex.matches(line)) return Triple("Traceback", line, true)
            if (permissionErrorRegex.containsMatchIn(line)) return Triple("权限错误", line, true)
            return Triple("需要你的授权", line, false)
        }

        // ---- code / tables / json ----

        private fun isTableLine(line: String): Boolean {
            val t = line.trim()
            if (t.isEmpty()) return false
            if (asciiTableBorderRegex.matches(t)) return true
            if (tableBorderRegex.matches(t) && boxCharRegex.containsMatchIn(t)) return true
            if (line.count { it == '│' || it == '┃' || it == '║' } >= 1 && boxCharRegex.containsMatchIn(line)) return true
            if (line.count { it == '│' || it == '┃' || it == '║' } >= 2) return true
            if (markdownTableRegex.matches(t) && t.count { it == '|' } >= 3) return true
            return false
        }

        private fun isDiffLine(line: String): Boolean = diffLineRegex.containsMatchIn(line)

        private fun isJsonStart(trimmed: String): Boolean {
            if (trimmed.length < 2) return false
            val first = trimmed.first()
            if (first != '{' && first != '[') return false
            // `[1] foo` / `[hc] note` are not JSON.
            if (first == '[' && Regex("^\\[[^\\]\"{\\[]*]\\s+\\S").containsMatchIn(trimmed)) return false
            return trimmed.endsWith("{") || trimmed.endsWith("[") ||
                (trimmed.endsWith("}") || trimmed.endsWith("]") || trimmed.endsWith(",")) && trimmed.contains(':') ||
                trimmed == "{" || trimmed == "["
        }

        private fun isIndentedCode(line: String): Boolean {
            if (line.isBlank()) return false
            if (paraText != null) return false
            if (indentOf(line) < baseIndent + 4) return false
            val t = line.trim()
            if (unorderedBulletRegex.matches(line) || orderedBulletRegex.matches(line)) return false
            if (isToolHead(line) || toolChildRegex.matches(line)) return false
            val cjk = t.count { isCjk(it) }
            if (cjk >= 2) {
                val hasCodeSyntax = t.startsWith("//") || t.contains(" = ") ||
                    t.startsWith("val ") || t.startsWith("var ") || t.startsWith("fun ") ||
                    t.endsWith("{") || t.endsWith("}") || t.endsWith(";")
                if (!hasCodeSyntax) return false
            }
            return cjk * 2 < t.length
        }

        private fun consumeFence() {
            val sb = StringBuilder()
            val opener = lines[i].trim().take(3)
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (l.trim().startsWith(opener)) {
                    i++
                    break
                }
                sb.append(l).append('\n')
                i++
            }
            addCode(sb.toString())
        }

        private fun consumeCodeRun(predicate: (String) -> Boolean) {
            val run = ArrayList<String>()
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) {
                    val next = nextNonBlank(i + 1)
                    if (next != null && predicate(lines[next])) {
                        run.add("")
                        i++
                        continue
                    }
                    break
                }
                if (!predicate(l)) break
                run.add(l)
                i++
            }
            if (run.isEmpty()) {
                appendProse(lines[i])
                i++
                return
            }
            addCode(run.joinToString("\n"))
        }

        private fun consumeJson() {
            val run = ArrayList<String>()
            var depth = 0
            var consumed = 0
            while (i < lines.size && consumed < MAX_JSON_LINES) {
                val l = lines[i]
                run.add(l)
                depth += jsonDepthDelta(l)
                i++
                consumed++
                if (depth <= 0) break
            }
            addCode(run.joinToString("\n"))
        }

        private fun jsonDepthDelta(line: String): Int {
            var delta = 0
            var inString = false
            var escaped = false
            for (c in line) {
                if (escaped) {
                    escaped = false
                    continue
                }
                when {
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    !inString && (c == '{' || c == '[') -> delta++
                    !inString && (c == '}' || c == ']') -> delta--
                }
            }
            return delta
        }

        private fun addCode(content: String) {
            val rawLines = content.trimEnd('\n').split('\n')
            val minIndent = rawLines.filter { it.isNotBlank() }.minOfOrNull { indentOf(it) } ?: 0
            val dedented = rawLines.joinToString("\n") { if (it.length >= minIndent) it.substring(minIndent) else it.trimStart() }
                .trimEnd()
            if (dedented.isBlank()) return
            val last = blocks.lastOrNull() as? TerminalSemanticBlock.CodeOrTableBlock
            if (last != null) {
                blocks[blocks.lastIndex] = TerminalSemanticBlock.CodeOrTableBlock(last.content + "\n" + dedented)
            } else {
                blocks.add(TerminalSemanticBlock.CodeOrTableBlock(dedented))
            }
        }

        // ---- prose ----

        private fun appendProse(rawLine: String) {
            // `❯ 1. Yes` (menu cursor) -> `  1. Yes`
            val line = if (promptMenuOptionRegex.matches(rawLine)) {
                rawLine.replaceFirst(Regex("[>❯›]"), " ")
            } else {
                rawLine
            }
            val indent = indentOf(line)
            val width = displayWidth(line)

            headingRegex.find(line)?.let {
                flushParagraph()
                blocks.add(TerminalSemanticBlock.AgentProseBlock(cleanInline(it.groupValues[1]), isHeading = true))
                return
            }
            boldHeadingRegex.find(line)?.let {
                flushParagraph()
                blocks.add(TerminalSemanticBlock.AgentProseBlock(it.groupValues[1].trim(), isHeading = true))
                return
            }
            orderedBulletRegex.find(line)?.let {
                flushParagraph()
                val marker = it.groupValues[2].trim()
                startParagraph("$marker ${it.groupValues[3].trim()}", bullet = true, indent = indent, width = width)
                return
            }
            unorderedBulletRegex.find(line)?.let {
                flushParagraph()
                startParagraph(it.groupValues[2].trim(), bullet = true, indent = indent, width = width)
                return
            }
            proseBulletRegex.find(line)?.let {
                flushParagraph()
                startParagraph(it.groupValues[2].trim(), bullet = false, indent = indent, width = width)
                return
            }

            val text = line.trim()
            val current = paraText
            if (current != null) {
                val previousWasFull = paraLastWidth >= wrapThreshold
                val bulletContinuation = paraBullet && indent >= paraIndent
                val endsSentence = current.isNotEmpty() && current.last() in "。！？.!?；;:："
                if (previousWasFull || !endsSentence || bulletContinuation) {
                    val joined = joinWrapped(current.toString(), text, true)
                    current.setLength(0)
                    current.append(joined)
                    paraLastWidth = width
                    return
                }
                flushParagraph()
            }
            startParagraph(text, bullet = false, indent = indent, width = width)
        }

        private fun startParagraph(text: String, bullet: Boolean, indent: Int, width: Int) {
            paraText = StringBuilder(text)
            paraBullet = bullet
            paraIndent = indent
            paraLastWidth = width
        }

        private fun flushParagraph() {
            val text = paraText?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                blocks.add(TerminalSemanticBlock.AgentProseBlock(cleanInline(text), isBullet = paraBullet))
            }
            paraText = null
            paraBullet = false
            paraIndent = 0
            paraLastWidth = 0
        }

        // ---- helpers ----

        private fun nextNonBlank(from: Int): Int? {
            var j = from
            while (j < lines.size) {
                if (lines[j].isNotBlank()) return j
                j++
            }
            return null
        }

        private fun mergeAdjacentToolGroups(input: List<TerminalSemanticBlock>): List<TerminalSemanticBlock> {
            if (input.size < 2) return input
            val out = ArrayList<TerminalSemanticBlock>(input.size)
            for (b in input) {
                val prev = out.lastOrNull()
                if (prev is TerminalSemanticBlock.ToolGroupBlock && b is TerminalSemanticBlock.ToolGroupBlock) {
                    val steps = (prev.steps + b.steps).take(MAX_GROUP_STEPS)
                    val heads = steps.count { !it.startsWith(" ") }.coerceAtLeast(1)
                    val hasError = prev.hasError || b.hasError
                    val running = b.summaryLabel.contains("进行中")
                    val label = buildString {
                        append("已执行 ").append(heads).append(" 项思考与工具操作")
                        if (hasError) append(" · 含失败")
                        if (running) append(" · 进行中")
                    }
                    out[out.lastIndex] = TerminalSemanticBlock.ToolGroupBlock(label, steps, hasError)
                } else {
                    out.add(b)
                }
            }
            return out
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------------

    private fun isToolHead(line: String): Boolean =
        thoughtRegex.matches(line) || toolVerbRegex.matches(line) || toolCallRegex.matches(line) ||
            bareToolSummaryRegex.matches(line) || moreActionsRegex.matches(line) || spinnerRegex.matches(line)

    private fun indentOf(line: String): Int {
        var n = 0
        for (c in line) {
            if (c == ' ') n++ else break
        }
        return n
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x2E80..0x9FFF || code in 0xAC00..0xD7A3 || code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F || code in 0xFF00..0xFF60 || code in 0xFFE0..0xFFE6 ||
            c.isSurrogate()
    }

    /** Terminal column width: CJK / full-width characters occupy two cells. */
    private fun displayWidth(s: String): Int {
        var w = 0
        var idx = 0
        while (idx < s.length) {
            val cp = s.codePointAt(idx)
            w += if (
                cp in 0x1100..0x115F || cp in 0x2E80..0xA4CF || cp in 0xAC00..0xD7A3 ||
                cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 ||
                cp in 0xFFE0..0xFFE6 || cp >= 0x1F300
            ) 2 else 1
            idx += Character.charCount(cp)
        }
        return w
    }

    /** Joins a hard-wrapped continuation: no space at CJK boundaries, a single space between Latin words. */
    private fun joinWrapped(previous: String, next: String, wrapped: Boolean): String {
        if (previous.isEmpty()) return next
        if (next.isEmpty()) return previous
        if (!wrapped) return "$previous $next"
        val a = previous.last()
        val b = next.first()
        return if (isCjk(a) || isCjk(b) || a == '/' || a == '-' && previous.length > 1 && previous[previous.length - 2].isLetter()) {
            previous + next
        } else {
            "$previous $next"
        }
    }

    private fun cleanInline(text: String): String =
        text.replace(Regex("\\s{2,}"), " ").trim()

    private fun findMenuOptions(tail: List<String>): List<Pair<String, String>> {
        val window = tail.takeLast(12)
        var best: List<Pair<String, String>> = emptyList()
        var current = ArrayList<Pair<String, String>>()
        for (line in window) {
            val m = menuOptionRegex.find(line)
            if (m != null) {
                val n = m.groupValues[1]
                val expected = (current.size + 1).toString()
                if (n == expected) {
                    current.add(n to m.groupValues[2].trim())
                    continue
                }
                if (n == "1") {
                    current = arrayListOf(n to m.groupValues[2].trim())
                    continue
                }
            }
            if (current.size >= 2) best = current
            current = ArrayList()
        }
        if (current.size >= 2) best = current
        return best
    }

    private fun blockedActions(options: List<Pair<String, String>>, hasYesNo: Boolean): List<QuickReplyAction> {
        val cancel = QuickReplyAction("✕ 取消 (ESC)", "esc", isSpecialKey = true)
        return when {
            options.isNotEmpty() -> {
                val optionActions = options.take(3).mapIndexed { idx, (n, text) ->
                    val short = truncateWidth(text.replace(Regex("[(（].*$"), "").trim().ifEmpty { text }, 16)
                    val negative = Regex("(?i)^(no|deny|reject|cancel|拒绝|取消|否)").containsMatchIn(text.trim())
                    QuickReplyAction(
                        label = "$n · $short",
                        payload = n,
                        isPrimary = idx == 0 && !negative,
                        isDanger = false
                    )
                }
                optionActions + QuickReplyAction("⏎ 确认", "enter", isSpecialKey = true) + cancel
            }
            hasYesNo -> listOf(
                QuickReplyAction("✓ 批准 (y)", "y", isPrimary = true),
                QuickReplyAction("✕ 拒绝 (n)", "n"),
                QuickReplyAction("⏎ 确认", "enter", isSpecialKey = true),
                cancel
            )
            else -> listOf(
                QuickReplyAction("✓ 批准 (y)", "y", isPrimary = true),
                QuickReplyAction("⏎ 确认", "enter", isSpecialKey = true),
                QuickReplyAction("选项 1", "1"),
                cancel
            )
        }
    }

    private fun findQuestionLine(tail: List<String>): String? {
        // A question just above a numbered menu is the most precise ask.
        val menuStart = tail.indexOfFirst { menuOptionRegex.matches(it) && menuOptionRegex.find(it)?.groupValues?.get(1) == "1" }
        val searchEnd = if (menuStart > 0) menuStart else tail.size
        for (idx in searchEnd - 1 downTo 0) {
            val t = tail[idx].trim()
            if (t.isEmpty()) continue
            if (questionTailRegex.containsMatchIn(t) || blockingPromptRegex.containsMatchIn(t)) return t
        }
        return null
    }

    private fun firstSentence(text: String): String {
        val m = Regex("^(.{8,}?[。！？!?]|.{12,}?[.](?=\\s|$))").find(text)
        return m?.value ?: text
    }

    private fun stripHcWrapper(text: String): String =
        text.replace(Regex("</?hc[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun polishHeadline(text: String): String {
        val cleaned = stripHcWrapper(text)
            .replace(Regex("^[●⏺•○◦▸▾▼✓✔✗✘>❯›#\\s]+"), "")
            .replace("**", "")
            .replace("`", "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return truncateWidth(cleaned, HEADLINE_MAX_WIDTH)
    }

    private fun truncateWidth(text: String, maxWidth: Int): String {
        if (displayWidth(text) <= maxWidth) return text
        val sb = StringBuilder()
        var w = 0
        var idx = 0
        while (idx < text.length) {
            val cp = text.codePointAt(idx)
            val chunk = String(Character.toChars(cp))
            val cw = displayWidth(chunk)
            if (w + cw > maxWidth - 1) break
            sb.append(chunk)
            w += cw
            idx += Character.charCount(cp)
        }
        return sb.toString().trimEnd() + "…"
    }
}
