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
    CONTEXT("/context", "上下文占用"),
    USAGE("/usage", "配额与用量"),
    CREDITS("/credits", "AI Credits 额度"),
    HELP("/help", "帮助与快捷键"),
    CHANGELOG("/changelog", "更新日志"),
    HOOKS("/hooks", "Hooks 钩子配置"),
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
    val bodyLines: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val effortHint: String? = null,
    val supportsLeftRightAdjust: Boolean = false,
    val supportsTabSwitch: Boolean = false,
    val dismissWithEnterAllowed: Boolean = false,
    val rawHintFooter: String = "",
    val startRawLineIndex: Int = -1,
    val endRawLineIndex: Int = -1
)

data class AgyBtwTurn(
    val question: String,
    val isRunning: Boolean = false,
    val statusSummary: String = "",
    val toolSteps: List<String> = emptyList(),
    val answerText: String = "",
    val isError: Boolean = false
)

data class AgyBtwCardState(
    val question: String,
    val isRunning: Boolean,
    val statusSummary: String = "",
    val toolSteps: List<String> = emptyList(),
    val answerText: String = "",
    val isError: Boolean = false,
    val isExpanded: Boolean = false,
    val footerHint: String = "",
    val startRawLineIndex: Int = -1,
    val endRawLineIndex: Int = -1,
    val turns: List<AgyBtwTurn> = emptyList()
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
    val activeBtwCard: AgyBtwCardState? = null,
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
            description = "查看各模型配额占用与刷新时间",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.USAGE
        ),
        SlashCommandSpec(
            cmd = "/context",
            title = "上下文占用",
            description = "查看当前上下文 Token 分布与系统规则文件",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.CONTEXT
        ),
        SlashCommandSpec(
            cmd = "/credits",
            title = "AI Credits",
            description = "查看剩余 AI Credits 与配额开关",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.CREDITS
        ),
        SlashCommandSpec(
            cmd = "/hooks",
            title = "Hooks 钩子",
            description = "查看或管理 PreToolUse / Stop 等生命周期钩子",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.HOOKS
        ),
        SlashCommandSpec(
            cmd = "/help",
            title = "帮助与快捷键",
            description = "打开通用信息 / 命令列表 / 快捷键面板",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.HELP
        ),
        SlashCommandSpec(
            cmd = "/changelog",
            title = "更新日志",
            description = "浏览当前版本 Antigravity CLI 变更说明",
            category = Category.PANELS,
            modalKind = AgySlashModalKind.CHANGELOG
        ),
        SlashCommandSpec(
            cmd = "/statusline",
            title = "状态栏开关",
            description = "切换自定义状态栏显示状态",
            category = Category.CONTEXT
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
        "(?i)(?:↑/↓|←/→|^Keyboard:|\\bNavigate\\b|\\bChange\\b|\\bScroll\\b|\\bswitch view\\b|\\bswitch tab\\b|\\bCycle Tabs\\b|tab\\s+(?:to\\s+cycle|Switch|Effort|Amend)|space\\s+to\\s+toggle|enter\\s+(?:Submit|Select|View|Expand|Actions|Save|open)|esc\\s+(?:to\\s+(?:close|exit|discard|cancel|back|skip)|Close|Exit|Cancel|Dismiss|Go [Bb]ack|Clear)|Enter/Esc\\s+to\\s+(?:dismiss|cancel))"
    )
    private val modalHeaderKnownRegex = Regex(
        "(?i)^(?:Available\\s+models|Select\\s+Model|Switch\\s+Model|Reasoning\\s+Effort|Select\\s+Effort|Settings|Conversations|Background\\s+Tasks|Running\\s+Background\\s+Tasks|Available\\s+Agents|Create\\s+New\\s+Agents|Artifacts|Action\\s+required\\s*\\(|Manage\\s+MCP\\s+servers|MCP\\s+Servers|All\\s+Changes|Per\\s+Turn|Commit\\s+Tree|Diff\\s*\\(git\\)|Rule\\s+—|Permission\\s+Config\\s+Editor|Permissions|Rewind(?:\\s+Conversation)?|\\d+\\s+skills|Skills|Remaining\\s+AI\\s+Credits|Model\\s+AI\\s+Credits|Plugins|Hooks|Tasks|└\\s*Context\\s+Usage|Context\\s+Usage|└\\s*Models\\s*&\\s*Quota|Models\\s*&\\s*Quota|Antigravity\\s+CLI\\s+Changelog|Antigravity\\s+CLI\\s+(?:general|credits))\\b.*"
    )
    private val effortTokenRegex = Regex("(?i)\\b(low|medium|high|xhigh|max)\\b")
    private val workingLineRegex = Regex(
        "(?i)(?:^[\\u2800-\\u28FF✻✽✶✳✢]\\s+\\S|^Model unavailable\\s*\\(attempt|^(?:[╭╰]─\\s*|[●◆⚙🔧⏳]\\s+)(?:[●◆⚙🔧⏳]\\s*)?(?:THINKING|WORKING|TOOL)\\b|Compacting conversation\\.{2,3})"
    )
    private val slashSuggestionItemRegex = Regex("^/[a-z][a-z0-9-]{1,24}\\b.*")
    private val diffHeaderPosRegex = Regex("(?i)(?:\\bchange\\s+\\d+/\\d+|\\bfile\\s+\\d+/\\d+|All\\s+Changes|Commit\\s+Tree|Diff\\s*\\(git\\))")
    private val searchPromptRegex = Regex("^(?:Search|Filter)\\s*[:：]\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val searchPlaceholderRegex = Regex("(?i)^Type to search\\b.*$")
    private val shortRuleRegex = Regex("^[─━═]{4,}$")
    private val splitColumnsRegex = Regex("\\s{2,}")
    private val tabActiveSegmentRegex = Regex("(?:\\[([^\\]]+)]|\\(([^)]+)\\)|●\\s*(\\S[^·|]{1,24}))")
    private val btwSpinnerLineRegex = Regex("^[\\u2800-\\u28FF✻✽✶✳✢]\\s+(.+)$")
    private val btwFooterLineRegex = Regex("(?i)(?:Tab to expand|Enter/Esc to (?:cancel|dismiss)|esc to (?:cancel|dismiss))")

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
                activeBtwCard = null,
                hasWorkingHint = footerMeta.hasWorking
            )
        }

        val btwCard = detectBtwCard(rawLines, composerInfo)

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
                activeBtwCard = btwCard,
                hasWorkingHint = footerMeta.hasWorking
            )
        }

        val slashModal = detectSlashModal(rawLines, composerInfo)
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
                activeBtwCard = btwCard,
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
            activeBtwCard = btwCard,
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
        val isSlashSuggestionOpen: Boolean,
        val topRuleIdx: Int = -1,
        val bottomRuleIdx: Int = -1
    )

    private fun extractComposerInfo(rawLines: List<AgyRawStyledLine>): ComposerInfo {
        var bottomRuleIdx = -1
        val minSearch = 0
        // Note: when docked cards like `/config` (~28 rows) or `/usage` (~38 rows) sit BELOW the composer box,
        // `bottomRuleIdx` can sit near the top of a 45-line screen! Scan upward skipping panel lines.
        for (idx in rawLines.lastIndex downTo minSearch) {
            val t = rawLines[idx].text.trim()
            if (longRuleRegex.matches(t) && idx > 0) {
                // Verify the line above looks like a composer prompt `> ...` / `› ...` with another rule within 6 lines above
                val probeTop = (idx - 6).coerceAtLeast(0)
                val maybeTop = (idx - 1 downTo probeTop).firstOrNull { longRuleRegex.matches(rawLines[it].text.trim()) }
                if (maybeTop != null) {
                    val firstInner = ((maybeTop + 1) until idx)
                        .firstOrNull { rawLines[it].indent >= 0 && rawLines[it].text.isNotBlank() }
                        ?.let { rawLines[it].text.trim() }
                        .orEmpty()
                    val unwrappedFirst = if (firstInner.startsWith("│") && firstInner.endsWith("│") && firstInner.length >= 2) {
                        firstInner.substring(1, firstInner.length - 1).trimStart()
                    } else {
                        firstInner
                    }
                    if (unwrappedFirst.startsWith(">") || unwrappedFirst.startsWith("›") || unwrappedFirst.startsWith("❯")) {
                        bottomRuleIdx = idx
                        break
                    }
                }
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
                isSlashSuggestionOpen = false,
                topRuleIdx = topRuleIdx,
                bottomRuleIdx = bottomRuleIdx
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
            isSlashSuggestionOpen = hasSlashPopup,
            topRuleIdx = topRuleIdx,
            bottomRuleIdx = bottomRuleIdx
        )
    }

    private fun detectBtwCard(rawLines: List<AgyRawStyledLine>, composerInfo: ComposerInfo): AgyBtwCardState? {
        var cursor = if (composerInfo.hasComposerBox && composerInfo.topRuleIdx > 0) {
            composerInfo.topRuleIdx - 1
        } else {
            rawLines.lastIndex
        }
        val skipTrailingFooterAndHc = !(composerInfo.hasComposerBox && composerInfo.topRuleIdx > 0)
        while (cursor >= 0) {
            val t = rawLines[cursor].text.trim()
            val shouldSkip = t.isEmpty() || (
                skipTrailingFooterAndHc && (
                    t.startsWith("[hc]") ||
                        AntigravityTranscriptSpec.isModelStatusFooter(t) ||
                        AntigravityTranscriptSpec.isCustomStatusLine(t)
                    )
                )
            if (shouldSkip) cursor-- else break
        }
        if (cursor < 0 || rawLines[cursor].firstChar != '┃') {
            return null
        }
        val endIdx = cursor
        var startIdx = cursor
        val floor = (cursor - 240).coerceAtLeast(0)
        while (cursor >= floor) {
            val l = rawLines[cursor]
            if (l.firstChar == '┃') {
                startIdx = cursor
                cursor--
            } else {
                break
            }
        }
        val cardLines = rawLines.subList(startIdx, endIdx + 1).map { line ->
            val trimmed = line.text.trimStart()
            trimmed.removePrefix("┃").removePrefix(" ").trimEnd()
        }
        if (cardLines.isEmpty()) return null
        val firstNonBlank = cardLines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val isBtwHeader = firstNonBlank.startsWith("/btw", ignoreCase = true) ||
            firstNonBlank.startsWith("/help ", ignoreCase = true)
        if (!isBtwHeader) {
            return null
        }

        // Split multi-turn ┃ blocks wherever a new `/btw ...` (or `/help ...`) header begins
        val turnSlices = ArrayList<ArrayList<String>>()
        for (cardLine in cardLines) {
            val trimmed = cardLine.trim()
            val startsNewTurn = trimmed.equals("/btw", ignoreCase = true) ||
                trimmed.startsWith("/btw ", ignoreCase = true) ||
                trimmed.startsWith("/help ", ignoreCase = true)
            if (startsNewTurn || turnSlices.isEmpty()) {
                turnSlices.add(ArrayList())
            }
            turnSlices.last().add(cardLine)
        }

        var sharedFooterHint = ""
        var sharedExpanded = false
        val parsedTurns = turnSlices.map { slice ->
            val parsed = parseBtwTurnSlice(slice)
            if (parsed.footerHint.isNotEmpty()) sharedFooterHint = parsed.footerHint
            if (parsed.isExpanded) sharedExpanded = true
            parsed.turn
        }
        if (parsedTurns.isEmpty()) return null
        val latest = parsedTurns.last()
        return AgyBtwCardState(
            question = latest.question,
            isRunning = latest.isRunning,
            statusSummary = latest.statusSummary,
            toolSteps = latest.toolSteps,
            answerText = latest.answerText,
            isError = latest.isError,
            isExpanded = sharedExpanded,
            footerHint = sharedFooterHint,
            startRawLineIndex = startIdx,
            endRawLineIndex = endIdx,
            turns = parsedTurns
        )
    }

    private data class ParsedBtwSlice(
        val turn: AgyBtwTurn,
        val footerHint: String,
        val isExpanded: Boolean
    )

    private fun parseBtwTurnSlice(sliceLines: List<String>): ParsedBtwSlice {
        val qParts = ArrayList<String>()
        var idx = 0
        while (idx < sliceLines.size) {
            val trimmed = sliceLines[idx].trim()
            if (trimmed.isEmpty()) {
                idx++
                break
            }
            val qLine = when {
                idx == 0 && trimmed.startsWith("/btw ", ignoreCase = true) -> trimmed.drop(5).trim()
                idx == 0 && trimmed.equals("/btw", ignoreCase = true) -> ""
                idx == 0 && trimmed.startsWith("/help ", ignoreCase = true) -> trimmed.drop(6).trim()
                else -> trimmed
            }
            if (idx > 0 && (trimmed.startsWith("✓") || trimmed.startsWith("✗") ||
                    trimmed.startsWith("●") || trimmed.startsWith("○") ||
                    btwSpinnerLineRegex.matches(trimmed) || trimmed.startsWith("Called ") ||
                    btwFooterLineRegex.containsMatchIn(trimmed))
            ) {
                break
            }
            if (qLine.isNotEmpty()) qParts.add(qLine)
            idx++
        }
        val question = qParts.joinToString(" ").trim()
        val tailLines = if (idx < sliceLines.size) sliceLines.subList(idx, sliceLines.size) else emptyList()

        var footerHint = ""
        val toolSteps = ArrayList<String>()
        var statusSummary = ""
        val answerLines = ArrayList<String>()
        var running = false
        var errorFlag = false
        var expandedFlag = false
        var sawCalledSummary = false

        for (rawBodyLine in tailLines) {
            val t = rawBodyLine.trim()
            if (t.isEmpty()) {
                if (answerLines.isNotEmpty() && answerLines.last().isNotEmpty()) {
                    answerLines.add("")
                }
                continue
            }
            if (btwFooterLineRegex.containsMatchIn(t)) {
                footerHint = t
                val lower = t.lowercase()
                if ("to cancel" in lower) running = true
                if ("to collapse" in lower) expandedFlag = true
                val spin = btwSpinnerLineRegex.find(t)
                if (spin != null) {
                    statusSummary = spin.groupValues[1].replace(Regex("\\s*\\(Enter/Esc to cancel\\)", RegexOption.IGNORE_CASE), "").trim()
                    running = true
                }
                continue
            }
            val spinMatch = btwSpinnerLineRegex.find(t)
            if (spinMatch != null && answerLines.isEmpty()) {
                running = true
                statusSummary = spinMatch.groupValues[1].trim()
                continue
            }
            if (answerLines.isEmpty() && !sawCalledSummary &&
                (t.startsWith("✓ ") || t.startsWith("✗ ") || t.startsWith("● ") || t.startsWith("○ "))
            ) {
                toolSteps.add(t)
                if (t.startsWith("✗ ")) errorFlag = true
                continue
            }
            if (answerLines.isEmpty() && Regex("^Called \\d+ tools?\\b.*", RegexOption.IGNORE_CASE).matches(t)) {
                statusSummary = t
                sawCalledSummary = true
                continue
            }
            if (t.startsWith("Error:", ignoreCase = true) ||
                t.startsWith("✗ ") ||
                (answerLines.isEmpty() && t.contains("failed", ignoreCase = true))
            ) {
                errorFlag = true
            }
            // Preserve leading indentation so tables, ASCII boxes, and fenced code stay aligned
            answerLines.add(rawBodyLine)
        }
        while (answerLines.isNotEmpty() && answerLines.first().isBlank()) {
            answerLines.removeAt(0)
        }
        while (answerLines.isNotEmpty() && answerLines.last().isBlank()) {
            answerLines.removeAt(answerLines.lastIndex)
        }
        val minIndent = answerLines
            .filter { it.isNotBlank() }
            .minOfOrNull { line ->
                val idxFirst = line.indexOfFirst { it != ' ' }
                if (idxFirst < 0) 0 else idxFirst
            } ?: 0
        val answer = answerLines.joinToString("\n") { line ->
            if (line.isBlank()) {
                ""
            } else {
                var drop = 0
                while (drop < minIndent && drop < line.length && line[drop] == ' ') drop++
                line.substring(drop)
            }
        }.trimEnd()

        return ParsedBtwSlice(
            turn = AgyBtwTurn(
                question = question.ifBlank { "/btw" },
                isRunning = running,
                statusSummary = statusSummary,
                toolSteps = toolSteps,
                answerText = answer,
                isError = errorFlag
            ),
            footerHint = footerHint,
            isExpanded = expandedFlag
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
            if (t.startsWith("[hc]") ||
                AntigravityTranscriptSpec.isKeyHintFooter(t) ||
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

    private fun detectSlashModal(rawLines: List<AgyRawStyledLine>, composerInfo: ComposerInfo): AgySlashModalState? {
        if (rawLines.isEmpty()) return null

        val isDockedBelowComposer = composerInfo.hasComposerBox &&
            composerInfo.bottomRuleIdx in 0 until rawLines.lastIndex

        var startRawIdx: Int
        var endRawIdx: Int
        if (isDockedBelowComposer) {
            startRawIdx = composerInfo.bottomRuleIdx + 1
            endRawIdx = rawLines.lastIndex
            while (endRawIdx >= startRawIdx) {
                val t = rawLines[endRawIdx].text.trim()
                if (t.isEmpty() ||
                    t.startsWith("[hc]") ||
                    AntigravityTranscriptSpec.isModelStatusFooter(t) ||
                    AntigravityTranscriptSpec.isCustomStatusLine(t) ||
                    t.equals("? for shortcuts", ignoreCase = true) ||
                    t.equals("esc to cancel", ignoreCase = true)
                ) {
                    endRawIdx--
                } else {
                    break
                }
            }
            if (startRawIdx > endRawIdx) return null
        } else {
            endRawIdx = rawLines.lastIndex
            while (endRawIdx >= 0) {
                val t = rawLines[endRawIdx].text.trim()
                if (t.isEmpty() ||
                    t.startsWith("[hc]") ||
                    AntigravityTranscriptSpec.isModelStatusFooter(t) ||
                    AntigravityTranscriptSpec.isCustomStatusLine(t)
                ) {
                    endRawIdx--
                } else {
                    break
                }
            }
            var hdrIdx = -1
            for (idx in endRawIdx downTo 0) {
                val t = rawLines[idx].text.trim()
                if (t.isNotEmpty() && (modalHeaderKnownRegex.matches(t) || diffHeaderPosRegex.containsMatchIn(t))) {
                    hdrIdx = idx
                }
            }
            startRawIdx = if (hdrIdx >= 0) {
                if (hdrIdx <= 3) 0 else hdrIdx
            } else {
                (endRawIdx - 52).coerceAtLeast(0)
            }
            if (startRawIdx > endRawIdx) return null
        }

        val rawSlice = rawLines.subList(startRawIdx, endRawIdx + 1)
        val window = rawSlice.filter { it.indent >= 0 }
        if (window.isEmpty()) return null

        val nonEmptyTexts = window.map { it.text.trim() }
        // Strip `/` autocomplete popup if user just typed `/model` in composer (when `draft.startsWith("/")`)
        if (composerInfo.isSlashSuggestionOpen) return null
        if (nonEmptyTexts.all { slashSuggestionItemRegex.matches(it) }) return null

        val footerLines = nonEmptyTexts.takeLast(4).filter {
            modalFooterHintRegex.containsMatchIn(it) || AntigravityTranscriptSpec.isKeyHintFooter(it)
        }
        val joined = nonEmptyTexts.joinToString("\n")
        val hasKnownHeader = nonEmptyTexts.any { modalHeaderKnownRegex.matches(it) } ||
            diffHeaderPosRegex.containsMatchIn(joined) ||
            ("Available Models" in joined || "Select Model" in joined || "Switch Model" in joined || "No models match" in joined)

        val hasStrongModalFooter = nonEmptyTexts.takeLast(4).any { t ->
            t.startsWith("Keyboard:", ignoreCase = true) ||
                "↑/↓" in t || "←/→" in t || "Navigate" in t || "Switch Tab" in t || "switch view" in t.lowercase() ||
                "tab Amend" in t || "space to toggle" in t.lowercase() ||
                "Enter Select" in t || "Enter Confirm" in t || "Enter Resume" in t ||
                "esc Close" in t || "esc Exit" in t || "esc Cancel" in t || "esc Dismiss" in t ||
                "Esc to close" in t || "Esc to back" in t || "Esc to exit" in t || "esc Go Back" in t ||
                "Kill Task" in t || "Kill Active Subagent" in t
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

        for (line in window.take(10)) {
            val t = line.text.trim()
            searchPromptRegex.find(t)?.let { m ->
                searchQuery = m.groupValues[1].trim()
            }
            if (searchQuery == null && searchPlaceholderRegex.matches(t)) {
                searchQuery = ""
            }
            if (tabs.isEmpty()) {
                when {
                    "All Changes" in t && ("Commit Tree" in t || "Per Turn" in t || "Diff (git)" in t) -> {
                        val cleanedTabs = t.removePrefix("Diff (git)").trim()
                        val parts = splitColumnsRegex.split(cleanedTabs).map { it.trim('[', ']', ' ') }.filter { it.isNotEmpty() }
                        if (parts.size in 2..4) tabs.addAll(parts)
                    }
                    "general" in t && "commands" in t && "shortcuts" in t -> {
                        tabs.addAll(listOf("general", "commands", "shortcuts"))
                    }
                    "Installed" in t && "Discover" in t -> {
                        val cleanedTabs = t.replace(Regex("\\(tab to cycle\\)", RegexOption.IGNORE_CASE), "").trim()
                        val parts = splitColumnsRegex.split(cleanedTabs).map { it.trim('[', ']', ' ') }.filter { it.isNotEmpty() }
                        if (parts.size in 2..4) tabs.addAll(parts)
                    }
                    ("CLI" in t && "Other" in t && "tab to cycle" in t.lowercase()) ||
                        ("TUI" in t && "Remote Agent" in t) ||
                        ("Conversation" in t && "Other" in t) -> {
                        val cleanedTabs = t.replace(Regex("\\(tab to cycle\\)", RegexOption.IGNORE_CASE), "").trim()
                        val parts = splitColumnsRegex.split(cleanedTabs).map { it.trim('[', ']', ' ') }.filter { it.isNotEmpty() }
                        if (parts.size in 2..4) tabs.addAll(parts)
                    }
                }
                if (tabs.isNotEmpty()) {
                    val activePos = tabs.indexOfFirst { p ->
                        "[${p}]" in t || "● $p" in t || (line.hasBgHighlight && t.contains(p))
                    }
                    if (activePos >= 0) activeTabIdx = activePos
                }
            }
            if (effortHint == null && "Effort" in t && effortTokenRegex.containsMatchIn(t)) {
                effortHint = effortTokenRegex.find(t)?.groupValues?.get(1)?.lowercase()
            }
        }
        if (effortHint == null) {
            for (t in nonEmptyTexts) {
                if (("Effort" in t) && effortTokenRegex.containsMatchIn(t)) {
                    effortHint = effortTokenRegex.find(t)?.groupValues?.get(1)?.lowercase()
                    if (effortHint != null) break
                }
            }
        }

        val isLoading = window.any { btwSpinnerLineRegex.matches(it.text.trim()) && "Loading" in it.text }
        val isInfoOnlyKind = kind == AgySlashModalKind.CONTEXT ||
            kind == AgySlashModalKind.USAGE ||
            kind == AgySlashModalKind.CHANGELOG

        val footerSet = footerLines.toSet()
        val items = ArrayList<AgyModalItem>()
        val bodyLines = ArrayList<String>()
        var selectedIdx = 0
        var foundExplicitCursor = false

        for (line in window) {
            val rawTrimEnd = line.text.trimEnd()
            val t = rawTrimEnd.trim()
            if (t.isEmpty() || t.startsWith("[hc]") || t in footerSet || longRuleRegex.matches(t) || shortRuleRegex.matches(t) ||
                AntigravityTranscriptSpec.isModelStatusFooter(t) ||
                AntigravityTranscriptSpec.isCustomStatusLine(t) ||
                searchPromptRegex.matches(t) ||
                searchPlaceholderRegex.matches(t)
            ) {
                continue
            }
            if (t == headerTitle || (kind == AgySlashModalKind.CREDITS && t.equals("Model AI Credits", ignoreCase = true))) {
                continue
            }
            if (tabs.isNotEmpty() && tabs.all { it in t } && t.length < 96) continue
            if (t.startsWith("╭") || t.startsWith("╰") || t.startsWith("─")) continue
            if ("type to filter" in t.lowercase() || "tip:" in t.lowercase()) continue

            if (isInfoOnlyKind) {
                bodyLines.add(rawTrimEnd.trimStart(' '))
                continue
            }

            // Detect descriptive paragraphs, section headers, planner gauges, or empty states vs selectable rows
            val hasCursorChar = t.startsWith("> ") || t.startsWith("› ") || t.startsWith("▸ ") ||
                (line.firstChar == '●' && line.hasBgHighlight)
            val isEmptyState = t.equals("No background tasks.", ignoreCase = true) ||
                t.equals("No artifacts", ignoreCase = true) ||
                t.startsWith("No models match", ignoreCase = true) ||
                t.startsWith("No conversations", ignoreCase = true)
            val isProseOrMeta = !hasCursorChar && (
                t.startsWith("▸ Planner") || t.startsWith("Executor ") ||
                    t.startsWith("Workspace:") || t.startsWith("Global:") || t.startsWith("Shared:") ||
                    t.startsWith("Project Config:") || t.startsWith("Log File:") || t.startsWith("Conversation:") ||
                    t.startsWith("Remaining AI Credits:") || t.startsWith("Select a config scope") ||
                    t.startsWith("Related:") || t.startsWith("[") && " of " in t && "items]" in t ||
                    Regex("^\\d+\\s+(?:skills|hook types|file\\(s\\) changed)\\b", RegexOption.IGNORE_CASE).matches(t) ||
                    t.equals("Create new skills", true) || t.equals("Create New Agents", true) ||
                    t.equals("Actions", true) || t.equals("Available Agents", true) ||
                    t.equals("Quick Reference", true) || t.startsWith("Version ", true) ||
                    t.startsWith("Account:", true) || t.startsWith("Shared skills ·", true) ||
                    t.endsWith("· Built-in") || t.startsWith("'") ||
                    (kind == AgySlashModalKind.HELP && !Regex("^/[a-z]", RegexOption.IGNORE_CASE).containsMatchIn(t)) ||
                    (kind == AgySlashModalKind.CONFIG && t.length > 76 && !splitColumnsRegex.containsMatchIn(t)) ||
                    (kind == AgySlashModalKind.PERMISSIONS && t.startsWith("Rules that apply", true))
                )

            if (isEmptyState || isProseOrMeta) {
                bodyLines.add(t)
                continue
            }

            val isRowSelected = hasCursorChar || line.hasBgHighlight
            val cleanBody = t
                .removePrefix("> ")
                .removePrefix("› ")
                .removePrefix("❯ ")
                .removePrefix("▸ ")
                .trim()
            if (cleanBody.isEmpty()) continue
            if (cleanBody.startsWith("←") || cleanBody.startsWith("↑/↓")) continue

            val isActiveMark = "✓" in cleanBody || "(current)" in cleanBody || "[on]" in cleanBody || "[enabled]" in cleanBody
            val cols = when {
                kind == AgySlashModalKind.SKILLS && ":" in cleanBody -> {
                    listOf(cleanBody.substringBefore(':').trim(), cleanBody.substringAfter(':').trim())
                }
                else -> splitColumnsRegex.split(cleanBody, limit = 2)
            }
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

        if (!hasKnownHeader && items.isEmpty() && bodyLines.isEmpty()) {
            return null
        }

        val rawFooter = footerLines.joinToString(" · ")
        val supportsLR = kind in setOf(
            AgySlashModalKind.MODEL,
            AgySlashModalKind.EFFORT,
            AgySlashModalKind.CONFIG,
            AgySlashModalKind.MCP,
            AgySlashModalKind.PERMISSIONS,
            AgySlashModalKind.DIFF,
            AgySlashModalKind.HELP,
            AgySlashModalKind.TASKS
        ) || "←/→" in rawFooter
        val supportsTab = tabs.size > 1 ||
            "Switch Tab" in rawFooter ||
            "Cycle Tabs" in rawFooter ||
            "switch view" in rawFooter.lowercase() ||
            "tab Effort" in rawFooter ||
            kind in setOf(AgySlashModalKind.CONFIG, AgySlashModalKind.DIFF, AgySlashModalKind.PLUGIN, AgySlashModalKind.RESUME, AgySlashModalKind.HELP)

        return AgySlashModalState(
            kind = kind,
            headerTitle = headerTitle,
            tabs = tabs,
            activeTabIndex = activeTabIdx,
            searchQuery = searchQuery,
            items = items,
            selectedIndex = selectedIdx.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            bodyLines = bodyLines.take(60),
            isLoading = isLoading,
            effortHint = effortHint,
            supportsLeftRightAdjust = supportsLR,
            supportsTabSwitch = supportsTab,
            dismissWithEnterAllowed = "enter/esc" in rawFooter.lowercase(),
            rawHintFooter = rawFooter,
            startRawLineIndex = startRawIdx,
            endRawLineIndex = endRawIdx
        )
    }

    private fun classifyModalKind(lines: List<String>, joined: String): AgySlashModalKind {
        val topHead = lines.take(4).joinToString("\n")
        return when {
            "Antigravity CLI Changelog" in topHead -> AgySlashModalKind.CHANGELOG
            "Diff (git)" in topHead || diffHeaderPosRegex.containsMatchIn(topHead) -> AgySlashModalKind.DIFF
            "Switch Model" in topHead || "Available models" in topHead || "Select Model" in topHead -> AgySlashModalKind.MODEL
            "Settings" in topHead -> AgySlashModalKind.CONFIG
            "└ Context Usage" in topHead || "Context Usage" in topHead -> AgySlashModalKind.CONTEXT
            "└ Models & Quota" in topHead || "Models & Quota" in topHead -> AgySlashModalKind.USAGE
            "Model AI Credits" in topHead || "Antigravity CLI  credits" in topHead -> AgySlashModalKind.CREDITS
            "Antigravity CLI" in topHead && "general" in topHead -> AgySlashModalKind.HELP
            "Switch Model" in joined || "Available models" in joined || "Select Model" in joined || "No models match" in joined -> AgySlashModalKind.MODEL
            "Reasoning Effort" in joined || "Select Effort" in joined -> AgySlashModalKind.EFFORT
            "Settings" in joined || "agentMode" in joined || "Agent Mode" in joined || "artifactReviewPolicy" in joined || "←/→ Change" in joined -> AgySlashModalKind.CONFIG
            "└ Context Usage" in joined || "Context Usage" in joined -> AgySlashModalKind.CONTEXT
            "└ Models & Quota" in joined || "Models & Quota" in joined || "Weekly Limit Remaining" in joined -> AgySlashModalKind.USAGE
            "Model AI Credits" in joined || "Remaining AI Credits" in joined -> AgySlashModalKind.CREDITS
            "Antigravity CLI Changelog" in joined -> AgySlashModalKind.CHANGELOG
            ("Antigravity CLI" in joined && "general" in joined && "commands" in joined) || ("Quick Reference" in joined && "↑/↓ Navigate" in joined) -> AgySlashModalKind.HELP
            "5 hook types" in joined || ("Hooks" in lines && "PreToolUse" in joined) -> AgySlashModalKind.HOOKS
            "Conversations" in joined || "Exited /resume" in joined -> AgySlashModalKind.RESUME
            "Rewind" in joined && "↑/↓" in joined -> AgySlashModalKind.REWIND
            "No background tasks." in joined || "Running Background Tasks" in joined || "Background Tasks" in joined || ("Tasks" in lines && "Kill Task" in joined) -> AgySlashModalKind.TASKS
            "Available Agents" in joined || "Create New Agents" in joined || "Exited /agents" in joined -> AgySlashModalKind.AGENTS
            "Action required (" in joined || "Artifacts" in joined || "Artifact Review" in joined -> AgySlashModalKind.ARTIFACT
            diffHeaderPosRegex.containsMatchIn(joined) -> AgySlashModalKind.DIFF
            lines.any { Regex("^\\d+\\s+skills\\b", RegexOption.IGNORE_CASE).containsMatchIn(it) } || ("Skills" in lines && "Create new skills" in joined) -> AgySlashModalKind.SKILLS
            "Manage MCP servers" in joined || "MCP Servers" in joined -> AgySlashModalKind.MCP
            "Plugins" in joined && ("Installed" in joined || "Discover" in joined) -> AgySlashModalKind.PLUGIN
            "Permission Config Editor" in joined || "Rule —" in joined || ("Permissions" in joined && "↑/↓" in joined) -> AgySlashModalKind.PERMISSIONS
            else -> AgySlashModalKind.GENERIC
        }
    }
}
