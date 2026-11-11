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
        val hasError: Boolean = false,
        val defaultExpanded: Boolean = false
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

/**
 * Single-pass parsed workbench snapshot computed off the main thread so Reader Mode
 * never runs duplicate cleaning or regex block passes during Compose composition.
 */
data class WorkbenchSnapshot(
    val blocks: List<TerminalSemanticBlock>,
    val quickActions: List<QuickReplyAction>,
    val liveStatus: AgentStateWord,
    val isExpandedMode: Boolean
)

object StructuredTerminalReader {

    // ---------------------------------------------------------------------------------------------
    // Limits
    // ---------------------------------------------------------------------------------------------

    private const val MAX_INPUT_LINES = 2000
    private const val MAX_HC_MESSAGE_LINES = 400
    private const val MAX_JSON_LINES = 300
    private const val MAX_GROUP_STEPS = 300
    private const val MAX_EXPANDED_GROUP_STEPS = 600
    private const val MAX_CODEX_CHILD_PREVIEW_LINES = 4
    private const val MAX_EXPANDED_CHILD_PREVIEW_LINES = 80
    private const val COMPACT_STEP_LINE_CHARS = 120
    private const val EXPANDED_STEP_LINE_CHARS = 240
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
    private val tipRegex = Regex("^(?:[※⎿*└├][─\\s]*)?Tip:")

    private fun containsCommonFooterMarker(trimmed: String): Boolean {
        if (trimmed.length < 7) return false
        if (" for " in trimmed) {
            if ("? for shortcuts" in trimmed || "? for help" in trimmed || "← for agents" in trimmed) return true
        }
        if (" to " in trimmed) {
            if ("esc to cancel" in trimmed || "Esc to cancel" in trimmed ||
                "esc to interrupt" in trimmed || "Esc to interrupt" in trimmed ||
                "ctrl+c to " in trimmed || "Ctrl+C to " in trimmed ||
                "shift+tab to" in trimmed || "Shift+Tab to" in trimmed ||
                "/artifact to review" in trimmed ||
                "f2 to view" in trimmed || "F2 to view" in trimmed
            ) {
                return true
            }
        }
        return "auto-accept" in trimmed ||
            "bypassing permissions" in trimmed ||
            "bypass permissions" in trimmed ||
            "-- INSERT --" in trimmed ||
            ("tok/s" in trimmed && "TTFT:" in trimmed) ||
            "Cline upstream:" in trimmed ||
            "Thinking blocks:" in trimmed ||
            "Tool output:" in trimmed ||
            "[hc] incomplete:" in trimmed ||
            "context left" in trimmed ||
            "Context left" in trimmed ||
            ("MCP:" in trimmed && ("server" in trimmed || "Server" in trimmed))
    }

    private fun hasBoxDrawingCornerOrCross(s: String): Boolean {
        for (ch in s) {
            if (ch in '\u2500'..'\u257F') {
                when (ch) {
                    '┌', '┬', '┐', '├', '┼', '┤', '└', '┴', '┘',
                    '╞', '╪', '╡', '╘', '╧', '╛', '╒', '╤', '╕',
                    '╔', '╦', '╗', '╠', '╬', '╣', '╚', '╩', '╝' -> return true
                }
            }
        }
        return false
    }

    // Antigravity (`agy`) & Codex bottom docks and transcript grammars live in
    // AntigravityTranscriptSpec & CodexTranscriptSpec.

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

    // Quick-action & precompiled helper regexes (zero runtime Regex allocations in hot loops)
    private val menuOptionRegex = Regex("^\\s*(?:[❯›>]\\s*)?(\\d{1,2})[.)]\\s+(.{1,80})$")
    private val yesNoRegex = Regex("(?i)(\\(y/n\\)|\\[y/n]|\\(yes/no\\)|\\[yes/no])")
    private val questionTailRegex = Regex("[?？]\\s*$")

    private val codexWorkedForDetectRegex = Regex("\\bWorked for (?:<1s|\\d+[dhms])")
    private val codexErrorDetectRegex = Regex("(?m)^\\s*■[\\s\\u200A]+\\S")
    private val claudeDotDetectRegex = Regex("(?m)^\\s*⏺\\s+")
    private val shellPromptDetectRegex = Regex("(?m)^(?:[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+:[^$#%\\n]*[$#%]|PS\\s+[A-Za-z]:\\\\[^>\\n]*>)\\s")
    private val brailleWorkingRegex = Regex("^\\s*[\\u2800-\\u28FF]\\s{1,3}\\S")
    private val statusBarWorkingRegex = Regex("(?i)^(?:[╭╰]─\\s*|[●◆⚙🔧⏳]\\s+)(?:[●◆⚙🔧⏳]\\s*)?(?:THINKING|WORKING|TOOL)\\b")
    private val childPrefixStripRegex = Regex("^(?:[└├][─\\s]*|⎿|↳)\\s*")
    private val piTodoChildStripRegex = Regex("^[└├][─\\s]*")
    private val warningErrorWordRegex = Regex("(?i)(error|failed|unauthenticated|denied)")
    private val menuCursorCharRegex = Regex("[>❯›]")
    private val codexRunningVerbRegex = Regex("^(?:Running|Thinking|Generating|Loading|Waiting|Working|Calling|Searching|Browsing|Opening|Resuming|Exploring)\\b")
    private val agyInlineCodeStartRegex = Regex(
        "^(?:const|let|var|val|fun|def|function|async\\s+function|export\\s+(?:const|function|class)|import\\s+\\{)\\s+\\w+"
    )
    private val agyInlineCodeTokenRegex = Regex(
        "(?:===|!==|==|!=|<=|>=|=>|->|&&|\\|\\||;\\s*$|\\{\\s*$)"
    )
    private val cleanHeadPipeRegex = Regex("^│\\s*")
    private val cleanHeadBulletRegex = Regex("^[●⏺•○◦▸▾▼✓✔✗✘∴\\u2800-\\u28FF✻✽✶✳✢]\\s*")
    private val zeroFailPosRegex = Regex("(?i)\\b(?:0\\s+(?:failed|errors?)|status\\s+0\\b|exited\\s+with\\s+code\\s+0\\b)")
    private val zeroFailNegRegex = Regex("(?i)\\b[1-9]\\d*\\s+(?:failed|errors?)\\b")
    private val jsonBracketTagRegex = Regex("^\\[[^\\]\"{\\[]*]\\s+\\S")
    private val groupCountExtractRegex = Regex("已执行 (\\d+) 项")
    private val groupDurExtractRegex = Regex("耗时 ([^·]+)$")
    private val multiSpaceRegex = Regex("\\s{2,}")
    private val boldMarkdownRegex = Regex("\\*\\*([^*]+)\\*\\*")
    private val optionParenTailRegex = Regex("[(（].*$")
    private val negativeOptionRegex = Regex("(?i)^(no|deny|reject|cancel|拒绝|取消|否)")
    private val firstSentenceRegex = Regex("^(.{8,}?[。！？!?]|.{12,}?[.](?=\\s|$))")
    private val hcTagStripRegex = Regex("</?hc[^>]*>")
    private val anyWhitespaceRegex = Regex("\\s+")
    private val headlineLeadStripRegex = Regex("^[●⏺•○◦▸▾▼✓✔✗✘>❯›#\\s]+")

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
                rawScreen.contains("T R A N S C R I P T") ||
                codexWorkedForDetectRegex.containsMatchIn(rawScreen) ||
                codexErrorDetectRegex.containsMatchIn(rawScreen) -> TerminalDialect.CODEX
            rawScreen.contains("[click to jump]") ||
                rawScreen.contains("click to show more") ||
                rawScreen.contains("Cline upstream:") -> TerminalDialect.PI
            rawScreen.contains("(ctrl+o to expand)") ||
                rawScreen.contains("(ctrl+o to collapse)") ||
                rawScreen.contains("▸ Thought for ") ||
                rawScreen.contains("▾ Thought for ") ||
                rawScreen.contains("○ Thought for ") ||
                rawScreen.contains("/artifact to review") ||
                rawScreen.contains("Conversation compacted") -> TerminalDialect.ANTIGRAVITY
            rawScreen.contains('\u23BF') || claudeDotDetectRegex.containsMatchIn(rawScreen) -> TerminalDialect.CLAUDE
            shellPromptDetectRegex.containsMatchIn(rawScreen) ->
                TerminalDialect.SHELL
            else -> TerminalDialect.GENERIC
        }
    }

    /**
     * Single-pass analysis for `AgentWorkbenchPane`: cleans the screen once, parses semantic blocks
     * once, resolves live status once, and builds quick actions once.
     */
    fun analyzeWorkbench(rawScreen: String?, summary: TerminalSummary?): WorkbenchSnapshot {
        val raw = rawScreen.orEmpty()
        val dialect = detectDialect(summary?.agentApp, raw)
        if (raw.isBlank()) {
            val fallbackStatus = summary?.agentStatus ?: AgentStateWord.NONE
            val actions = buildQuickActionsForStatus(
                status = fallbackStatus,
                dialect = dialect,
                isExpanded = false,
                menuOptions = emptyList(),
                hasYesNo = false
            )
            return WorkbenchSnapshot(
                blocks = emptyList(),
                quickActions = actions,
                liveStatus = fallbackStatus,
                isExpandedMode = false
            )
        }
        val cleaned = cleanScreen(raw, dialect)
        val blocks = parseBlocksFromCleaned(cleaned, dialect)
        val cleanTail = cleaned.trimmedLines.asReversed().asSequence().filter { it.isNotEmpty() }.take(16).toList().asReversed()
        val menuOptions = findMenuOptions(cleanTail)
        val hasYesNo = cleanTail.any { ('/' in it) && yesNoRegex.containsMatchIn(it) }
        val status = resolveStatusInternal(cleaned.rawTail, cleanTail, menuOptions, hasYesNo, summary, blocks)
        val actions = buildQuickActionsForStatus(status, dialect, cleaned.isExpandedMode, menuOptions, hasYesNo)
        return WorkbenchSnapshot(
            blocks = blocks,
            quickActions = actions,
            liveStatus = status,
            isExpandedMode = cleaned.isExpandedMode
        )
    }

    fun parseBlocks(rawScreen: String, agentApp: String? = null): List<TerminalSemanticBlock> {
        if (rawScreen.isBlank()) return emptyList()
        val dialect = detectDialect(agentApp, rawScreen)
        val cleaned = cleanScreen(rawScreen, dialect)
        return parseBlocksFromCleaned(cleaned, dialect)
    }

    fun resolveLiveStatus(
        rawScreen: String?,
        summary: TerminalSummary?,
        preParsedBlocks: List<TerminalSemanticBlock>? = null
    ): AgentStateWord {
        val raw = rawScreen.orEmpty()
        if (raw.isBlank()) return summary?.agentStatus ?: AgentStateWord.NONE
        val dialect = detectDialect(summary?.agentApp, raw)
        val cleaned = cleanScreen(raw, dialect)
        val cleanTail = cleaned.lines.asReversed().asSequence().filter { it.isNotBlank() }.take(16).toList().asReversed()
        val menuOptions = findMenuOptions(cleanTail)
        val hasYesNo = cleanTail.any { yesNoRegex.containsMatchIn(it) }
        return resolveStatusInternal(cleaned.rawTail, cleanTail, menuOptions, hasYesNo, summary, preParsedBlocks)
    }

    private fun resolveStatusInternal(
        rawTail: List<String>,
        cleanTail: List<String>,
        menuOptions: List<Pair<String, String>>,
        hasYesNo: Boolean,
        summary: TerminalSummary?,
        preParsedBlocks: List<TerminalSemanticBlock>? = null
    ): AgentStateWord {
        val lastBlockRunning = (preParsedBlocks?.lastOrNull() as? TerminalSemanticBlock.ToolGroupBlock)
            ?.summaryLabel?.contains("· 进行中") == true
        val looksWorking = lastBlockRunning || rawTail.takeLast(8).any {
            workingHintRegex.containsMatchIn(it) ||
                (it.contains("esc to interrupt", ignoreCase = true) && CodexTranscriptSpec.liveStatusIndicatorRegex.matches(it)) ||
                (it.contains("Model unavailable", ignoreCase = true) && AntigravityTranscriptSpec.modelRetryStatusRegex.matches(it)) ||
                brailleWorkingRegex.containsMatchIn(it) ||
                statusBarWorkingRegex.containsMatchIn(it)
        }
        val looksBlocked = menuOptions.isNotEmpty() || hasYesNo ||
            rawTail.takeLast(8).any { AntigravityTranscriptSpec.isSubagentQuestion(it) } ||
            cleanTail.takeLast(8).any {
                val t = it.trim()
                blockingPromptRegex.containsMatchIn(t) ||
                    AntigravityTranscriptSpec.isPermissionTitle(t) ||
                    CodexTranscriptSpec.approvalOverlayTitleRegex.matches(t) ||
                    (t.startsWith("Question", ignoreCase = true) && CodexTranscriptSpec.requestUserInputOverlayRegex.matches(t))
            }
        val looksError = cleanTail.takeLast(6).any {
            errorRegex.matches(it) ||
                (it.contains('⚠') && agyWarningErrorRegex.matches(it)) ||
                (it.contains('■') && CodexTranscriptSpec.errorSquareRegex.matches(it)) ||
                (it.contains('ⓘ') && CodexTranscriptSpec.safetyBlockRegex.matches(it))
        }

        return when {
            looksBlocked -> AgentStateWord.BLOCKED
            looksWorking -> AgentStateWord.WORKING
            summary?.agentStatus != null && summary.agentStatus != AgentStateWord.NONE -> summary.agentStatus
            looksError -> AgentStateWord.ERROR
            else -> AgentStateWord.DONE
        }
    }

    fun extractQuickActions(rawScreen: String?, summary: TerminalSummary?): List<QuickReplyAction> =
        analyzeWorkbench(rawScreen, summary).quickActions

    private fun buildQuickActionsForStatus(
        status: AgentStateWord,
        dialect: TerminalDialect,
        isExpanded: Boolean,
        menuOptions: List<Pair<String, String>>,
        hasYesNo: Boolean
    ): List<QuickReplyAction> {
        val expandKey = if (dialect == TerminalDialect.CODEX) "ctrl+t" else "ctrl+o"
        val expandLabel = when {
            dialect == TerminalDialect.CODEX && isExpanded -> "收起视图 Ctrl+T"
            dialect == TerminalDialect.CODEX -> "展开输出 Ctrl+T"
            isExpanded -> "收起详情 Ctrl+O"
            else -> "展开详情 Ctrl+O"
        }
        val errorExpandLabel = when {
            isExpanded -> expandLabel
            dialect == TerminalDialect.CODEX -> "展开报错"
            else -> "查看报错"
        }

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
            AgentStateWord.DONE, AgentStateWord.IDLE, AgentStateWord.NONE -> if (isExpanded) {
                listOf(
                    QuickReplyAction(expandLabel, expandKey, isSpecialKey = true, isPrimary = true),
                    QuickReplyAction("继续推进", "继续"),
                    QuickReplyAction("按这个先做", "按这个先做"),
                    QuickReplyAction("跑测试验证", "跑一遍测试，修复所有失败项后汇报结果"),
                    QuickReplyAction("提交并推送", "提交当前改动并推送到远端")
                )
            } else {
                listOf(
                    QuickReplyAction("继续推进", "继续", isPrimary = true),
                    QuickReplyAction("按这个先做", "按这个先做"),
                    QuickReplyAction("跑测试验证", "跑一遍测试，修复所有失败项后汇报结果"),
                    QuickReplyAction("提交并推送", "提交当前改动并推送到远端")
                )
            }
        }
        return actions.take(5)
    }

    private fun parseBlocksFromCleaned(cleaned: CleanedScreen, dialect: TerminalDialect): List<TerminalSemanticBlock> {
        if (cleaned.lines.isEmpty()) return emptyList()
        return if (dialect == TerminalDialect.SHELL) {
            runCatching { parseShellBlocks(cleaned.lines) }.getOrElse {
                listOf(TerminalSemanticBlock.CodeOrTableBlock(cleaned.lines.joinToString("\n").trim()))
            }
        } else {
            runCatching { BlockParser(cleaned, dialect).parse() }.getOrElse {
                val fallback = cleaned.lines.joinToString("\n").trim()
                if (fallback.isEmpty()) emptyList() else listOf(TerminalSemanticBlock.CodeOrTableBlock(fallback))
            }
        }
    }

    fun extractLatestHeadline(rawScreen: String?, summary: TerminalSummary): String {
        val status = summary.agentStatus
        val isUrgent = status == AgentStateWord.BLOCKED || status == AgentStateWord.ERROR

        // Fast path: when not blocked/errored and hc already supplies agentMessage, skip full-screen parsing!
        if (!isUrgent) {
            summary.agentMessage?.takeIf { it.isNotBlank() }?.let { return polishHeadline(it) }
        }

        if (rawScreen.isNullOrBlank()) {
            summary.agentMessage?.takeIf { it.isNotBlank() }?.let { return polishHeadline(it) }
            return when (status) {
                AgentStateWord.WORKING -> "运行中…"
                AgentStateWord.BLOCKED -> "等待你的授权或确认"
                AgentStateWord.ERROR -> "运行出错，等待处理"
                AgentStateWord.DONE -> "已完成，等待下一轮指令"
                AgentStateWord.IDLE -> "空闲"
                AgentStateWord.NONE -> summary.title.ifBlank { summary.workspaceSubtitle }
            }
        }

        val dialect = detectDialect(summary.agentApp, rawScreen)
        val cleaned = cleanScreen(rawScreen, dialect)
        val blocks = parseBlocksFromCleaned(cleaned, dialect)

        // 1. Blocked / error: concrete question or failure alert (reusing single-pass `cleaned`).
        if (isUrgent) {
            val cleanTail = cleaned.trimmedLines.asReversed().asSequence().filter { it.isNotEmpty() }.take(16).toList().asReversed()
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

        // 2. hc's own agent message fallback (if BLOCKED/ERROR had no explicit question/alert on screen).
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
    // Cleaning, Pre-Strip ANSI SGR Metadata & Dialect Bottom-Dock Stripping
    // ---------------------------------------------------------------------------------------------

    /**
     * Per-line SGR style metadata extracted BEFORE ANSI CSI sequences are stripped.
     * Used to disambiguate `Ctrl+T` (`Codex` `ReasoningSummaryCell` & dim tool output)
     * and `Ctrl+O` (`Antigravity` expanded thinking & tool output) from formal assistant prose.
     */
    private data class LineStyle(
        val isDim: Boolean = false,
        val isItalic: Boolean = false
    )

    private data class StyledLine(
        var text: String,
        val style: LineStyle = LineStyle(),
        var indent: Int = -1,
        var firstChar: Char = '\u0000',
        var hasBoxCorner: Boolean = false,
        var hasBlockBar: Boolean = false,
        var boxPipeCount: Int = 0
    ) {
        fun recomputeLexicalMeta() {
            val s = text
            var ind = -1
            var corners = false
            var bars = false
            var pipes = 0
            for (k in s.indices) {
                val ch = s[k]
                if (ind < 0 && ch != ' ') ind = k
                if (ch in '\u2500'..'\u259F') {
                    when (ch) {
                        '│', '┃', '║' -> pipes++
                        '┌', '┬', '┐', '├', '┼', '┤', '└', '┴', '┘',
                        '╞', '╪', '╡', '╘', '╧', '╛', '╒', '╤', '╕',
                        '╔', '╦', '╗', '╠', '╬', '╣', '╚', '╩', '╝' -> corners = true
                        in '\u2580'..'\u259F' -> bars = true
                    }
                }
            }
            indent = ind
            firstChar = if (ind >= 0) s[ind] else '\u0000'
            hasBoxCorner = corners
            hasBlockBar = bars
            boxPipeCount = pipes
        }
    }

    private data class CleanedScreen(
        val lines: List<String>,
        val trimmedLines: List<String>,
        val styles: List<LineStyle>,
        val indents: IntArray,
        val firstChars: CharArray,
        val boxPipeCounts: IntArray,
        val hasBoxCorners: BooleanArray,
        val isExpandedMode: Boolean,
        val rawTail: List<String>
    )

    private fun isSgrMarkerChar(ch: Char): Boolean = when (ch) {
        '•', '●', '⏺', '○', '◦', '▸', '▾', '▼', '│', '└', '├', '⎿', '↳' -> true
        else -> false
    }

    private fun isControlChar(ch: Char): Boolean {
        val code = ch.code
        return code in 0x00..0x08 || code == 0x0B || code == 0x0C || code in 0x0E..0x1F || code == 0x7F
    }

    private fun buildStyledLine(cleanText: String, style: LineStyle = LineStyle()): StyledLine =
        StyledLine(cleanText, style).also { it.recomputeLexicalMeta() }

    private fun extractStyledLines(raw: String): MutableList<StyledLine> {
        val hasEsc = raw.indexOf('\u001B') >= 0
        val preCleaned = if (hasEsc) {
            var s = if (raw.indexOf("\u001B]") >= 0) oscRegex.replace(raw, "") else raw
            s = charsetRegex.replace(s, "")
            s = escTwoCharRegex.replace(s, "")
            if (s.indexOf('\t') >= 0) s.replace("\t", "    ") else s
        } else {
            if (raw.indexOf('\t') >= 0) raw.replace("\t", "    ") else raw
        }
        val normalized = if (preCleaned.indexOf('\r') >= 0) preCleaned.replace("\r\n", "\n") else preCleaned
        val rawSplit = normalized
            .split('\n')
            .let { if (it.size > MAX_INPUT_LINES) it.takeLast(MAX_INPUT_LINES) else it }

        val result = ArrayList<StyledLine>(rawSplit.size)
        if (!hasEsc) {
            for (rawLine in rawSplit) {
                val crCleaned = if (rawLine.indexOf('\r') >= 0) {
                    rawLine.trimEnd('\r').substringAfterLast('\r')
                } else {
                    rawLine
                }
                var needsClean = false
                for (k in crCleaned.indices) {
                    val ch = crCleaned[k]
                    if (ch == '\u00A0' || isControlChar(ch)) {
                        needsClean = true
                        break
                    }
                }
                val finalLine = if (!needsClean) {
                    crCleaned.trimEnd()
                } else {
                    val sb = StringBuilder(crCleaned.length)
                    for (k in crCleaned.indices) {
                        val ch = crCleaned[k]
                        if (ch == '\u00A0') {
                            sb.append(' ')
                        } else if (!isControlChar(ch)) {
                            sb.append(ch)
                        }
                    }
                    sb.toString().trimEnd()
                }
                result.add(buildStyledLine(finalLine))
            }
            return result
        }

        var dim = false
        var italic = false
        var fgDim = false

        for (rawLine in rawSplit) {
            val crCleaned = if (rawLine.indexOf('\r') >= 0) {
                rawLine.trimEnd('\r').substringAfterLast('\r').replace('\u00A0', ' ')
            } else if (rawLine.indexOf('\u00A0') >= 0) {
                rawLine.replace('\u00A0', ' ')
            } else {
                rawLine
            }
            val sb = StringBuilder(crCleaned.length)
            var visibleChars = 0
            var dimChars = 0
            var italicChars = 0
            var idx = 0
            while (idx < crCleaned.length) {
                val ch = crCleaned[idx]
                if (ch == '\u001B' && idx + 1 < crCleaned.length && crCleaned[idx + 1] == '[') {
                    var end = idx + 2
                    while (end < crCleaned.length && crCleaned[end].code in 0x30..0x3F) end++
                    while (end < crCleaned.length && crCleaned[end].code in 0x20..0x2F) end++
                    if (end < crCleaned.length && crCleaned[end].code in 0x40..0x7E) {
                        val finalByte = crCleaned[end]
                        if (finalByte == 'm') {
                            val params = crCleaned.substring(idx + 2, end)
                            if (params.isEmpty()) {
                                dim = false
                                italic = false
                                fgDim = false
                            } else {
                                val parts = params.split(';', ':')
                                var p = 0
                                while (p < parts.size) {
                                    val code = parts[p].toIntOrNull() ?: 0
                                    when (code) {
                                        0 -> {
                                            dim = false
                                            italic = false
                                            fgDim = false
                                        }
                                        1 -> dim = false
                                        2 -> dim = true
                                        3 -> italic = true
                                        22 -> dim = false
                                        23 -> italic = false
                                        39 -> fgDim = false
                                        90 -> fgDim = true
                                        38 -> {
                                            val mode = parts.getOrNull(p + 1)?.toIntOrNull()
                                            if (mode == 5) {
                                                val colorIdx = parts.getOrNull(p + 2)?.toIntOrNull() ?: -1
                                                fgDim = colorIdx in 236..248
                                                p += 2
                                            } else if (mode == 2) {
                                                val r = parts.getOrNull(p + 2)?.toIntOrNull() ?: 255
                                                val g = parts.getOrNull(p + 3)?.toIntOrNull() ?: 255
                                                val b = parts.getOrNull(p + 4)?.toIntOrNull() ?: 255
                                                fgDim = r in 70..165 && g in 70..165 && b in 70..165 &&
                                                    kotlin.math.abs(r - g) <= 18 && kotlin.math.abs(g - b) <= 18
                                                p += 4
                                            }
                                        }
                                    }
                                    p++
                                }
                            }
                        }
                        idx = end + 1
                        continue
                    }
                }
                if (ch != '\u001B' && !isControlChar(ch)) {
                    sb.append(ch)
                    if (!ch.isWhitespace() && !isSgrMarkerChar(ch)) {
                        visibleChars++
                        if (dim || fgDim) dimChars++
                        if (italic) italicChars++
                    }
                }
                idx++
            }
            val cleanText = sb.toString().trimEnd()
            val lineStyle = LineStyle(
                isDim = visibleChars > 0 && dimChars * 2 >= visibleChars,
                isItalic = visibleChars > 0 && italicChars * 2 >= visibleChars
            )
            result.add(buildStyledLine(cleanText, lineStyle))
        }
        return result
    }

    private fun cleanLines(raw: String, dialect: TerminalDialect): List<String> =
        cleanScreen(raw, dialect).lines

    private fun detectCodexExpandedMode(rawLines: List<StyledLine>): Boolean {
        val window = rawLines.takeLast(120)
        var latestState: Boolean? = null
        for (styled in window) {
            if (styled.indent < 0) continue
            val line = styled.text
            val c0 = styled.firstChar
            val t = if (styled.indent == 0) line else line.substring(styled.indent)
            when {
                ((c0 == '/' || c0 == 'T') && t.contains("T R A N S C R I P T") && CodexTranscriptSpec.transcriptOverlayHeaderRegex.matches(t)) ||
                    CodexTranscriptSpec.isTranscriptOverlayFooter(t) ||
                    ((c0 == '−' || c0 == '-') && CodexTranscriptSpec.expandedDisclosureRegex.matches(t)) ||
                    (styled.indent == 0 && c0 == '•' && line.startsWith("• ") &&
                        styled.style.isDim && styled.style.isItalic &&
                        !CodexTranscriptSpec.isActivityHead(line)) -> latestState = true
                line.contains(" to expand)", ignoreCase = true) && ctrlExpandSuffixRegex.containsMatchIn(line) -> latestState = false
            }
        }
        return latestState == true
    }

    private fun isToolChildFast(firstChar: Char, line: String, indent: Int): Boolean {
        if (indent < 0) return false
        return when (firstChar) {
            '└', '├', '⎿' -> true
            '↳' -> indent + 1 < line.length && line.substring(indent + 1).any { !it.isWhitespace() }
            else -> false
        }
    }

    private fun detectAntigravityExpandedMode(rawLines: List<StyledLine>): Boolean {
        var latestState: Boolean? = null
        for (idx in rawLines.indices) {
            val styled = rawLines[idx]
            val ind = styled.indent
            if (ind < 0) continue
            val c0 = styled.firstChar
            // Fast O(1) filter: trajectory/attachment/rollup rows in Antigravity ONLY start with these markers!
            if (c0 != '●' && c0 != '⏺' && c0 != '○' && c0 != '◦' &&
                c0 != '▸' && c0 != '▾' && c0 != '▼' &&
                c0 != '✓' && c0 != '✔' && c0 != '✗' && c0 != '✘' &&
                c0 != '…' && c0 != '.' && !c0.isSurrogate()
            ) {
                continue
            }
            val line = styled.text
            if ((c0 == '●' || c0 == '⏺') && AntigravityTranscriptSpec.isExpandedToolCallHead(line, ind)) {
                val limit = (idx + 8).coerceAtMost(rawLines.size - 1)
                var hasDirectChild = "(ctrl+o to collapse)" in line
                if (!hasDirectChild) {
                    for (k in idx + 1..limit) {
                        val child = rawLines[k]
                        if (isToolChildFast(child.firstChar, child.text, child.indent) ||
                            "(ctrl+o to collapse)" in child.text
                        ) {
                            hasDirectChild = true
                            break
                        }
                    }
                }
                if (hasDirectChild) {
                    latestState = true
                    continue
                }
            }
            val isMoreActions = (c0 == '…' || c0 == '.') && "more actions" in line &&
                AntigravityTranscriptSpec.moreActionsRowRegex.matches(line)
            val isTrajectoryRow = isMoreActions ||
                AntigravityTranscriptSpec.isThoughtRow(line, ind) ||
                AntigravityTranscriptSpec.isActionRow(line, ind) ||
                ("📎" in line && AntigravityTranscriptSpec.attachmentBadgeRegex.matches(line))
            if (!isTrajectoryRow) continue
            val hasSpaceAfterMarker = ind + 1 < line.length && line[ind + 1] == ' '
            when {
                "(ctrl+o to collapse)" in line ||
                    ((c0 == '▾' || c0 == '▼') && hasSpaceAfterMarker) -> latestState = true
                "(ctrl+o to expand)" in line ||
                    (c0 == '▸' && hasSpaceAfterMarker) ||
                    isMoreActions -> latestState = false
            }
        }
        return latestState == true
    }

    private fun cleanScreen(raw: String, dialect: TerminalDialect): CleanedScreen {
        val rawLines = extractStyledLines(raw)
        val rawTail = rawLines.asReversed().asSequence()
            .filter { it.indent >= 0 }
            .map { it.text }
            .take(30)
            .toList()
            .asReversed()

        val isExpandedMode = when (dialect) {
            TerminalDialect.CODEX -> detectCodexExpandedMode(rawLines)
            TerminalDialect.ANTIGRAVITY -> detectAntigravityExpandedMode(rawLines)
            else -> false
        }

        stripBottomDock(rawLines, dialect)
        if (dialect == TerminalDialect.PI) {
            stripPiBanners(rawLines)
        } else if (dialect == TerminalDialect.CODEX) {
            stripCodexBanners(rawLines)
        }

        val cap = rawLines.size
        val outLines = ArrayList<String>(cap)
        val outTrimmed = ArrayList<String>(cap)
        val outStyles = ArrayList<LineStyle>(cap)
        val outIndents = IntArray(cap)
        val outFirstChars = CharArray(cap)
        val outBoxPipes = IntArray(cap)
        val outBoxCorners = BooleanArray(cap)
        var count = 0
        var lastBlank = true
        var insideCodexStatusCard = false

        for (idx in rawLines.indices) {
            val styled = rawLines[idx]
            var line = styled.text
            var ind = styled.indent
            var c0 = styled.firstChar
            var boxPipes = styled.boxPipeCount
            var boxCorners = styled.hasBoxCorner
            val hasBlockBar = styled.hasBlockBar

            // Only check framed-message unwrapping when the first non-space char is actually `│`!
            if (c0 == '│') {
                val partOfBoxTable = (idx > 0 && rawLines[idx - 1].hasBoxCorner) ||
                    (idx + 1 < rawLines.size && rawLines[idx + 1].hasBoxCorner)
                if (!partOfBoxTable && !(dialect == TerminalDialect.PI && piPipeSummaryRegex.matches(line))) {
                    val unwrapped = unwrapFramedMessageRow(line, dialect)
                    if (unwrapped !== line) {
                        line = unwrapped
                        val tmp = buildStyledLine(line, styled.style)
                        ind = tmp.indent
                        c0 = tmp.firstChar
                        boxPipes = tmp.boxPipeCount
                        boxCorners = tmp.hasBoxCorner
                    }
                }
            }

            if (ind < 0) {
                if (!lastBlank) {
                    outLines.add("")
                    outTrimmed.add("")
                    outStyles.add(LineStyle())
                    outIndents[count] = -1
                    outFirstChars[count] = '\u0000'
                    outBoxPipes[count] = 0
                    outBoxCorners[count] = false
                    count++
                }
                lastBlank = true
                if (insideCodexStatusCard) {
                    val nextStyled = rawLines.getOrNull(idx + 1)
                    val nextTrim = if (nextStyled != null && nextStyled.indent >= 0) {
                        nextStyled.text.substring(nextStyled.indent)
                    } else {
                        ""
                    }
                    if (!nextTrim.startsWith("Server:") &&
                        !nextTrim.startsWith("Model:") &&
                        !nextTrim.startsWith("5h limit:") &&
                        !nextTrim.startsWith("Visit ")
                    ) {
                        insideCodexStatusCard = false
                    }
                }
                continue
            }

            val trimmed = if (ind == 0) line else line.substring(ind)
            if (dialect == TerminalDialect.CODEX && c0 == '>' && trimmed.startsWith(">_ OpenAI Codex")) {
                insideCodexStatusCard = true
            }

            if (isNoise(trimmed, c0, hasBlockBar, dialect, insideCodexStatusCard)) continue

            outLines.add(line)
            outTrimmed.add(trimmed)
            outStyles.add(styled.style)
            outIndents[count] = ind
            outFirstChars[count] = c0
            outBoxPipes[count] = boxPipes
            outBoxCorners[count] = boxCorners
            count++
            lastBlank = false
        }

        while (count > 0 && outLines[count - 1].isEmpty()) {
            count--
            outLines.removeAt(count)
            outTrimmed.removeAt(count)
            outStyles.removeAt(count)
        }

        return CleanedScreen(
            lines = outLines,
            trimmedLines = outTrimmed,
            styles = outStyles,
            indents = if (count == outIndents.size) outIndents else outIndents.copyOf(count),
            firstChars = if (count == outFirstChars.size) outFirstChars else outFirstChars.copyOf(count),
            boxPipeCounts = if (count == outBoxPipes.size) outBoxPipes else outBoxPipes.copyOf(count),
            hasBoxCorners = if (count == outBoxCorners.size) outBoxCorners else outBoxCorners.copyOf(count),
            isExpandedMode = isExpandedMode,
            rawTail = rawTail
        )
    }

    /**
     * Deterministically removes the live interactive input composer & status dock at the bottom
     * of each agent's TUI so live drafts, placeholders (`Ask Codex to do anything`), model badges,
     * and background cron bars never leak into the conversation blocks.
     */
    private fun stripBottomDock(lines: MutableList<StyledLine>, dialect: TerminalDialect) {
        if (lines.isEmpty()) return

        when (dialect) {
            TerminalDialect.ANTIGRAVITY -> {
                var end = lines.lastIndex
                val floor = (lines.size - 32).coerceAtLeast(0)
                var sawBottomRule = false
                while (end >= floor) {
                    val styled = lines[end]
                    val raw = styled.text
                    val t = if (styled.indent < 0) "" else raw.substring(styled.indent).trimEnd()
                    when {
                        t.isEmpty() -> end--
                        containsCommonFooterMarker(t) ||
                            AntigravityTranscriptSpec.isModelStatusFooter(t) ||
                            AntigravityTranscriptSpec.isKeyHintFooter(t) ||
                            AntigravityTranscriptSpec.isCustomStatusLine(t) ||
                            ((styled.firstChar == 'R' || styled.firstChar == 'r') && AntigravityTranscriptSpec.exitResumeHeaderRegex.matches(raw)) ||
                            ((styled.firstChar == 'a' || styled.firstChar == 'A') && AntigravityTranscriptSpec.exitResumeCmdRegex.matches(raw)) -> end--
                        (styled.firstChar == '●' || styled.firstChar == '○') && (
                            AntigravityTranscriptSpec.trayTaskRowRegex.matches(raw) ||
                                AntigravityTranscriptSpec.traySubagentRowRegex.matches(raw)
                            ) -> end--
                        longRuleRegex.matches(t) -> {
                            sawBottomRule = true
                            end--
                        }
                        sawBottomRule && (t == ">" || (t.startsWith("> ") && !promptMenuOptionRegex.matches(raw))) &&
                            !t.contains("<hc") -> {
                            // Live composer prompt `> ...` is sandwiched between two `───` rules at the bottom.
                            val aboveIdx = (end - 1 downTo floor).firstOrNull { lines[it].indent >= 0 }
                            if (aboveIdx != null && longRuleRegex.matches(lines[aboveIdx].text.trim())) {
                                end = aboveIdx - 1
                            }
                            // Also strip right-aligned `N artifact(s) · /artifact to review` immediately above the top rule.
                            while (end >= floor && (lines[end].indent < 0 || AntigravityTranscriptSpec.isKeyHintFooter(lines[end].text))) {
                                end--
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
                val floor = (lines.size - 28).coerceAtLeast(0)
                var sawFooterBelow = false
                while (end >= floor) {
                    val styled = lines[end]
                    val raw = styled.text
                    val t = if (styled.indent < 0) "" else raw.substring(styled.indent).trimEnd()
                    when {
                        t.isEmpty() -> end--
                        styled.firstChar == '~' && CodexTranscriptSpec.transcriptOverlayTildeRowRegex.matches(t) -> end--
                        containsCommonFooterMarker(t) ||
                            (styled.firstChar in "GgOoCc" && '·' in t && CodexTranscriptSpec.modelCwdBarRegex.matches(raw)) ||
                            (styled.firstChar == '⚠' && (CodexTranscriptSpec.quotaInlineRegex.matches(raw) || CodexTranscriptSpec.quotaHeadsUpRegex.matches(raw))) ||
                            CodexTranscriptSpec.isTranscriptOverlayFooter(t) -> {
                            sawFooterBelow = true
                            end--
                        }
                        styled.firstChar == '›' && CodexTranscriptSpec.composerPlaceholderRegex.matches(t) -> {
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
                    val t = lines[end].text.trim()
                    when {
                        t.isEmpty() -> end--
                        containsCommonFooterMarker(t) || (t.contains("tok/s") && piTelemetryLineRegex.matches(t)) -> end--
                        longRuleRegex.matches(t) -> {
                            sawBottomRule = true
                            end--
                        }
                        sawBottomRule && (t == "❯" || t.startsWith("❯ ")) -> {
                            val aboveIdx = (end - 1 downTo floor).firstOrNull { lines[it].indent >= 0 }
                            if (aboveIdx != null && longRuleRegex.matches(lines[aboveIdx].text.trim())) {
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
                    val t = lines[end].text.trim()
                    when {
                        t.isEmpty() -> end--
                        containsCommonFooterMarker(t) ||
                            AntigravityTranscriptSpec.isModelStatusFooter(t) -> end--
                        longRuleRegex.matches(t) || inputBoxBorderRegex.matches(t) -> {
                            sawBottomRule = true
                            end--
                        }
                        sawBottomRule && lonePromptRegex.matches(t) -> {
                            val aboveIdx = (end - 1 downTo floor).firstOrNull { lines[it].indent >= 0 }
                            if (aboveIdx != null && (longRuleRegex.matches(lines[aboveIdx].text.trim()) || inputBoxBorderRegex.matches(lines[aboveIdx].text.trim()))) {
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
    private fun stripPiBanners(lines: MutableList<StyledLine>) {
        var i = 0
        while (i < lines.size) {
            if (lines[i].firstChar == 'P' && lines[i].text.trim() == "Package Updates Available") {
                val start = if (i > 0 && longRuleRegex.matches(lines[i - 1].text.trim())) i - 1 else i
                var end = i + 1
                while (end < lines.size && !longRuleRegex.matches(lines[end].text.trim())) {
                    end++
                }
                if (end < lines.size && longRuleRegex.matches(lines[end].text.trim())) {
                    lines.subList(start, end + 1).clear()
                    i = start
                    continue
                }
            }
            i++
        }
    }

    /** Strips Codex's `UpdateAvailableHistoryCell` (`notices.rs:2964`) boxed or plain update banner. */
    private fun stripCodexBanners(lines: MutableList<StyledLine>) {
        var i = 0
        while (i < lines.size) {
            val rawText = lines[i].text
            if (rawText.contains("Update available", ignoreCase = true)) {
                val unwrapped = unwrapFramedMessageRow(rawText).trim()
                if (CodexTranscriptSpec.updateAvailableBannerRegex.matches(unwrapped)) {
                    val hasTopBorder = i > 0 && inputBoxBorderRegex.matches(lines[i - 1].text.trim())
                    val start = if (hasTopBorder) i - 1 else i
                    var end = i + 1
                    val limit = (i + 12).coerceAtMost(lines.size)
                    while (end < limit) {
                        val t = lines[end].text.trim()
                        if (hasTopBorder && inputBoxBorderRegex.matches(t)) {
                            end++
                            break
                        }
                        val u = unwrapFramedMessageRow(lines[end].text).trim()
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
            }
            i++
        }
    }

    /** `│ text │` or `│ text` (a framed message, not a multi-column table) -> `text`, keeping relative indent. */
    private fun unwrapFramedMessageRow(line: String, dialect: TerminalDialect = TerminalDialect.GENERIC): String {
        val pipeCount = line.count { it == '│' }
        val trimmedStart = line.trimStart()
        if (!trimmedStart.startsWith("│")) return line
        // In Antigravity (`agy`), single-pipe `  │ <text>` is a Markdown blockquote / callout (`> [!NOTE]`), not a box frame!
        if (dialect == TerminalDialect.ANTIGRAVITY && pipeCount == 1) return line
        // In Codex (`exec_cell/render.rs:383`), `  │ <cmd>` is a multi-line command continuation under `• Ran` / `• Running`!
        if (dialect == TerminalDialect.CODEX && pipeCount == 1 && CodexTranscriptSpec.commandContPipeRegex.matches(line)) return line
        val leading = line.length - trimmedStart.length
        return when {
            pipeCount == 1 -> " ".repeat(leading) + trimmedStart.removePrefix("│").removePrefix(" ")
            pipeCount == 2 && line.trimEnd().endsWith("│") ->
                " ".repeat(leading) + trimmedStart.removePrefix("│").trimEnd().removeSuffix("│").removePrefix(" ").trimEnd()
            else -> line
        }
    }

    private fun isNoise(
        trimmed: String,
        firstChar: Char,
        hasBlockBar: Boolean,
        dialect: TerminalDialect,
        insideCodexStatusCard: Boolean
    ): Boolean {
        when (firstChar) {
            '─', '━', '═', '╌', '╍', '┄', '┅', '┈', '┉', '-', '_', '=', '~', '—' -> {
                if (fullRuleRegex.matches(trimmed)) return true
                if (CodexTranscriptSpec.markdownEmDashRuleRegex.matches(trimmed)) return true
                if (trimmed.contains("Conversation compacted", ignoreCase = true) && compactedDividerRegex.matches(trimmed)) return true
            }
            '╭', '╰', '┌', '└', '│', '├' -> {
                if (inputBoxBorderRegex.matches(trimmed)) return true
                if (emptyBoxRowRegex.matches(trimmed)) return true
                if (trimmed.contains("Tip:") && tipRegex.containsMatchIn(trimmed)) return true
            }
            '>', '❯', '›', '$' -> {
                if (trimmed.length <= 2 && lonePromptRegex.matches(trimmed)) return true
            }
            '※', '⎿', '*', 'T' -> {
                if (trimmed.contains("Tip:") && tipRegex.containsMatchIn(trimmed)) return true
            }
            'C', 'c' -> {
                if (trimmed.startsWith("Conversation compacted", ignoreCase = true) && compactedDividerRegex.matches(trimmed)) return true
            }
        }

        if (!insideCodexStatusCard && hasBlockBar && progressBarRegex.containsMatchIn(trimmed)) return true
        if (containsCommonFooterMarker(trimmed)) return true

        return when (dialect) {
            TerminalDialect.ANTIGRAVITY ->
                ((firstChar == '●' || firstChar == '○') && (
                    AntigravityTranscriptSpec.trayTaskRowRegex.matches(trimmed) ||
                        AntigravityTranscriptSpec.traySubagentRowRegex.matches(trimmed)
                    )) ||
                    ((firstChar == 'R' || firstChar == 'r') && AntigravityTranscriptSpec.exitResumeHeaderRegex.matches(trimmed)) ||
                    ((firstChar == 'a' || firstChar == 'A') && AntigravityTranscriptSpec.exitResumeCmdRegex.matches(trimmed)) ||
                    AntigravityTranscriptSpec.isModelStatusFooter(trimmed) ||
                    AntigravityTranscriptSpec.isKeyHintFooter(trimmed) ||
                    AntigravityTranscriptSpec.isCustomStatusLine(trimmed)
            TerminalDialect.CODEX ->
                (firstChar == '›' && CodexTranscriptSpec.composerPlaceholderRegex.matches(trimmed)) ||
                    (firstChar == '⚠' && (
                        CodexTranscriptSpec.quotaHeadsUpRegex.matches(trimmed) ||
                            CodexTranscriptSpec.quotaInlineRegex.matches(trimmed)
                        )) ||
                    (firstChar == '~' && CodexTranscriptSpec.transcriptOverlayTildeRowRegex.matches(trimmed)) ||
                    ((firstChar == '/' || firstChar == 'T') && CodexTranscriptSpec.transcriptOverlayHeaderRegex.matches(trimmed)) ||
                    (firstChar in "GgOoCc" && '·' in trimmed && CodexTranscriptSpec.modelCwdBarRegex.matches(trimmed)) ||
                    CodexTranscriptSpec.isTranscriptOverlayFooter(trimmed)
            TerminalDialect.PI ->
                ((firstChar == 'R' || firstChar == 'r' || firstChar == 'T' || firstChar == 't') && piSessionNoiseRegex.matches(trimmed)) ||
                    (trimmed.contains("tok/s") && piTelemetryLineRegex.matches(trimmed))
            else ->
                firstChar == '›' && CodexTranscriptSpec.composerPlaceholderRegex.matches(trimmed)
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
        private val screen: CleanedScreen,
        private val dialect: TerminalDialect
    ) {
        private val lines: List<String> = screen.lines
        private val trimmedLines: List<String> = screen.trimmedLines
        private val styles: List<LineStyle> = screen.styles
        private val firstChars: CharArray = screen.firstChars
        private val boxPipeCounts: IntArray = screen.boxPipeCounts
        private val hasBoxCorners: BooleanArray = screen.hasBoxCorners
        private val isExpandedMode: Boolean = screen.isExpandedMode
        private val blocks = ArrayList<TerminalSemanticBlock>()
        private val maxGroupSteps: Int = if (isExpandedMode) MAX_EXPANDED_GROUP_STEPS else MAX_GROUP_STEPS
        private val maxCodexChildPreviewLines: Int =
            if (isExpandedMode) MAX_EXPANDED_CHILD_PREVIEW_LINES else MAX_CODEX_CHILD_PREVIEW_LINES
        private val maxAgyChildPreviewLines: Int =
            if (isExpandedMode) MAX_EXPANDED_CHILD_PREVIEW_LINES else 6
        private val maxStepChars: Int = if (isExpandedMode) EXPANDED_STEP_LINE_CHARS else COMPACT_STEP_LINE_CHARS

        // Precomputed O(N) per-line lookup tables using O(1) firstChar/prefix lexical dispatch
        private val lineIndents = IntArray(lines.size) { screen.indents[it].coerceAtLeast(0) }
        private val lineIsBlank = BooleanArray(lines.size) { screen.indents[it] < 0 }
        private val nextNonBlankIdx = IntArray(lines.size)
        private val lineIsPrompt = BooleanArray(lines.size)
        private val lineIsMoreActions = BooleanArray(lines.size)
        private val lineIsToolHead = BooleanArray(lines.size)
        private val lineIsToolChild = BooleanArray(lines.size)
        private val lineIsAlert = BooleanArray(lines.size)
        private val lineIsTable = BooleanArray(lines.size)
        private val lineIsDiff = BooleanArray(lines.size)
        private val hasLaterCodexCellInTurn = BooleanArray(lines.size)
        private val hasLaterAgyToolOnOrAfter = BooleanArray(lines.size)

        init {
            var nextNb = -1
            for (k in lines.lastIndex downTo 0) {
                if (!lineIsBlank[k]) {
                    nextNb = k
                    val c0 = firstChars[k]
                    lineIsMoreActions[k] = (c0 == '…' || c0 == '.') && moreActionsRegex.matches(lines[k])
                    lineIsPrompt[k] = computeIsPromptLineIdx(k)
                    lineIsToolHead[k] = computeIsToolHeadIdx(k)
                    lineIsToolChild[k] = (c0 == '└' || c0 == '├' || c0 == '⎿' || c0 == '↳') && toolChildRegex.matches(lines[k])
                    lineIsAlert[k] = computeIsAlertLineIdx(k)
                    lineIsTable[k] = computeIsTableLineIdx(k)
                    lineIsDiff[k] = computeIsDiffLineIdx(k)
                }
                nextNonBlankIdx[k] = nextNb
            }
            if (dialect == TerminalDialect.CODEX) {
                var seenCell = false
                for (k in lines.lastIndex downTo 0) {
                    hasLaterCodexCellInTurn[k] = seenCell
                    if (lineIsBlank[k]) continue
                    val l = lines[k]
                    val c0 = firstChars[k]
                    if (lineIsPrompt[k] ||
                        ((c0 == 'W' || c0 == 'w') && CodexTranscriptSpec.finalSeparatorRegex.matches(l)) ||
                        isCodexStructuredAlertStartIdx(k)
                    ) {
                        seenCell = false
                    } else if (lineIsToolHead[k] ||
                        lineIsToolChild[k] ||
                        (c0 == '│' && CodexTranscriptSpec.commandContPipeRegex.matches(l)) ||
                        l.startsWith("• ")
                    ) {
                        seenCell = true
                    }
                }
            } else if (dialect == TerminalDialect.ANTIGRAVITY) {
                var seenTool = false
                for (k in lines.lastIndex downTo 0) {
                    if (!lineIsBlank[k]) {
                        if (lineIsPrompt[k] || lineIsAlert[k]) {
                            seenTool = false
                        } else if (lineIsToolHead[k] || lineIsToolChild[k] || lineIsMoreActions[k]) {
                            seenTool = true
                        }
                    }
                    hasLaterAgyToolOnOrAfter[k] = seenTool
                }
            }
        }

        private val maxWidth: Int = lines.maxOfOrNull { displayWidth(it) } ?: 0
        private val wrapThreshold: Int = maxOf(40, (maxWidth * 0.72f).toInt())
        private val baseIndent: Int = when (dialect) {
            TerminalDialect.ANTIGRAVITY -> 2
            TerminalDialect.CODEX -> 2
            else -> lines.indices
                .asSequence()
                .filter { !lineIsBlank[it] && !lineIsToolHead[it] }
                .minOfOrNull { lineIndents[it] }
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
            // Handle scrollback starting mid-diff / mid-tool-output whose header scrolled off top.
            if (dialect == TerminalDialect.CODEX && lines.isNotEmpty() && isCodexOrphanToolStart()) {
                consumeCodexToolGroup(orphanStart = true)
            } else if (dialect == TerminalDialect.ANTIGRAVITY && lines.isNotEmpty() && isAgyOrphanToolStart()) {
                consumeAgyOrphanToolOutput()
            }

            while (i < lines.size) {
                if (lineIsBlank[i]) {
                    flushParagraph()
                    i++
                    continue
                }
                val line = lines[i]
                val trimmed = trimmedLines[i]
                val c0 = firstChars[i]
                val unfencedEnd = if ((dialect == TerminalDialect.CODEX || dialect == TerminalDialect.ANTIGRAVITY) && paraText == null) {
                    CodexTranscriptSpec.detectUnfencedCodeBlockEnd(lines, i, wrapThreshold)
                } else {
                    null
                }
                when {
                    dialect == TerminalDialect.CODEX && c0 == '>' && trimmed.startsWith(">_ OpenAI Codex") -> {
                        flushParagraph()
                        consumeCodexStatusCard()
                    }
                    dialect == TerminalDialect.CODEX && (c0 == 'W' || c0 == 'w') && CodexTranscriptSpec.finalSeparatorRegex.matches(line) -> {
                        flushParagraph()
                        consumeCodexWorkedFor()
                    }
                    dialect == TerminalDialect.CODEX && c0 in "+−-…⋮." && CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(trimmed) -> {
                        i++
                    }
                    dialect == TerminalDialect.CODEX && c0 == '[' && CodexTranscriptSpec.userImageBadgeRegex.matches(line) -> {
                        flushParagraph()
                        consumeCodexUserImageBadge()
                    }
                    dialect == TerminalDialect.CODEX && c0 == '•' && CodexTranscriptSpec.userShellHeadRegex.matches(trimmed) -> {
                        flushParagraph()
                        consumeCodexUserShell()
                    }
                    dialect == TerminalDialect.CODEX && c0 == '•' && CodexTranscriptSpec.delegatedMessageHeadRegex.matches(trimmed) -> {
                        flushParagraph()
                        val taskId = CodexTranscriptSpec.delegatedMessageHeadRegex.find(trimmed)!!.groupValues[1]
                        blocks.add(TerminalSemanticBlock.AgentProseBlock("来自子任务 $taskId", isHeading = true))
                        i++
                    }
                    dialect == TerminalDialect.CODEX && c0 == '•' && CodexTranscriptSpec.proposedPlanHeadRegex.matches(trimmed) -> {
                        flushParagraph()
                        blocks.add(TerminalSemanticBlock.AgentProseBlock("Proposed Plan", isHeading = true))
                        i++
                    }
                    dialect == TerminalDialect.CODEX && c0 == '•' && CodexTranscriptSpec.planUpdateHeadRegex.matches(trimmed) -> {
                        flushParagraph()
                        consumeCodexPlanUpdate()
                    }
                    dialect == TerminalDialect.CODEX && c0 == '•' && CodexTranscriptSpec.questionsResultHeadRegex.matches(trimmed) -> {
                        flushParagraph()
                        consumeCodexQuestionsResult()
                    }
                    dialect == TerminalDialect.CODEX && c0 == '↳' && CodexTranscriptSpec.threadRecapHeadRegex.matches(line) -> {
                        flushParagraph()
                        consumeCodexThreadRecap()
                    }
                    dialect == TerminalDialect.CODEX && isCodexStructuredAlertStartIdx(i) -> {
                        flushParagraph()
                        consumeCodexStructuredAlert()
                    }
                    dialect == TerminalDialect.ANTIGRAVITY && (c0 == '▸' || c0 == '▾' || c0 == '▼' || c0.isSurrogate()) &&
                        line.contains("\uD83D\uDCCE") && AntigravityTranscriptSpec.attachmentBadgeRegex.matches(line) -> {
                        val badge = AntigravityTranscriptSpec.attachmentBadgeRegex.find(line)!!.groupValues[1].trim()
                        val prev = blocks.lastOrNull()
                        if (paraText == null && prev is TerminalSemanticBlock.UserPromptBlock) {
                            blocks[blocks.lastIndex] = prev.copy(text = "${prev.text}\n[📎 $badge]")
                        } else {
                            flushParagraph()
                            blocks.add(TerminalSemanticBlock.UserPromptBlock("[📎 $badge]"))
                        }
                        i++
                        skipExpandedAgyAttachmentChildren(line)
                    }
                    dialect == TerminalDialect.ANTIGRAVITY && (c0 in '0'..'9' || c0 == '•' || c0 == '◦' || c0 == '▪' || c0 == '‣') &&
                        isAgyLoneListMarkerStart(i) -> {
                        flushParagraph()
                        consumeAgyLoneListMarker()
                    }
                    dialect == TerminalDialect.ANTIGRAVITY && c0 == '│' && !isAgyPipeTableStart(i) &&
                        !lineIsTable[i] && AntigravityTranscriptSpec.calloutPipeLineRegex.matches(line) -> {
                        consumeAgyCalloutLine(line)
                        i++
                    }
                    dialect == TerminalDialect.PI && c0 == '●' && piTodosHeaderRegex.matches(line) -> {
                        flushParagraph()
                        consumePiTodos()
                    }
                    (c0 == '`' || c0 == '~') && fenceRegex.matches(line) -> {
                        flushParagraph()
                        consumeFence()
                    }
                    lineIsPrompt[i] -> {
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
                    lineIsToolHead[i] || isCodexReasoningCellStart(i) -> {
                        flushParagraph()
                        if (dialect == TerminalDialect.CODEX) {
                            consumeCodexToolGroup(orphanStart = false)
                        } else {
                            consumeToolGroup()
                        }
                    }
                    lineIsAlert[i] -> {
                        flushParagraph()
                        consumeAlerts()
                    }
                    lineIsTable[i] || lineIsDiff[i] -> {
                        flushParagraph()
                        val isTbl = lineIsTable[i]
                        consumeCodeRunByIndex(kindLabel = if (isTbl) "表格" else "Diff") { idx -> lineIsTable[idx] || lineIsDiff[idx] }
                    }
                    (c0 == '{' || c0 == '[') && isJsonStart(trimmed) -> {
                        flushParagraph()
                        consumeJson()
                    }
                    unfencedEnd != null -> {
                        addCode(lines.subList(i, unfencedEnd).joinToString("\n"), kindLabel = "代码")
                        i = unfencedEnd
                    }
                    dialect == TerminalDialect.ANTIGRAVITY && isAgyInlineUnfencedCodeLine(i) -> {
                        flushParagraph()
                        addCode(trimmed, kindLabel = "代码")
                        i++
                    }
                    isIndentedCodeIdx(i) -> {
                        flushParagraph()
                        consumeCodeRunByIndex(kindLabel = "代码") { idx -> isIndentedCodeIdx(idx) }
                    }
                    lineIsToolChild[i] -> {
                        flushParagraph()
                        if (dialect == TerminalDialect.CODEX) {
                            consumeCodexToolGroup(orphanStart = true)
                        } else {
                            consumeToolGroup()
                        }
                    }
                    else -> {
                        appendProse(i)
                        i++
                    }
                }
            }
            flushParagraph()
            val merged = mergeAdjacentToolGroups(blocks)
            if (!isExpandedMode || merged.isEmpty()) return merged

            // When Ctrl+O / Ctrl+T expanded view is active, auto-expand ToolGroupBlocks in the latest turn
            // (or the last ToolGroupBlock if no user prompt is visible) so details are immediately visible.
            val lastPromptIdx = merged.indexOfLast { it is TerminalSemanticBlock.UserPromptBlock }
            val lastToolIdx = merged.indexOfLast {
                it is TerminalSemanticBlock.ToolGroupBlock && !isDedicatedSummaryGroup(it)
            }
            if (lastToolIdx < 0) return merged
            val autoExpandFrom = if (lastPromptIdx >= 0 && lastToolIdx > lastPromptIdx) lastPromptIdx + 1 else lastToolIdx
            return merged.mapIndexed { idx, blk ->
                if (idx >= autoExpandFrom && blk is TerminalSemanticBlock.ToolGroupBlock && !isDedicatedSummaryGroup(blk)) {
                    blk.copy(defaultExpanded = true)
                } else {
                    blk
                }
            }
        }

        // ---- prompts ----

        private fun isPromptLine(line: String): Boolean = computeIsPromptLine(line)

        private fun computeIsPromptLineIdx(idx: Int): Boolean {
            val c0 = firstChars[idx]
            val line = lines[idx]
            if ((c0 == '>' || c0 == '❯' || c0 == '›') && promptMenuOptionRegex.matches(line)) return false
            return when (dialect) {
                TerminalDialect.ANTIGRAVITY -> c0 == '>' && lineIndents[idx] == 0 && agyPromptRegex.matches(line)
                TerminalDialect.CODEX ->
                    (c0 == '›' && lineIndents[idx] == 0 && codexPromptRegex.matches(line) &&
                        !CodexTranscriptSpec.composerPlaceholderRegex.matches(trimmedLines[idx])) ||
                        (c0 == '/' && lineIndents[idx] == 0 && CodexTranscriptSpec.slashCmdRegex.matches(line))
                TerminalDialect.PI ->
                    (c0 == '❯' && claudePromptRegex.matches(line)) ||
                        (trimmedLines[idx].endsWith("[click to jump]") && piClickToJumpPromptRegex.matches(line))
                TerminalDialect.CLAUDE -> c0 == '❯' && claudePromptRegex.matches(line)
                else -> (c0 == '>' || c0 == '❯' || c0 == '›') && genericPromptRegex.matches(line) &&
                    !CodexTranscriptSpec.composerPlaceholderRegex.matches(trimmedLines[idx])
            }
        }

        private fun computeIsPromptLine(line: String): Boolean {
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

        private fun isPromptBoundaryIdx(idx: Int): Boolean {
            if (lineIsBlank[idx]) return false
            if (lineIsToolHead[idx] || lineIsToolChild[idx] || lineIsPrompt[idx] || lineIsAlert[idx]) return true
            if (dialect == TerminalDialect.CODEX) {
                if (lineIndents[idx] < 2) return true
                val line = lines[idx]
                val c0 = firstChars[idx]
                if (line.startsWith("• ") || isCodexStructuredAlertStartIdx(idx) ||
                    ((c0 == 'W' || c0 == 'w') && CodexTranscriptSpec.finalSeparatorRegex.matches(line)) ||
                    (c0 in "+−-…⋮." && CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(trimmedLines[idx])) ||
                    (c0 == '↳' && CodexTranscriptSpec.threadRecapHeadRegex.matches(line))
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

            // In Antigravity (`agy`), slash commands (`> /artifact`, `> /agents`) are immediately followed by `  ⎿  <outcome>`
            if (dialect == TerminalDialect.ANTIGRAVITY && AntigravityTranscriptSpec.slashPromptRegex.matches(first)) {
                if (i < lines.size && AntigravityTranscriptSpec.slashOutcomeRowRegex.matches(lines[i])) {
                    val outcome = AntigravityTranscriptSpec.slashOutcomeRowRegex.find(lines[i])!!.groupValues[1].trim()
                    i++
                    if (AntigravityTranscriptSpec.noopSlashOutcomeRegex.matches(outcome)) {
                        return
                    }
                    blocks.add(TerminalSemanticBlock.UserPromptBlock("$first → $outcome"))
                    return
                }
            }

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
                        if (next == null || isPromptBoundaryIdx(next) || lineIndents[next] <= promptIndent) {
                            break
                        }
                        combined += "\n"
                        lastWidth = 0
                        i++
                        consumed++
                        continue
                    }
                    if (isPromptBoundaryIdx(i)) break
                    combined = joinWrapped(combined, t, wrapped = lastWidth >= wrapThreshold || !combined.endsWith('\n'))
                    lastWidth = displayWidth(l)
                    i++
                    consumed++
                    if (t.endsWith("…") && !t.contains("</hc>")) {
                        truncatedByEllipsis = true
                    }
                    if (t.contains("</hc>")) {
                        val hasImmediatePromptCont = i < lines.size &&
                            !lineIsBlank[i] &&
                            lineIndents[i] > promptIndent &&
                            !isPromptBoundaryIdx(i)
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
                val t = trimmedLines[i]
                if (dialect == TerminalDialect.ANTIGRAVITY && l.contains("\uD83D\uDCCE") && AntigravityTranscriptSpec.attachmentBadgeRegex.matches(l)) {
                    val badge = AntigravityTranscriptSpec.attachmentBadgeRegex.find(l)!!.groupValues[1].trim()
                    text = "$text\n[📎 $badge]"
                    i++
                    skipExpandedAgyAttachmentChildren(l)
                    continue
                }
                if (prevTruncated && !t.startsWith("<hc")) break
                if (lineIsBlank[i]) break
                if (lineIndents[i] <= promptIndent) break
                if (isPromptBoundaryIdx(i)) break
                text = if (dialect == TerminalDialect.ANTIGRAVITY && firstChars[i] == '[' && AntigravityTranscriptSpec.artifactVerdictLineRegex.matches(t)) {
                    "$text\n$t"
                } else {
                    joinWrapped(text, t, lastWidth >= wrapThreshold)
                }
                lastWidth = displayWidth(l)
                prevTruncated = t.endsWith("…")
                i++
            }
            blocks.add(TerminalSemanticBlock.UserPromptBlock(text))
        }

        // ---- Antigravity (`agy`) specific blocks ----

        private fun isAgyLoneListMarkerStart(idx: Int): Boolean {
            if (idx + 1 >= lines.size) return false
            val cur = lines[idx]
            val isLone = AntigravityTranscriptSpec.loneOrderedMarkerRegex.matches(cur) ||
                AntigravityTranscriptSpec.loneUnorderedMarkerRegex.matches(cur)
            if (!isLone) return false
            if (lineIsBlank[idx + 1]) return false
            if (lineIndents[idx + 1] < lineIndents[idx]) return false
            return !lineIsPrompt[idx + 1] && !lineIsToolHead[idx + 1] && !lineIsAlert[idx + 1] && !lineIsTable[idx + 1]
        }

        private fun consumeAgyLoneListMarker() {
            val markerLine = lines[i]
            val bodyLine = lines[i + 1]
            val indent = indentOf(markerLine)
            val level = bulletIndentLevel(indent)
            val width = displayWidth(bodyLine)
            val ordMatch = AntigravityTranscriptSpec.loneOrderedMarkerRegex.find(markerLine)
            if (ordMatch != null) {
                val marker = ordMatch.groupValues[2].trim()
                startParagraph("$marker ${bodyLine.trim()}", bullet = true, indent = indent, indentLevel = level, width = width)
            } else {
                startParagraph(bodyLine.trim(), bullet = true, indent = indent, indentLevel = level, width = width)
            }
            i += 2
        }

        private fun consumeAgyCalloutLine(line: String) {
            val m = AntigravityTranscriptSpec.calloutPipeLineRegex.find(line) ?: return
            val indent = m.groupValues[1].length
            val rawBody = m.groupValues[2].trim()
            if (rawBody.isEmpty()) {
                flushParagraph()
                return
            }
            val width = displayWidth(line)
            val bulletMatch = unorderedBulletRegex.find(rawBody)
            if (bulletMatch != null) {
                flushParagraph()
                startParagraph("▎ ${bulletMatch.groupValues[2].trim()}", bullet = true, indent = indent, indentLevel = 0, width = width)
                return
            }
            val current = paraText
            if (current != null && current.startsWith("▎ ") && !paraBullet) {
                val joined = joinWrapped(current.toString(), rawBody, paraLastWidth >= wrapThreshold)
                current.setLength(0)
                current.append(joined)
                paraLastWidth = width
                return
            }
            flushParagraph()
            startParagraph("▎ $rawBody", bullet = false, indent = indent, indentLevel = 0, width = width)
        }

        private fun skipExpandedAgyAttachmentChildren(badgeLine: String) {
            val trimmedStart = badgeLine.trimStart()
            val badgeExpanded = trimmedStart.startsWith("▾") ||
                trimmedStart.startsWith("▼") ||
                AntigravityTranscriptSpec.ctrlOCollapseMarkerRegex.containsMatchIn(badgeLine)
            if (!badgeExpanded) return
            while (i < lines.size && !lineIsBlank[i]) {
                if (lineIsPrompt[i] || lineIsToolHead[i] || lineIsAlert[i]) break
                if (lineIndents[i] >= 4 || lineIsToolChild[i]) {
                    i++
                } else {
                    break
                }
            }
        }

        private fun isAgyOrphanToolStart(): Boolean {
            val firstIdx = nextNonBlank(0) ?: return false
            val first = lines[firstIdx]
            if (lineIsToolChild[firstIdx]) return true
            if (AntigravityTranscriptSpec.editDiffRowRegex.matches(first) ||
                AntigravityTranscriptSpec.moreLinesRowRegex.matches(first) ||
                AntigravityTranscriptSpec.diffFoldDividerRegex.matches(first) ||
                (lineIndents[firstIdx] >= 4 && first.trim() == "Command output:")
            ) {
                return true
            }
            return false
        }

        private fun consumeAgyOrphanToolOutput() {
            val firstIdx = nextNonBlank(0) ?: return
            i = firstIdx
            val steps = ArrayList<String>()
            val hasError = consumeExpandedAgyToolOutput(steps)
            if (steps.isNotEmpty()) {
                val label = buildToolSummaryLabel(1, hasError, running = false)
                blocks.add(TerminalSemanticBlock.ToolGroupBlock(label, steps, hasError))
            }
        }

        private fun isAgyInlineUnfencedCodeLine(idx: Int): Boolean {
            if (lineIndents[idx] < 2) return false
            val c0 = firstChars[idx]
            if (c0 != 'c' && c0 != 'l' && c0 != 'v' && c0 != 'f' && c0 != 'd' && c0 != 'a' && c0 != 'e' && c0 != 'i') return false
            val t = trimmedLines[idx]
            if (t.isEmpty() || t.any { isCjk(it) }) return false
            return agyInlineCodeStartRegex.containsMatchIn(t) && agyInlineCodeTokenRegex.containsMatchIn(t)
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
                    outLines.add(t.replace(childPrefixStripRegex, ""))
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
                    val clean = t.replace(childPrefixStripRegex, "")
                    if (clean.isNotEmpty() && steps.size < maxGroupSteps) steps.add(clean)
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

        private fun isCodexStructuredAlertStartIdx(idx: Int): Boolean {
            val c0 = firstChars[idx]
            val line = lines[idx]
            return when (c0) {
                '■' -> CodexTranscriptSpec.errorSquareRegex.matches(line)
                'ⓘ' -> CodexTranscriptSpec.safetyBlockRegex.matches(line)
                '⚠' -> CodexTranscriptSpec.warningCellRegex.matches(line)
                'W', 'w', 'D', 'd', 'A', 'a' -> CodexTranscriptSpec.approvalOverlayTitleRegex.matches(line)
                'Q', 'q' -> CodexTranscriptSpec.requestUserInputOverlayRegex.matches(line)
                else -> false
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
                    val isErr = warningErrorWordRegex.containsMatchIn(detail)
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
                                lineIndents[next] >= 2 && !CodexTranscriptSpec.finalSeparatorRegex.matches(nl) &&
                                !lineIsToolHead[next] && !lineIsPrompt[next]
                            if (continuesOverlay) {
                                i = next
                                continue
                            }
                            break
                        }
                        if (lineIsPrompt[i] || lineIsToolHead[i] || CodexTranscriptSpec.finalSeparatorRegex.matches(l)) break
                        val cleanedRow = if (promptMenuOptionRegex.matches(l)) {
                            l.replaceFirst(menuCursorCharRegex, " ").trim()
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

        /**
         * `history_cell/messages.rs`: In `Ctrl+T` (`TranscriptOverlay`), `ReasoningSummaryCell`
         * (`transcript_only = true`) becomes visible at column 0 (`• ...`) with `dim().italic()` SGR
         * and/or a bold summary title (`• **<Title>**`) followed by subsequent cells in the same turn.
         *
         * In Compact Mode (`!isExpandedMode` and no `dim+italic` SGR), `ReasoningSummaryCell` is NEVER
         * rendered on screen, so `• **<Heading>**` is guaranteed to be a formal `AgentMarkdownCell` heading.
         */
        private fun isCodexReasoningCellStart(idx: Int): Boolean {
            if (dialect != TerminalDialect.CODEX || idx !in lines.indices) return false
            val style = styles[idx]
            if (!isExpandedMode && !(style.isDim && style.isItalic)) return false
            val line = lines[idx]
            val trimmed = line.trim()
            if (!line.startsWith("• ") ||
                lineIsToolHead[idx] ||
                isCodexStructuredAlertStart(line) ||
                CodexTranscriptSpec.proposedPlanHeadRegex.matches(trimmed) ||
                CodexTranscriptSpec.planUpdateHeadRegex.matches(trimmed) ||
                CodexTranscriptSpec.delegatedMessageHeadRegex.matches(trimmed) ||
                CodexTranscriptSpec.questionsResultHeadRegex.matches(trimmed)
            ) {
                return false
            }
            if (style.isDim && style.isItalic) return true
            val isBoldReasoningHead = CodexTranscriptSpec.reasoningBoldHeaderRegex.matches(trimmed)
            if (!isBoldReasoningHead && !(style.isDim || style.isItalic)) return false
            return isFollowedByCodexCellInSameTurn(idx)
        }

        private fun isFollowedByCodexCellInSameTurn(fromIdx: Int): Boolean =
            fromIdx in hasLaterCodexCellInTurn.indices && hasLaterCodexCellInTurn[fromIdx]

        private fun consumeCodexToolGroup(orphanStart: Boolean) {
            val steps = ArrayList<String>()
            var headCount = if (orphanStart) 1 else 0
            var hasError = false
            var running = false
            var childPreviewLines = 0
            var omittedChildLines = 0

            fun flushOmittedChildren() {
                if (omittedChildLines > 0 && steps.size < maxGroupSteps) {
                    steps.add("    … (已省略其余 $omittedChildLines 行)")
                }
                omittedChildLines = 0
                childPreviewLines = 0
            }

            while (i < lines.size) {
                val line = lines[i]
                val trimmed = line.trim()
                val indent = lineIndents[i]

                when {
                    trimmed.isEmpty() -> {
                        val next = nextNonBlank(i + 1)
                        val nextLine = next?.let { lines[it] }
                        val nextTrim = nextLine?.trim().orEmpty()
                        if (next != null && nextLine != null && (
                                lineIsToolHead[next] ||
                                    isCodexReasoningCellStart(next) ||
                                    CodexTranscriptSpec.commandContPipeRegex.matches(nextLine) ||
                                    (lineIsToolChild[next] && !CodexTranscriptSpec.threadRecapHeadRegex.matches(nextLine)) ||
                                    CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(nextTrim) ||
                                    // In `codex-rs/tui`, every top-level cell starts at indent 0 (`• `) or 2;
                                    // 4+ space indented lines or dim SGR lines after an activity header always belong to that activity cell.
                                    (headCount > 0 && !nextLine.startsWith("• ") &&
                                        !lineIsPrompt[next] && !isCodexStructuredAlertStart(nextLine) &&
                                        !CodexTranscriptSpec.finalSeparatorRegex.matches(nextLine) &&
                                        (lineIndents[next] >= 4 || styles[next].isDim))
                                )
                        ) {
                            i = next
                        } else {
                            break
                        }
                    }

                    isCodexReasoningCellStart(i) -> {
                        flushOmittedChildren()
                        headCount++
                        val leadClean = cleanInline(trimmed.removePrefix("• ").trim(), dialect)
                        if (steps.size < maxGroupSteps) steps.add("思考：$leadClean")
                        i++
                        while (i < lines.size) {
                            val rl = lines[i]
                            val rt = rl.trim()
                            if (rt.isEmpty()) {
                                val next = nextNonBlank(i + 1)
                                if (next != null && lineIndents[next] >= 2 &&
                                    !lines[next].startsWith("• ") &&
                                    !lineIsToolHead[next] &&
                                    !lineIsToolChild[next] &&
                                    !CodexTranscriptSpec.commandContPipeRegex.matches(lines[next]) &&
                                    !lineIsPrompt[next] &&
                                    !isCodexStructuredAlertStart(lines[next]) &&
                                    !CodexTranscriptSpec.finalSeparatorRegex.matches(lines[next]) &&
                                    ((styles[next].isDim && styles[next].isItalic) || isFollowedByCodexCellInSameTurn(next))
                                ) {
                                    i = next
                                    continue
                                }
                                break
                            }
                            if (lineIndents[i] >= 2 &&
                                !rl.startsWith("• ") &&
                                !lineIsToolHead[i] &&
                                !lineIsToolChild[i] &&
                                !CodexTranscriptSpec.commandContPipeRegex.matches(rl) &&
                                !lineIsPrompt[i] &&
                                !isCodexStructuredAlertStart(rl) &&
                                !CodexTranscriptSpec.finalSeparatorRegex.matches(rl)
                            ) {
                                if (childPreviewLines < maxCodexChildPreviewLines && steps.size < maxGroupSteps) {
                                    steps.add("  ${cleanInline(rt, dialect).take(maxStepChars)}")
                                    childPreviewLines++
                                } else {
                                    omittedChildLines++
                                }
                                i++
                            } else {
                                break
                            }
                        }
                        flushOmittedChildren()
                    }

                    lineIsToolHead[i] -> {
                        flushOmittedChildren()
                        headCount++
                        val clean = cleanStepText(cleanToolHead(trimmed))
                        running = spinnerRegex.matches(line) ||
                            CodexTranscriptSpec.liveStatusIndicatorRegex.matches(line) ||
                            codexRunningVerbRegex.containsMatchIn(clean)
                        if (stepErrorRegex.containsMatchIn(clean) && !zeroFailures(clean)) hasError = true
                        if (steps.size < maxGroupSteps) steps.add(clean)
                        i++
                    }

                    CodexTranscriptSpec.commandContPipeRegex.matches(line) -> {
                        if (headCount == 0) headCount = 1
                        val cmdCont = CodexTranscriptSpec.commandContPipeRegex.find(line)?.groupValues?.get(1)?.trim().orEmpty()
                        if (cmdCont.isNotEmpty() && steps.size < maxGroupSteps) {
                            steps.add("  │ ${cmdCont.take(maxStepChars)}")
                        }
                        i++
                    }

                    CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(trimmed) -> {
                        i++
                    }

                    lineIsToolChild[i] && !CodexTranscriptSpec.threadRecapHeadRegex.matches(line) -> {
                        flushOmittedChildren()
                        if (headCount == 0) headCount = 1
                        val clean = cleanStepText(trimmed.replace(childPrefixStripRegex, ""))
                        if (clean.isNotEmpty() && !CodexTranscriptSpec.disclosureOrEllipsisRegex.matches(clean)) {
                            if (stepErrorRegex.containsMatchIn(clean) && !zeroFailures(clean)) hasError = true
                            if (steps.size < maxGroupSteps) steps.add("  ${clean.take(maxStepChars)}")
                        }
                        i++
                    }

                    (headCount > 0 && !line.startsWith("• ") && !lineIsPrompt[i] &&
                        !lineIsAlert[i] && !isCodexStructuredAlertStart(line) &&
                        !CodexTranscriptSpec.finalSeparatorRegex.matches(line) &&
                        (indent >= 4 || styles[i].isDim || (indent == 0 && !fenceRegex.matches(line) && !isTableLine(line)))) -> {
                        if (childPreviewLines < maxCodexChildPreviewLines && steps.size < maxGroupSteps) {
                            steps.add("    ${trimmed.take(maxStepChars)}")
                            childPreviewLines++
                        } else {
                            omittedChildLines++
                        }
                        i++
                    }

                    else -> break
                }
            }
            flushOmittedChildren()

            if (steps.isEmpty()) {
                i++
                return
            }
            val finalHeads = headCount.coerceAtLeast(1)
            val label = buildToolSummaryLabel(finalHeads, hasError, running)
            blocks.add(TerminalSemanticBlock.ToolGroupBlock(label, steps, hasError))
        }

        // ---- Antigravity / Pi / Claude / Generic tool groups ----

        private fun parenDelta(s: String): Int {
            var delta = 0
            for (ch in s) {
                when (ch) {
                    '(' -> delta++
                    ')' -> delta--
                }
            }
            return delta
        }

        private fun consumeWrappedAgyToolCallHead(firstTrimmed: String): String {
            var depth = parenDelta(firstTrimmed)
            if (depth <= 0) return firstTrimmed
            val sb = StringBuilder(firstTrimmed)
            var wrappedCount = 0
            while (i < lines.size && depth > 0 && wrappedCount < 16) {
                if (lineIsBlank[i] || lineIsToolChild[i] || lineIsToolHead[i] || lineIsPrompt[i] || lineIsAlert[i]) {
                    break
                }
                val cont = lines[i].trim()
                val joined = joinWrapped(sb.toString(), cont, wrapped = true)
                sb.setLength(0)
                sb.append(joined)
                depth += parenDelta(cont)
                wrappedCount++
                i++
            }
            return sb.toString()
        }

        private fun consumeToolGroup() {
            val steps = ArrayList<String>()
            var headCount = 0
            var pendingAgySummaryCount = 0
            var activeAgyDetailRunCount = 0
            var hasError = false
            var running = false
            var headIndent = lineIndents[i]

            fun flushAgySummaryAndDetailCounts() {
                if (pendingAgySummaryCount > 0 || activeAgyDetailRunCount > 0) {
                    headCount += maxOf(pendingAgySummaryCount, activeAgyDetailRunCount)
                    pendingAgySummaryCount = 0
                    activeAgyDetailRunCount = 0
                }
            }

            while (i < lines.size) {
                val line = lines[i]
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> {
                        val next = nextNonBlank(i + 1)
                        if (next != null && (lineIsToolHead[next] || lineIsToolChild[next] || moreActionsRegex.matches(lines[next]))) {
                            i = next
                        } else {
                            break
                        }
                    }

                    moreActionsRegex.matches(line) -> {
                        flushAgySummaryAndDetailCounts()
                        val m = moreActionsRegex.find(line)!!
                        val extraCount = m.groupValues[1].toIntOrNull() ?: 1
                        val rest = m.groupValues[2].trim().trimEnd('…', '.').trim()
                        headCount += extraCount
                        if (stepErrorRegex.containsMatchIn(rest) && !zeroFailures(rest)) hasError = true
                        if (steps.size < maxGroupSteps) {
                            val stepDesc = if (rest.isNotEmpty()) "折叠的 $extraCount 项操作 $rest" else "折叠的 $extraCount 项操作"
                            steps.add(stepDesc)
                        }
                        i++
                    }

                    dialect == TerminalDialect.PI && piThoughtHeaderRegex.matches(line) -> {
                        val m = piThoughtHeaderRegex.find(line)!!
                        headCount++
                        if (steps.size < maxGroupSteps) {
                            steps.add("Thought for ${m.groupValues[1].trim()} (${m.groupValues[2].trim()})")
                        }
                        i++
                        // In Pi, `Thought for Xs · N more lines · click to show more` is followed by
                        // 1-2 short internal-monologue preview paragraphs before the next tool call.
                        consumePiThoughtPreviewIfFollowedByTool(steps)
                    }

                    lineIsToolHead[i] -> {
                        headIndent = lineIndents[i]
                        val isAgyThought = dialect == TerminalDialect.ANTIGRAVITY &&
                            AntigravityTranscriptSpec.thoughtRowRegex.matches(line)
                        val isExpandedAgyThought = isAgyThought &&
                            (trimmed.startsWith("▾") || trimmed.startsWith("▼") ||
                                AntigravityTranscriptSpec.ctrlOCollapseMarkerRegex.containsMatchIn(line))
                        val isAgyDetailCall = dialect == TerminalDialect.ANTIGRAVITY &&
                            !isAgyThought &&
                            AntigravityTranscriptSpec.expandedToolCallHeadRegex.matches(line)

                        i++
                        val fullTrimmedHead = if (isAgyDetailCall) {
                            consumeWrappedAgyToolCallHead(trimmed)
                        } else {
                            trimmed
                        }
                        val clean = cleanStepText(cleanToolHead(fullTrimmedHead))

                        if (dialect == TerminalDialect.ANTIGRAVITY) {
                            when {
                                isAgyThought || spinnerRegex.matches(line) -> {
                                    flushAgySummaryAndDetailCounts()
                                    headCount += 1
                                }
                                isAgyDetailCall -> {
                                    activeAgyDetailRunCount += 1
                                }
                                else -> {
                                    if (activeAgyDetailRunCount > 0) {
                                        flushAgySummaryAndDetailCounts()
                                    }
                                    pendingAgySummaryCount += AntigravityTranscriptSpec.countActionsInSummary(clean)
                                }
                            }
                        } else {
                            headCount += 1
                        }

                        running = spinnerRegex.matches(line) ||
                            AntigravityTranscriptSpec.modelRetryStatusRegex.matches(line) ||
                            clean.startsWith("Running") || clean.startsWith("Generating") ||
                            clean.startsWith("Loading") || clean.startsWith("Thinking") || clean.endsWith("…")
                        val failed = if (dialect == TerminalDialect.ANTIGRAVITY) {
                            AntigravityTranscriptSpec.hasRealStepFailure(fullTrimmedHead) && !zeroFailures(clean)
                        } else {
                            stepErrorRegex.containsMatchIn(clean) && !zeroFailures(clean)
                        }
                        if (failed) hasError = true
                        if (steps.size < maxGroupSteps) steps.add(clean.take(maxStepChars))

                        val isExpandedAgyTool = dialect == TerminalDialect.ANTIGRAVITY &&
                            !isExpandedAgyThought &&
                            (isAgyDetailCall ||
                                AntigravityTranscriptSpec.expandedActionRowRegex.matches(line) ||
                                (i < lines.size && (lineIsToolChild[i] ||
                                    AntigravityTranscriptSpec.editDiffRowRegex.matches(lines[i]) ||
                                    AntigravityTranscriptSpec.moreLinesRowRegex.matches(lines[i]))))

                        if (isExpandedAgyThought) {
                            consumeExpandedAgyThoughtBody(steps)
                        } else if (isExpandedAgyTool) {
                            if (consumeExpandedAgyToolOutput(steps)) hasError = true
                        }
                    }

                    lineIsToolChild[i] -> {
                        if (headCount == 0 && pendingAgySummaryCount == 0 && activeAgyDetailRunCount == 0) {
                            headCount = 1
                        }
                        val clean = cleanStepText(trimmed.replace(childPrefixStripRegex, ""))
                        val failed = if (dialect == TerminalDialect.ANTIGRAVITY) {
                            AntigravityTranscriptSpec.hasRealStepFailure(clean) && !zeroFailures(clean)
                        } else {
                            stepErrorRegex.containsMatchIn(clean) && !zeroFailures(clean)
                        }
                        if (failed) hasError = true
                        if (clean.isNotEmpty() && steps.size < maxGroupSteps) steps.add("  ${clean.take(maxStepChars)}")
                        i++
                    }

                    // CRITICAL: In Antigravity (`agy`), ALL agent prose is indented by 2 spaces (`  ...`)
                    // right after `▸ Thought for ...` or `● Ran ...` without a blank line.
                    // Never swallow 2-space indented prose into `ToolGroupBlock` in ANTIGRAVITY or PI!
                    dialect != TerminalDialect.ANTIGRAVITY &&
                        dialect != TerminalDialect.PI &&
                        headCount > 0 &&
                        lineIndents[i] > headIndent + 1 &&
                        !lineIsPrompt[i] &&
                        !lineIsAlert[i] -> {
                        val clean = cleanStepText(trimmed)
                        if (stepErrorRegex.containsMatchIn(clean) && !zeroFailures(clean)) hasError = true
                        if (steps.size < maxGroupSteps) steps.add("  ${clean.take(maxStepChars)}")
                        i++
                    }

                    else -> break
                }
            }
            flushAgySummaryAndDetailCounts()

            if (steps.isEmpty()) {
                i++
                return
            }
            val label = buildToolSummaryLabel(headCount.coerceAtLeast(1), hasError, running)
            blocks.add(TerminalSemanticBlock.ToolGroupBlock(label, steps, hasError))
        }

        private fun hasLaterAgyToolInSameTurn(fromIdx: Int): Boolean =
            fromIdx in hasLaterAgyToolOnOrAfter.indices && hasLaterAgyToolOnOrAfter[fromIdx]

        private fun findAgyParagraphEnd(startIdx: Int): Int {
            var j = startIdx
            while (j < lines.size && !lineIsBlank[j]) {
                if (lineIsPrompt[j] || lineIsToolHead[j] || lineIsToolChild[j] ||
                    lineIsMoreActions[j] || lineIsAlert[j]
                ) {
                    break
                }
                j++
            }
            return j
        }

        private fun paragraphWrapsToColZero(startIdx: Int): Boolean {
            val endIdx = findAgyParagraphEnd(startIdx)
            for (k in startIdx + 1 until endIdx) {
                if (lineIndents[k] == 0) return true
            }
            return false
        }

        private fun paragraphTouchesToolDirectly(startIdx: Int): Boolean {
            val endIdx = findAgyParagraphEnd(startIdx)
            return endIdx < lines.size &&
                !lineIsBlank[endIdx] &&
                (lineIsToolHead[endIdx] || lineIsMoreActions[endIdx])
        }

        private fun hasLaterAgyParagraphInSameTurn(fromIdx: Int): Boolean {
            var j = fromIdx
            var sawBlank = false
            while (j < lines.size) {
                if (lineIsBlank[j]) {
                    sawBlank = true
                    j++
                    continue
                }
                if (lineIsPrompt[j] || lineIsAlert[j] || lineIsToolHead[j] || lineIsToolChild[j] || lineIsMoreActions[j]) return false
                if (sawBlank) return true
                j++
            }
            return false
        }

        /**
         * Consumes multi-paragraph expanded `▾ Thought for ... (ctrl+o to collapse)` bodies in `Antigravity`,
         * handling column-0 soft-wrapped lines and stopping cleanly before interim or final assistant prose.
         */
        private fun consumeExpandedAgyThoughtBody(steps: MutableList<String>) {
            var previewCount = 0
            var omittedCount = 0
            var sawStyledLine = false
            var lastWasThoughtLine = false

            while (i < lines.size) {
                val t = trimmedLines[i]
                if (t.isEmpty()) {
                    lastWasThoughtLine = false
                    val next = nextNonBlank(i + 1) ?: break
                    val nt = trimmedLines[next]
                    if (lineIndents[next] < 2 || lineIsPrompt[next] || lineIsToolHead[next] ||
                        lineIsToolChild[next] || lineIsMoreActions[next] || lineIsAlert[next]
                    ) {
                        break
                    }
                    val nextStyled = styles[next].isDim || styles[next].isItalic
                    val nextParaEnd = findAgyParagraphEnd(next)
                    val continuesThought = when {
                        nextStyled -> true
                        paragraphWrapsToColZero(next) || paragraphTouchesToolDirectly(next) -> true
                        sawStyledLine -> hasLaterAgyToolInSameTurn(next)
                        else -> AntigravityTranscriptSpec.englishMonologueStartRegex.matches(nt) &&
                            (hasLaterAgyToolInSameTurn(next) || hasLaterAgyParagraphInSameTurn(nextParaEnd))
                    }
                    if (continuesThought) {
                        i = next
                        continue
                    }
                    break
                }

                if (lineIsPrompt[i] || lineIsToolHead[i] || lineIsToolChild[i] ||
                    lineIsMoreActions[i] || lineIsAlert[i]
                ) {
                    break
                }

                val indent = lineIndents[i]
                // In `agy`, formal prose never touches column 0; `indent == 0` right after a thought line
                // is a soft-wrapped continuation of that thought line.
                if (indent == 0) {
                    if (!lastWasThoughtLine) break
                    if (omittedCount == 0 && steps.isNotEmpty()) {
                        val mergedLine = joinWrapped(steps.last(), t, wrapped = true)
                        steps[steps.lastIndex] = mergedLine.take(maxStepChars)
                    }
                    i++
                    continue
                }
                if (indent < 2) break

                if (styles[i].isDim || styles[i].isItalic) sawStyledLine = true
                if (previewCount < maxAgyChildPreviewLines && steps.size < maxGroupSteps) {
                    steps.add("  ${t.take(maxStepChars)}")
                } else {
                    omittedCount++
                }
                previewCount++
                lastWasThoughtLine = true
                i++
            }
            if (omittedCount > 0 && steps.size < maxGroupSteps) {
                steps.add("  … (已省略其余 $omittedCount 行)")
            }
        }

        /**
         * Consumes expanded tool output (`  ⎿  ...`, blank line + `     Command output:`, col-0 wrapped
         * stdout lines, numbered `● Edit` context/diffs, `… and N more lines`, and `↕ ···` fold dividers)
         * in `Antigravity` without swallowing 2-space interim or final assistant prose.
         */
        private fun consumeExpandedAgyToolOutput(steps: MutableList<String>): Boolean {
            var previewCount = 0
            var omittedCount = 0
            var sawError = false
            var lastWasOutputLine = false

            while (i < lines.size) {
                val l = lines[i]
                val t = trimmedLines[i]
                if (t.isEmpty()) {
                    lastWasOutputLine = false
                    val next = nextNonBlank(i + 1) ?: break
                    val nl = lines[next]
                    val nc0 = firstChars[next]
                    if (lineIndents[next] < 2 || lineIsPrompt[next] || lineIsToolHead[next] ||
                        lineIsToolChild[next] || lineIsMoreActions[next] || lineIsAlert[next]
                    ) {
                        break
                    }
                    val continuesToolOutput = styles[next].isDim ||
                        (lineIndents[next] >= 4 && !(nc0 in "•-*·◦▪‣" && unorderedBulletRegex.matches(nl)) &&
                            !((nc0 in '0'..'9' || nc0 == '(' || nc0 == '（') && orderedBulletRegex.matches(nl))) ||
                        (nc0 in '0'..'9' && AntigravityTranscriptSpec.editDiffRowRegex.matches(nl)) ||
                        ((nc0 == '…' || nc0 == '.') && AntigravityTranscriptSpec.moreLinesRowRegex.matches(nl)) ||
                        lineIsDiff[next] ||
                        (nc0 == '↕' && AntigravityTranscriptSpec.diffFoldDividerRegex.matches(nl))
                    if (continuesToolOutput) {
                        i = next
                        continue
                    }
                    break
                }

                val c0 = firstChars[i]
                if (lineIsPrompt[i] || lineIsToolHead[i] ||
                    lineIsMoreActions[i] || lineIsAlert[i] ||
                    (c0 == '│' && AntigravityTranscriptSpec.calloutPipeLineRegex.matches(l)) ||
                    isAgyPipeTableStart(i)
                ) {
                    break
                }

                val indent = lineIndents[i]
                if (indent == 0) {
                    if (!lastWasOutputLine) break
                    if (omittedCount == 0 && steps.isNotEmpty()) {
                        val mergedLine = joinWrapped(steps.last(), t, wrapped = true)
                        steps[steps.lastIndex] = mergedLine.take(maxStepChars)
                    }
                    i++
                    continue
                }
                if (indent < 2) break

                val isExplicitToolLine = styles[i].isDim ||
                    lineIsToolChild[i] ||
                    indent >= 4 ||
                    (c0 in '0'..'9' && AntigravityTranscriptSpec.editDiffRowRegex.matches(l)) ||
                    ((c0 == '…' || c0 == '.') && AntigravityTranscriptSpec.moreLinesRowRegex.matches(l)) ||
                    lineIsDiff[i] ||
                    (c0 == '↕' && AntigravityTranscriptSpec.diffFoldDividerRegex.matches(l))

                if (!isExplicitToolLine) {
                    break
                }

                val clean = if (c0 == '└' || c0 == '├' || c0 == '⎿' || c0 == '↳') {
                    t.replace(childPrefixStripRegex, "")
                } else {
                    t
                }
                if (AntigravityTranscriptSpec.hasRealStepFailure(clean) && !zeroFailures(clean)) {
                    sawError = true
                }
                if (clean.isNotEmpty()) {
                    if (previewCount < maxAgyChildPreviewLines && steps.size < maxGroupSteps) {
                        steps.add("  ${clean.take(maxStepChars)}")
                    } else {
                        omittedCount++
                    }
                    previewCount++
                }
                lastWasOutputLine = true
                i++
            }
            if (omittedCount > 0 && steps.size < maxGroupSteps) {
                steps.add("  … (已省略其余 $omittedCount 行)")
            }
            return sawError
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
                if (lineIsBlank[probe]) {
                    probe++
                    continue
                }
                if (lineIsPrompt[probe] || lineIsAlert[probe] || fenceRegex.matches(pl) || isTableLine(pl)) {
                    followedByTool = false
                    break
                }
                if (lineIsToolHead[probe] || lineIsToolChild[probe]) {
                    followedByTool = true
                    break
                }
                nonBlankCount++
                probe++
            }
            if (followedByTool && probe > i) {
                while (i < probe) {
                    val t = lines[i].trim()
                    if (t.isNotEmpty() && steps.size < maxGroupSteps) {
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
                if (lineIsToolChild[i] || l.trimStart().startsWith("├") || l.trimStart().startsWith("└")) {
                    val clean = l.trim().replace(piTodoChildStripRegex, "")
                    if (steps.size < maxGroupSteps) steps.add(clean)
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
                .replace(cleanHeadPipeRegex, "")
                .replace(cleanHeadBulletRegex, "")
                .trim()

        private fun cleanStepText(text: String): String {
            var s = text
            if (s.endsWith(')')) {
                if ("ctrl+o" in s || "Ctrl+O" in s) s = s.replace(AntigravityTranscriptSpec.ctrlOSuffixRegex, "")
                if ("to expand)" in s) s = s.replace(ctrlExpandSuffixRegex, "")
            }
            if ("click to show more" in s) {
                s = s.replace(clickShowMoreSuffixRegex, "")
            }
            return s.trim()
        }

        private fun zeroFailures(text: String): Boolean =
            zeroFailPosRegex.containsMatchIn(text) &&
                !zeroFailNegRegex.containsMatchIn(text)

        // ---- alerts ----

        private fun isAlertLine(line: String): Boolean = computeIsAlertLine(line)

        private fun computeIsAlertLineIdx(idx: Int): Boolean {
            val c0 = firstChars[idx]
            val line = lines[idx]
            val trimmed = trimmedLines[idx]
            if (c0 == '⚠') {
                if (agyWarningErrorRegex.matches(line)) return true
                if (dialect == TerminalDialect.ANTIGRAVITY && AntigravityTranscriptSpec.warningBannerRegex.matches(line)) return true
            }
            if ((c0 in "●⏺•✗✘EeFfPp") && (':' in trimmed || '：' in trimmed) && errorRegex.matches(line)) {
                return true
            }
            if (dialect == TerminalDialect.ANTIGRAVITY) {
                if (AntigravityTranscriptSpec.isPermissionTitle(trimmed)) return true
                if (AntigravityTranscriptSpec.isSubagentQuestion(line)) return true
            }
            if (c0 == 'T' && trimmed.startsWith("Traceback (") && tracebackRegex.matches(line)) {
                return true
            }
            if (("denied" in trimmed || "Denied" in trimmed || "DENIED" in trimmed || "拒绝访问" in trimmed) &&
                permissionErrorRegex.containsMatchIn(line)
            ) {
                return true
            }
            if (mayContainBlockingPhrase(trimmed) && blockingPromptRegex.containsMatchIn(line)) {
                return true
            }
            return false
        }

        private fun mayContainBlockingPhrase(trimmed: String): Boolean =
            "permission" in trimmed || "Permission" in trimmed ||
                "approval" in trimmed || "Approval" in trimmed ||
                "would you like" in trimmed || "Would you like" in trimmed ||
                "do you want" in trimmed || "Do you want" in trimmed ||
                "allow " in trimmed || "Allow " in trimmed ||
                "waiting for" in trimmed || "Waiting for" in trimmed ||
                "需要" in trimmed || "是否" in trimmed

        private fun computeIsAlertLine(line: String): Boolean =
            errorRegex.matches(line) ||
                agyWarningErrorRegex.matches(line) ||
                (dialect == TerminalDialect.ANTIGRAVITY && (
                    AntigravityTranscriptSpec.warningBannerRegex.matches(line) ||
                        AntigravityTranscriptSpec.isSubagentQuestion(line) ||
                        AntigravityTranscriptSpec.isPermissionTitle(line.trim())
                    )) ||
                tracebackRegex.matches(line) ||
                permissionErrorRegex.containsMatchIn(line) ||
                blockingPromptRegex.containsMatchIn(line)

        private fun consumeAlerts() {
            while (i < lines.size && !lineIsBlank[i] && lineIsAlert[i]) {
                val rawAlert = trimmedLines[i]
                // Multi-line `⚠ UNAUTHENTICATED ...` + `Error ID: ...` in Antigravity
                if (firstChars[i] == '⚠' && agyWarningErrorRegex.matches(rawAlert)) {
                    val m = agyWarningErrorRegex.find(rawAlert)!!
                    val sb = StringBuilder()
                    sb.append(m.groupValues[1].trim())
                    val firstRest = m.groupValues[2].trim()
                    if (firstRest.isNotEmpty()) sb.append(": ").append(firstRest)
                    i++
                    while (i < lines.size && !lineIsBlank[i] &&
                        !lineIsPrompt[i] && !lineIsToolHead[i]
                    ) {
                        val cont = trimmedLines[i]
                        sb.append(if (cont.startsWith("Error ID:")) "\n$cont" else " $cont")
                        i++
                    }
                    blocks.add(TerminalSemanticBlock.UrgentAlertBlock("运行异常", sb.toString().trim(), true))
                    continue
                }

                // General `⚠ ...` warning in Antigravity (e.g. `⚠ The <plugin> plugin needs you to sign in...`)
                if (dialect == TerminalDialect.ANTIGRAVITY && firstChars[i] == '⚠' &&
                    AntigravityTranscriptSpec.warningBannerRegex.matches(rawAlert)
                ) {
                    val detailSb = StringBuilder(AntigravityTranscriptSpec.warningBannerRegex.find(rawAlert)!!.groupValues[1].trim())
                    i++
                    while (i < lines.size && !lineIsBlank[i] && !lineIsPrompt[i] && !lineIsToolHead[i]) {
                        detailSb.append(" ").append(trimmedLines[i])
                        i++
                    }
                    val detailStr = detailSb.toString().trim()
                    val needsAction = detailStr.contains("sign in", ignoreCase = true)
                    blocks.add(
                        TerminalSemanticBlock.UrgentAlertBlock(
                            title = if (needsAction) "需要你的授权" else "提示",
                            detail = detailStr,
                            isError = false
                        )
                    )
                    continue
                }

                // Interactive permission / question picker in Antigravity (`Allow calling this tool?` + numbered options)
                if (dialect == TerminalDialect.ANTIGRAVITY && (
                        AntigravityTranscriptSpec.isPermissionTitle(rawAlert) ||
                            AntigravityTranscriptSpec.isSubagentQuestion(rawAlert)
                        )
                ) {
                    val isQuestion = AntigravityTranscriptSpec.isSubagentQuestion(rawAlert)
                    val detailLines = ArrayList<String>()
                    detailLines.add(rawAlert)
                    i++
                    var blanks = 0
                    while (i < lines.size) {
                        val l = lines[i]
                        val t = trimmedLines[i]
                        if (t.isEmpty()) {
                            blanks++
                            val next = nextNonBlank(i + 1)
                            if (next == null || blanks > 2) break
                            val nl = lines[next]
                            if (menuOptionRegex.matches(nl) || promptMenuOptionRegex.matches(nl) ||
                                (lineIndents[next] >= 2 && !lineIsPrompt[next] && !lineIsToolHead[next])
                            ) {
                                i = next
                                continue
                            }
                            break
                        }
                        if (lineIsPrompt[i] || lineIsToolHead[i]) break
                        detailLines.add(
                            if (promptMenuOptionRegex.matches(l)) l.replaceFirst(menuCursorCharRegex, " ").trim() else t
                        )
                        i++
                    }
                    blocks.add(
                        TerminalSemanticBlock.UrgentAlertBlock(
                            title = if (isQuestion) "子代理待回答" else "需要你的授权",
                            detail = detailLines.joinToString("\n"),
                            isError = false
                        )
                    )
                    continue
                }

                val (title, detail, isError) = classifyAlert(rawAlert)
                i++

                // In Pi, `Error: Connection error.` may be followed after a blank line by `Aborted after N retry attempts`.
                var fullDetail = detail
                val nextIdx = nextNonBlank(i)
                if (nextIdx != null && trimmedLines[nextIdx].startsWith("Aborted after ")) {
                    fullDetail = listOf(detail, trimmedLines[nextIdx]).filter { it.isNotBlank() }.joinToString(" · ")
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
            val c0 = firstChars[idx]
            if ((c0 == '─' || c0 == '━' || c0 == '═') && CodexTranscriptSpec.gridTableRuleRegex.matches(lines[idx])) return true
            if (idx + 1 < lines.size && !lineIsBlank[idx] &&
                !lineIsPrompt[idx] && !lineIsToolHead[idx] &&
                (firstChars[idx + 1] == '─' || firstChars[idx + 1] == '━' || firstChars[idx + 1] == '═') &&
                CodexTranscriptSpec.gridTableRuleRegex.matches(lines[idx + 1])
            ) {
                return true
            }
            return false
        }

        private fun consumeCodexSpacedTable() {
            val run = ArrayList<String>()
            while (i < lines.size) {
                if (lineIsBlank[i] || lineIsPrompt[i] || lineIsToolHead[i]) break
                run.add(lines[i])
                i++
            }
            addCode(run.joinToString("\n"), kindLabel = "表格")
        }

        /**
         * Antigravity renders Markdown tables with `│` column dividers and `───┼───` row dividers
         * (no outer box borders), including 2-column tables that have only a single `│` per row.
         */
        private fun isAgyPipeTableStart(idx: Int): Boolean {
            val c0 = firstChars[idx]
            if ((c0 == '─' || c0 == '━' || c0 == '═') && hasBoxCorners[idx] && pipeCrossSeparatorRegex.matches(lines[idx])) return true
            if (boxPipeCounts[idx] > 0 && !(dialect == TerminalDialect.PI && piPipeSummaryRegex.matches(lines[idx])) &&
                idx + 1 < lines.size &&
                (firstChars[idx + 1] == '─' || firstChars[idx + 1] == '━' || firstChars[idx + 1] == '═') &&
                hasBoxCorners[idx + 1] && pipeCrossSeparatorRegex.matches(lines[idx + 1])
            ) {
                return true
            }
            return false
        }

        private fun consumeAgyPipeTable() {
            val run = ArrayList<String>()
            while (i < lines.size) {
                if (lineIsBlank[i]) break
                val l = lines[i]
                if ((hasBoxCorners[i] && pipeCrossSeparatorRegex.matches(l)) ||
                    (boxPipeCounts[i] > 0 && !(dialect == TerminalDialect.PI && piPipeSummaryRegex.matches(l)))
                ) {
                    run.add(l)
                    i++
                } else {
                    break
                }
            }
            addCode(run.joinToString("\n"), kindLabel = "表格")
        }

        private fun computeIsTableLineIdx(idx: Int): Boolean {
            val c0 = firstChars[idx]
            val pipes = boxPipeCounts[idx]
            val corners = hasBoxCorners[idx]
            if (pipes == 0 && !corners && c0 != '+' && c0 != '|' && c0 != '─' && c0 != '━' && c0 != '═') {
                return false
            }
            val line = lines[idx]
            val t = trimmedLines[idx]
            if (c0 == '│' && piPipeSummaryRegex.matches(line)) return false
            if (c0 == '+' && asciiTableBorderRegex.matches(t)) return true
            if ((c0 == '─' || c0 == '━' || c0 == '═') && corners && pipeCrossSeparatorRegex.matches(t)) return true
            if (corners && tableBorderRegex.matches(t)) return true
            if (pipes >= 1 && corners) return true
            if (pipes >= 2) return true
            if (c0 == '|' && t.endsWith('|') && t.count { it == '|' } >= 3) return true
            return false
        }

        private fun isTableLine(line: String): Boolean {
            val t = line.trim()
            if (t.isEmpty()) return false
            if (piPipeSummaryRegex.matches(line)) return false
            if (asciiTableBorderRegex.matches(t)) return true
            if (pipeCrossSeparatorRegex.matches(t)) return true
            if (tableBorderRegex.matches(t) && hasBoxDrawingCornerOrCross(t)) return true
            val pipes = line.count { it == '│' || it == '┃' || it == '║' }
            if (pipes >= 1 && hasBoxDrawingCornerOrCross(line)) return true
            if (pipes >= 2) return true
            if (markdownTableRegex.matches(t) && t.count { it == '|' } >= 3) return true
            return false
        }

        private fun computeIsDiffLineIdx(idx: Int): Boolean {
            val c0 = firstChars[idx]
            val ind = lineIndents[idx]
            if (c0 !in '0'..'9' && (ind != 0 || (c0 != '@' && c0 != 'd' && c0 != '+' && c0 != '-' && c0 != 'i'))) {
                return false
            }
            return diffLineRegex.containsMatchIn(lines[idx])
        }

        private fun isDiffLine(line: String): Boolean {
            val trimmed = line.trimStart()
            if (trimmed.isEmpty()) return false
            val c0 = trimmed[0]
            if (c0 !in '0'..'9' && c0 != '@' && c0 != 'd' && c0 != '+' && c0 != '-' && c0 != 'i') return false
            return diffLineRegex.containsMatchIn(line)
        }

        private fun isJsonStart(trimmed: String): Boolean {
            if (trimmed.length < 2) return false
            val first = trimmed.first()
            if (first != '{' && first != '[') return false
            if (first == '[' && jsonBracketTagRegex.containsMatchIn(trimmed)) return false
            return trimmed.endsWith("{") || trimmed.endsWith("[") ||
                (trimmed.endsWith("}") || trimmed.endsWith("]") || trimmed.endsWith(",")) && trimmed.contains(':') ||
                trimmed == "{" || trimmed == "["
        }

        private fun isIndentedCodeIdx(idx: Int): Boolean {
            if (lineIsBlank[idx] || paraText != null) return false
            if (lineIndents[idx] < baseIndent + 4) return false
            if (lineIsToolHead[idx] || lineIsToolChild[idx]) return false
            val line = lines[idx]
            val c0 = firstChars[idx]
            if ((c0 in "•-*·◦▪‣") && unorderedBulletRegex.matches(line)) return false
            if ((c0 in '0'..'9' || c0 == '(' || c0 == '（') && orderedBulletRegex.matches(line)) return false
            val t = trimmedLines[idx]
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
            val opener = m?.groupValues?.get(1) ?: trimmedLines[i].take(3)
            val lang = m?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (trimmedLines[i].startsWith(opener)) {
                    i++
                    break
                }
                sb.append(l).append('\n')
                i++
            }
            addCode(sb.toString(), kindLabel = lang ?: "代码")
        }

        private fun consumeCodeRunByIndex(kindLabel: String? = null, predicate: (Int) -> Boolean) {
            val run = ArrayList<String>()
            while (i < lines.size) {
                if (lineIsBlank[i]) {
                    val next = nextNonBlank(i + 1)
                    if (next != null && predicate(next)) {
                        run.add("")
                        i++
                        continue
                    }
                    break
                }
                if (!predicate(i)) break
                run.add(lines[i])
                i++
            }
            if (run.isEmpty()) {
                appendProse(i)
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
            // Both `codex` and `agy` render Markdown lists at `indent = 2 + level * 4` (2, 6, 10 spaces).
            TerminalDialect.CODEX, TerminalDialect.ANTIGRAVITY -> ((indent - 2).coerceAtLeast(0) / 4).coerceAtMost(2)
            else -> if (indent >= baseIndent + 3) 1 else 0
        }

        private fun appendProse(idx: Int) {
            val rawLine = lines[idx]
            val rawC0 = firstChars[idx]
            // `❯ 1. Yes` (menu cursor) -> `  1. Yes`
            var line = if ((rawC0 == '>' || rawC0 == '❯' || rawC0 == '›') && promptMenuOptionRegex.matches(rawLine)) {
                rawLine.replaceFirst(menuCursorCharRegex, " ")
            } else {
                rawLine
            }
            var indent = if (line === rawLine) lineIndents[idx] else indentOf(line)
            val width = displayWidth(line)

            // `messages.rs:1374`: Codex prepends `• ` at col 0 to line 0 of `AgentMarkdownCell`
            // (replacing the 2-space `"  "` gutter used on subsequent lines).
            if (dialect == TerminalDialect.CODEX && indent == 0 && rawC0 == '•' && line.startsWith("• ")) {
                val body = line.substring(2).trim()
                val bc0 = if (body.isNotEmpty()) body[0] else '\u0000'
                val isStructuralLead = (bc0 == '#' && headingRegex.matches(body)) ||
                    (bc0 == '*' && body.startsWith("**") && boldHeadingRegex.matches(body)) ||
                    (bc0 == '>' && blockquoteLineRegex.matches(body)) ||
                    ((bc0 == '☐' || bc0 == '☑') && CodexTranscriptSpec.taskListBulletRegex.matches(body)) ||
                    ((bc0 in '0'..'9' || bc0 == '(' || bc0 == '（') && orderedBulletRegex.matches(body))
                if (isStructuralLead) {
                    line = "  $body"
                    indent = 2
                } else {
                    flushParagraph()
                    startParagraph(body, bullet = false, indent = 2, indentLevel = 0, width = width)
                    return
                }
            }

            val leadChar = if (line === rawLine) rawC0 else if (indent < line.length) line[indent] else '\u0000'
            when (leadChar) {
                '#' -> headingRegex.find(line)?.let {
                    flushParagraph()
                    blocks.add(TerminalSemanticBlock.AgentProseBlock(cleanInline(it.groupValues[1], dialect), isHeading = true))
                    return
                }
                '*' -> {
                    if (line.contains("**")) {
                        boldHeadingRegex.find(line)?.let {
                            flushParagraph()
                            blocks.add(TerminalSemanticBlock.AgentProseBlock(cleanInline(it.groupValues[1], dialect), isHeading = true))
                            return
                        }
                    }
                    unorderedBulletRegex.find(line)?.let {
                        flushParagraph()
                        val level = bulletIndentLevel(indent)
                        startParagraph(it.groupValues[2].trim(), bullet = true, indent = indent, indentLevel = level, width = width)
                        return
                    }
                }
                '>' -> blockquoteLineRegex.find(line)?.let {
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
                '☐', '☑' -> CodexTranscriptSpec.taskListBulletRegex.find(line)?.let {
                    flushParagraph()
                    val box = it.groupValues[2]
                    val level = bulletIndentLevel(indent)
                    startParagraph("$box ${it.groupValues[3].trim()}", bullet = true, indent = indent, indentLevel = level, width = width)
                    return
                }
                in '0'..'9', '(', '（' -> orderedBulletRegex.find(line)?.let {
                    flushParagraph()
                    val marker = it.groupValues[2].trim()
                    val level = bulletIndentLevel(indent)
                    startParagraph("$marker ${it.groupValues[3].trim()}", bullet = true, indent = indent, indentLevel = level, width = width)
                    return
                }
                '•', '-', '·', '◦', '▪', '‣' -> unorderedBulletRegex.find(line)?.let {
                    flushParagraph()
                    val level = bulletIndentLevel(indent)
                    startParagraph(it.groupValues[2].trim(), bullet = true, indent = indent, indentLevel = level, width = width)
                    return
                }
                '●', '⏺' -> proseLeadDotRegex.find(line)?.let {
                    flushParagraph()
                    startParagraph(it.groupValues[2].trim(), bullet = false, indent = indent, indentLevel = 0, width = width)
                    return
                }
            }

            val text = if (line === rawLine) trimmedLines[idx] else line.trim()
            val current = paraText
            if (current != null) {
                val previousWasFull = paraLastWidth >= wrapThreshold
                val bulletContinuation = paraBullet && indent >= paraIndent
                val midTokenDotCont = current.length >= 2 && current.last() == '.' &&
                    current[current.length - 2].isLetterOrDigit() &&
                    text.isNotEmpty() && (text.first() in 'a'..'z' || text.first().isDigit())
                val endsSentence = !midTokenDotCont && current.isNotEmpty() && current.last() in "。！？.!?；;:："
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

        private fun isSpinnerStartChar(c: Char): Boolean =
            c in '\u2800'..'\u28FF' || c == '✻' || c == '✽' || c == '✶' || c == '✳' || c == '✢'

        private fun isThoughtStartChar(c: Char): Boolean =
            c == 'T' || c == 't' || c == '○' || c == '◦' || c == '▸' || c == '▾' || c == '▼' ||
                c == '•' || c == '●' || c == '⏺' || c == '∴' || c == '✻'

        private fun computeIsToolHeadIdx(idx: Int): Boolean {
            val c0 = firstChars[idx]
            val ind = lineIndents[idx]
            val line = lines[idx]
            return when (dialect) {
                TerminalDialect.CODEX ->
                    CodexTranscriptSpec.isActivityHead(line) ||
                        ((c0 == '✔' || c0 == '✓' || c0 == '✗' || c0 == '✘') && CodexTranscriptSpec.approvalAuditRegex.matches(line)) ||
                        (("esc to interrupt" in line || "Esc to interrupt" in line) && CodexTranscriptSpec.liveStatusIndicatorRegex.matches(line)) ||
                        (isThoughtStartChar(c0) && thoughtRegex.matches(line)) ||
                        (isSpinnerStartChar(c0) && spinnerRegex.matches(line))
                TerminalDialect.ANTIGRAVITY ->
                    AntigravityTranscriptSpec.isThoughtRow(line, ind) ||
                        AntigravityTranscriptSpec.isActionRow(line, ind) ||
                        AntigravityTranscriptSpec.isExpandedToolCallHead(line, ind) ||
                        lineIsMoreActions[idx] ||
                        ("Model unavailable" in line && AntigravityTranscriptSpec.modelRetryStatusRegex.matches(line)) ||
                        (isSpinnerStartChar(c0) && spinnerRegex.matches(line))
                TerminalDialect.PI ->
                    ((c0 == 'T' || c0 == 't') && piThoughtHeaderRegex.matches(line)) ||
                        (c0 == '●' && piToolGroupHeaderRegex.matches(line)) ||
                        ((c0 == '✓' || c0 == '✔' || c0 == '✗' || c0 == '✘') && piCheckToolRegex.matches(line)) ||
                        (c0 == '│' && piPipeSummaryRegex.matches(line)) ||
                        (isThoughtStartChar(c0) && thoughtRegex.matches(line)) ||
                        AntigravityTranscriptSpec.isActionRow(line, ind) ||
                        (isSpinnerStartChar(c0) && spinnerRegex.matches(line))
                TerminalDialect.CLAUDE ->
                    (isThoughtStartChar(c0) && thoughtRegex.matches(line)) ||
                        ((c0 == '●' || c0 == '⏺' || c0 == '•') && toolCallParenRegex.matches(line)) ||
                        AntigravityTranscriptSpec.isActionRow(line, ind) ||
                        (isSpinnerStartChar(c0) && spinnerRegex.matches(line))
                else ->
                    (isThoughtStartChar(c0) && thoughtRegex.matches(line)) ||
                        AntigravityTranscriptSpec.isActionRow(line, ind) ||
                        CodexTranscriptSpec.isActivityHead(line) ||
                        ((c0 == '●' || c0 == '⏺' || c0 == '•') && toolCallParenRegex.matches(line)) ||
                        (c0 in "REWSUC" && bareToolSummaryRegex.matches(line)) ||
                        lineIsMoreActions[idx] ||
                        (isSpinnerStartChar(c0) && spinnerRegex.matches(line))
            }
        }

        private fun isToolHead(line: String): Boolean {
            val ind = indentOf(line)
            if (ind >= line.length) return false
            val c0 = line[ind]
            return when (dialect) {
                TerminalDialect.CODEX ->
                    CodexTranscriptSpec.isActivityHead(line) ||
                        ((c0 == '✔' || c0 == '✓' || c0 == '✗' || c0 == '✘') && CodexTranscriptSpec.approvalAuditRegex.matches(line)) ||
                        (line.contains("esc to interrupt", ignoreCase = true) && CodexTranscriptSpec.liveStatusIndicatorRegex.matches(line)) ||
                        (isThoughtStartChar(c0) && thoughtRegex.matches(line)) ||
                        (isSpinnerStartChar(c0) && spinnerRegex.matches(line))
                TerminalDialect.ANTIGRAVITY ->
                    AntigravityTranscriptSpec.isThoughtRow(line, ind) ||
                        AntigravityTranscriptSpec.isActionRow(line, ind) ||
                        AntigravityTranscriptSpec.isExpandedToolCallHead(line, ind) ||
                        ((c0 == '…' || c0 == '.') && moreActionsRegex.matches(line)) ||
                        (line.contains("Model unavailable", ignoreCase = true) && AntigravityTranscriptSpec.modelRetryStatusRegex.matches(line)) ||
                        (isSpinnerStartChar(c0) && spinnerRegex.matches(line))
                TerminalDialect.PI ->
                    ((c0 == 'T' || c0 == 't') && piThoughtHeaderRegex.matches(line)) ||
                        (c0 == '●' && piToolGroupHeaderRegex.matches(line)) ||
                        ((c0 == '✓' || c0 == '✔' || c0 == '✗' || c0 == '✘') && piCheckToolRegex.matches(line)) ||
                        (c0 == '│' && piPipeSummaryRegex.matches(line)) ||
                        (isThoughtStartChar(c0) && thoughtRegex.matches(line)) ||
                        AntigravityTranscriptSpec.isActionRow(line, ind) ||
                        (isSpinnerStartChar(c0) && spinnerRegex.matches(line))
                TerminalDialect.CLAUDE ->
                    (isThoughtStartChar(c0) && thoughtRegex.matches(line)) ||
                        ((c0 == '●' || c0 == '⏺' || c0 == '•') && toolCallParenRegex.matches(line)) ||
                        AntigravityTranscriptSpec.isActionRow(line, ind) ||
                        (isSpinnerStartChar(c0) && spinnerRegex.matches(line))
                else ->
                    (isThoughtStartChar(c0) && thoughtRegex.matches(line)) ||
                        AntigravityTranscriptSpec.isActionRow(line, ind) ||
                        CodexTranscriptSpec.isActivityHead(line) ||
                        ((c0 == '●' || c0 == '⏺' || c0 == '•') && toolCallParenRegex.matches(line)) ||
                        (c0 in "REWSUC" && bareToolSummaryRegex.matches(line)) ||
                        ((c0 == '…' || c0 == '.') && moreActionsRegex.matches(line)) ||
                        (isSpinnerStartChar(c0) && spinnerRegex.matches(line))
            }
        }

        // ---- helpers ----

        private fun nextNonBlank(from: Int): Int? =
            if (from !in nextNonBlankIdx.indices) null else nextNonBlankIdx[from].takeIf { it >= 0 }

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
                    val steps = (prev.steps + b.steps).take(maxGroupSteps)
                    val prevCount = groupCountExtractRegex.find(prev.summaryLabel)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val nextCount = groupCountExtractRegex.find(b.summaryLabel)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val totalHeads = prevCount + nextCount
                    val hasError = prev.hasError || b.hasError
                    val running = b.summaryLabel.contains("进行中")
                    val dur = groupDurExtractRegex.find(b.summaryLabel)?.groupValues?.get(1)?.trim()
                        ?: groupDurExtractRegex.find(prev.summaryLabel)?.groupValues?.get(1)?.trim()
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
        val prev2 = if (previous.length >= 2) previous[previous.length - 2] else ' '
        val looksLikeFilenameIpOrUrlDot = a == '.' && prev2.isLetterOrDigit() && (b in 'a'..'z' || b.isDigit())
        val looksLikeDigitComma = a == ',' && prev2.isDigit() && b.isDigit()
        val noSpace = isCjk(a) || isCjk(b) ||
            a == '/' || a == '\\' || b == '/' || b == '\\' ||
            a == '(' || b == ')' ||
            looksLikeFilenameIpOrUrlDot ||
            looksLikeDigitComma ||
            (a == '-' && previous.length > 1 && (prev2.isLetterOrDigit() || prev2 == '-'))
        return if (noSpace) previous + next else "$previous $next"
    }

    private fun cleanInline(text: String, dialect: TerminalDialect): String {
        var cleaned = if ("  " in text || '\t' in text) text.replace(multiSpaceRegex, " ").trim() else text.trim()
        // Strip raw Markdown bold `**...**` markers for cleaner mobile prose reading
        if ("**" in cleaned) {
            cleaned = cleaned.replace(boldMarkdownRegex, "$1")
        }
        if (dialect == TerminalDialect.CODEX && '(' in cleaned && ')' in cleaned && ('/' in cleaned || '\\' in cleaned)) {
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
                    val short = truncateWidth(text.replace(optionParenTailRegex, "").trim().ifEmpty { text }, 16)
                    val negative = negativeOptionRegex.containsMatchIn(text.trim())
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
        val m = firstSentenceRegex.find(text)
        return m?.value ?: text
    }

    private fun stripHcWrapper(text: String): String =
        text.replace(hcTagStripRegex, " ").replace(anyWhitespaceRegex, " ").trim()

    private fun polishHeadline(text: String): String {
        val cleaned = stripHcWrapper(text)
            .replace(headlineLeadStripRegex, "")
            .replace("**", "")
            .replace("`", "")
            .replace(anyWhitespaceRegex, " ")
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
