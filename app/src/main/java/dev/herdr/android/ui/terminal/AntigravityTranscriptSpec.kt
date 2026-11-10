package dev.herdr.android.ui.terminal

/**
 * Verified terminal grammar for Google Antigravity CLI (`agy`, v1.0–v1.5.3+).
 *
 * Derived from official `agy` TUI release specifications (`agy changelog`, `examples/statusline`,
 * `examples/title`) cross-verified against paired `.system_generated/logs/transcript.jsonl`
 * protocol logs and live `hc read` terminal snapshots across multi-hundred-turn sessions:
 *
 * 1. **Bottom Dock & Status Chrome**:
 *    - Right-aligned artifact review hint: `N artifact(s) · /artifact to review`
 *    - Composer sandwich: `───...` / `> [draft]` / `───...`
 *    - Active tray between `───` rules:
 *      - Background tasks/timers/cron: `  ● [HH:MM:SS] <cmd|5m timer|cron (...)> running|scheduled|...`
 *      - Subagent status rows: `  ● <name> [· <Model>] · <Running...|Blocked · AskQuestion|Done|...>`
 *    - Default status line: `? for shortcuts` / `esc to cancel` / `↑/↓ Navigate · tab Amend · e edit command`
 *      paired with `<Model> [· low|medium|high|xhigh|max] [· N task(s) · /tasks]`
 *    - Custom statusline (`statusline.sh`): `● READY|◆ THINKING|⚙ WORKING|🔧 TOOL|⏳ REVIEWING ... ctx ██·· X% · artifacts N · subagents N · tasks N · sandbox on|off`
 *    - Exit resume hint: `Resume with -c (or command below):` + `agy --conversation=<uuid>`
 *
 * 2. **User Prompts, Attachments, Slash Commands & Artifact Approvals**:
 *    - Turn divider `──────...` + `> <prompt>` (continuation lines indented by 2 spaces)
 *    - Collapsed/expanded media badge under `> <prompt>`: `  ▸ 📎 N image(s) (96 KB)  (ctrl+o to expand)`
 *    - Slash command inline result: `> /artifact` + `  ⎿  Exited /artifact command` (no-op UI toggle)
 *      or `  ⎿  Review submitted`
 *    - Multi-artifact review batch: `> [Approved] a.md` + `  [Approved] b.md` (`[Rejected]`, `[Commented]`)
 *
 * 3. **Thinking & Accumulated Tool Step Rows (`StepRenderInfo` / `AccumulatedItemRenderInfo`)**:
 *    - Pre-response thought: `○ Thought for 2m 49s (<preview...>)`
 *    - Final-response thought: `▸ Thought for 16s, 73 tokens` (or expanded `▾ Thought for ... (ctrl+o to collapse)`)
 *    - Tool rows (`●` done / `○` running): single & compound summaries (`● Ran 6 commands, checked 1 task, killed 1 task (...) [failed] [(ctrl+o to expand)]`, `● Explored N files`, `● Edited N files`, `● Spawned N subagents`, `● Messaged N subagent(s)`, `● Scheduled 1 timer`, `● Listed tasks`, etc.)
 *    - Collapsed action rollup: `… N more actions (R reads, E edits, C commands: F failed) …`
 *    - Live braille spinner + tip child: `⣟  Running command...` + `└ Tip: ...`
 *
 * 4. **Assistant Markdown Body (2-space base margin)**:
 *    - Callouts / Blockquotes (`> ...`, `> [!NOTE]`): single left pipe `  │ <text>` or `  │ • <item>`
 *    - Standalone list markers before wrapped/bold lead phrases: `  1.` or `  •` on its own line followed by `  <body line>`
 *    - Exact list nesting: `indent = 2 + level * 4` (`2`, `6`, `10` spaces -> levels `0`, `1`, `2`)
 *    - Borderless pipe tables: `col1 │ col2` separated by `───┼───`
 *    - Unfenced code blocks (`CodeBlock` rendered with background tint & syntax colors, stripped of ``` fences)
 *
 * 5. **Alerts, Subagent Questions & Permission Prompts**:
 *    - `⚠ UNAUTHENTICATED (code 401): ...` + `Error ID: <id>`
 *    - `⚠ The <plugin> plugin needs you to sign in. Open /plugin to sign in.`
 *    - `<agent> has a question` / `Blocked · AskQuestion`
 *    - `Allow calling this tool?` / `Allow access to this URL?` + numbered options + `↑/↓ Navigate · tab Amend`
 */
internal object AntigravityTranscriptSpec {

    // ---------------------------------------------------------------------------------------------
    // 1. Bottom Dock, Subagent/Task Tray, Status Line & Exit Chrome
    // ---------------------------------------------------------------------------------------------

    /** Default model + optional effort + optional task count in `agy` status line. */
    val modelStatusFooterRegex = Regex(
        "(?i)(?:Claude\\s+(?:Opus|Sonnet|Haiku)[\\w.\\s-]*|Gemini\\s+[\\w.-]+(?:\\s+[\\w().-]+){0,3}|GPT-[\\w.-]+)" +
            "(?:\\s*·\\s*(?:low|medium|high|xhigh|max))?" +
            "(?:\\s*·\\s*\\d+\\s+tasks?\\(s\\)|\\s*·\\s*/tasks)+" +
            "|(?:Claude\\s+(?:Opus|Sonnet|Haiku)[\\w.\\s-]*|Gemini\\s+[\\w.-]+(?:\\s+[\\w().-]+){0,2}|GPT-[\\w.-]+)" +
            "\\s*·\\s*(?:low|medium|high|xhigh|max)\\b"
    )

    /** Permission / panel navigation hints in `agy` bottom status line. */
    val keyHintFooterRegex = Regex(
        "(?i)(?:↑/↓\\s*Navigate|\\btab\\s+Amend\\b|\\be\\s+edit command\\b|" +
            "Press\\s+ctrl\\+[cd]\\s+again\\s+to\\s+exit|" +
            "\\d+\\s+artifacts?\\s*·\\s*/artifact\\s+to\\s+review)"
    )

    /** Custom statusline (`examples/statusline/statusline.sh`): wide 1-line, medium `╭─`/`╰─` 2-line, and narrow 2-line layouts. */
    val customStatusLineRegex = Regex(
        "(?i)(?:^(?:[╭╰]─\\s*|[●◆⚙🔧⏳]\\s+)(?:[●◆⚙🔧⏳]\\s*)?(?:READY|THINKING|WORKING|TOOL|REVIEWING|INITIALIZING)\\b(?:\\s*╱\\s*.+|.*\\bctx\\b.*)?$|" +
            "(?:^[╭╰]─\\s*)?.*\\bctx\\s+[█▓▒░·\\s]+\\d+(?:\\.\\d+)?%.*(?:\\bsandbox\\s+(?:on|off)\\b|\\btasks\\s+\\d+\\b).*)"
    )

    /** Background task / timer / cron row in bottom tray: `  ● [10:43:53] ... running|scheduled|completed|failed`. */
    val trayTaskRowRegex = Regex(
        "^\\s*[●○]\\s+\\[\\d{2}:\\d{2}:\\d{2}]\\s+.+$"
    )

    /** Subagent status row in bottom tray above/below composer: `  ● helper · Claude Opus 5.5 · Blocked · AskQuestion`. */
    val traySubagentRowRegex = Regex(
        "^\\s*[●○]\\s+[A-Za-z0-9_.-]+\\s*·\\s*(?:(?:Claude|Gemini|GPT)[^·]+·\\s*)?" +
            "(?:Running|Working|Thinking|Blocked(?:\\s*·\\s*\\S+)?|Done|Idle|Completed|Failed|Waiting)\\b.*$",
        RegexOption.IGNORE_CASE
    )

    /** Session exit footer printed when `agy` closes (`Resume with -c (or command below):` + `agy --conversation=...`). */
    val exitResumeHeaderRegex = Regex("^\\s*Resume with -c\\s*\\(or command below\\):\\s*$", RegexOption.IGNORE_CASE)
    val exitResumeCmdRegex = Regex("^\\s*agy(?:\\.exe)?\\s+(?:--conversation|--continue|-c)\\b.*$", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------------------------------------
    // 2. User Prompts, Attachment Folds, Slash Results & Artifact Review Batches
    // ---------------------------------------------------------------------------------------------

    /** User attachment fold line directly under `> <prompt>`: `  ▸ 📎 1 image(s) (96 KB)  (ctrl+o to expand)`. */
    val attachmentBadgeRegex = Regex(
        "^\\s*[▸▾▼]?\\s*📎\\s*(.+?)(?:\\s*\\(ctrl\\+o to (?:expand|collapse)\\))?\\s*$",
        RegexOption.IGNORE_CASE
    )

    /** Slash command invoked at `> /cmd`. */
    val slashPromptRegex = Regex("^/(?:artifact|agents|tasks|model|effort|mode|config|settings|permissions|plugin|plugins|skills|mcp|diff|compact|clear|help|copy|btw|resume|conv|conv-switch|fork|rewind|quota|usage|credits|remote-control|hooks|keybindings|title|voice|login|logout|exit|quit)\\b.*$")

    /** Inline slash-command outcome row (`  ⎿  Exited /artifact command`, `  ⎿  Review submitted`). */
    val slashOutcomeRowRegex = Regex("^\\s*⎿\\s+(.+)$")

    /** No-op UI modal open/close outcome (`Exited /artifact command`, `Exited /agents`, `Canceled`). */
    val noopSlashOutcomeRegex = Regex(
        "^Exited\\s+/\\S+(?:\\s+command)?\\.?$",
        RegexOption.IGNORE_CASE
    )

    /** Artifact review batch item line (`[Approved] file.md`, `[Rejected] file.md`, `[Commented] file.md`). */
    val artifactVerdictLineRegex = Regex("^\\[(?:Approved|Rejected|Commented|Changes requested)]\\s+\\S+.*$", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------------------------------------
    // 3. Thinking, Accumulated Actions & Tool Verb Rows
    // ---------------------------------------------------------------------------------------------

    /** Pre-response (`○`), final (`▸`), or expanded (`▾`/`▼`) thought row. */
    val thoughtRowRegex = Regex(
        "^\\s*([○◦▸▾▼●])\\s*Thought for\\s+(.+?)(?:\\s*\\(ctrl\\+o to (?:expand|collapse)\\))?\\s*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Authoritative `agy` tool/action summary rows (`●` completed, `○` active, `▸`/`▾` expandable):
     * Covers single tools, multi-command batches (`● Ran 6 commands, checked 1 task, killed 1 task`),
     * subagent orchestration (`● Spawned 2 subagents`, `● Messaged 1 subagent`), timers (`● Scheduled 1 timer`),
     * browser tools, file exploration/editing, and MCP calls.
     */
    val actionRowRegex = Regex(
        "^\\s*[●⏺○◦▸▾▼✓✔✗✘]\\s+(?:" +
            "Thought|Thinking|Ran|Run|Running|Edited|Editing|Explored|Exploring|" +
            "Read|Reading|Wrote|Writing|Searched|Searching|Updated|Updating|Created|Creating|Deleted|Deleting|" +
            "Listed|Listing|Called|Calling|Executed|Executing|Viewed|Viewing|Analyzed|Analyzing|Fetched|Fetching|" +
            "Applied|Applying|Scheduled|Scheduling|Checked|Checking|Killed|Killing|Cancelled|Canceled|" +
            "Sent|Sending|Messaged|Messaging|Spawned|Spawning|Invoked|Invoking|Defined|Defining|" +
            "Asked|Asking|Answered|Worked|Waited|Waiting|Grepped|Globbed|Patched|Inspected|" +
            "Generated|Generating|Browsed|Browsing|Opened|Opening|Clicked|Typed|Navigated" +
            ")\\b.*$"
    )

    /** Collapsed action rollup (`… 53 more actions (18 reads, 11 edits, 24 commands: 10 failed) …`). */
    val moreActionsRowRegex = Regex(
        "^\\s*(?:…|\\.{2,3})\\s*(\\d+)\\s+more\\s+actions\\b(.*)$",
        RegexOption.IGNORE_CASE
    )

    /** Live retry status row (`Model unavailable (attempt 2/10), retrying in 12s`). */
    val modelRetryStatusRegex = Regex(
        "^\\s*(?:[\\u2800-\\u28FF✻✽✶✳✢●○]\\s*)?Model unavailable\\s*\\(attempt\\s+\\d+/\\d+\\),\\s*retrying in\\b.*$",
        RegexOption.IGNORE_CASE
    )

    /** Expand/collapse suffix (`(ctrl+o to expand)` / `(ctrl+o to collapse)`). */
    val ctrlOSuffixRegex = Regex("\\s*\\(ctrl\\+o to (?:expand|collapse)\\)\\s*$", RegexOption.IGNORE_CASE)

    /** Explicit `Ctrl+O` expanded marker (`(ctrl+o to collapse)`). */
    val ctrlOCollapseMarkerRegex = Regex("\\(ctrl\\+o to collapse\\)", RegexOption.IGNORE_CASE)

    /** Explicit collapsed marker (`(ctrl+o to expand)`). */
    val ctrlOExpandMarkerRegex = Regex("\\(ctrl\\+o to expand\\)", RegexOption.IGNORE_CASE)

    /** Expanded tool/action header (`▾ Ran ...` or `● Ran ... (ctrl+o to collapse)`). */
    val expandedActionRowRegex = Regex(
        "^\\s*(?:[▾▼]\\s+.*|[●⏺○◦▸▾▼✓✔✗✘]\\s+.*\\(ctrl\\+o to collapse\\)\\s*)$",
        RegexOption.IGNORE_CASE
    )

    /** `agy changelog:65`: Fold divider row (`↕ ···`) inside expanded diffs. */
    val diffFoldDividerRegex = Regex("^\\s*↕\\s*···.*$")

    /**
     * Common English internal-monologue starters in `agy` expanded `▾ Thought` blocks,
     * used as a fallback boundary signal when ANSI SGR codes have already been stripped.
     */
    val englishMonologueStartRegex = Regex(
        "^(?:I(?:'m| am| need| should| will| can| have|'ve|'ll)\\b|Let's\\b|Wait[,:\\s]|" +
            "First[,\\s]|Second[,\\s]|Next[,\\s]|Looking at\\b|The user\\b|Considering\\b|" +
            "We (?:need|should|can|have)\\b|Now (?:I|let's|we)\\b|Checking\\b|Inspecting\\b|" +
            "To (?:fix|solve|handle|address)\\b|This (?:means|suggests|indicates)\\b).*"
    )

    /**
     * Extracts total action count from a compound `agy` summary row such as
     * `Ran 6 commands, checked 1 task, killed 1 task (...)` -> `8`, or `1` for a single action.
     */
    private val clauseCountRegex = Regex(
        "\\b(\\d+)\\s+(?:commands?|files?|tasks?|subagents?|timers?|images?|urls?|pages?|queries|searches|edits?|reads?)\\b",
        RegexOption.IGNORE_CASE
    )

    fun countActionsInSummary(cleanedStep: String): Int {
        val headerPart = cleanedStep.substringBefore('(').trim()
        val sum = clauseCountRegex.findAll(headerPart)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .sum()
        return if (sum > 0) sum else 1
    }

    /**
     * Distinguishes real `agy` step failure markers (`) failed`, `: failed`, `N failed`, `✗`)
     * from benign occurrences of the word `"failed"` inside a quoted shell command argument!
     */
    fun hasRealStepFailure(cleanedStep: String): Boolean {
        if (Regex("^[✗✘]\\s").containsMatchIn(cleanedStep)) return true
        if (Regex("(?i)(?:\\)\\s*failed\\b|:\\s*failed\\b|\\b[1-9]\\d*\\s+failed\\b)").containsMatchIn(cleanedStep)) {
            return true
        }
        val outsideParens = cleanedStep.substringBefore('(') + " " + cleanedStep.substringAfterLast(')', "")
        return Regex("(?i)\\b(?:failed|failure|error|denied)\\b").containsMatchIn(outsideParens)
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Assistant Markdown Callouts & Standalone List Markers
    // ---------------------------------------------------------------------------------------------

    /**
     * `agy` renders Markdown blockquotes (`> ...`) and GitHub alerts (`> [!NOTE]`, `> [!IMPORTANT]`, etc.)
     * with a single left vertical bar at ~2-space indent: `  │ <text>` or empty spacer `  │`.
     */
    val calloutPipeLineRegex = Regex("^(\\s{0,6})│(?:\\s(.*))?$")

    /** Standalone ordered list marker on its own line (`  1.` / `  2)`) when the lead phrase wraps onto the next line. */
    val loneOrderedMarkerRegex = Regex("^(\\s*)(\\d{1,2}[.)])\\s*$")

    /** Standalone unordered bullet marker on its own line (`  •`) when the lead phrase wraps onto the next line. */
    val loneUnorderedMarkerRegex = Regex("^(\\s*)([•◦▪‣])\\s*$")

    // ---------------------------------------------------------------------------------------------
    // 5. Warnings, Errors, Subagent Questions & Tool Permission Prompts
    // ---------------------------------------------------------------------------------------------

    /** `⚠ ...` warning/error banner in `agy` (`⚠ UNAUTHENTICATED...`, `⚠ The <plugin> plugin needs you to sign in...`). */
    val warningBannerRegex = Regex("^\\s*⚠\\s+(.+)$")

    /** Subagent question alert (`<agent> has a question` or `Blocked · AskQuestion`). */
    val subagentQuestionRegex = Regex(
        "^(?:\\s*[●⚠]?\\s*([A-Za-z0-9_.-]+)\\s+has a question\\b.*|.*\\bBlocked\\s*·\\s*AskQuestion\\b.*)$",
        RegexOption.IGNORE_CASE
    )

    /** Tool / URL / file permission confirmation header (`Allow calling this tool?`, `Allow access to this URL?`, etc.). */
    val permissionTitleRegex = Regex(
        "(?i)^\\s*(?:Allow\\s+(?:calling|running|reading|writing|editing|access|this)\\b.*\\?|" +
            "Do you want to\\s+(?:allow|run|proceed|edit|write|read|create|delete)\\b.*\\?)\\s*$"
    )
}
