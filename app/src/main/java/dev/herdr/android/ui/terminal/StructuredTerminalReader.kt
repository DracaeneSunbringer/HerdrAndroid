package dev.herdr.android.ui.terminal

import dev.herdr.android.protocol.AgentStateWord
import dev.herdr.android.protocol.TerminalSummary

/**
 * Semantic, phone-friendly view of a remote agent TUI screen.
 *
 * Different desktop agents (`antigravity`/`agy`, `codex`, `pi`, `claude`, plain `shell`) have
 * drastically different TUI grammars—for example, top-level `• ` in Codex marks both tool calls
 * AND assistant prose lead paragraphs, Antigravity indents all prose by 2 spaces right under
 * `▸ Thought` / `● Ran` headers, and Pi right-aligns `[click to jump]` on user prompts.
 *
 * [StructuredTerminalReader] dispatches to per-agent [TerminalDialect] rules so each agent's
 * terminal output is faithfully parsed into mobile-native blocks.
 */
sealed interface TerminalSemanticBlock {
    /** A user instruction (`> ` / `❯ ` / `› ` / `[click to jump]` / shell command), including `<hc ...>...</hc>`. */
    data class UserPromptBlock(val text: String) : TerminalSemanticBlock

    /** Consecutive thinking / tool-call lines folded into one collapsible group. */
    data class ToolGroupBlock(
        val summaryLabel: String,
        val steps: List<String>,
        val hasError: Boolean = false
    ) : TerminalSemanticBlock

    /** Monospace content that must keep its layout: fenced/indented code, tables, diffs, JSON, shell output. */
    data class CodeOrTableBlock(
        val content: String,
        val kindLabel: String? = null
    ) : TerminalSemanticBlock

    /** Errors and blocking prompts (permission / confirmation / runtime auth errors). */
    data class UrgentAlertBlock(
        val title: String,
        val detail: String,
        val isError: Boolean
    ) : TerminalSemanticBlock

    /** Agent prose with hard wraps re-joined into natural paragraphs. */
    data class AgentProseBlock(
        val text: String,
        val isBullet: Boolean = false,
        val isHeading: Boolean = false,
        val indentLevel: Int = 0
    ) : TerminalSemanticBlock
}

/**
 * Per-agent TUI dialect used to disambiguate prompts, tool trees, tables, and bottom status docks.
 */
enum class TerminalDialect {
    ANTIGRAVITY,
    CODEX,
    PI,
    CLAUDE,
    SHELL,
    GENERIC
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
    private const val MAX_CODEX_CHILD_PREVIEW_LINES = 4
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
    // Shared & dialect-specific TUI chrome / status bar patterns
    // ---------------------------------------------------------------------------------------------

    private val fullRuleRegex = Regex("^[─━═╌╍┄┅┈┉\\-_=~]{4,}$")
    private val longRuleRegex = Regex("^[─━═]{8,}$")
    private val compactedDividerRegex = Regex("^[─━\\s]*Conversation compacted[─━\\s]*$", RegexOption.IGNORE_CASE)
    private val inputBoxBorderRegex = Regex("^[╭╰┌└][─━]{4,}[╮╯┐┘]$")
    private val emptyBoxRowRegex = Regex("^│\\s*(?:[>❯›]\\s*)?│?$")
    private val lonePromptRegex = Regex("^[>❯›]\\s*$")
    private val progressBarRegex = Regex("[█▉▊▋▌▍▎▏░▒▓]{3,}")
    private val tipRegex = Regex("^(?:[※⎿*]\\s*)?Tip:")

    private val commonFooterRegex = Regex(
        "(?i)(\\? for shortcuts|esc to (?:cancel|interrupt)|ctrl\\+c to (?:exit|quit|cancel)|" +
            "shift\\+tab to|auto-accept|bypass(?:ing)? permissions|-- INSERT --|" +
            "tok/s.*TTFT:|Cline upstream:|← for agents|Thinking blocks:|Tool output:|" +
            "\\[hc] incomplete:|context left|\\bMCP:\\s*\\d+\\s+servers?|" +
            "\\d+\\s+artifacts?\\s*·\\s*/artifact to review|" +
            "⚠\\s*\\d+\\s*warnings?\\s*·\\s*f2 to view)"
    )

    // Antigravity bottom dock lines: model badge & background daemon task rows
    private val agyModelFooterRegex = Regex(
        "(?i)(?:Claude\\s+(?:Opus|Sonnet|Haiku)|Gemini\\s+[\\w.-]+(?:\\s+[\\w.-]+)?|GPT-[\\w.-]+)\\s*·\\s*(?:low|medium|high|xhigh)\\b"
    )
    private val agyDaemonTaskRegex = Regex("^\\s*●\\s+\\[\\d{2}:\\d{2}:\\d{2}]\\s+.*\\brunning\\s*$")

    // Codex bottom dock & transcript grammar are defined in CodexTranscriptSpec (`codex-rs/tui` source truth).

    // Pi specific patterns
    private val piClickToJumpPromptRegex = Regex("^(.+?)\\s{2,}\\[click to jump]\\s*$")
    private val piThoughtHeaderRegex = Regex(
        "^\\s*Thought for\\s+([^·]+?)\\s*·\\s*(\\d+\\s+more\\s+lines)(?:\\s*·\\s*click to show more)?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val piToolGroupHeaderRegex = Regex(
        "^\\s*●\\s+([A-Za-z][A-Za-z0-9_-]*):\\s+(.+?)(?:\\s*·\\s*click to show more)?\\s*$"
    )
    private val piTodosHeaderRegex = Regex("^\\s*●\\s+Todos\\s*\\((\\d+/\\d+)\\)\\s*$")
    private val piCheckToolRegex = Regex(
        "^\\s*[✓✔✗✘]\\s+(?:Bash|Read|Edit|Write|Grep|Glob|LS|Fetch|Search|MCP|Agent|Task)\\b(.*)$"
    )
    private val piPipeSummaryRegex = Regex(
        "^\\s*│\\s*(?:Ran|Read|Edited|Wrote|Searched|Executed)\\s+\\d+\\s+.*$"
    )
    private val piSessionNoiseRegex = Regex(
        "^\\s*(?:Resumed session|Thinking level:\\s*\\S+)\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val piTelemetryLineRegex = Regex(".*[◉◕◐◯]\\s*(?:low|medium|high|xhigh)\\s*·.*tok/s.*")

    // Shell prompt patterns
    private val unixShellPromptRegex = Regex("^([A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:[^$#%\\n]*[$#%]|(?:bash|zsh|sh)-\\d+\\.\\d+[$#])\\s*(.*)$")
    private val winShellPromptRegex = Regex("^(PS\\s+[A-Za-z]:\\\\[^>\\n]*>|[A-Za-z]:\\\\[^>\\n]*>)\\s*(.*)$")

    private val workingHintRegex = Regex(
        "(?i)(esc to (?:interrupt|cancel)|^\\s*[\\u2800-\\u28FF✻✽✶✳✢]\\s+\\S.*(?:…|\\.\\.\\.|ing\\b))"
    )

    // ---------------------------------------------------------------------------------------------
    // Line classification regexes
    // ---------------------------------------------------------------------------------------------

    private val genericPromptRegex = Regex("^\\s*(?:>|❯|›)\\s+(.*\\S.*)$")
    private val agyPromptRegex = Regex("^>\\s+(.*\\S.*)$")
    private val codexPromptRegex = Regex("^›\\s+(.*\\S.*)$")
    private val claudePromptRegex = Regex("^\\s*❯\\s+(.*\\S.*)$")
    private val promptMenuOptionRegex = Regex("^\\s*[>❯›]\\s*\\d{1,2}[.)]\\s+\\S.*$")

    private val thoughtRegex = Regex("^\\s*[○◦▸▾▼•●⏺∴✻]?\\s*Thought for\\b.*$")

    private val agyToolVerbRegex = Regex(
        "^\\s*[●⏺○◦▸▾▼✓✔✗✘]\\s+(?:Thought|Thinking|Ran|Run|Running|Edited|Editing|Explored|Exploring|" +
            "Read|Reading|Wrote|Writing|Searched|Searching|Updated|Updating|Created|Creating|Deleted|Deleting|" +
            "Listed|Listing|Called|Calling|Executed|Executing|Viewed|Viewing|Analyzed|Fetched|Fetching|" +
            "Applied|Applying|Scheduling|Scheduled|Checked|Checking|Killed|Killing|Cancelled|Canceled|" +
            "Sent|Sending|Invoked|Invoking|Defined|Defining|Asked|Asking|Worked|Waited|Grepped|Globbed|Patched|Inspected)\\b.*$"
    )

    private val codexLeadProseRegex = Regex("^•\\s+(.+)$")
    private val codexOrphanDiffLineRegex = Regex("^\\s{3,}\\d+\\s+(?:[+-]|\\s).*$")

    private val toolCallParenRegex = Regex("^\\s*[●⏺•]\\s+[A-Z][A-Za-z0-9_.:-]*\\(.*$")
    private val bareToolSummaryRegex = Regex(
        "^\\s*(?:Ran|Edited|Explored|Read|Searched|Wrote|Updated|Created)\\s+\\d+\\s+\\S+.*$"
    )
    // Supports BOTH Unicode horizontal ellipsis `…` (\u2026) and ASCII `...`
    private val moreActionsRegex = Regex("^\\s*(?:…|\\.{2,3})\\s*(\\d+)\\s+more\\s+actions\\b(.*)$", RegexOption.IGNORE_CASE)
    private val spinnerRegex = Regex("^\\s*[\\u2800-\\u28FF✻✽✶✳✢]\\s+\\S.*$")
    private val toolChildRegex = Regex("^\\s*(?:[└├](?:[─\\s]*\\S.*)?|⎿(?:\\s*\\S.*)?|↳\\s*\\S.*)$")

    private val errorRegex = Regex(
        "^\\s*(?:[●⏺•✗✘]\\s*)?(Error|ERROR|error|Fatal|FATAL|fatal|panic|Exception|FAILED)" +
            "(?:\\[[^\\]]*])?\\s*[:：]\\s*(.*)$"
    )
    private val agyWarningErrorRegex = Regex(
        "^\\s*⚠\\s+([A-Z_]{3,}(?:\\s*\\([^)]+\\))?|[A-Za-z0-9_.-]*Error|Failed|Permission|Quota|Rate limit)\\s*[:：]?\\s*(.*)$"
    )
    private val tracebackRegex = Regex("^\\s*Traceback \\(most recent call last\\):?\\s*$")
    private val permissionErrorRegex = Regex("(?i)(permission denied|access is denied|拒绝访问)")
    private val blockingPromptRegex = Regex(
        "(?i)(locked \\(permission\\)|permission required|requires? (?:your )?(?:approval|permission)|" +
            "needs your approval\\.|" +
            "would you like to (?:run|send input|grant|make)|do you want to (?:proceed|make this edit|run|create|allow|continue|apply|approve)|" +
            "allow (?:this|once|always)|waiting for (?:your )?(?:approval|confirmation|input)|" +
            "需要(?:你的)?(?:授权|确认|批准)|是否(?:允许|继续|执行))"
    )

    private val fenceRegex = Regex("^\\s*(```|~~~)\\s*([A-Za-z0-9_+.#-]*)\\s*$")
    private val tableBorderRegex = Regex("^\\s*[┌┬┐├┼┤└┴┘─━═╞╪╡╘╧╛╒╤╕╔╦╗╠╬╣╚╩╝║+|:\\-\\s]{3,}$")
    private val boxCornerOrCrossRegex = Regex("[┌┬┐├┼┤└┴┘╞╪╡╘╧╛╒╤╕╔╦╗╠╬╣╚╩╝]")
    private val pipeCrossSeparatorRegex = Regex("^\\s*[─━═]{2,}(?:[┼╪╬┬┴][─━═]{2,})+\\s*$")
    private val asciiTableBorderRegex = Regex("^\\s*\\+[-=+]{3,}\\+\\s*$")
    private val markdownTableRegex = Regex("^\\s*\\|.*\\|\\s*$")
    private val diffLineRegex = Regex(
        "^(?:\\s*\\d+\\s*[+-]\\s|@@ .* @@|diff --git |\\+\\+\\+ |--- (?:a/|b/|/dev/null)|index [0-9a-f]+\\.\\.)"
    )

    private val headingRegex = Regex("^\\s*#{1,6}\\s+(.+)$")
    private val boldHeadingRegex = Regex("^\\s*\\*\\*([^*]{1,80})\\*\\*\\s*[:：]?\\s*$")
    private val blockquoteLineRegex = Regex("^(\\s*)>+\\s*(.*)$")
    private val unorderedBulletRegex = Regex("^(\\s*)[•\\-*·◦▪‣]\\s+(.+)$")
    private val orderedBulletRegex = Regex("^(\\s*)(\\d{1,2}[.)]\\s+|\\d{1,2}、\\s*|[（(]\\d{1,2}[)）]\\s*)(.+)$")
    private val proseLeadDotRegex = Regex("^(\\s*)[●⏺]\\s+(.+)$")

    private val stepErrorRegex = Regex(
        "(?i)(\\bfailed\\b|\\bfailure\\b|\\berror\\b|status\\s+[1-9]\\d*|exit code [1-9]|\\bexit [1-9]|✗|✘|denied|timed out)"
    )

    // Inline cleanup regexes
    private val ctrlExpandSuffixRegex = Regex("\\s*\\(ctrl\\+[a-z] to expand\\)\\s*$", RegexOption.IGNORE_CASE)
    private val clickShowMoreSuffixRegex = Regex("\\s*·\\s*click to show more\\s*$", RegexOption.IGNORE_CASE)
    private val codexRedundantFileLinkRegex = Regex(
        "([A-Za-z0-9_.-]+\\.[A-Za-z0-9]{1,8}(?::\\d+(?:-\\d+)?)?)\\s+" +
            "\\((?:[A-Za-z]:|/|\\.{0,2}/|[A-Za-z0-9_\\u4E00-\\u9FFF.-]+/)[^()]*?" +
            "([A-Za-z0-9_.-]+\\.[A-Za-z0-9]{1,8}(?::\\d+(?:-\\d+)?)?)\\)"
    )
    private val codexLongAbsPathParenRegex = Regex(
        "\\((?:/)?[A-Za-z]:[/\\\\](?:[^()\\\\/]+[/\\\\]){3,}([^()]+)\\)"
    )

    // Quick-action detection
    private val menuOptionRegex = Regex("^\\s*(?:[❯›>]\\s*)?(\\d{1,2})[.)]\\s+(.{1,80})$")
    private val yesNoRegex = Regex("(?i)(\\(y/n\\)|\\[y/n]|\\(yes/no\\)|\\[yes/no])")
    private val questionTailRegex = Regex("[?？]\\s*$")

    // ---------------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------------

    fun detectDialect(agentApp: String?, rawScreen: String = ""): TerminalDialect {
        when (agentApp?.lowercase()?.trim()) {
            "antigravity", "agy" -> return TerminalDialect.ANTIGRAVITY
            "codex", "openai-codex" -> return TerminalDialect.CODEX
            "pi", "π" -> return TerminalDialect.PI
            "claude", "claude-code" -> return TerminalDialect.CLAUDE
            "bash", "zsh", "sh", "fish", "pwsh", "powershell", "cmd", "nu" -> return TerminalDialect.SHELL
        }
        if (rawScreen.isBlank()) return TerminalDialect.GENERIC
        return when {
            rawScreen.contains("Ask Codex to do anything") ||
                rawScreen.contains("OpenAI Codex") ||
                rawScreen.contains("← for agents") ||
                rawScreen.contains("ctrl+t to expand") ||
                rawScreen.contains("ctrl+t to view transcript") ||
                Regex("\\bWorked for (?:<1s|\\d+[dhms])").containsMatchIn(rawScreen) ||
                Regex("(?m)^\\s*■[\\s\\u200A]+\\S").containsMatchIn(rawScreen) -> TerminalDialect.CODEX
            rawScreen.contains("[click to jump]") ||
                rawScreen.contains("click to show more") ||
                rawScreen.contains("Cline upstream:") -> TerminalDialect.PI
            rawScreen.contains("(ctrl+o to expand)") ||
                rawScreen.contains("▸ Thought for ") ||
                rawScreen.contains("○ Thought for ") ||
                rawScreen.contains("/artifact to review") ||
                rawScreen.contains("Conversation compacted") -> TerminalDialect.ANTIGRAVITY
            rawScreen.contains('\u23BF') || Regex("(?m)^\\s*⏺\\s+").containsMatchIn(rawScreen) -> TerminalDialect.CLAUDE
            Regex("(?m)^(?:[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:[^$#%\\n]*[$#%]|PS\\s+[A-Za-z]:\\\\[^>\\n]*>)\\s").containsMatchIn(rawScreen) ->
                TerminalDialect.SHELL
            else -> TerminalDialect.GENERIC
        }
    }

    fun parseBlocks(rawScreen: String, agentApp: String? = null): List<TerminalSemanticBlock> {
        if (rawScreen.isBlank()) return emptyList()
        val dialect = detectDialect(agentApp, rawScreen)
        return runCatching {
            val cleaned = cleanLines(rawScreen, dialect)
            if (cleaned.isEmpty()) return emptyList()
            if (dialect == TerminalDialect.SHELL) {
                parseShellBlocks(cleaned)
            } else {
                BlockParser(cleaned, dialect).parse()
            }
        }.getOrElse {
            val fallback = cleanLines(rawScreen, TerminalDialect.GENERIC).joinToString("\n").trim()
            if (fallback.isEmpty()) emptyList() else listOf(TerminalSemanticBlock.CodeOrTableBlock(fallback))
        }
    }

    fun extractQuickActions(rawScreen: String?, summary: TerminalSummary?): List<QuickReplyAction> {
        val raw = rawScreen.orEmpty()
        val dialect = detectDialect(summary?.agentApp, raw)
        val rawTail = stripControl(raw).replace("\r\n", "\n").split('\n')
            .map { it.trimEnd('\r').substringAfterLast('\r').trimEnd() }
            .filter { it.isNotBlank() }
            .takeLast(30)
        val cleanTail = cleanLines(raw, dialect).filter { it.isNotBlank() }.takeLast(16)

        val menuOptions = findMenuOptions(cleanTail)
        val hasYesNo = cleanTail.any { yesNoRegex.containsMatchIn(it) }
        val looksWorking = rawTail.takeLast(8).any {
            workingHintRegex.containsMatchIn(it) || CodexTranscriptSpec.liveStatusIndicatorRegex.matches(it)
        }
        val looksBlocked = menuOptions.isNotEmpty() || hasYesNo ||
            cleanTail.takeLast(8).any {
                blockingPromptRegex.containsMatchIn(it) ||
                    CodexTranscriptSpec.approvalOverlayTitleRegex.matches(it) ||
                    CodexTranscriptSpec.requestUserInputOverlayRegex.matches(it)
            }
        val looksError = cleanTail.takeLast(6).any {
            errorRegex.matches(it) ||
                agyWarningErrorRegex.matches(it) ||
                CodexTranscriptSpec.errorSquareRegex.matches(it) ||
                CodexTranscriptSpec.safetyBlockRegex.matches(it)
        }

        val status = when (val s = summary?.agentStatus ?: AgentStateWord.NONE) {
            AgentStateWord.NONE -> when {
                looksBlocked -> AgentStateWord.BLOCKED
                looksWorking -> AgentStateWord.WORKING
                looksError -> AgentStateWord.ERROR
                else -> AgentStateWord.DONE
            }
            else -> s
        }

        val expandKey = if (dialect == TerminalDialect.CODEX) "ctrl+t" else "ctrl+o"
        val expandLabel = if (dialect == TerminalDialect.CODEX) "展开输出 Ctrl+T" else "展开详情 Ctrl+O"
        val errorExpandLabel = if (dialect == TerminalDialect.CODEX) "展开报错" else "查看报错"

        val actions = when (status) {
            AgentStateWord.BLOCKED -> blockedActions(menuOptions, hasYesNo)
            AgentStateWord.ERROR -> listOf(
                QuickReplyAction("重试/继续", "继续，重试刚才失败的步骤", isPrimary = true),
                QuickReplyAction(errorExpandLabel, expandKey, isSpecialKey = true),
                QuickReplyAction("分析原因", "先分析这个报错的根本原因，给出修复方案，暂不改代码"),
                QuickReplyAction("⏎ 回车", "enter", isSpecialKey = true)
            )
            AgentStateWord.WORKING -> listOf(
                QuickReplyAction(expandLabel, expandKey, isSpecialKey = true, isPrimary = true),
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
        val dialect = detectDialect(summary.agentApp, rawScreen.orEmpty())
        val blocks = if (rawScreen.isNullOrBlank()) emptyList() else parseBlocks(rawScreen, summary.agentApp)
        val status = summary.agentStatus

        // 1. Blocked / error: concrete question or failure alert.
        if (status == AgentStateWord.BLOCKED || status == AgentStateWord.ERROR) {
            val cleanTail = if (rawScreen.isNullOrBlank()) emptyList()
            else cleanLines(rawScreen, dialect).filter { it.isNotBlank() }.takeLast(16)
            val question = findQuestionLine(cleanTail)
            if (question != null) return polishHeadline(question)
            val alert = blocks.lastOrNull { it is TerminalSemanticBlock.UrgentAlertBlock }
                as? TerminalSemanticBlock.UrgentAlertBlock
            if (alert != null) {
                val firstDetail = alert.detail.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                val text = when {
                    firstDetail.isBlank() -> alert.title
                    alert.title.equals("Error", ignoreCase = true) || alert.title == "运行异常" -> firstDetail
                    else -> "${alert.title}: $firstDetail"
                }
                return polishHeadline(text)
            }
        }

        // 2. hc's own agent message (populated by Antigravity hooks, etc.).
        summary.agentMessage?.takeIf { it.isNotBlank() }?.let { return polishHeadline(it) }

        // 3. The agent's latest prose block after the last user prompt (prefer lead prose over bullets).
        val lastPromptIndex = blocks.indexOfLast { it is TerminalSemanticBlock.UserPromptBlock }
        val postPromptProse = blocks.drop(if (lastPromptIndex >= 0) lastPromptIndex + 1 else 0)
            .filterIsInstance<TerminalSemanticBlock.AgentProseBlock>()
            .filter { !it.isHeading && it.text.isNotBlank() }

        // In Codex & Agy, the first non-bullet paragraph of the final response section is the crispest summary,
        // or the last non-bullet paragraph (conclusion / question to user).
        val conclusionProse = postPromptProse.lastOrNull { !it.isBullet } ?: postPromptProse.lastOrNull()
        if (conclusionProse != null) {
            return polishHeadline(firstSentence(conclusionProse.text))
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

        // 6. Any earlier prose on screen.
        blocks.asReversed().firstOrNull {
            it is TerminalSemanticBlock.AgentProseBlock && !it.isHeading && it.text.isNotBlank()
        }?.let { return polishHeadline(firstSentence((it as TerminalSemanticBlock.AgentProseBlock).text)) }

        // 7. Status fallback.
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
    // Cleaning & Dialect Bottom-Dock Stripping
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

    private fun cleanLines(raw: String, dialect: TerminalDialect): List<String> {
        val rawLines = stripControl(raw)
            .replace("\r\n", "\n")
            .split('\n')
            .takeLast(MAX_INPUT_LINES)
            .map { it.trimEnd('\r').substringAfterLast('\r').replace('\u00A0', ' ').trimEnd() }
            .toMutableList()

        stripBottomDock(rawLines, dialect)
        if (dialect == TerminalDialect.PI) {
            stripPiBanners(rawLines)
        } else if (dialect == TerminalDialect.CODEX) {
            stripCodexBanners(rawLines)
        }

        val out = ArrayList<String>(rawLines.size)
        var lastBlank = true
        var insideCodexStatusCard = false

        for (idx in rawLines.indices) {
            var line = rawLines[idx]
            val prevTrimmed = rawLines.getOrNull(idx - 1)?.trim().orEmpty()
            val nextTrimmed = rawLines.getOrNull(idx + 1)?.trim().orEmpty()
            val partOfBoxTable = boxCornerOrCrossRegex.containsMatchIn(prevTrimmed) ||
                boxCornerOrCrossRegex.containsMatchIn(nextTrimmed)

            if (!partOfBoxTable && !piPipeSummaryRegex.matches(line)) {
                line = unwrapFramedMessageRow(line)
            }

            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                if (!lastBlank) out.add("")
                lastBlank = true
                if (insideCodexStatusCard && !nextTrimmed.startsWith("Server:") &&
                    !nextTrimmed.startsWith("Model:") && !nextTrimmed.startsWith("5h limit:") &&
                    !nextTrimmed.startsWith("Visit ")
                ) {
                    insideCodexStatusCard = false
                }
                continue
            }

            if (dialect == TerminalDialect.CODEX && trimmed.startsWith(">_ OpenAI Codex")) {
                insideCodexStatusCard = true
            }

            if (isNoise(trimmed, dialect, insideCodexStatusCard)) continue
            out.add(line)
            lastBlank = false
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.lastIndex)
        return out
    }

    /**
     * Deterministically removes the live interactive input composer & status dock at the bottom
     * of each agent's TUI so live drafts, placeholders (`Ask Codex to do anything`), model badges,
     * and background cron bars never leak into the conversation blocks.
     */
    private fun stripBottomDock(lines: MutableList<String>, dialect: TerminalDialect) {
        if (lines.isEmpty()) return

        when (dialect) {
            TerminalDialect.ANTIGRAVITY -> {
                var end = lines.lastIndex
                val floor = (lines.size - 28).coerceAtLeast(0)
                var sawBottomRule = false
                while (end >= floor) {
                    val t = lines[end].trim()
                    when {
                        t.isEmpty() -> end--
                        commonFooterRegex.containsMatchIn(t) || agyModelFooterRegex.containsMatchIn(t) -> end--
                        agyDaemonTaskRegex.matches(lines[end]) -> end--
                        longRuleRegex.matches(t) -> {
                            sawBottomRule = true
                            end--
                        }
                        sawBottomRule && (t == ">" || t.startsWith("> ")) -> {
                            // Live composer prompt `> ...` is sandwiched between two `───` rules at the bottom.
                            val aboveIdx = (end - 1 downTo floor).firstOrNull { lines[it].isNotBlank() }
                            if (aboveIdx != null && longRuleRegex.matches(lines[aboveIdx].trim())) {
                                end = aboveIdx - 1
                            }
                            break
                        }
                        else -> break
                    }
                }
                if (end < lines.lastIndex) {
                    lines.subList(end + 1, lines.size).clear()
                }
            }

            TerminalDialect.CODEX -> {
                var end = lines.lastIndex
                val floor = (lines.size - 24).coerceAtLeast(0)
                var sawFooterBelow = false
                while (end >= floor) {
                    val raw = lines[end]
                    val t = raw.trim()
                    when {
                        t.isEmpty() -> end--
                        commonFooterRegex.containsMatchIn(t) ||
                            CodexTranscriptSpec.modelCwdBarRegex.matches(raw) ||
                            CodexTranscriptSpec.quotaInlineRegex.matches(raw) ||
                            CodexTranscriptSpec.quotaHeadsUpRegex.matches(raw) -> {
                            sawFooterBelow = true
                            end--
                        }
                        CodexTranscriptSpec.composerPlaceholderRegex.matches(t) -> {
                            end--
                            break
                        }
                        sawFooterBelow &&
                            (t == "›" || (t.startsWith("› ") && !promptMenuOptionRegex.matches(raw))) &&
                            !t.contains("<hc") -> {
                            // Unsubmitted text sitting in the bottom Codex composer right above the model bar.
                            end--
                            break
                        }
                        else -> break
                    }
                }
                if (end < lines.lastIndex) {
                    lines.subList(end + 1, lines.size).clear()
                }
            }

            TerminalDialect.PI -> {
                var end = lines.lastIndex
                val floor = (lines.size - 18).coerceAtLeast(0)
                var sawBottomRule = false
                while (end >= floor) {
                    val t = lines[end].trim()
                    when {
                        t.isEmpty() -> end--
                        commonFooterRegex.containsMatchIn(t) || piTelemetryLineRegex.matches(t) -> end--
                        longRuleRegex.matches(t) -> {
                            sawBottomRule = true
                            end--
                        }
                        sawBottomRule && (t == "❯" || t.startsWith("❯ ")) -> {
                            val aboveIdx = (end - 1 downTo floor).firstOrNull { lines[it].isNotBlank() }
                            if (aboveIdx != null && longRuleRegex.matches(lines[aboveIdx].trim())) {
                                end = aboveIdx - 1
                            }
                            break
                        }
                        else -> break
                    }
                }
                if (end < lines.lastIndex) {
                    lines.subList(end + 1, lines.size).clear()
                }
            }

            TerminalDialect.CLAUDE, TerminalDialect.GENERIC -> {
                var end = lines.lastIndex
                val floor = (lines.size - 16).coerceAtLeast(0)
                var sawBottomRule = false
                while (end >= floor) {
                    val t = lines[end].trim()
                    when {
                        t.isEmpty() -> end--
                        commonFooterRegex.containsMatchIn(t) || agyModelFooterRegex.containsMatchIn(t) -> end--
                        longRuleRegex.matches(t) || inputBoxBorderRegex.matches(t) -> {
                            sawBottomRule = true
                            end--
                        }
                        sawBottomRule && lonePromptRegex.matches(t) -> {
                            val aboveIdx = (end - 1 downTo floor).firstOrNull { lines[it].isNotBlank() }
                            if (aboveIdx != null && (longRuleRegex.matches(lines[aboveIdx].trim()) || inputBoxBorderRegex.matches(lines[aboveIdx].trim()))) {
                                end = aboveIdx - 1
                            }
                            break
                        }
                        else -> break
                    }
                }
                if (end < lines.lastIndex) {
                    lines.subList(end + 1, lines.size).clear()
                }
            }

            TerminalDialect.SHELL -> Unit
        }
    }

    /** Strips Pi's `Package Updates Available` box between horizontal rules. */
    private fun stripPiBanners(lines: MutableList<String>) {
        var i = 0
        while (i < lines.size) {
            if (lines[i].trim() == "Package Updates Available") {
                val start = if (i > 0 && longRuleRegex.matches(lines[i - 1].trim())) i - 1 else i
                var end = i + 1
                while (end < lines.size && !longRuleRegex.matches(lines[end].trim())) {
                    end++
                }
                if (end < lines.size && longRuleRegex.matches(lines[end].trim())) {
                    lines.subList(start, end + 1).clear()
                    i = start
                    continue
                }
            }
            i++
        }
    }

    /** Strips Codex's `UpdateAvailableHistoryCell` (`notices.rs:2964`) boxed or plain update banner. */
    private fun stripCodexBanners(lines: MutableList<String>) {
        var i = 0
        while (i < lines.size) {
            val unwrapped = unwrapFramedMessageRow(lines[i]).trim()
            if (CodexTranscriptSpec.updateAvailableBannerRegex.matches(unwrapped)) {
                val hasTopBorder = i > 0 && inputBoxBorderRegex.matches(lines[i - 1].trim())
                val start = if (hasTopBorder) i - 1 else i
                var end = i + 1
                val limit = (i + 12).coerceAtMost(lines.size)
                while (end < limit) {
                    val t = lines[end].trim()
                    if (hasTopBorder && inputBoxBorderRegex.matches(t)) {
                        end++
                        break
                    }
                    val u = unwrapFramedMessageRow(lines[end]).trim()
                    if (!hasTopBorder && u.startsWith("https://github.com/openai/codex/releases")) {
                        end++
                        break
                    }
                    end++
                }
                lines.subList(start, end).clear()
                i = start
                continue
            }
            i++
        }
    }

    /** `│ text │` or `│ text` (a framed message, not a multi-column table) -> `text`, keeping relative indent. */
    private fun unwrapFramedMessageRow(line: String): String {
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

    private fun isNoise(trimmed: String, dialect: TerminalDialect, insideCodexStatusCard: Boolean): Boolean {
        if (compactedDividerRegex.matches(trimmed)) return true
        if (fullRuleRegex.matches(trimmed)) return true
        if (CodexTranscriptSpec.markdownEmDashRuleRegex.matches(trimmed)) return true
        if (inputBoxBorderRegex.matches(trimmed)) return true
        if (emptyBoxRowRegex.matches(trimmed)) return true
        if (lonePromptRegex.matches(trimmed)) return true
        if (tipRegex.containsMatchIn(trimmed)) return true
        if (!insideCodexStatusCard && progressBarRegex.containsMatchIn(trimmed)) return true
        if (commonFooterRegex.containsMatchIn(trimmed)) return true

        return when (dialect) {
            TerminalDialect.ANTIGRAVITY ->
                agyModelFooterRegex.containsMatchIn(trimmed) || agyDaemonTaskRegex.matches(trimmed)
            TerminalDialect.CODEX ->
                CodexTranscriptSpec.composerPlaceholderRegex.matches(trimmed) ||
                    CodexTranscriptSpec.quotaHeadsUpRegex.matches(trimmed) ||
                    CodexTranscriptSpec.quotaInlineRegex.matches(trimmed) ||
                    CodexTranscriptSpec.modelCwdBarRegex.matches(trimmed)
            TerminalDialect.PI ->
                piSessionNoiseRegex.matches(trimmed) || piTelemetryLineRegex.matches(trimmed)
            else ->
                CodexTranscriptSpec.composerPlaceholderRegex.matches(trimmed)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Shell Parser (`bash` / `zsh` / `pwsh` / `cmd`)
    // ---------------------------------------------------------------------------------------------

    private fun parseShellBlocks(lines: List<String>): List<TerminalSemanticBlock> {
        val blocks = ArrayList<TerminalSemanticBlock>()
        val outputBuf = ArrayList<String>()

        fun flushOutput() {
            while (outputBuf.isNotEmpty() && outputBuf.last().isBlank()) outputBuf.removeAt(outputBuf.lastIndex)
            while (outputBuf.isNotEmpty() && outputBuf.first().isBlank()) outputBuf.removeAt(0)
            if (outputBuf.isNotEmpty()) {
                blocks.add(TerminalSemanticBlock.CodeOrTableBlock(outputBuf.joinToString("\n"), kindLabel = "终端输出"))
                outputBuf.clear()
            }
        }

        for (line in lines) {
            val unixMatch = unixShellPromptRegex.find(line)
            val winMatch = if (unixMatch == null) winShellPromptRegex.find(line) else null
            val match = unixMatch ?: winMatch
            if (match != null) {
                flushOutput()
                val promptPrefix = if (winMatch != null) "PS>" else "$"
                val cmd = match.groupValues[2].trim()
                if (cmd.isNotEmpty()) {
                    blocks.add(TerminalSemanticBlock.UserPromptBlock("$promptPrefix $cmd"))
                }
            } else {
                outputBuf.add(line)
            }
        }
        flushOutput()
        return blocks
    }

    // ---------------------------------------------------------------------------------------------
    // Dialect-Aware Block Parser
    // ---------------------------------------------------------------------------------------------

    private class BlockParser(
        private val lines: List<String>,
        private val dialect: TerminalDialect
    ) {
        private val blocks = ArrayList<TerminalSemanticBlock>()
        private val maxWidth: Int = lines.maxOfOrNull { displayWidth(it) } ?: 0
        private val wrapThreshold: Int = maxOf(40, (maxWidth * 0.72f).toInt())
        private val baseIndent: Int = when (dialect) {
            TerminalDialect.ANTIGRAVITY -> 2
            TerminalDialect.CODEX -> 2
            else -> lines
                .filter { it.isNotBlank() && !isToolHead(it) }
                .minOfOrNull { indentOf(it) }
                ?.coerceAtMost(4) ?: 0
        }

        // Current prose paragraph state
        private var paraText: StringBuilder? = null
        private var paraBullet = false
        private var paraIndent = 0
        private var paraIndentLevel = 0
        private var paraLastWidth = 0

        private var i = 0

        fun parse(): List<TerminalSemanticBlock> {
            // Handle Codex scrollback starting mid-diff / mid-tool-output whose header scrolled off top.
            if (dialect == TerminalDialect.CODEX && lines.isNotEmpty() && isCodexOrphanToolStart()) {
                consumeCodexToolGroup(orphanStart = true)
            }

            while (i < lines.size) {
                val line = lines[i]
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> {
                        flushParagraph()
                        i++
                    }
                    dialect == TerminalDialect.CODEX && trimmed.startsWith(">_ OpenAI Codex") -> {
                        flushParagraph()
                        consumeCodexStatusCard()
                    }
                    dialect == TerminalDialect.CODEX && CodexTranscriptSpec.finalSeparatorRegex.matches(line) -> {
                        flushParagraph()
                        consumeCodexWorkedFor()
                    }
                    dialect == TerminalDialect.CODEX && CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(trimmed) -> {
                        i++
                    }
                    dialect == TerminalDialect.CODEX && CodexTranscriptSpec.userImageBadgeRegex.matches(line) -> {
                        flushParagraph()
                        consumeCodexUserImageBadge()
                    }
                    dialect == TerminalDialect.CODEX && CodexTranscriptSpec.userShellHeadRegex.matches(trimmed) -> {
                        flushParagraph()
                        consumeCodexUserShell()
                    }
                    dialect == TerminalDialect.CODEX && CodexTranscriptSpec.delegatedMessageHeadRegex.matches(trimmed) -> {
                        flushParagraph()
                        val taskId = CodexTranscriptSpec.delegatedMessageHeadRegex.find(trimmed)!!.groupValues[1]
                        blocks.add(TerminalSemanticBlock.AgentProseBlock("来自子任务 $taskId", isHeading = true))
                        i++
                    }
                    dialect == TerminalDialect.CODEX && CodexTranscriptSpec.proposedPlanHeadRegex.matches(trimmed) -> {
                        flushParagraph()
                        blocks.add(TerminalSemanticBlock.AgentProseBlock("Proposed Plan", isHeading = true))
                        i++
                    }
                    dialect == TerminalDialect.CODEX && CodexTranscriptSpec.planUpdateHeadRegex.matches(trimmed) -> {
                        flushParagraph()
                        consumeCodexPlanUpdate()
                    }
                    dialect == TerminalDialect.CODEX && CodexTranscriptSpec.questionsResultHeadRegex.matches(trimmed) -> {
                        flushParagraph()
                        consumeCodexQuestionsResult()
                    }
                    dialect == TerminalDialect.CODEX && CodexTranscriptSpec.threadRecapHeadRegex.matches(line) -> {
                        flushParagraph()
                        consumeCodexThreadRecap()
                    }
                    dialect == TerminalDialect.CODEX && isCodexStructuredAlertStart(line) -> {
                        flushParagraph()
                        consumeCodexStructuredAlert()
                    }
                    dialect == TerminalDialect.PI && piTodosHeaderRegex.matches(line) -> {
                        flushParagraph()
                        consumePiTodos()
                    }
                    fenceRegex.matches(line) -> {
                        flushParagraph()
                        consumeFence()
                    }
                    isPromptLine(line) -> {
                        flushParagraph()
                        consumePrompt()
                    }
                    isCodexSpacedTableStart(i) -> {
                        flushParagraph()
                        consumeCodexSpacedTable()
                    }
                    isAgyPipeTableStart(i) -> {
                        flushParagraph()
                        consumeAgyPipeTable()
                    }
                    isToolHead(line) -> {
                        flushParagraph()
                        if (dialect == TerminalDialect.CODEX) {
                            consumeCodexToolGroup(orphanStart = false)
                        } else {
                            consumeToolGroup()
                        }
                    }
                    isAlertLine(line) -> {
                        flushParagraph()
                        consumeAlerts()
                    }
                    isTableLine(line) || isDiffLine(line) -> {
                        flushParagraph()
                        val isTbl = isTableLine(line)
                        consumeCodeRun(kindLabel = if (isTbl) "表格" else "Diff") { isTableLine(it) || isDiffLine(it) }
                    }
                    isJsonStart(trimmed) -> {
                        flushParagraph()
                        consumeJson()
                    }
                    dialect == TerminalDialect.CODEX && paraText == null &&
                        CodexTranscriptSpec.detectUnfencedCodeBlockEnd(lines, i, wrapThreshold) != null -> {
                        val endIdx = CodexTranscriptSpec.detectUnfencedCodeBlockEnd(lines, i, wrapThreshold)!!
                        addCode(lines.subList(i, endIdx).joinToString("\n"), kindLabel = "代码")
                        i = endIdx
                    }
                    isIndentedCode(line) -> {
                        flushParagraph()
                        consumeCodeRun(kindLabel = "代码") { isIndentedCode(it) }
                    }
                    toolChildRegex.matches(line) -> {
                        flushParagraph()
                        if (dialect == TerminalDialect.CODEX) {
                            consumeCodexToolGroup(orphanStart = true)
                        } else {
                            consumeToolGroup()
                        }
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

        private fun isPromptLine(line: String): Boolean {
            if (promptMenuOptionRegex.matches(line)) return false
            return when (dialect) {
                TerminalDialect.ANTIGRAVITY -> agyPromptRegex.matches(line)
                TerminalDialect.CODEX ->
                    (codexPromptRegex.matches(line) && !CodexTranscriptSpec.composerPlaceholderRegex.matches(line.trim())) ||
                        CodexTranscriptSpec.slashCmdRegex.matches(line)
                TerminalDialect.PI ->
                    piClickToJumpPromptRegex.matches(line) || claudePromptRegex.matches(line)
                TerminalDialect.CLAUDE -> claudePromptRegex.matches(line)
                else -> genericPromptRegex.matches(line) && !CodexTranscriptSpec.composerPlaceholderRegex.matches(line.trim())
            }
        }

        private fun isPromptBoundary(line: String): Boolean {
            val t = line.trim()
            if (t.isEmpty()) return false
            if (isToolHead(line) || toolChildRegex.matches(line) || isPromptLine(line) || isAlertLine(line)) return true
            if (dialect == TerminalDialect.CODEX) {
                if (indentOf(line) < 2) return true
                if (line.startsWith("• ") || isCodexStructuredAlertStart(line) ||
                    CodexTranscriptSpec.finalSeparatorRegex.matches(line) ||
                    CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(t) ||
                    CodexTranscriptSpec.threadRecapHeadRegex.matches(line)
                ) return true
            }
            return false
        }

        private fun consumePrompt(leadingPrefix: String = "") {
            val rawFirst = lines[i]
            if (dialect == TerminalDialect.PI) {
                piClickToJumpPromptRegex.find(rawFirst)?.let { m ->
                    blocks.add(TerminalSemanticBlock.UserPromptBlock(m.groupValues[1].trim()))
                    i++
                    return
                }
            }
            if (dialect == TerminalDialect.CODEX && CodexTranscriptSpec.slashCmdRegex.matches(rawFirst)) {
                blocks.add(TerminalSemanticBlock.UserPromptBlock(rawFirst.trim()))
                i++
                return
            }

            val rawBody = genericPromptRegex.find(rawFirst)?.groupValues?.get(1)?.trim() ?: rawFirst.trim()
            val first = if (leadingPrefix.isNotEmpty()) "$leadingPrefix $rawBody" else rawBody
            val promptIndent = indentOf(rawFirst)
            i++

            if (first.contains("<hc") && !first.contains("</hc>")) {
                var combined = first
                var lastWidth = displayWidth(rawFirst)
                var consumed = 0
                var truncatedByEllipsis = first.endsWith("…")
                while (i < lines.size && consumed < MAX_HC_MESSAGE_LINES) {
                    val l = lines[i]
                    val t = l.trim()
                    if (truncatedByEllipsis) {
                        if (t.startsWith("<hc") && indentOf(l) > promptIndent) {
                            combined += "\n"
                            truncatedByEllipsis = false
                        } else {
                            break
                        }
                    }
                    if (t.isEmpty()) {
                        val next = nextNonBlank(i + 1)
                        if (next == null || isPromptBoundary(lines[next]) || indentOf(lines[next]) <= promptIndent) {
                            break
                        }
                        combined += "\n"
                        lastWidth = 0
                        i++
                        consumed++
                        continue
                    }
                    if (isPromptBoundary(l)) break
                    combined = joinWrapped(combined, t, wrapped = lastWidth >= wrapThreshold || !combined.endsWith('\n'))
                    lastWidth = displayWidth(l)
                    i++
                    consumed++
                    if (t.endsWith("…") && !t.contains("</hc>")) {
                        truncatedByEllipsis = true
                    }
                    if (t.contains("</hc>")) {
                        val hasImmediatePromptCont = i < lines.size &&
                            lines[i].isNotBlank() &&
                            indentOf(lines[i]) > promptIndent &&
                            !isPromptBoundary(lines[i])
                        if (!hasImmediatePromptCont) break
                        combined += "\n"
                    }
                }
                blocks.add(TerminalSemanticBlock.UserPromptBlock(combined.trim()))
                return
            }

            var text = first
            var lastWidth = displayWidth(rawFirst)
            var prevTruncated = first.endsWith("…")
            while (i < lines.size) {
                val l = lines[i]
                if (prevTruncated && !l.trim().startsWith("<hc")) break
                if (l.isBlank()) break
                if (indentOf(l) <= promptIndent) break
                if (isPromptBoundary(l)) break
                text = joinWrapped(text, l.trim(), lastWidth >= wrapThreshold)
                lastWidth = displayWidth(l)
                prevTruncated = l.trim().endsWith("…")
                i++
            }
            blocks.add(TerminalSemanticBlock.UserPromptBlock(text))
        }

        // ---- Codex specific blocks (`codex-rs/tui` source bijection) ----

        private fun isCodexOrphanToolStart(): Boolean {
            val firstIdx = lines.indexOfFirst { it.isNotBlank() }
            if (firstIdx < 0) return false
            val first = lines[firstIdx]
            if (CodexTranscriptSpec.threadRecapHeadRegex.matches(first)) return false
            if (toolChildRegex.matches(first) || CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(first.trim())) return true
            if (codexOrphanDiffLineRegex.matches(first) || indentOf(first) >= 4) {
                val lookahead = lines.drop(firstIdx).take(16)
                return lookahead.any {
                    toolChildRegex.matches(it) || CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(it.trim())
                }
            }
            return false
        }

        private fun consumeCodexStatusCard() {
            val precededByStatusCmd = (blocks.lastOrNull() as? TerminalSemanticBlock.UserPromptBlock)
                ?.text?.startsWith("/status") == true
            val buf = ArrayList<String>()
            while (i < lines.size) {
                val l = lines[i]
                val t = l.trim()
                if (t.startsWith("• ") || t.startsWith("› ") || isAlertLine(l) || isCodexStructuredAlertStart(l)) break
                if (t.isEmpty()) {
                    val next = nextNonBlank(i + 1)
                    val nextTrim = next?.let { lines[it].trim() }.orEmpty()
                    val continuesCard = nextTrim.startsWith("Visit ") ||
                        nextTrim.startsWith("Server:", ignoreCase = true) ||
                        nextTrim.startsWith("Model:", ignoreCase = true) ||
                        nextTrim.startsWith("Directory:", ignoreCase = true) ||
                        nextTrim.startsWith("Account:", ignoreCase = true) ||
                        nextTrim.startsWith("Session:", ignoreCase = true) ||
                        nextTrim.startsWith("5h limit:", ignoreCase = true) ||
                        nextTrim.startsWith("Weekly limit:", ignoreCase = true) ||
                        nextTrim.startsWith("permissions:", ignoreCase = true)
                    if (!continuesCard) break
                }
                buf.add(l)
                i++
            }
            val joined = buf.joinToString("\n")
            val isFullStatusCard = precededByStatusCmd ||
                joined.contains("limit:") ||
                joined.contains("Server:") ||
                joined.contains("Account:")
            val hasLaterConversation = (i until lines.size).any {
                val t = lines[it].trim()
                t.startsWith("› ") || t.startsWith("• ")
            }
            if (isFullStatusCard || !hasLaterConversation) {
                addCode(joined, kindLabel = "Codex 状态")
            }
        }

        private fun consumeCodexWorkedFor() {
            val firstLine = lines[i]
            val dur = CodexTranscriptSpec.finalSeparatorRegex.find(firstLine)?.groupValues?.get(1)?.trim().orEmpty()
            i++
            // Consume any wrapped runtime metrics continuation lines (`separators.rs:1712`)
            while (i < lines.size && lines[i].isNotBlank() &&
                (CodexTranscriptSpec.finalSeparatorMetricsContRegex.matches(lines[i]) ||
                    (indentOf(lines[i]) == 2 && !isPromptLine(lines[i]) && !isToolHead(lines[i]) &&
                        !CodexTranscriptSpec.threadRecapHeadRegex.matches(lines[i]) &&
                        !isCodexStructuredAlertStart(lines[i])))
            ) {
                i++
            }
            if (dur.isEmpty()) return
            val lastPromptIdx = blocks.indexOfLast { it is TerminalSemanticBlock.UserPromptBlock }
            for (idx in blocks.lastIndex downTo (lastPromptIdx + 1).coerceAtLeast(0)) {
                val blk = blocks[idx]
                if (blk is TerminalSemanticBlock.ToolGroupBlock &&
                    !blk.summaryLabel.startsWith("任务计划") &&
                    !blk.summaryLabel.startsWith("问答确认") &&
                    !blk.summaryLabel.contains("耗时")
                ) {
                    val baseLabel = blk.summaryLabel.replace(" · 进行中", "")
                    blocks[idx] = blk.copy(summaryLabel = "$baseLabel · 耗时 $dur")
                    break
                }
            }
        }

        private fun consumeCodexUserImageBadge() {
            val badges = ArrayList<String>()
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) {
                    val next = nextNonBlank(i + 1)
                    if (next != null && CodexTranscriptSpec.userImageBadgeRegex.matches(lines[next])) {
                        i = next
                        continue
                    }
                    break
                }
                if (CodexTranscriptSpec.userImageBadgeRegex.matches(l)) {
                    badges.add(l.trim())
                    i++
                } else {
                    break
                }
            }
            val badgePrefix = badges.joinToString(" ")
            val next = nextNonBlank(i)
            if (next != null && codexPromptRegex.matches(lines[next]) &&
                !CodexTranscriptSpec.composerPlaceholderRegex.matches(lines[next].trim())
            ) {
                i = next
                consumePrompt(leadingPrefix = badgePrefix)
            } else if (badgePrefix.isNotEmpty()) {
                blocks.add(TerminalSemanticBlock.UserPromptBlock(badgePrefix))
            }
        }

        private fun consumeCodexUserShell() {
            val cmd = CodexTranscriptSpec.userShellHeadRegex.find(lines[i].trim())?.groupValues?.get(1)?.trim().orEmpty()
            if (cmd.isNotEmpty()) {
                blocks.add(TerminalSemanticBlock.UserPromptBlock("! $cmd"))
            }
            i++
            val outLines = ArrayList<String>()
            while (i < lines.size) {
                val l = lines[i]
                val t = l.trim()
                if (t.isEmpty()) {
                    val next = nextNonBlank(i + 1)
                    if (next != null && indentOf(lines[next]) >= 4 && !CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(lines[next].trim())) {
                        outLines.add("")
                        i = next
                        continue
                    }
                    break
                }
                if (CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(t)) {
                    i++
                    continue
                }
                if (toolChildRegex.matches(l)) {
                    outLines.add(t.replace(Regex("^(?:[└├][─\\s]*|⎿|↳)\\s*"), ""))
                    i++
                } else if (indentOf(l) >= 4) {
                    outLines.add(l.removePrefix("    "))
                    i++
                } else {
                    break
                }
            }
            if (outLines.isNotEmpty()) {
                addCode(outLines.joinToString("\n"), kindLabel = "终端输出")
            }
        }

        private fun consumeCodexPlanUpdate() {
            val m = CodexTranscriptSpec.planUpdateHeadRegex.find(lines[i].trim())
            val progress = m?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            val steps = ArrayList<String>()
            i++
            while (i < lines.size) {
                val l = lines[i]
                val t = l.trim()
                if (t.isEmpty()) break
                if (CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(t)) {
                    i++
                    continue
                }
                if (toolChildRegex.matches(l) || indentOf(l) >= 2 && (t.startsWith("✔") || t.startsWith("✓") || t.startsWith("□") || t.startsWith("☐") || t.startsWith("☑") || indentOf(l) >= 4)) {
                    val clean = t.replace(Regex("^(?:[└├][─\\s]*|⎿|↳)\\s*"), "")
                    if (clean.isNotEmpty() && steps.size < MAX_GROUP_STEPS) steps.add(clean)
                    i++
                } else {
                    break
                }
            }
            val label = if (progress != null) "任务计划 ($progress)" else "任务计划 Updated Plan"
            blocks.add(TerminalSemanticBlock.ToolGroupBlock(label, steps, hasError = false))
        }

        private fun consumeCodexQuestionsResult() {
            val ratio = CodexTranscriptSpec.questionsResultHeadRegex.find(lines[i].trim())?.groupValues?.get(1).orEmpty()
            val steps = ArrayList<String>()
            i++
            while (i < lines.size) {
                val l = lines[i]
                val t = l.trim()
                if (t.isEmpty()) break
                if (indentOf(l) >= 2) {
                    val clean = t.removePrefix("• ").trim()
                    if (clean.isNotEmpty() && steps.size < MAX_GROUP_STEPS) {
                        steps.add(if (indentOf(l) >= 4) "  $clean" else clean)
                    }
                    i++
                } else {
                    break
                }
            }
            blocks.add(
                TerminalSemanticBlock.ToolGroupBlock(
                    summaryLabel = if (ratio.isNotEmpty()) "问答确认 ($ratio)" else "问答确认",
                    steps = steps,
                    hasError = false
                )
            )
        }

        private fun consumeCodexThreadRecap() {
            val first = CodexTranscriptSpec.threadRecapHeadRegex.find(lines[i])?.groupValues?.get(1)?.trim().orEmpty()
            var combined = first
            var lastWidth = displayWidth(lines[i])
            i++
            while (i < lines.size && lines[i].isNotBlank() && indentOf(lines[i]) >= 2 &&
                !isPromptLine(lines[i]) && !isToolHead(lines[i]) && !isCodexStructuredAlertStart(lines[i])
            ) {
                val cont = lines[i].trim()
                combined = if (cont.startsWith("Next:")) "$combined · $cont" else joinWrapped(combined, cont, lastWidth >= wrapThreshold)
                lastWidth = displayWidth(lines[i])
                i++
            }
            if (combined.isNotEmpty()) {
                blocks.add(TerminalSemanticBlock.AgentProseBlock("会话摘要：${cleanInline(combined, dialect)}"))
            }
        }

        private fun isCodexStructuredAlertStart(line: String): Boolean =
            CodexTranscriptSpec.errorSquareRegex.matches(line) ||
                CodexTranscriptSpec.safetyBlockRegex.matches(line) ||
                CodexTranscriptSpec.warningCellRegex.matches(line) ||
                CodexTranscriptSpec.approvalOverlayTitleRegex.matches(line) ||
                CodexTranscriptSpec.requestUserInputOverlayRegex.matches(line)

        private fun consumeCodexStructuredAlert() {
            val firstLine = lines[i]
            when {
                CodexTranscriptSpec.errorSquareRegex.matches(firstLine) -> {
                    var detail = CodexTranscriptSpec.errorSquareRegex.find(firstLine)!!.groupValues[1].trim()
                    var lastWidth = displayWidth(firstLine)
                    i++
                    while (i < lines.size && lines[i].isNotBlank() &&
                        !isPromptLine(lines[i]) && !isToolHead(lines[i]) && !isCodexStructuredAlertStart(lines[i]) &&
                        !CodexTranscriptSpec.finalSeparatorRegex.matches(lines[i])
                    ) {
                        detail = joinWrapped(detail, lines[i].trim(), lastWidth >= wrapThreshold)
                        lastWidth = displayWidth(lines[i])
                        i++
                    }
                    blocks.add(TerminalSemanticBlock.UrgentAlertBlock("Error", detail, isError = true))
                }

                CodexTranscriptSpec.safetyBlockRegex.matches(firstLine) -> {
                    val title = CodexTranscriptSpec.safetyBlockRegex.find(firstLine)!!.groupValues[1].trim()
                    val detailLines = ArrayList<String>()
                    i++
                    while (i < lines.size && lines[i].isNotBlank() && indentOf(lines[i]) >= 2) {
                        detailLines.add(lines[i].trim())
                        i++
                    }
                    blocks.add(
                        TerminalSemanticBlock.UrgentAlertBlock(
                            title = title,
                            detail = detailLines.joinToString("\n"),
                            isError = true
                        )
                    )
                }

                CodexTranscriptSpec.warningCellRegex.matches(firstLine) -> {
                    var detail = CodexTranscriptSpec.warningCellRegex.find(firstLine)!!.groupValues[1].trim()
                    var lastWidth = displayWidth(firstLine)
                    i++
                    while (i < lines.size && lines[i].isNotBlank() && indentOf(lines[i]) >= 2 &&
                        !isCodexStructuredAlertStart(lines[i])
                    ) {
                        detail = joinWrapped(detail, lines[i].trim(), lastWidth >= wrapThreshold)
                        lastWidth = displayWidth(lines[i])
                        i++
                    }
                    val isErr = Regex("(?i)(error|failed|unauthenticated|denied)").containsMatchIn(detail)
                    blocks.add(
                        TerminalSemanticBlock.UrgentAlertBlock(
                            title = if (isErr) "运行异常" else "提示",
                            detail = detail,
                            isError = isErr
                        )
                    )
                }

                else -> {
                    // `ApprovalOverlay` (`approval_overlay.rs`) or `RequestUserInputOverlay` (`request_user_input/render.rs`)
                    val isQuestionOverlay = CodexTranscriptSpec.requestUserInputOverlayRegex.matches(firstLine)
                    val titleLabel = if (isQuestionOverlay) "等待你的回答" else "需要你的授权"
                    val detailLines = ArrayList<String>()
                    detailLines.add(firstLine.trim())
                    i++
                    var blankCount = 0
                    while (i < lines.size) {
                        val l = lines[i]
                        val t = l.trim()
                        if (t.isEmpty()) {
                            blankCount++
                            val next = nextNonBlank(i + 1)
                            if (next == null || blankCount > 2) break
                            val nl = lines[next]
                            val nt = nl.trim()
                            val continuesOverlay = menuOptionRegex.matches(nl) ||
                                promptMenuOptionRegex.matches(nl) ||
                                nt.startsWith("$ ") ||
                                nt.startsWith("Reason:") ||
                                nt.startsWith("Grant ") ||
                                indentOf(nl) >= 2 && !CodexTranscriptSpec.finalSeparatorRegex.matches(nl) &&
                                !isToolHead(nl) && !isPromptLine(nl)
                            if (continuesOverlay) {
                                i = next
                                continue
                            }
                            break
                        }
                        if (isPromptLine(l) || isToolHead(l) || CodexTranscriptSpec.finalSeparatorRegex.matches(l)) break
                        val cleanedRow = if (promptMenuOptionRegex.matches(l)) {
                            l.replaceFirst(Regex("[>❯›]"), " ").trim()
                        } else {
                            t
                        }
                        detailLines.add(cleanedRow)
                        i++
                    }
                    blocks.add(
                        TerminalSemanticBlock.UrgentAlertBlock(
                            title = titleLabel,
                            detail = detailLines.joinToString("\n"),
                            isError = false
                        )
                    )
                }
            }
        }

        private fun consumeCodexToolGroup(orphanStart: Boolean) {
            val steps = ArrayList<String>()
            var headCount = if (orphanStart) 1 else 0
            var hasError = false
            var running = false
            var childPreviewLines = 0

            while (i < lines.size) {
                val line = lines[i]
                val trimmed = line.trim()
                val indent = indentOf(line)

                when {
                    trimmed.isEmpty() -> {
                        val next = nextNonBlank(i + 1)
                        val nextLine = next?.let { lines[it] }
                        val nextTrim = nextLine?.trim().orEmpty()
                        if (next != null && nextLine != null && (
                                isToolHead(nextLine) ||
                                    (toolChildRegex.matches(nextLine) && !CodexTranscriptSpec.threadRecapHeadRegex.matches(nextLine)) ||
                                    CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(nextTrim) ||
                                    // In `codex-rs/tui`, every top-level cell starts at indent 0 or 2;
                                    // 4+ space indented lines after an activity header (e.g. multi-hunk single-file diffs
                                    // or multi-paragraph stdout) always belong to that activity cell.
                                    (headCount > 0 && indentOf(nextLine) >= 4 &&
                                        !isPromptLine(nextLine) && !isCodexStructuredAlertStart(nextLine) &&
                                        !CodexTranscriptSpec.finalSeparatorRegex.matches(nextLine))
                                )
                        ) {
                            i = next
                        } else {
                            break
                        }
                    }

                    isToolHead(line) -> {
                        headCount++
                        childPreviewLines = 0
                        val clean = cleanStepText(cleanToolHead(trimmed))
                        running = spinnerRegex.matches(line) ||
                            CodexTranscriptSpec.liveStatusIndicatorRegex.matches(line) ||
                            Regex("^(?:Running|Thinking|Generating|Loading|Waiting|Working|Calling|Searching|Browsing|Opening|Resuming|Exploring)\\b")
                                .containsMatchIn(clean)
                        if (stepErrorRegex.containsMatchIn(clean) && !zeroFailures(clean)) hasError = true
                        if (steps.size < MAX_GROUP_STEPS) steps.add(clean)
                        i++
                    }

                    CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(trimmed) -> {
                        i++
                    }

                    toolChildRegex.matches(line) && !CodexTranscriptSpec.threadRecapHeadRegex.matches(line) -> {
                        if (headCount == 0) headCount = 1
                        childPreviewLines = 0
                        val clean = cleanStepText(trimmed.replace(Regex("^(?:[└├][─\\s]*|⎿|↳)\\s*"), ""))
                        if (clean.isNotEmpty() && !CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(clean)) {
                            if (stepErrorRegex.containsMatchIn(clean) && !zeroFailures(clean)) hasError = true
                            if (steps.size < MAX_GROUP_STEPS) steps.add("  $clean")
                        }
                        i++
                    }

                    (headCount > 0 && indent >= 4 && !isPromptLine(line)) -> {
                        if (childPreviewLines < MAX_CODEX_CHILD_PREVIEW_LINES && steps.size < MAX_GROUP_STEPS) {
                            steps.add("    ${trimmed.take(120)}")
                            childPreviewLines++
                        }
                        i++
                    }

                    else -> break
                }
            }

            if (steps.isEmpty()) {
                i++
                return
            }
            val finalHeads = headCount.coerceAtLeast(1)
            val label = buildToolSummaryLabel(finalHeads, hasError, running)
            blocks.add(TerminalSemanticBlock.ToolGroupBlock(label, steps, hasError))
        }

        // ---- Antigravity / Pi / Claude / Generic tool groups ----

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

                    moreActionsRegex.matches(line) -> {
                        val m = moreActionsRegex.find(line)!!
                        val extraCount = m.groupValues[1].toIntOrNull() ?: 1
                        val rest = m.groupValues[2].trim().trimEnd('…', '.').trim()
                        headCount += extraCount
                        if (stepErrorRegex.containsMatchIn(rest) && !zeroFailures(rest)) hasError = true
                        if (steps.size < MAX_GROUP_STEPS) {
                            val stepDesc = if (rest.isNotEmpty()) "折叠的 $extraCount 项操作 $rest" else "折叠的 $extraCount 项操作"
                            steps.add(stepDesc)
                        }
                        i++
                    }

                    dialect == TerminalDialect.PI && piThoughtHeaderRegex.matches(line) -> {
                        val m = piThoughtHeaderRegex.find(line)!!
                        headCount++
                        if (steps.size < MAX_GROUP_STEPS) {
                            steps.add("Thought for ${m.groupValues[1].trim()} (${m.groupValues[2].trim()})")
                        }
                        i++
                        // In Pi, `Thought for Xs · N more lines · click to show more` is followed by
                        // 1-2 short internal-monologue preview paragraphs before the next tool call.
                        consumePiThoughtPreviewIfFollowedByTool(steps)
                    }

                    isToolHead(line) -> {
                        headCount++
                        headIndent = indentOf(line)
                        val clean = cleanStepText(cleanToolHead(trimmed))
                        running = spinnerRegex.matches(line) ||
                            clean.startsWith("Running") || clean.startsWith("Generating") ||
                            clean.startsWith("Loading") || clean.startsWith("Thinking") || clean.endsWith("…")
                        if (stepErrorRegex.containsMatchIn(clean) && !zeroFailures(clean)) hasError = true
                        if (steps.size < MAX_GROUP_STEPS) steps.add(clean)
                        i++
                    }

                    toolChildRegex.matches(line) -> {
                        if (headCount == 0) headCount = 1
                        val clean = cleanStepText(trimmed.replace(Regex("^(?:[└├][─\\s]*|⎿|↳)\\s*"), ""))
                        if (stepErrorRegex.containsMatchIn(clean) && !zeroFailures(clean)) hasError = true
                        if (clean.isNotEmpty() && steps.size < MAX_GROUP_STEPS) steps.add("  $clean")
                        i++
                    }

                    // CRITICAL: In Antigravity (`agy`), ALL agent prose is indented by 2 spaces (`  ...`)
                    // right after `▸ Thought for ...` or `● Ran ...` without a blank line.
                    // Never swallow 2-space indented prose into `ToolGroupBlock` in ANTIGRAVITY or PI!
                    dialect != TerminalDialect.ANTIGRAVITY &&
                        dialect != TerminalDialect.PI &&
                        headCount > 0 &&
                        indentOf(line) > headIndent + 1 &&
                        !isPromptLine(line) &&
                        !isAlertLine(line) -> {
                        val clean = cleanStepText(trimmed)
                        if (stepErrorRegex.containsMatchIn(clean) && !zeroFailures(clean)) hasError = true
                        if (steps.size < MAX_GROUP_STEPS) steps.add("  $clean")
                        i++
                    }

                    else -> break
                }
            }

            if (steps.isEmpty()) {
                i++
                return
            }
            val label = buildToolSummaryLabel(headCount.coerceAtLeast(1), hasError, running)
            blocks.add(TerminalSemanticBlock.ToolGroupBlock(label, steps, hasError))
        }

        /**
         * In Pi, if the short paragraphs immediately following `Thought for Xs · N more lines` are
         * followed within <= 6 lines by another tool/thought header, they are Pi's thought preview
         * snippet rather than the final answer—fold them into [steps].
         */
        private fun consumePiThoughtPreviewIfFollowedByTool(steps: MutableList<String>) {
            var probe = i
            var nonBlankCount = 0
            var followedByTool = false
            while (probe < lines.size && nonBlankCount <= 6) {
                val pl = lines[probe]
                if (pl.isBlank()) {
                    probe++
                    continue
                }
                if (isPromptLine(pl) || isAlertLine(pl) || fenceRegex.matches(pl) || isTableLine(pl)) {
                    followedByTool = false
                    break
                }
                if (isToolHead(pl) || toolChildRegex.matches(pl)) {
                    followedByTool = true
                    break
                }
                nonBlankCount++
                probe++
            }
            if (followedByTool && probe > i) {
                while (i < probe) {
                    val t = lines[i].trim()
                    if (t.isNotEmpty() && steps.size < MAX_GROUP_STEPS) {
                        steps.add("  $t")
                    }
                    i++
                }
            }
        }

        private fun consumePiTodos() {
            val progress = piTodosHeaderRegex.find(lines[i])?.groupValues?.get(1).orEmpty()
            val steps = ArrayList<String>()
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) break
                if (toolChildRegex.matches(l) || l.trimStart().startsWith("├") || l.trimStart().startsWith("└")) {
                    val clean = l.trim().replace(Regex("^[└├][─\\s]*"), "")
                    if (steps.size < MAX_GROUP_STEPS) steps.add(clean)
                    i++
                } else {
                    break
                }
            }
            blocks.add(
                TerminalSemanticBlock.ToolGroupBlock(
                    summaryLabel = "任务清单 Todos ($progress)",
                    steps = steps,
                    hasError = false
                )
            )
        }

        private fun buildToolSummaryLabel(count: Int, hasError: Boolean, running: Boolean): String = buildString {
            append("已执行 ").append(count).append(" 项思考与工具操作")
            if (hasError) append(" · 含失败")
            if (running) append(" · 进行中")
        }

        private fun cleanToolHead(trimmed: String): String =
            trimmed
                .replace(Regex("^│\\s*"), "")
                .replace(Regex("^[●⏺•○◦▸▾▼✓✔✗✘∴\\u2800-\\u28FF✻✽✶✳✢]\\s*"), "")
                .trim()

        private fun cleanStepText(text: String): String =
            text
                .replace(ctrlExpandSuffixRegex, "")
                .replace(clickShowMoreSuffixRegex, "")
                .trim()

        private fun zeroFailures(text: String): Boolean =
            Regex("(?i)\\b(?:0\\s+(?:failed|errors?)|status\\s+0\\b)").containsMatchIn(text) &&
                !Regex("(?i)\\b[1-9]\\d*\\s+(?:failed|errors?)\\b").containsMatchIn(text)

        // ---- alerts ----

        private fun isAlertLine(line: String): Boolean =
            errorRegex.matches(line) ||
                agyWarningErrorRegex.matches(line) ||
                tracebackRegex.matches(line) ||
                permissionErrorRegex.containsMatchIn(line) ||
                blockingPromptRegex.containsMatchIn(line)

        private fun consumeAlerts() {
            while (i < lines.size && lines[i].isNotBlank() && isAlertLine(lines[i])) {
                val rawAlert = lines[i].trim()
                // Multi-line `⚠ UNAUTHENTICATED ...` + `Error ID: ...` in Antigravity
                if (agyWarningErrorRegex.matches(rawAlert)) {
                    val m = agyWarningErrorRegex.find(rawAlert)!!
                    val sb = StringBuilder()
                    sb.append(m.groupValues[1].trim())
                    val firstRest = m.groupValues[2].trim()
                    if (firstRest.isNotEmpty()) sb.append(": ").append(firstRest)
                    i++
                    while (i < lines.size && lines[i].isNotBlank() &&
                        !isPromptLine(lines[i]) && !isToolHead(lines[i])
                    ) {
                        val cont = lines[i].trim()
                        sb.append(if (cont.startsWith("Error ID:")) "\n$cont" else " $cont")
                        i++
                    }
                    blocks.add(TerminalSemanticBlock.UrgentAlertBlock("运行异常", sb.toString().trim(), true))
                    continue
                }

                val (title, detail, isError) = classifyAlert(rawAlert)
                i++

                // In Pi, `Error: Connection error.` may be followed after a blank line by `Aborted after N retry attempts`.
                var fullDetail = detail
                val nextIdx = nextNonBlank(i)
                if (nextIdx != null && lines[nextIdx].trim().startsWith("Aborted after ")) {
                    fullDetail = listOf(detail, lines[nextIdx].trim()).filter { it.isNotBlank() }.joinToString(" · ")
                    i = nextIdx + 1
                }

                val last = blocks.lastOrNull() as? TerminalSemanticBlock.UrgentAlertBlock
                if (last != null && last.title == title && last.isError == isError) {
                    val merged = if (last.detail == fullDetail) last.detail
                    else listOf(last.detail, fullDetail).filter { it.isNotBlank() }.joinToString("\n")
                    blocks[blocks.lastIndex] = last.copy(detail = merged)
                } else {
                    blocks.add(TerminalSemanticBlock.UrgentAlertBlock(title, fullDetail, isError))
                }
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

        // ---- Dialect-specific tables & code ----

        /** Codex renders Markdown tables without `│`, using multi-segment `━━━  ━━━` / `───  ───` rules. */
        private fun isCodexSpacedTableStart(idx: Int): Boolean {
            if (dialect != TerminalDialect.CODEX && dialect != TerminalDialect.GENERIC) return false
            val cur = lines[idx]
            if (CodexTranscriptSpec.gridTableRuleRegex.matches(cur)) return true
            if (idx + 1 < lines.size && cur.isNotBlank() &&
                !isPromptLine(cur) && !isToolHead(cur) &&
                CodexTranscriptSpec.gridTableRuleRegex.matches(lines[idx + 1])
            ) {
                return true
            }
            return false
        }

        private fun consumeCodexSpacedTable() {
            val run = ArrayList<String>()
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) break
                if (isPromptLine(l) || isToolHead(l)) break
                run.add(l)
                i++
            }
            addCode(run.joinToString("\n"), kindLabel = "表格")
        }

        /**
         * Antigravity renders Markdown tables with `│` column dividers and `───┼───` row dividers
         * (no outer box borders), including 2-column tables that have only a single `│` per row.
         */
        private fun isAgyPipeTableStart(idx: Int): Boolean {
            val cur = lines[idx]
            if (pipeCrossSeparatorRegex.matches(cur)) return true
            if (cur.contains('│') && !piPipeSummaryRegex.matches(cur) &&
                idx + 1 < lines.size && pipeCrossSeparatorRegex.matches(lines[idx + 1])
            ) {
                return true
            }
            return false
        }

        private fun consumeAgyPipeTable() {
            val run = ArrayList<String>()
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) break
                if (pipeCrossSeparatorRegex.matches(l) || (l.contains('│') && !piPipeSummaryRegex.matches(l))) {
                    run.add(l)
                    i++
                } else {
                    break
                }
            }
            addCode(run.joinToString("\n"), kindLabel = "表格")
        }

        private fun isTableLine(line: String): Boolean {
            val t = line.trim()
            if (t.isEmpty()) return false
            if (piPipeSummaryRegex.matches(line)) return false
            if (asciiTableBorderRegex.matches(t)) return true
            if (pipeCrossSeparatorRegex.matches(t)) return true
            if (tableBorderRegex.matches(t) && boxCornerOrCrossRegex.containsMatchIn(t)) return true
            if (line.count { it == '│' || it == '┃' || it == '║' } >= 1 && boxCornerOrCrossRegex.containsMatchIn(line)) return true
            if (line.count { it == '│' || it == '┃' || it == '║' } >= 2) return true
            if (markdownTableRegex.matches(t) && t.count { it == '|' } >= 3) return true
            return false
        }

        private fun isDiffLine(line: String): Boolean = diffLineRegex.containsMatchIn(line)

        private fun isJsonStart(trimmed: String): Boolean {
            if (trimmed.length < 2) return false
            val first = trimmed.first()
            if (first != '{' && first != '[') return false
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
                    t.startsWith("const ") || t.startsWith("let ") ||
                    t.endsWith("{") || t.endsWith("}") || t.endsWith(";")
                if (!hasCodeSyntax) return false
            }
            return cjk * 2 < t.length
        }

        private fun consumeFence() {
            val sb = StringBuilder()
            val m = fenceRegex.find(lines[i])
            val opener = m?.groupValues?.get(1) ?: lines[i].trim().take(3)
            val lang = m?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
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
            addCode(sb.toString(), kindLabel = lang ?: "代码")
        }

        private fun consumeCodeRun(kindLabel: String? = null, predicate: (String) -> Boolean) {
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
            addCode(run.joinToString("\n"), kindLabel = kindLabel)
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
            addCode(run.joinToString("\n"), kindLabel = "JSON")
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

        private fun addCode(content: String, kindLabel: String? = null) {
            val rawLines = content.trimEnd('\n').split('\n')
            val minIndent = rawLines.filter { it.isNotBlank() }.minOfOrNull { indentOf(it) } ?: 0
            val dedented = rawLines.joinToString("\n") {
                if (it.length >= minIndent) it.substring(minIndent) else it.trimStart()
            }.trimEnd()
            if (dedented.isBlank()) return
            val last = blocks.lastOrNull() as? TerminalSemanticBlock.CodeOrTableBlock
            if (last != null && last.kindLabel == kindLabel) {
                blocks[blocks.lastIndex] = TerminalSemanticBlock.CodeOrTableBlock(
                    last.content + "\n" + dedented,
                    kindLabel = last.kindLabel
                )
            } else {
                blocks.add(TerminalSemanticBlock.CodeOrTableBlock(dedented, kindLabel = kindLabel))
            }
        }

        // ---- prose ----

        private fun bulletIndentLevel(indent: Int): Int = when (dialect) {
            // `markdown_render.rs:5341` + `messages.rs:1378`: depth 1 = indent 2, depth 2 = indent 6, depth 3 = indent 10
            TerminalDialect.CODEX -> ((indent - 2).coerceAtLeast(0) / 4).coerceAtMost(2)
            else -> if (indent >= baseIndent + 3) 1 else 0
        }

        private fun appendProse(rawLine: String) {
            // `❯ 1. Yes` (menu cursor) -> `  1. Yes`
            var line = if (promptMenuOptionRegex.matches(rawLine)) {
                rawLine.replaceFirst(Regex("[>❯›]"), " ")
            } else {
                rawLine
            }
            var indent = indentOf(line)
            val width = displayWidth(line)

            // `messages.rs:1374`: Codex prepends `• ` at col 0 to line 0 of `AgentMarkdownCell`
            // (replacing the 2-space `"  "` gutter used on subsequent lines).
            if (dialect == TerminalDialect.CODEX && indent == 0 && codexLeadProseRegex.matches(line)) {
                val body = codexLeadProseRegex.find(line)!!.groupValues[1].trim()
                val isStructuralLead = headingRegex.matches(body) ||
                    boldHeadingRegex.matches(body) ||
                    blockquoteLineRegex.matches(body) ||
                    CodexTranscriptSpec.taskListBulletRegex.matches(body) ||
                    orderedBulletRegex.matches(body)
                if (isStructuralLead) {
                    line = "  $body"
                    indent = 2
                } else {
                    flushParagraph()
                    startParagraph(body, bullet = false, indent = 2, indentLevel = 0, width = width)
                    return
                }
            }

            headingRegex.find(line)?.let {
                flushParagraph()
                blocks.add(TerminalSemanticBlock.AgentProseBlock(cleanInline(it.groupValues[1], dialect), isHeading = true))
                return
            }
            boldHeadingRegex.find(line)?.let {
                flushParagraph()
                blocks.add(TerminalSemanticBlock.AgentProseBlock(cleanInline(it.groupValues[1], dialect), isHeading = true))
                return
            }
            blockquoteLineRegex.find(line)?.let {
                val quoteBody = it.groupValues[2].trim()
                if (quoteBody.isEmpty()) {
                    flushParagraph()
                    return
                }
                val current = paraText
                if (current != null && current.startsWith("▎ ")) {
                    val joined = joinWrapped(current.toString(), quoteBody, paraLastWidth >= wrapThreshold)
                    current.setLength(0)
                    current.append(joined)
                    paraLastWidth = width
                    return
                }
                flushParagraph()
                startParagraph("▎ $quoteBody", bullet = false, indent = indent, indentLevel = 0, width = width)
                return
            }
            CodexTranscriptSpec.taskListBulletRegex.find(line)?.let {
                flushParagraph()
                val box = it.groupValues[2]
                val level = bulletIndentLevel(indent)
                startParagraph("$box ${it.groupValues[3].trim()}", bullet = true, indent = indent, indentLevel = level, width = width)
                return
            }
            orderedBulletRegex.find(line)?.let {
                flushParagraph()
                val marker = it.groupValues[2].trim()
                val level = bulletIndentLevel(indent)
                startParagraph("$marker ${it.groupValues[3].trim()}", bullet = true, indent = indent, indentLevel = level, width = width)
                return
            }
            unorderedBulletRegex.find(line)?.let {
                flushParagraph()
                val level = bulletIndentLevel(indent)
                startParagraph(it.groupValues[2].trim(), bullet = true, indent = indent, indentLevel = level, width = width)
                return
            }
            proseLeadDotRegex.find(line)?.let {
                flushParagraph()
                startParagraph(it.groupValues[2].trim(), bullet = false, indent = indent, indentLevel = 0, width = width)
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
            startParagraph(text, bullet = false, indent = indent, indentLevel = 0, width = width)
        }

        private fun startParagraph(text: String, bullet: Boolean, indent: Int, indentLevel: Int, width: Int) {
            paraText = StringBuilder(text)
            paraBullet = bullet
            paraIndent = indent
            paraIndentLevel = indentLevel
            paraLastWidth = width
        }

        private fun flushParagraph() {
            val text = paraText?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                blocks.add(
                    TerminalSemanticBlock.AgentProseBlock(
                        text = cleanInline(text, dialect),
                        isBullet = paraBullet,
                        isHeading = false,
                        indentLevel = paraIndentLevel
                    )
                )
            }
            paraText = null
            paraBullet = false
            paraIndent = 0
            paraIndentLevel = 0
            paraLastWidth = 0
        }

        // ---- tool-head dispatch by dialect ----

        private fun isToolHead(line: String): Boolean = when (dialect) {
            TerminalDialect.CODEX ->
                CodexTranscriptSpec.activityHeadRegex.matches(line) ||
                    CodexTranscriptSpec.approvalAuditRegex.matches(line) ||
                    CodexTranscriptSpec.liveStatusIndicatorRegex.matches(line) ||
                    thoughtRegex.matches(line) ||
                    spinnerRegex.matches(line)
            TerminalDialect.ANTIGRAVITY ->
                thoughtRegex.matches(line) || agyToolVerbRegex.matches(line) ||
                    toolCallParenRegex.matches(line) || moreActionsRegex.matches(line) || spinnerRegex.matches(line)
            TerminalDialect.PI ->
                piThoughtHeaderRegex.matches(line) || piToolGroupHeaderRegex.matches(line) ||
                    piCheckToolRegex.matches(line) || piPipeSummaryRegex.matches(line) ||
                    thoughtRegex.matches(line) || agyToolVerbRegex.matches(line) || spinnerRegex.matches(line)
            TerminalDialect.CLAUDE ->
                thoughtRegex.matches(line) || toolCallParenRegex.matches(line) ||
                    agyToolVerbRegex.matches(line) || spinnerRegex.matches(line)
            else ->
                thoughtRegex.matches(line) || agyToolVerbRegex.matches(line) ||
                    CodexTranscriptSpec.activityHeadRegex.matches(line) ||
                    toolCallParenRegex.matches(line) || bareToolSummaryRegex.matches(line) ||
                    moreActionsRegex.matches(line) || spinnerRegex.matches(line)
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

        private fun isDedicatedSummaryGroup(block: TerminalSemanticBlock.ToolGroupBlock): Boolean =
            block.summaryLabel.startsWith("任务清单") ||
                block.summaryLabel.startsWith("任务计划") ||
                block.summaryLabel.startsWith("问答确认")

        private fun mergeAdjacentToolGroups(input: List<TerminalSemanticBlock>): List<TerminalSemanticBlock> {
            if (input.size < 2) return input
            val out = ArrayList<TerminalSemanticBlock>(input.size)
            for (b in input) {
                val prev = out.lastOrNull()
                if (prev is TerminalSemanticBlock.ToolGroupBlock && b is TerminalSemanticBlock.ToolGroupBlock &&
                    !isDedicatedSummaryGroup(prev) && !isDedicatedSummaryGroup(b)
                ) {
                    val steps = (prev.steps + b.steps).take(MAX_GROUP_STEPS)
                    val prevCount = Regex("已执行 (\\d+) 项").find(prev.summaryLabel)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val nextCount = Regex("已执行 (\\d+) 项").find(b.summaryLabel)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val totalHeads = prevCount + nextCount
                    val hasError = prev.hasError || b.hasError
                    val running = b.summaryLabel.contains("进行中")
                    val dur = Regex("耗时 ([^·]+)$").find(b.summaryLabel)?.groupValues?.get(1)?.trim()
                        ?: Regex("耗时 ([^·]+)$").find(prev.summaryLabel)?.groupValues?.get(1)?.trim()
                    val label = buildString {
                        append("已执行 ").append(totalHeads).append(" 项思考与工具操作")
                        if (hasError) append(" · 含失败")
                        if (running) append(" · 进行中")
                        if (dur != null) append(" · 耗时 ").append(dur)
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
    // Shared Character, Wrapping & Inline Cleanup Helpers
    // ---------------------------------------------------------------------------------------------

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
            code in 0x2014..0x2026 || code in 0x2018..0x201F ||
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

    /**
     * Joins a hard-wrapped continuation:
     * - No space at CJK / fullwidth punctuation boundaries
     * - No space inside URLs, file paths (`/` or `\`), `.ext` continuations, or hyphenated identifiers
     * - Single space between Latin words
     */
    private fun joinWrapped(previous: String, next: String, wrapped: Boolean): String {
        if (previous.isEmpty()) return next
        if (next.isEmpty()) return previous
        if (!wrapped) return "$previous $next"
        val a = previous.last()
        val b = next.first()
        val tailToken = previous.takeLastWhile { !it.isWhitespace() }
        val looksLikePathOrUrlDot = a == '.' && b.isLetterOrDigit() && (
            tailToken.contains('/') || tailToken.contains('\\') ||
                tailToken.contains('_') || tailToken.contains("www.", ignoreCase = true) ||
                tailToken.contains("http", ignoreCase = true)
            )
        val noSpace = isCjk(a) || isCjk(b) ||
            a == '/' || a == '\\' || b == '/' || b == '\\' ||
            a == '(' || b == ')' ||
            looksLikePathOrUrlDot ||
            (a == '-' && previous.length > 1 && (previous[previous.length - 2].isLetterOrDigit() || previous[previous.length - 2] == '-'))
        return if (noSpace) previous + next else "$previous $next"
    }

    private fun cleanInline(text: String, dialect: TerminalDialect): String {
        var cleaned = text.replace(Regex("\\s{2,}"), " ").trim()
        // Strip raw Markdown bold `**...**` markers for cleaner mobile prose reading
        cleaned = cleaned.replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        if (dialect == TerminalDialect.CODEX) {
            // `ZeroTierPeerWarmup.kt (/D:/.../ZeroTierPeerWarmup.kt:14)` -> `ZeroTierPeerWarmup.kt:14`
            cleaned = codexRedundantFileLinkRegex.replace(cleaned) { m ->
                val label = m.groupValues[1]
                val target = m.groupValues[2]
                val labelBase = label.substringBefore(':')
                val targetBase = target.substringBefore(':')
                if (targetBase.equals(labelBase, ignoreCase = true)) {
                    if (target.contains(':')) target else label
                } else {
                    m.value
                }
            }
            // Shorten remaining deep Windows absolute paths in `(/D:/a/b/c/rel/file.kt:16)` -> `(rel/file.kt:16)`
            cleaned = codexLongAbsPathParenRegex.replace(cleaned) { m ->
                "(${m.groupValues[1].replace('\\', '/')})"
            }
        }
        return cleaned
    }

    // ---------------------------------------------------------------------------------------------
    // Quick Actions & Headline Helpers
    // ---------------------------------------------------------------------------------------------

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
