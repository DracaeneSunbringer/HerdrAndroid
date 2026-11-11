package dev.herdr.android.ui.terminal

/**
 * Source-verified terminal grammar for OpenAI Codex CLI (`openai/codex`, `codex-rs/tui/src/`).
 *
 * Every pattern in this specification maps 1-to-1 to concrete `HistoryCell` and `BottomPane`
 * implementations in `codex-rs/tui/src/`:
 * - `history_cell/messages.rs`: `UserHistoryCell`, `AgentMarkdownCell`, `AgentMessageCell`, `ReasoningSummaryCell`
 * - `exec_cell/render.rs` & `compact.rs` & `history_cell/exec.rs`: `ExecCell`, `UnifiedExecInteractionCell`, `UnifiedExecProcessesCell`
 * - `history_cell/patches.rs` & `diff_render.rs`: `PatchHistoryCell`, `PatchFailureCell`, `ViewImageHistoryCell`, `ImageGenerationHistoryCell`
 * - `history_cell/plans.rs`: `ProposedPlanCell`, `PlanUpdateCell`
 * - `history_cell/mcp.rs` & `dynamic.rs`: `McpToolCallCell`, `McpInventoryLoadingCell`, `DynamicToolCallCell`
 * - `history_cell/search.rs`: `WebSearchCell`
 * - `multi_agents.rs` & `history_cell/delegated_message.rs`: Collab / SubAgent cells & `DelegatedMessageCell`
 * - `history_cell/hook_cell.rs`: `HookCell`
 * - `history_cell/approvals.rs`: `new_approval_decision_cell`, guardian denial/timeout cells
 * - `history_cell/request_user_input.rs`: `RequestUserInputResultCell`
 * - `history_cell/notices.rs` & `warnings.rs`: `new_error_event` (`■ `), `WarningHistoryCell` (`⚠ `), `SafetyAccessBlockCell` (`ⓘ `), `ThreadRecapHistoryCell`, `UpdateAvailableHistoryCell`
 * - `history_cell/separators.rs`: `FinalMessageSeparator` (`Worked for <1s|... • <time> • <metrics>`)
 * - `history_cell/session.rs`: `SessionHeaderHistoryCell` (`>_ OpenAI Codex (v...)`)
 * - `transcript_view/layout.rs`: `activity_layout` disclosure labels (`+ Show details`, `− Hide details`, `+ N lines`, `− Show less`)
 * - `status_indicator_widget.rs`: `StatusIndicatorWidget` (`Working (Ns • esc to interrupt)`)
 * - `bottom_pane/approval_overlay.rs`: `ApprovalOverlay` modal prompts & choices
 * - `markdown_render.rs` & `task_lists.rs` & `local_links.rs`: unfenced 2-space code blocks, `———` rules, `☐`/`☑` task items, `━━━  ━━━` tables, local path links
 */
internal object CodexTranscriptSpec {

    // ---------------------------------------------------------------------------------------------
    // 1. Bottom Pane, Startup Header, Update Box & Quota Chrome
    // ---------------------------------------------------------------------------------------------

    /** `chat_composer.rs`: Idle composer placeholder lines (`› Ask Codex to do anything`, etc.). */
    val composerPlaceholderRegex = Regex(
        "^›\\s*(?:Ask Codex to do anything|Summarize recent commits|Explain this codebase|Find and fix a bug.*)\\s*$",
        RegexOption.IGNORE_CASE
    )

    /** `status_surface.rs` / `footer.rs`: Model + effort + CWD + thread title line below composer. */
    val modelCwdBarRegex = Regex(
        "^\\s*(?:GPT-[\\w.-]+|o[134](?:-[\\w.-]+)?|codex-[\\w.-]+|claude-[\\w.-]+|gemini-[\\w.-]+)" +
            "(?:\\s+(?:low|medium|high|xhigh|minimal))?\\s*·\\s+(?:[A-Za-z]:\\\\|/|~).*$"
    )

    /** `rate_limits.rs` / `warnings.rs`: Rate-limit heads-up & inline percentage notices. */
    val quotaHeadsUpRegex = Regex(
        "^\\s*⚠\\s*Heads up,\\s*you have less than \\d+% of your .* limit left\\..*$",
        RegexOption.IGNORE_CASE
    )
    val quotaInlineRegex = Regex(
        "^\\s*⚠\\s*(?:5h|weekly|daily)\\s+limit:\\s*\\d+%\\s+left\\b.*$",
        RegexOption.IGNORE_CASE
    )

    /** `session.rs`: `SessionHeaderHistoryCell` (`  >_ OpenAI Codex (v0.167.0)`). */
    val sessionHeaderTitleRegex = Regex("^\\s*>_\\s+OpenAI Codex\\s*\\(v[^)]+\\)\\s*$")

    /** `notices.rs:2964`: `UpdateAvailableHistoryCell` banner (`✨ Update available! 0.1.0 -> 0.2.0`). */
    val updateAvailableBannerRegex = Regex("^\\s*(?:✨[\\s\\u200A]*)?Update available!(?:\\s+.*)?$", RegexOption.IGNORE_CASE)

    /** `messages.rs`: `UserHistoryCell` image attachment badge above `› ` (`  [Image #1]`). */
    val userImageBadgeRegex = Regex("^\\s{2}(\\[Image #\\d+])(?:\\s+(\\[Image #\\d+]))*\\s*$")

    /** `slash_command.rs`: User-invoked slash commands in scrollback (`/status`, `/compact`, `/mcp`, etc.). */
    val slashCmdRegex = Regex(
        "^/(?:status|compact|clear|model|diff|review|init|help|approvals|permissions|mcp|mention|subagents|ps|daemon|theme|copy|resume|fork|new)\\b.*$"
    )

    // ---------------------------------------------------------------------------------------------
    // 2. Activity / Tool / Collab / Audit HistoryCells (`activity_ids() != empty` or tool events)
    // ---------------------------------------------------------------------------------------------

    /**
     * Matches top-level activity headers in `codex-rs/tui`:
     * - `ExecCell` (`exec_cell/render.rs`, `compact.rs`, `history_cell/exec.rs`):
     *   `Running`, `Ran`, `Exited (status N)`, `Background output`, `Interacted with`, `Waited for`,
     *   `Exploring`, `Explored`
     * - `PatchHistoryCell` & `PatchFailureCell` (`diff_render.rs`, `patches.rs`):
     *   `Added`, `Deleted`, `Edited`, `✘ Failed to apply patch`, `Viewed image`, `Generated Image:`, `✗ Image generation failed`
     * - `McpToolCallCell` & `DynamicToolCallCell` (`mcp.rs`, `dynamic.rs`):
     *   `Calling`, `Called`, `Failed <tool>`, `Interrupted <tool>`, `Loading MCP inventory`
     * - `WebSearchCell` (`search.rs`):
     *   `Searching the web`, `Searched the web`, `Browsing the web`, `Opening`, `Opened`, `Searching for`, `Searching page`, `Searched for`, `Searched page`
     * - `Collab / Multi-Agent` (`multi_agents.rs`):
     *   `Spawned`, `Agent spawn failed`, `Sent input to`, `Waiting for`, `Finished waiting`, `Closed`,
     *   `Resuming`, `Resumed`, `Agent resume failed`, `Started \``, `Interrupted \``, `Completed \``
     * - `HookCell` (`hook_cell.rs`):
     *   `Hook completed`, `Hook failed`, `Blocked by hook`, `Hook stopped`, `Hook running`, `↳ Hook ·`
     * - `ThreadRecapLoadingCell` (`notices.rs`):
     *   `Generating conversation recap`
     */
    val activityHeadRegex = Regex(
        "^(?:" +
            "•\\s+(?:" +
            "Exited\\s*\\(status\\s+\\d+\\)|" +
            "Explored\\b|Exploring\\b|" +
            "Background output\\s+|" +
            "Interacted with\\s+`|" +
            "Waited for\\s+(?:`|background terminal\\b)|" +
            "Viewed image\\s+|Generated Image:|" +
            "Loading MCP inventory|Generating conversation recap|" +
            "Browsing the web\\b|" +
            "(?:Searching|Searched)\\s+(?:the web\\b|page\\b|for\\s+)|" +
            "(?:Opening|Opened)(?:\\s+page\\b|\\s+https?://|\\s+\\S)|" +
            "Spawned\\s+|Agent (?:spawn|resume) failed\\b|Sent input to\\s+|" +
            "Waiting for\\s+|Finished waiting\\b|Closed\\s+|Resuming\\s+|Resumed\\s+|" +
            "(?:Started|Interrupted|Completed)\\s+`[^`]+`|" +
            "Hook (?:completed|failed|stopped|running)\\b|Blocked by hook\\b|" +
            "(?:Ran|Run|Running|Edited|Editing|Added|Adding|Deleted|Deleting|Moved|Moving|" +
            "Renamed|Renaming|Searched|Searching|Read|Reading|Wrote|Writing|Created|Creating|" +
            "Updated(?!\\s+Plan\\b)|Patched|Patching|Applied|Applying|Called|Calling|" +
            "Failed(?=\\s+[\\w.:-]+(?:\\s*·\\s*\\d+ms)?$)|" +
            "Interrupted(?=\\s+[\\w.:-]+$)|" +
            "Executed|Executing|Fetched|Fetching|Thinking|Thought|Inspected)\\b" +
            ")|" +
            "✘\\s+Failed to apply patch\\b|" +
            "✗\\s+Image generation failed\\b|" +
            "↳\\s+(?:Interacted with background terminal\\b|Hook\\s*·)" +
            ").*$"
    )

    /** `exec_cell/render.rs`: User-invoked `!cmd` shell execution inside Codex (`• You ran <cmd>`). */
    val userShellHeadRegex = Regex("^•\\s+You ran\\s+(.+)$")

    /**
     * `status_indicator_widget.rs`: Live in-flight status indicator above composer:
     * e.g. `• Working (12s • esc to interrupt)` or spinner + `Working (1m 02s • esc to interrupt)`.
     */
    val liveStatusIndicatorRegex = Regex(
        "^\\s*(?:[•\\u2800-\\u28FF✻✽✶✳✢]\\s+)?([A-Z][\\w\\s]{1,40}?)\\s*\\(\\d+[hms][^)]*esc to interrupt[^)]*\\).*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * `approvals.rs`: Completed approval / denial audit cells:
     * `✔ You approved codex to run ...`, `✗ You did not approve ...`, `✗ Request denied ...`, `✗ Review timed out ...`
     */
    val approvalAuditRegex = Regex(
        "^(?:[✔✓]\\s+(?:You|Auto-reviewer)\\s+(?:approved|persisted)\\b|" +
            "[✗✘]\\s+(?:You\\s+(?:did not approve|denied|canceled)|Auto-reviewer\\s+denied|Request\\s+denied|Review\\s+timed out)\\b).*$"
    )

    /** `plans.rs`: `ProposedPlanCell` (`• Proposed Plan`) vs `PlanUpdateCell` (`• Updated Plan [· X/Y complete]`). */
    val proposedPlanHeadRegex = Regex("^•\\s+Proposed Plan\\s*$")
    val planUpdateHeadRegex = Regex("^•\\s+Updated Plan(?:\\s*·\\s*(\\d+/\\d+)\\s+complete)?\\s*$")

    /** `delegated_message.rs`: `DelegatedMessageCell` (`• Sent by Codex from task <thread_id>`). */
    val delegatedMessageHeadRegex = Regex("^•\\s+Sent by Codex from task\\s+(\\S+)\\s*$")

    /** `request_user_input.rs`: `RequestUserInputResultCell` (`• Questions 2/2 answered [(interrupted)]`). */
    val questionsResultHeadRegex = Regex("^•\\s+Questions\\s+(\\d+/\\d+)\\s+answered(?:\\s*\\(interrupted\\))?\\s*$")

    /** `notices.rs`: `ThreadRecapHistoryCell` (`  ↳ Recap: ...`). */
    val threadRecapHeadRegex = Regex("^\\s*↳\\s+Recap:\\s*(.*)$")

    /**
     * `transcript_view/layout.rs` & `exec_cell/render.rs` & `dynamic.rs`:
     * Disclosure controls & truncation markers inside/after activity cells.
     */
    val disclosureOrEllipsisRegex = Regex(
        "^\\s*(?:" +
            "\\+\\s*Show details|" +
            "[−-]\\s*(?:Hide details|Show less)|" +
            "\\+\\s*\\d+\\s+lines?(?:\\s*\\([^)]*to expand\\))?|" +
            "…\\s*\\+\\d+\\s+lines?(?:\\s*\\([^)]*to view transcript\\))?|" +
            "…\\s*\\d+\\s+earlier lines hidden|" +
            "⋮|\\.{3}" +
            ")\\s*$",
        RegexOption.IGNORE_CASE
    )

    /** Explicit expanded-disclosure marker (`− Hide details` / `− Show less`). */
    val expandedDisclosureRegex = Regex("^\\s*[−-]\\s*(?:Hide details|Show less)\\s*$", RegexOption.IGNORE_CASE)

    /**
     * `pager_overlay/transcript.rs:104-106`: `TranscriptOverlay` (`Ctrl+T`) top banner:
     * `Span::from("/ ".repeat(area.width / 2))` overlaid with `"/ T R A N S C R I P T"`.
     */
    val transcriptOverlayHeaderRegex = Regex("^(?:/\\s*)*T\\s+R\\s+A\\s+N\\s+S\\s+C\\s+R\\s+I\\s+P\\s+T(?:\\s*/)*\\s*$")

    /** `pager_overlay.rs:108`: Empty viewport row marker (`~` at column 0 below transcript end). */
    val transcriptOverlayTildeRowRegex = Regex("^~\\s*$")

    private val singleWordActivityVerbs: HashSet<String> = hashSetOf(
        "Ran", "Run", "Running", "Edited", "Editing", "Added", "Adding", "Deleted", "Deleting",
        "Moved", "Moving", "Renamed", "Renaming", "Read", "Reading", "Wrote", "Writing",
        "Created", "Creating", "Patched", "Patching", "Applied", "Applying", "Called", "Calling",
        "Executed", "Executing", "Fetched", "Fetching", "Thinking", "Thought", "Inspected",
        "Explored", "Exploring"
    )

    fun isActivityHead(line: String): Boolean {
        if (line.length < 3) return false
        return when (line[0]) {
            '•' -> {
                if (line[1] != ' ') return false
                var pos = 2
                while (pos < line.length && line[pos] == ' ') pos++
                val wordStart = pos
                while (pos < line.length && line[pos].isLetter()) pos++
                if (pos == wordStart) return false
                val firstWord = line.substring(wordStart, pos)
                if ((pos == line.length || !line[pos].isLetterOrDigit()) && firstWord in singleWordActivityVerbs) {
                    return true
                }
                activityHeadRegex.matches(line)
            }
            '✘', '✗', '↳' -> activityHeadRegex.matches(line)
            else -> false
        }
    }

    /** `pager_overlay/transcript.rs:110-135`: Bottom pager status / key-hint rows in `Ctrl+T` overlay. */
    val transcriptOverlayFooterRegex = Regex(
        "(?i)^\\s*(?:" +
            "Line\\s+\\d+(?:[–-]\\d+)?\\s+of\\s+\\d+.*|" +
            "Viewing\\s+transcript\\b.*" +
            ")$"
    )

    fun isTranscriptOverlayFooter(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return false
        val c0 = trimmed[0]
        if ((c0 == 'L' || c0 == 'l' || c0 == 'V' || c0 == 'v') && transcriptOverlayFooterRegex.matches(trimmed)) {
            return true
        }
        if ("q or Esc" in trimmed || "q or esc" in trimmed ||
            "Esc close" in trimmed || "esc close" in trimmed ||
            "ctrl+t close" in trimmed || "Ctrl+T close" in trimmed ||
            "ctrl+t to close" in trimmed || "Ctrl+T to close" in trimmed ||
            "ctrl+t back" in trimmed || "Ctrl+T back" in trimmed ||
            "PgUp/PgDn" in trimmed || "pgup/pgdn" in trimmed ||
            "Home/End" in trimmed || "home/end" in trimmed ||
            ("/ find" in trimmed && "next" in trimmed)
        ) {
            return true
        }
        return false
    }

    /**
     * `exec_cell/render.rs:383`: `EXEC_DISPLAY_LAYOUT` prefixes wrapped/multi-line commands
     * under `• Ran` / `• Running` / `• You ran` with `"  │ "`.
     */
    val commandContPipeRegex = Regex("^\\s{2}│(?:\\s?(.*))?$")

    /**
     * `history_cell/messages.rs`: In `Ctrl+T` transcript mode, `ReasoningSummaryCell` becomes visible
     * and frequently starts with a bold summary title line (`• **Analyzing parser state**`).
     */
    val reasoningBoldHeaderRegex = Regex("^•\\s+\\*\\*([^*]{2,90})\\*\\*\\s*$")

    // ---------------------------------------------------------------------------------------------
    // 3. Errors, Warnings, Safety Blocks & Approval Overlay (`notices.rs`, `approval_overlay.rs`)
    // ---------------------------------------------------------------------------------------------

    /** `notices.rs:3261`: `new_error_event` renders `■ <message>` (with space or U+200A hair space). */
    val errorSquareRegex = Regex("^\\s*■[\\s\\u200A]+(.+)$")

    /** `notices.rs:3018`: `WarningHistoryCell` (`⚠ <message>`, excluding quota heads-up chrome). */
    val warningCellRegex = Regex("^\\s*⚠\\s+(.+)$")

    /** `notices.rs:3136`: `SafetyAccessBlockCell` (`ⓘ This content can't be shown`). */
    val safetyBlockRegex = Regex("^\\s*ⓘ\\s+(.+)$")

    /** `approval_overlay.rs:1324-1363`: Exact modal prompt titles when Codex is blocked on user approval. */
    val approvalOverlayTitleRegex = Regex(
        "(?i)^\\s*(?:" +
            "Would you like to run the following command\\?|" +
            "Would you like to send input to (?:the existing )?terminal.*\\?|" +
            "Do you want to approve network access to .*\\?|" +
            "Would you like to grant these permissions\\?|" +
            "Would you like to make the following edits\\?|" +
            "\\S+ needs your approval\\." +
            ")\\s*$"
    )

    /** `bottom_pane/request_user_input/render.rs`: Interactive question overlay (`Question 1/2`). */
    val requestUserInputOverlayRegex = Regex("^\\s*Question\\s+\\d+/\\d+\\b.*$", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------------------------------------
    // 4. Turn Completion Separator (`separators.rs`)
    // ---------------------------------------------------------------------------------------------

    /**
     * `separators.rs:1668`: `FinalMessageSeparator`:
     * `  Worked for <1s|54s|2m 31s|1h 02m 03s • 03:07 [• Local tools: ... • Inference: ...]`
     */
    val finalSeparatorRegex = Regex(
        "^\\s*Worked for\\s+(<1s|\\d+[dhms](?:\\s+\\d+[dhms])*)(?:\\s*[•·]\\s*(.*))?$",
        RegexOption.IGNORE_CASE
    )
    val finalSeparatorMetricsContRegex = Regex(
        "^\\s{2,}(?:Local tools:|Inference:|WebSocket:|Streams?:|\\d+ events received|Responses API |TTFT:|TBT:).*$"
    )

    // ---------------------------------------------------------------------------------------------
    // 5. Markdown Rendering Constructs (`markdown_render.rs`)
    // ---------------------------------------------------------------------------------------------

    /** `markdown_render.rs:4870`: `Event::Rule` renders as 3 em-dashes `———` (`  ———`). */
    val markdownEmDashRuleRegex = Regex("^\\s*———+\\s*$")

    /** `markdown_render.rs:4328`: Grid table header (`━━━  ━━━`) and body (`───  ───`) separators. */
    val gridTableRuleRegex = Regex("^\\s*[━─]{3,}(?:\\s{2,}[━─]{3,})+\\s*$")

    /** `markdown_render.rs:5109`: Blockquote inside `AgentMarkdownCell` (`• > ...` or `  > ...`). */
    val blockquoteRegex = Regex("^(?:•|\\s{2,})\\s*>\\s?(.*)$")

    /** `task_lists.rs:332`: Task list item (`  ☐ ...` or `  ☑ ...`). */
    val taskListBulletRegex = Regex("^(\\s*)([☐☑])\\s+(.+)$")

    private val strongCodeStartRegex = Regex(
        "^(?:" +
            "(?:const|var|function|async|await|import|export|interface|enum|" +
            "fun|val|package|override|suspend|data\\s+class|sealed\\s+(?:interface|class)|" +
            "fn|pub(?:\\([^)]+\\))?\\s+(?:fn|struct|enum|trait|mod|use|type|const)|impl\\b|struct\\s+\\w+|trait\\s+\\w+|" +
            "def|elif|except\\b|lambda\\b|" +
            "SELECT|INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET|DELETE\\s+FROM|CREATE\\s+TABLE|ALTER\\s+TABLE)\\b|" +
            "let\\s+(?:mut\\s+)?[A-Za-z_][\\w]*\\s*[:=]|" +
            "(?:if|else\\s+if|for|while|switch|catch)\\s*\\(|" +
            "(?:if|while)\\s+let\\s+|for\\s+\\w+\\s+in\\s+|use\\s+\\w+::|from\\s+[\\w.'\"]+\\s+import\\s+|" +
            "return\\s+[\"'`\\[{(\\w!-].*|" +
            "\\$\\s+\\S+|\\\$env:[A-Za-z_]|" +
            "//|#!/|/\\*|\\*\\s+|" +
            "[{}()\\[\\];]+$" +
            ")"
    )
    private val cliCommandLineRegex = Regex(
        "^(?:\\./|[A-Za-z0-9_.-]+/gradle|git|npm|pnpm|yarn|npx|bun|deno|node|python3?|pip3?|pytest|uv|cargo|rustc|go|gradle|gradlew|mvn|adb|fastboot|docker|kubectl|helm|curl|wget|ssh|scp|hc|tmux|rg|fd|jq)\\s+\\S+.*$"
    )
    private val strongCodeTokenRegex = Regex(
        "(?:===|!==|==|!=|<=|>=|=>|->|::|\\+=|-=|&&|\\|\\||\\?\\.|;\\s*$|\\{\\s*$|\\}\\s*(?:[;,)]|(?:else|catch|finally)\\b)?\\s*$|[A-Za-z_][\\w.]*\\([^()]*\\)\\s*[:;{]?$)"
    )
    private val proseSentenceEndRegex = Regex("(?:[a-z]{2,}[.!?]|[\u4E00-\u9FFF][。！？])\\s*$")
    private val alignedMetricLineRegex = Regex("^[A-Za-z_][A-Za-z0-9_.-]{1,24}\\s{2,}\\S+.*$")
    private val proseStructPrefixRegex = Regex("^(?:#{1,6}\\s|[•\\-*·◦▪‣☐☑⚠■ⓘ✔✗✘↳└├]\\s|\\d{1,2}[.)、]\\s|>\\s*)")
    private val approxMetricRowRegex = Regex("^[A-Za-z_][A-Za-z0-9_.-]{1,20}\\s+[≈~]?\\d+.*$")

    /**
     * `markdown_render.rs:4927, 5378`:
     * Codex strips ``` fences from `CodeBlockKind::Fenced` and renders code lines at the exact
     * same 2-space indent (`"  "`) as ordinary prose paragraphs, separated by blank lines!
     *
     * Detects a blank-delimited run of 2-space (or deeper) unfenced code / CLI / aligned metric lines
     * so they stay preserved in a monospace `CodeOrTableBlock` instead of being joined into prose.
     */
    fun detectUnfencedCodeBlockEnd(lines: List<String>, startIdx: Int, wrapThreshold: Int): Int? {
        if (startIdx > 0 && lines[startIdx - 1].isNotBlank()) return null
        val first = lines[startIdx]
        val firstNonSpace = first.indexOfFirst { it != ' ' }
        if (firstNonSpace < 2) return null
        val c0 = first[firstNonSpace]
        // Fast O(1) rejection for CJK prose, bullets, headings, quotes, alerts, rules, and pipe callouts
        if (c0.code in 0x2E80..0x9FFF || c0.code in 0xFF00..0xFFEF ||
            c0 in "#*>-•◦▪‣☐☑⚠■ⓘ✔✗✘↳└├│━─（(" || c0 in '0'..'9'
        ) {
            return null
        }

        val firstTrim = first.substring(firstNonSpace).trimEnd()
        var j = startIdx
        var codeLikeCount = 0
        var cliLikeCount = 0
        var metricLikeCount = 0
        var deeperIndentCount = 0
        var totalNonBlank = 0
        var lastTrim = ""

        while (j < lines.size) {
            val l = lines[j]
            val t = l.trim()
            if (t.isEmpty()) break
            val ind = l.takeWhile { it == ' ' }.length
            if (ind < 2) break
            // Prose bullets, headings, blockquotes, alerts, separators, or table rules are not unfenced code blocks
            if (proseStructPrefixRegex.containsMatchIn(t)) return null
            if (gridTableRuleRegex.matches(l) || finalSeparatorRegex.matches(l) || markdownEmDashRuleRegex.matches(l)) return null
            if (approvalOverlayTitleRegex.matches(l) || requestUserInputOverlayRegex.matches(l)) return null

            totalNonBlank++
            lastTrim = t
            if (ind >= 4) deeperIndentCount++

            val cjkCount = t.count { it.code in 0x4E00..0x9FFF }
            val isComment = t.startsWith("//") || t.startsWith("# ") || t.startsWith("/*") || t.startsWith("* ")
            if (!isComment && cjkCount >= 4 && t.last() in "。！？：；") return null

            if (strongCodeStartRegex.containsMatchIn(t) || strongCodeTokenRegex.containsMatchIn(t)) {
                codeLikeCount++
            }
            if (cliCommandLineRegex.matches(t) && !proseSentenceEndRegex.containsMatchIn(t)) {
                cliLikeCount++
            }
            if (alignedMetricLineRegex.matches(t) || (totalNonBlank >= 2 && approxMetricRowRegex.matches(t))) {
                metricLikeCount++
            }
            j++
        }

        if (totalNonBlank == 0) return null
        if (j < lines.size && lines[j].isNotBlank()) return null

        // Reject ordinary English/CJK prose paragraphs ending with natural sentence punctuation (unless comments)
        val lastIsComment = lastTrim.startsWith("//") || lastTrim.startsWith("# ") || lastTrim.endsWith("*/")
        if (!lastIsComment && proseSentenceEndRegex.containsMatchIn(lastTrim) && deeperIndentCount == 0) {
            return null
        }

        val isCodeBlock = (totalNonBlank >= 2 && codeLikeCount >= 2) ||
            (totalNonBlank >= 2 && codeLikeCount >= 1 && deeperIndentCount >= 1) ||
            (totalNonBlank == 1 && strongCodeStartRegex.containsMatchIn(firstTrim) && strongCodeTokenRegex.containsMatchIn(firstTrim))
        val isCliBlock = (totalNonBlank >= 2 && cliLikeCount == totalNonBlank) ||
            (totalNonBlank == 1 && (firstTrim.startsWith("$ ") || firstTrim.startsWith("./") || firstTrim.startsWith("\$env:")) && cliLikeCount == 1)
        val isAlignedMetricsBlock = totalNonBlank >= 3 && metricLikeCount == totalNonBlank &&
            (startIdx until j).all { lines[it].length < wrapThreshold }

        return if (isCodeBlock || isCliBlock || isAlignedMetricsBlock) j else null
    }
}
