package dev.herdr.android.ui.terminal

/**
 * Lightweight styled-line projection extracted prior to bottom-dock stripping so interactive
 * `agy` modals, cursors, checkboxes, draft composers, and status bars can be inspected precisely.
 */
internal data class AgyRawStyledLine(
    val text: String,
    val indent: Int,
    val firstChar: Char,
    val isDim: Boolean = false,
    val isItalic: Boolean = false,
    val isBold: Boolean = false,
    val hasBgHighlight: Boolean = false
)

enum class AgyUiPhase {
    COMPOSER_EMPTY,
    COMPOSER_HAS_DRAFT,
    ASK_QUESTION_MODAL,
    PERMISSION_MODAL,
    SLASH_MODAL,
    WORKING
}

enum class AgyAgentMode(
    val id: String,
    val label: String,
    val footerToken: String
) {
    AUTO("auto", "Auto", "auto"),
    ACCEPT_EDITS("accept-edits", "Accept-edits", "accept-edits"),
    PLAN("plan", "Plan", "plan"),
    BEST_OF_N("best-of-n", "Best-of-N", "best-of-n")
}

data class AgyAskOption(
    val number: Int,
    val label: String,
    val isCursor: Boolean = false,
    val isChecked: Boolean = false,
    val isWriteIn: Boolean = false,
    val writeInPreview: String? = null
)

data class AgyAskCardState(
    val questionIndex: Int,
    val totalQuestions: Int,
    val questionText: String,
    val isMultiSelect: Boolean,
    val options: List<AgyAskOption>,
    val isWriteInFocused: Boolean = false,
    val currentWriteInDraft: String = "",
    val startRawLineIndex: Int = -1
)

enum class AgySlashModalKind(
    val slashCmd: String,
    val displayTitle: String
) {
    MODEL("/model", "模型与推理选择"),
    EFFORT("/effort", "推理强度"),
    CONFIG("/config", "全局与会话设置"),
    RESUME("/resume", "历史会话切换"),
    REWIND("/rewind", "步骤回滚与分支"),
    TASKS("/tasks", "后台任务管理"),
    AGENTS("/agents", "子代理管理"),
    ARTIFACT("/artifact", "产物审阅"),
    DIFF("/diff", "代码变更审阅"),
    SKILLS("/skills", "技能目录"),
    MCP("/mcp", "MCP 服务管理"),
    PLUGIN("/plugin", "插件中心"),
    PERMISSIONS("/permissions", "权限规则"),
    USAGE("/usage", "配额与用量"),
    BTW("/btw", "旁路快问"),
    GENERIC("", "交互面板")
}

data class AgyModalItem(
    val index: Int,
    val title: String,
    val subtitleOrValue: String = "",
    val isSelected: Boolean = false,
    val isCurrentActive: Boolean = false
)

data class AgySlashModalState(
    val kind: AgySlashModalKind,
    val headerTitle: String,
    val tabs: List<String> = emptyList(),
    val activeTabIndex: Int = 0,
    val searchQuery: String? = null,
    val items: List<AgyModalItem> = emptyList(),
    val selectedIndex: Int = 0,
    val effortHint: String? = null,
    val supportsLeftRightAdjust: Boolean = false,
    val supportsTabSwitch: Boolean = false,
    val rawHintFooter: String = ""
)

data class AgyTerminalState(
    val phase: AgyUiPhase,
    val agentMode: AgyAgentMode? = null,
    val activeModelSummary: String? = null,
    val activeEffort: String? = null,
    val composerDraft: String = "",
    val isSlashSuggestionOpen: Boolean = false,
    val activeAskCard: AgyAskCardState? = null,
    val activeSlashModal: AgySlashModalState? = null,
    val hasWorkingHint: Boolean = false
)

/**
 * Complete `agy` 1.5.x slash command catalog split into direct/parameterized commands and
 * interactive full-screen/overlay panels.
 */
object AgySlashCatalog {
    enum class Category(val title: String) {
        MODE("执行模式"),
        MODEL_EFFORT("模型与推理"),
        CONTEXT("上下文与快问"),
        PANELS("交互面板")
    }

    data class SlashCommandSpec(
        val cmd: String,
        val title: String,
        val description: String,
        val category: Category,
        val modalKind: AgySlashModalKind? = null,
        val quickParams: List<String> = emptyList(),
        val requiresConfirm: Boolean = false,
        val acceptsFreeTextArg: Boolean = false,
        val argPlaceholder: String = ""
    )

    val allCommands: List<SlashCommandSpec> = listOf(
        SlashCommandSpec(
            cmd = "/model",
            title = "切换模型",
            description = "打开 /model 选择器，或直接指定模型别名",
            category = Category.MODEL_EFFORT,
            modalKind = AgySlashModalKind.MODEL,
            acceptsFreeTextArg = true,
            argPlaceholder = "模型名称或别名（留空打开面板）"
        ),
        SlashCommandSpec(
            cmd = "/effort",
            title = "推理强度",
            description = "直接设置 low / medium / high / xhigh / max，或打开选择器",
            category = Category.MODEL_EFFORT,
            modalKind = AgySlashModalKind.EFFORT,
            quickParams = listOf("low", "medium", "high", "xhigh", "max")
        ),
        SlashCommandSpec(
            cmd = "/thinking",
            title = "思考开关",
            description = "直接切换模型可见思考过程 [on | off]",
            category = Category.MODEL_EFFORT,
            quickParams = listOf("on", "off")
        ),
        SlashCommandSpec(
            cmd = "/compact",
            title = "压缩上下文",
            description = "摘要压缩历史对话以释放上下文窗口",
            category = Category.CONTEXT,
            acceptsFreeTextArg = true,
            argPlaceholder = "可选聚焦说明"
        ),
        SlashCommandSpec(
            cmd = "/btw",
            title = "旁路快问",
            description = "不打断主线上下文快速提问，支持工具只读检索",
            category = Category.CONTEXT,
            modalKind = AgySlashModalKind.BTW,
            acceptsFreeTextArg = true,
            argPlaceholder = "输入旁路问题"
        ),
        SlashCommandSpec(
            cmd = "/plan",
            title = "Plan 任务",
            description = "以研究与规划前缀发起只读方案推演",
            category = Category.CONTEXT,
            acceptsFreeTextArg = true,
            argPlaceholder = "输入需要规划的目标"
        ),
        SlashCommandSpec(
            cmd = "/rename",
            title = "重命名会话",
            description = "修改当前对话标题",
            category = Category.CONTEXT,
            acceptsFreeTextArg = true,
            argPlaceholder = "输入新的会话标题"
        ),
        SlashCommandSpec(
            cmd = "/add-dir",
            title = "挂载工作目录",
            description = "将额外本地文件夹纳入工作区读取与搜索范围",
            category = Category.CONTEXT,
            acceptsFreeTextArg = true,
            argPlaceholder = "例如 D:\\Code\\SharedLib"
        ),
        SlashCommandSpec(
            cmd = "/copy",
            title = "复制回复",
            description = "复制最近一轮回复或当前 /btw 回答到剪贴板",
            category = Category.CONTEXT,
            quickParams = listOf("btw")
        ),
        SlashCommandSpec(
            cmd = "/clear",
            title = "清空开启新会话",
            description = "重置当前终端上下文开启新对话（需二次确认）",
            category = Category.CONTEXT,
            requiresConfirm = true
        ),
        SlashCommandSpec(
            cmd = "/config",
            title = "设置面板",
            description = "打开设置面板（Agent Mode、产物审阅、通知、配色等）",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.CONFIG
        ),
        SlashCommandSpec(
            cmd = "/artifact",
            title = "产物审阅",
            description = "查看并批准/打回 Agent 生成的计划与报告产物",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.ARTIFACT
        ),
        SlashCommandSpec(
            cmd = "/diff",
            title = "代码变更",
            description = "浏览工作区未提交修改与 Commit 变更树",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.DIFF
        ),
        SlashCommandSpec(
            cmd = "/resume",
            title = "切换历史会话",
            description = "打开会话选择器，搜索或恢复历史对话",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.RESUME
        ),
        SlashCommandSpec(
            cmd = "/rewind",
            title = "步骤回滚",
            description = "回退对话历史与工作区快照或创建分支",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.REWIND
        ),
        SlashCommandSpec(
            cmd = "/tasks",
            title = "后台任务",
            description = "查看或终止后台命令、定时任务与 Cron",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.TASKS
        ),
        SlashCommandSpec(
            cmd = "/agents",
            title = "子代理",
            description = "查看或切换子代理状态与提问处理",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.AGENTS
        ),
        SlashCommandSpec(
            cmd = "/skills",
            title = "技能列表",
            description = "浏览已加载的全局/工作区 Skills",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.SKILLS,
            quickParams = listOf("reload")
        ),
        SlashCommandSpec(
            cmd = "/mcp",
            title = "MCP 服务",
            description = "查看、启用/停用或重连 MCP Server",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.MCP
        ),
        SlashCommandSpec(
            cmd = "/plugin",
            title = "插件市场",
            description = "浏览已安装插件与云端插件鉴权",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.PLUGIN
        ),
        SlashCommandSpec(
            cmd = "/permissions",
            title = "权限规则",
            description = "查看并管理工具执行白名单与拒绝规则",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.PERMISSIONS
        ),
        SlashCommandSpec(
            cmd = "/usage",
            title = "配额统计",
            description = "查看各模型配额占用与 G1 Credits",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.USAGE
        )
    )
}

internal object AgyInteractiveParser {

    private val askQuestionHeaderRegex = Regex("^Question\\s+(\\d+)\\s*/\\s*(\\d+)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val optionRowRegex = Regex(
        "^(?:([>❯›])\\s*)?(?:(✓)\\s+)?(\\d{1,2})\\.\\s+(?:\\[([ xX✓✔])]\\s+)?(.+)$"
    )
    private val altCheckboxRowRegex = Regex(
        "^(?:([>❯›])\\s*)?\\[([ xX✓✔])]\\s+(\\d{1,2})\\.\\s+(.+)$"
    )
    private val writeInLabelRegex = Regex("(?i)^(?:Write-in\\b|Other\\s*(?:\\(|\\.\\.\\.|:)|其他|自定义回答).*")
    private val yourAnswerRegex = Regex("^Your answer\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val longRuleRegex = Regex("^[╭╰]?[─━═]{8,}[╮╯]?$")
    private val modalFooterHintRegex = Regex(
        "(?i)(?:↑/↓|←/→|\\bNavigate\\b|\\bChange\\b|\\bswitch view\\b|\\bswitch tab\\b|tab\\s+Amend|space\\s+to\\s+toggle|enter\\s+(?:Submit|Select)|esc\\s+(?:to\\s+(?:close|exit|discard|cancel|back|skip)|Back|Skip))"
    )
    private val modalHeaderKnownRegex = Regex(
        "(?i)^(?:Available\\s+models|Select\\s+Model|Reasoning\\s+Effort|Settings|Conversations|Background\\s+Tasks|Running\\s+Background\\s+Tasks|Available\\s+Agents|Artifacts|Action\\s+required\\s*\\(|Manage\\s+MCP\\s+servers|MCP\\s+Servers|All\\s+Changes|Commit\\s+Tree|Rule\\s+—|Permissions|Rewind|\\d+\\s+skills|Remaining\\s+AI\\s+Credits|Plugins)\\b.*"
    )
    private val effortTokenRegex = Regex("(?i)\\b(low|medium|high|xhigh|max)\\b")
    private val workingLineRegex = Regex(
        "(?i)(?:^[\\u2800-\\u28FF✻✽✶✳✢]\\s+\\S|^Model unavailable\\s*\\(attempt|^(?:[╭╰]─\\s*|[●◆⚙🔧⏳]\\s+)(?:[●◆⚙🔧⏳]\\s*)?(?:THINKING|WORKING|TOOL)\\b|Compacting conversation\\.{2,3})"
    )
    private val slashSuggestionItemRegex = Regex("^/[a-z][a-z0-9-]{1,24}\\b.*")
    private val diffHeaderPosRegex = Regex("(?i)\\b(?:change\\s+\\d+/\\d+|file\\s+\\d+/\\d+|All\\s+Changes|Commit\\s+Tree)\\b")
    private val searchPromptRegex = Regex("^(?:Search|Filter)\\s*[:：]\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val splitColumnsRegex = Regex("\\s{2,}")
    private val tabActiveSegmentRegex = Regex("(?:\\[([^\\]]+)]|\\(([^)]+)\\)|●\\s*(\\S[^·|]{1,24}))")

    fun inspectRaw(rawScreen: String): AgyTerminalState {
        if (rawScreen.isBlank()) {
            return AgyTerminalState(phase = AgyUiPhase.COMPOSER_EMPTY)
        }
        val lines = StructuredTerminalReader.extractAgyRawStyledLines(rawScreen)
        return inspect(lines)
    }

    fun inspect(rawLines: List<AgyRawStyledLine>): AgyTerminalState {
        if (rawLines.isEmpty()) {
            return AgyTerminalState(phase = AgyUiPhase.COMPOSER_EMPTY)
        }

        val footerMeta = extractFooterMeta(rawLines)
        val composerInfo = extractComposerInfo(rawLines)
        val resolvedMode = composerInfo.placeholderMode ?: footerMeta.mode
        val askCard = detectAskQuestion(rawLines)
        if (askCard != null) {
            return AgyTerminalState(
                phase = AgyUiPhase.ASK_QUESTION_MODAL,
                agentMode = resolvedMode,
                activeModelSummary = footerMeta.modelSummary,
                activeEffort = footerMeta.effort,
                composerDraft = "",
                isSlashSuggestionOpen = false,
                activeAskCard = askCard,
                activeSlashModal = null,
                hasWorkingHint = footerMeta.hasWorking
            )
        }

        if (isPermissionModalVisible(rawLines)) {
            return AgyTerminalState(
                phase = AgyUiPhase.PERMISSION_MODAL,
                agentMode = resolvedMode,
                activeModelSummary = footerMeta.modelSummary,
                activeEffort = footerMeta.effort,
                composerDraft = "",
                isSlashSuggestionOpen = false,
                activeAskCard = null,
                activeSlashModal = null,
                hasWorkingHint = footerMeta.hasWorking
            )
        }

        val slashModal = if (!composerInfo.hasComposerBox) detectSlashModal(rawLines) else null
        if (slashModal != null) {
            return AgyTerminalState(
                phase = AgyUiPhase.SLASH_MODAL,
                agentMode = resolvedMode,
                activeModelSummary = footerMeta.modelSummary,
                activeEffort = footerMeta.effort,
                composerDraft = "",
                isSlashSuggestionOpen = false,
                activeAskCard = null,
                activeSlashModal = slashModal,
                hasWorkingHint = footerMeta.hasWorking
            )
        }

        val phase = when {
            composerInfo.draft.isNotEmpty() -> AgyUiPhase.COMPOSER_HAS_DRAFT
            footerMeta.hasWorking -> AgyUiPhase.WORKING
            else -> AgyUiPhase.COMPOSER_EMPTY
        }
        return AgyTerminalState(
            phase = phase,
            agentMode = resolvedMode,
            activeModelSummary = footerMeta.modelSummary,
            activeEffort = footerMeta.effort,
            composerDraft = composerInfo.draft,
            isSlashSuggestionOpen = composerInfo.isSlashSuggestionOpen,
            activeAskCard = null,
            activeSlashModal = null,
            hasWorkingHint = footerMeta.hasWorking
        )
    }

    private data class FooterMeta(
        val mode: AgyAgentMode?,
        val modelSummary: String?,
        val effort: String?,
        val hasWorking: Boolean
    )

    private fun extractFooterMeta(rawLines: List<AgyRawStyledLine>): FooterMeta {
        var mode: AgyAgentMode? = null
        var model: String? = null
        var effort: String? = null
        var working = false

        val tail = rawLines.takeLast(14)
        for (line in tail) {
            if (line.indent < 0) continue
            val t = line.text.trim()
            if (workingLineRegex.containsMatchIn(t)) {
                working = true
            }
            if (mode == null) {
                mode = parseModeToken(t)
            }
            if (model == null && (AntigravityTranscriptSpec.isModelStatusFooter(t) || AntigravityTranscriptSpec.isCustomStatusLine(t))) {
                val segments = t.split('·', '╱', '|').map { it.trim() }.filter { it.isNotEmpty() }
                for (seg in segments) {
                    if (mode == null) {
                        mode = parseModeToken(seg)
                    }
                    if (seg.contains("Gemini", true) || seg.contains("Claude", true) || seg.contains("GPT-", true) || seg.contains("Orchestrator", true)) {
                        model = seg
                    } else if (effort == null && effortTokenRegex.matches(seg)) {
                        effort = seg.lowercase()
                    }
                }
                if (effort == null) {
                    val parenEffort = Regex("\\((low|medium|high|xhigh|max)\\)", RegexOption.IGNORE_CASE).find(t)
                    if (parenEffort != null) {
                        effort = parenEffort.groupValues[1].lowercase()
                    }
                }
            }
        }
        return FooterMeta(mode, model, effort, working)
    }

    private fun parseModeToken(text: String): AgyAgentMode? {
        val parts = splitColumnsRegex.split(text.trim())
        for (part in parts) {
            val lower = part.trim().lowercase()
            when {
                lower.startsWith("auto ·") || lower == "auto" || "auto mode:" in lower -> return AgyAgentMode.AUTO
                lower.startsWith("accept-edits ·") || lower == "accept-edits" || "accept-edits mode:" in lower -> return AgyAgentMode.ACCEPT_EDITS
                lower.startsWith("plan ·") || lower == "plan" || "plan mode:" in lower -> return AgyAgentMode.PLAN
                lower.startsWith("best-of-n ·") || lower == "best-of-n" || "best-of-n mode:" in lower -> return AgyAgentMode.BEST_OF_N
            }
        }
        return null
    }

    private data class ComposerInfo(
        val hasComposerBox: Boolean,
        val draft: String,
        val placeholderMode: AgyAgentMode?,
        val isSlashSuggestionOpen: Boolean
    )

    private fun extractComposerInfo(rawLines: List<AgyRawStyledLine>): ComposerInfo {
        var bottomRuleIdx = -1
        val minSearch = (rawLines.size - 14).coerceAtLeast(0)
        for (idx in rawLines.lastIndex downTo minSearch) {
            val t = rawLines[idx].text.trim()
            if (longRuleRegex.matches(t)) {
                bottomRuleIdx = idx
                break
            }
        }
        if (bottomRuleIdx <= 0) {
            return ComposerInfo(false, "", null, false)
        }

        val topLimit = (bottomRuleIdx - 6).coerceAtLeast(0)
        var topRuleIdx = -1
        for (idx in bottomRuleIdx - 1 downTo topLimit) {
            val t = rawLines[idx].text.trim()
            if (longRuleRegex.matches(t)) {
                topRuleIdx = idx
                break
            }
        }
        if (topRuleIdx < 0) {
            return ComposerInfo(false, "", null, false)
        }

        // Collect inner lines between topRuleIdx and bottomRuleIdx, unwrapping optional box borders `│ ... │`
        val innerLines = mutableListOf<AgyRawStyledLine>()
        for (idx in topRuleIdx + 1 until bottomRuleIdx) {
            val rawLine = rawLines[idx]
            if (rawLine.indent >= 0) {
                val trimmed = rawLine.text.trim()
                val unwrapped = if (trimmed.startsWith("│") && trimmed.endsWith("│") && trimmed.length >= 2) {
                    trimmed.substring(1, trimmed.length - 1).trimEnd()
                } else {
                    rawLine.text
                }
                innerLines.add(rawLine.copy(text = unwrapped))
            }
        }
        val firstLine = innerLines.firstOrNull()?.text?.trimStart().orEmpty()
        if (!firstLine.startsWith(">") && !firstLine.startsWith("›") && !firstLine.startsWith("❯")) {
            return ComposerInfo(false, "", null, false)
        }
        if (optionRowRegex.matches(firstLine)) {
            return ComposerInfo(false, "", null, false)
        }

        val firstAfterChevron = firstLine.drop(1).trim()
        if (AntigravityTranscriptSpec.isComposerModePlaceholder("> $firstAfterChevron") ||
            AntigravityTranscriptSpec.isComposerModePlaceholder(firstAfterChevron)
        ) {
            return ComposerInfo(
                hasComposerBox = true,
                draft = "",
                placeholderMode = parseModeToken(firstAfterChevron),
                isSlashSuggestionOpen = false
            )
        }

        val draftParts = mutableListOf<String>()
        if (firstAfterChevron.isNotEmpty()) {
            draftParts.add(firstAfterChevron)
        }
        for (extra in innerLines.drop(1)) {
            val t = extra.text.trim()
            if (t.isNotEmpty() && !AntigravityTranscriptSpec.trayTaskRowRegex.matches(extra.text) &&
                !AntigravityTranscriptSpec.traySubagentRowRegex.matches(extra.text)
            ) {
                draftParts.add(t)
            }
        }
        val fullDraft = draftParts.joinToString("\n").trim()
        val hasSlashPopup = fullDraft.startsWith("/") && (
            rawLines.drop(bottomRuleIdx + 1).any { slashSuggestionItemRegex.matches(it.text.trim()) } ||
                rawLines.subList((topRuleIdx - 10).coerceAtLeast(0), topRuleIdx)
                    .any { slashSuggestionItemRegex.matches(it.text.trim()) }
            )
        return ComposerInfo(
            hasComposerBox = true,
            draft = fullDraft,
            placeholderMode = null,
            isSlashSuggestionOpen = hasSlashPopup
        )
    }

    private fun detectAskQuestion(rawLines: List<AgyRawStyledLine>): AgyAskCardState? {
        val searchFloor = (rawLines.size - 44).coerceAtLeast(0)
        // Find footer / write-in region from the bottom
        var yourAnswerLineIdx = -1
        var writeInDraft = ""
        var footerHintLine = ""
        var scanEnd = rawLines.lastIndex

        for (idx in rawLines.lastIndex downTo maxOf(searchFloor, rawLines.size - 18)) {
            val t = rawLines[idx].text.trim()
            if (t.isEmpty()) continue
            val answerMatch = yourAnswerRegex.find(t)
            if (answerMatch != null && yourAnswerLineIdx < 0) {
                yourAnswerLineIdx = idx
                writeInDraft = answerMatch.groupValues[1].trim()
                scanEnd = idx - 1
                continue
            }
            if (AntigravityTranscriptSpec.isKeyHintFooter(t) ||
                AntigravityTranscriptSpec.isModelStatusFooter(t) ||
                AntigravityTranscriptSpec.isCustomStatusLine(t) ||
                modalFooterHintRegex.containsMatchIn(t)
            ) {
                if (footerHintLine.isEmpty()) footerHintLine = t
                scanEnd = minOf(scanEnd, idx - 1)
                continue
            }
            if (longRuleRegex.matches(t) && idx > rawLines.size - 16) {
                scanEnd = minOf(scanEnd, idx - 1)
                continue
            }
            break
        }

        // Locate contiguous numbered option block ending near scanEnd
        val matchedOptions = ArrayList<Triple<Int, AgyAskOption, AgyRawStyledLine>>()
        var optStartIdx = -1
        var cursorIdx = scanEnd
        while (cursorIdx >= searchFloor) {
            val line = rawLines[cursorIdx]
            val t = line.text.trim()
            if (t.isEmpty() || longRuleRegex.matches(t)) {
                if (matchedOptions.isNotEmpty()) break
                cursorIdx--
                continue
            }
            val parsed = parseAskOptionRow(line)
            if (parsed != null) {
                matchedOptions.add(Triple(cursorIdx, parsed, line))
                optStartIdx = cursorIdx
                cursorIdx--
            } else {
                if (matchedOptions.isNotEmpty()) break
                if (cursorIdx < scanEnd - 4) break
                cursorIdx--
            }
        }
        if (matchedOptions.size < 2) return null

        val ordered = matchedOptions.asReversed().map { it.second }
        // Confirm sequential numbering 1..N
        for ((idx, opt) in ordered.withIndex()) {
            if (opt.number != idx + 1) return null
        }

        // Look up to 8 lines above optStartIdx for question title / Question m/n header
        var qIndex = 1
        var qTotal = 1
        var questionTitle: String? = null
        var startLineIdx = optStartIdx
        var hasCheckboxHint = false

        var titleScan = optStartIdx - 1
        var blankSkips = 0
        val titleParts = ArrayList<String>()
        while (titleScan >= searchFloor && optStartIdx - titleScan <= 9) {
            val l = rawLines[titleScan]
            val t = l.text.trim()
            if (t.isEmpty() || longRuleRegex.matches(t)) {
                if (titleParts.isNotEmpty()) break
                blankSkips++
                if (blankSkips > 2) break
                titleScan--
                continue
            }
            if (AntigravityTranscriptSpec.isPermissionTitle(t)) {
                // Explicitly a tool permission dialog -> keep rendered as UrgentAlertBlock
                return null
            }
            val qHeader = askQuestionHeaderRegex.find(t)
            if (qHeader != null) {
                qIndex = qHeader.groupValues[1].toIntOrNull() ?: 1
                qTotal = qHeader.groupValues[2].toIntOrNull() ?: 1
                questionTitle = qHeader.groupValues[3].trim()
                startLineIdx = titleScan
                break
            }
            if ("select all that apply" in t.lowercase() || "select multiple" in t.lowercase()) {
                hasCheckboxHint = true
            }
            titleParts.add(t)
            startLineIdx = titleScan
            if (t.endsWith("?") || t.endsWith("？")) break
            if (titleParts.size >= 3) break
            titleScan--
        }

        if (questionTitle == null && titleParts.isNotEmpty()) {
            questionTitle = titleParts.asReversed().joinToString(" ").trim()
        }
        if (questionTitle.isNullOrBlank()) return null

        val hasWriteInOpt = ordered.any { it.isWriteIn }
        val hasExplicitCheckboxes = ordered.any { opt ->
            val rawRow = matchedOptions.find { it.second.number == opt.number }?.third?.text.orEmpty()
            "[ ]" in rawRow || "[x]" in rawRow || "[X]" in rawRow || "[✓]" in rawRow || "[✔]" in rawRow
        }
        val isAskSignal = (qTotal > 1 || askQuestionHeaderRegex.matches(rawLines.getOrNull(startLineIdx)?.text?.trim().orEmpty())) ||
            hasWriteInOpt ||
            hasExplicitCheckboxes ||
            yourAnswerLineIdx >= 0 ||
            "space to toggle" in footerHintLine.lowercase() ||
            "submit all" in footerHintLine.lowercase()

        if (!isAskSignal) return null

        val isMultiSelect = hasExplicitCheckboxes ||
            hasCheckboxHint ||
            "space to toggle" in footerHintLine.lowercase() ||
            "select all" in questionTitle.lowercase()

        val footerLower = footerHintLine.lowercase()
        val writeInFocused = yourAnswerLineIdx >= 0 ||
            (("esc to discard" in footerLower || "enter submit" in footerLower) && ordered.any { it.isCursor && it.isWriteIn })

        // Trim duplicated `Question` header + pending `? <question>` preview rows immediately above `startLineIdx`
        var trimStart = startLineIdx
        var probe = trimStart - 1
        var sawQuestionWord = false
        while (probe >= 0 && startLineIdx - probe <= 14) {
            val pt = rawLines[probe].text.trim()
            if (pt.isEmpty() || longRuleRegex.matches(pt)) {
                probe--
                continue
            }
            if (pt.equals("Question", ignoreCase = true)) {
                sawQuestionWord = true
                trimStart = probe
                probe--
                continue
            }
            if (pt.startsWith("? ") && sawQuestionWord) {
                val nextNonBlank = ((probe + 1) until trimStart)
                    .map { rawLines[it].text.trim() }
                    .firstOrNull { it.isNotEmpty() }
                if (nextNonBlank != null && (nextNonBlank.startsWith("> ") || nextNonBlank.startsWith("› "))) {
                    break
                }
                trimStart = probe
                probe--
                continue
            }
            break
        }

        return AgyAskCardState(
            questionIndex = qIndex,
            totalQuestions = qTotal,
            questionText = questionTitle,
            isMultiSelect = isMultiSelect,
            options = ordered,
            isWriteInFocused = writeInFocused,
            currentWriteInDraft = writeInDraft,
            startRawLineIndex = trimStart
        )
    }

    private fun parseAskOptionRow(line: AgyRawStyledLine): AgyAskOption? {
        val t = line.text.trim()
        var cursor = line.hasBgHighlight
        var checked = false
        var number = -1
        var label = ""

        val std = optionRowRegex.find(t)
        if (std != null) {
            if (std.groupValues[1].isNotEmpty()) cursor = true
            if (std.groupValues[2].isNotEmpty()) checked = true
            number = std.groupValues[3].toIntOrNull() ?: return null
            val box = std.groupValues[4]
            if (box.isNotEmpty() && box != " ") checked = true
            label = std.groupValues[5].trim()
        } else {
            val alt = altCheckboxRowRegex.find(t) ?: return null
            if (alt.groupValues[1].isNotEmpty()) cursor = true
            if (alt.groupValues[2] != " ") checked = true
            number = alt.groupValues[3].toIntOrNull() ?: return null
            label = alt.groupValues[4].trim()
        }

        val isWriteIn = writeInLabelRegex.matches(label)
        var preview: String? = null
        var cleanLabel = label
        if (isWriteIn && label.startsWith("Write-in:", ignoreCase = true)) {
            preview = label.substringAfter(":").trim().takeIf { it.isNotEmpty() }
            cleanLabel = if (preview != null) "自定义：$preview" else "自定义回答…"
        } else if (isWriteIn) {
            cleanLabel = "自定义回答…"
        }
        return AgyAskOption(
            number = number,
            label = cleanLabel,
            isCursor = cursor,
            isChecked = checked,
            isWriteIn = isWriteIn,
            writeInPreview = preview
        )
    }

    private fun isPermissionModalVisible(rawLines: List<AgyRawStyledLine>): Boolean {
        val tail = rawLines.takeLast(24)
        return tail.any {
            val t = it.text.trim()
            AntigravityTranscriptSpec.isPermissionTitle(t) ||
                AntigravityTranscriptSpec.isSubagentQuestion(t)
        }
    }

    private fun detectSlashModal(rawLines: List<AgyRawStyledLine>): AgySlashModalState? {
        val window = rawLines.takeLast(46).filter { it.indent >= 0 }
        if (window.isEmpty()) return null

        val nonEmptyTexts = window.map { it.text.trim() }
        val footerLines = nonEmptyTexts.takeLast(4).filter {
            modalFooterHintRegex.containsMatchIn(it) || AntigravityTranscriptSpec.isKeyHintFooter(it)
        }
        val joined = nonEmptyTexts.joinToString("\n")
        val hasKnownHeader = nonEmptyTexts.any { modalHeaderKnownRegex.matches(it) } ||
            diffHeaderPosRegex.containsMatchIn(joined) ||
            ("Available Models" in joined || "Select Model" in joined || "No models match" in joined)

        val hasStrongModalFooter = nonEmptyTexts.takeLast(4).any { t ->
            "↑/↓" in t || "←/→" in t || "Navigate" in t || "Switch Tab" in t || "switch view" in t.lowercase() ||
                "tab Amend" in t || "space to toggle" in t.lowercase() ||
                "Enter Select" in t || "Enter Confirm" in t || "Enter Resume" in t ||
                "Esc to close" in t || "Esc to back" in t || "Esc to exit" in t
        }
        if (!hasKnownHeader && !hasStrongModalFooter) {
            return null
        }

        val kind = classifyModalKind(nonEmptyTexts, joined)
        var headerTitle = kind.displayTitle
        for (t in nonEmptyTexts.take(10)) {
            if (modalHeaderKnownRegex.matches(t) || diffHeaderPosRegex.containsMatchIn(t)) {
                headerTitle = t
                break
            }
        }

        var searchQuery: String? = null
        var effortHint: String? = null
        val tabs = ArrayList<String>()
        var activeTabIdx = 0

        for (line in window.take(8)) {
            val t = line.text.trim()
            searchPromptRegex.find(t)?.let { m ->
                searchQuery = m.groupValues[1].trim()
            }
            if (kind == AgySlashModalKind.CONFIG || kind == AgySlashModalKind.DIFF ||
                kind == AgySlashModalKind.PLUGIN || kind == AgySlashModalKind.PERMISSIONS ||
                kind == AgySlashModalKind.RESUME
            ) {
                if (tabs.isEmpty() && ("All Changes" in t || "Commit Tree" in t ||
                        "Installed" in t && "Discover" in t ||
                        "TUI" in t && "Remote Agent" in t ||
                        "Conversation" in t && "Other" in t)
                ) {
                    val parts = splitColumnsRegex.split(t).map { it.trim('[', ']', ' ') }.filter { it.isNotEmpty() }
                    if (parts.size in 2..4) {
                        tabs.addAll(parts)
                        val activePos = parts.indexOfFirst { p ->
                            "[${p}]" in t || "● $p" in t || (line.hasBgHighlight && t.contains(p))
                        }
                        if (activePos >= 0) activeTabIdx = activePos
                    }
                }
            }
            if ("Effort" in t && effortTokenRegex.containsMatchIn(t)) {
                effortHint = effortTokenRegex.find(t)?.groupValues?.get(1)?.lowercase()
            }
        }
        if (effortHint == null) {
            for (t in nonEmptyTexts) {
                if ("Effort:" in t || "  Effort  " in t || (kind == AgySlashModalKind.MODEL && effortTokenRegex.containsMatchIn(t))) {
                    effortTokenRegex.find(t)?.let {
                        effortHint = it.groupValues[1].lowercase()
                    }
                }
            }
        }

        val footerSet = footerLines.toSet()
        val items = ArrayList<AgyModalItem>()
        var selectedIdx = 0
        var foundExplicitCursor = false

        for (line in window) {
            val t = line.text.trim()
            if (t.isEmpty() || t in footerSet || longRuleRegex.matches(t) ||
                AntigravityTranscriptSpec.isModelStatusFooter(t) ||
                AntigravityTranscriptSpec.isCustomStatusLine(t) ||
                modalHeaderKnownRegex.matches(t) ||
                searchPromptRegex.matches(t)
            ) {
                continue
            }
            if (t.startsWith("╭") || t.startsWith("╰") || t.startsWith("─")) continue
            if ("type to filter" in t.lowercase() || "press enter" in t.lowercase() || "tip:" in t.lowercase()) continue

            val hasCursorChar = t.startsWith("> ") || t.startsWith("› ") || t.startsWith("❯ ") || t.startsWith("▸ ") ||
                (line.firstChar == '●' && line.hasBgHighlight)
            val isRowSelected = hasCursorChar || line.hasBgHighlight

            val cleanBody = t
                .removePrefix("> ")
                .removePrefix("› ")
                .removePrefix("❯ ")
                .removePrefix("▸ ")
                .trim()
            if (cleanBody.isEmpty()) continue
            if (cleanBody.startsWith("←") || cleanBody.startsWith("↑/↓")) continue

            val isActiveMark = "✓" in cleanBody || "(current)" in cleanBody || "[on]" in cleanBody
            val cols = splitColumnsRegex.split(cleanBody, limit = 2)
            val rowTitle = cols[0].trim()
            val rowValue = cols.getOrNull(1)?.trim().orEmpty()

            if (rowTitle.isNotEmpty() && items.size < 40) {
                val idx = items.size
                if (isRowSelected && !foundExplicitCursor) {
                    selectedIdx = idx
                    foundExplicitCursor = true
                }
                items.add(
                    AgyModalItem(
                        index = idx,
                        title = rowTitle,
                        subtitleOrValue = rowValue,
                        isSelected = isRowSelected,
                        isCurrentActive = isActiveMark
                    )
                )
            }
        }

        if (!hasKnownHeader && items.isEmpty()) {
            return null
        }

        val rawFooter = footerLines.joinToString(" · ")
        val supportsLR = kind in setOf(
            AgySlashModalKind.MODEL,
            AgySlashModalKind.EFFORT,
            AgySlashModalKind.CONFIG,
            AgySlashModalKind.MCP,
            AgySlashModalKind.PERMISSIONS,
            AgySlashModalKind.DIFF
        ) || "←/→" in rawFooter
        val supportsTab = tabs.size > 1 ||
            "Switch Tab" in rawFooter ||
            "switch view" in rawFooter.lowercase() ||
            kind in setOf(AgySlashModalKind.CONFIG, AgySlashModalKind.DIFF, AgySlashModalKind.PLUGIN, AgySlashModalKind.RESUME)

        return AgySlashModalState(
            kind = kind,
            headerTitle = headerTitle,
            tabs = tabs,
            activeTabIndex = activeTabIdx,
            searchQuery = searchQuery,
            items = items,
            selectedIndex = selectedIdx.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            effortHint = effortHint,
            supportsLeftRightAdjust = supportsLR,
            supportsTabSwitch = supportsTab,
            rawHintFooter = rawFooter
        )
    }

    private fun classifyModalKind(lines: List<String>, joined: String): AgySlashModalKind = when {
        "Available models" in joined || "Select Model" in joined || "No models match" in joined -> AgySlashModalKind.MODEL
        "Reasoning Effort" in joined -> AgySlashModalKind.EFFORT
        "Settings" in joined || "agentMode" in joined || "artifactReviewPolicy" in joined || "←/→ Change" in joined -> AgySlashModalKind.CONFIG
        "Conversations" in joined || "Exited /resume" in joined -> AgySlashModalKind.RESUME
        "Rewind" in joined && "↑/↓" in joined -> AgySlashModalKind.REWIND
        "Background Tasks" in joined || "Running Background Tasks" in joined -> AgySlashModalKind.TASKS
        "Available Agents" in joined || "Exited /agents" in joined -> AgySlashModalKind.AGENTS
        "Action required (" in joined || "Artifacts" in joined || "Artifact Review" in joined -> AgySlashModalKind.ARTIFACT
        diffHeaderPosRegex.containsMatchIn(joined) -> AgySlashModalKind.DIFF
        lines.any { Regex("^\\d+\\s+skills\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) } -> AgySlashModalKind.SKILLS
        "Manage MCP servers" in joined || "MCP Servers" in joined -> AgySlashModalKind.MCP
        "Plugins" in joined && ("Installed" in joined || "Discover" in joined) -> AgySlashModalKind.PLUGIN
        "Rule —" in joined || ("Permissions" in joined && "↑/↓" in joined) -> AgySlashModalKind.PERMISSIONS
        "Remaining AI Credits" in joined || "Usage" in joined && "Quota" in joined -> AgySlashModalKind.USAGE
        else -> AgySlashModalKind.GENERIC
    }
}
