package dev.herdr.android.ui.terminal

import dev.herdr.android.connection.ConnectionProfile
import dev.herdr.android.connection.ConnectionState
import dev.herdr.android.protocol.AgentStateWord
import dev.herdr.android.protocol.AttachMode
import dev.herdr.android.protocol.HcHelloInfo
import dev.herdr.android.protocol.HcPutResult
import dev.herdr.android.protocol.HcServerStatus
import dev.herdr.android.protocol.TerminalSession
import dev.herdr.android.protocol.TerminalSummary
import dev.herdr.android.ui.workbench.TerminalSequenceCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class AgyInteractiveAndSequenceTest {

    @Test(timeout = 15_000)
    fun askQuestionParsesSingleMultiWriteInAndFoldsScrollbackQa() {
        val rawScreen = """
            > 请规划下一步迁移方案

            ● 已完成模块盘点，进入交互确认前有几个关键问题。

            ? Which database migration strategy do you prefer?
              > Flyway versioned SQL
            ? Select target modules (Select all that apply)
              > [x] core-protocol, [ ] ui-workbench

            Question 2/3: Which safeguards should be enabled before deploy?
            Select all that apply

            › 1. [x] Canary rollout
              2. [ ] Automatic rollback
              3. Write-in...

            ↑/↓ Navigate · Space to toggle · ← Prev · → Next · Esc to cancel
        """.trimIndent()

        val state = AgyInteractiveParser.inspectRaw(rawScreen)
        assertEquals(AgyUiPhase.ASK_QUESTION_MODAL, state.phase)
        val card = state.activeAskCard
        assertNotNull(card)
        requireNotNull(card)
        assertEquals(2, card.questionIndex)
        assertEquals(3, card.totalQuestions)
        assertTrue(card.isMultiSelect)
        assertEquals("Which safeguards should be enabled before deploy?", card.questionText)
        assertEquals(3, card.options.size)
        assertTrue(card.options[0].isCursor)
        assertTrue(card.options[0].isChecked)
        assertFalse(card.options[1].isChecked)
        assertTrue(card.options[2].isWriteIn)

        val summary = TerminalSummary(
            address = "t1q",
            agentApp = "antigravity",
            agentStatus = AgentStateWord.BLOCKED,
            state = "live"
        )
        val snapshot = StructuredTerminalReader.analyzeWorkbench(rawScreen, summary)
        assertNotNull(snapshot.agyState?.activeAskCard)
        assertTrue(snapshot.blocks.any { it is TerminalSemanticBlock.AskCardBlock })
        val qaGroup = snapshot.blocks.filterIsInstance<TerminalSemanticBlock.ToolGroupBlock>()
            .firstOrNull { "问答确认" in it.summaryLabel }
        assertNotNull("Expected folded scrollback ? / > Q&A ToolGroupBlock", qaGroup)

        val headline = StructuredTerminalReader.extractLatestHeadline(rawScreen, summary)
        assertEquals("Which safeguards should be enabled before deploy?", headline)
    }

    @Test(timeout = 15_000)
    fun askWriteInFocusAndYourAnswerLineAreRecognized() {
        val writeInScreen = """
            Question 1/1: Enter custom deployment tag:

              1. v1-canary
              2. v2-stable
            › 3. Write-in: hotfix-42

            Your answer: hotfix-42
            Enter to submit · Esc to discard
        """.trimIndent()

        val state = AgyInteractiveParser.inspectRaw(writeInScreen)
        assertEquals(AgyUiPhase.ASK_QUESTION_MODAL, state.phase)
        val card = state.activeAskCard!!
        assertFalse(card.isMultiSelect)
        assertTrue(card.isWriteInFocused)
        assertEquals("hotfix-42", card.currentWriteInDraft)
        assertTrue(card.options[2].isWriteIn)
        assertEquals("hotfix-42", card.options[2].writeInPreview)
    }

    @Test(timeout = 15_000)
    fun slashModalsConfigModelResumeTasksAgentsArtifactDiffAndIdleFooterParseAccurately() {
        val modelScreen = """
            Available models
            Search: gemini

              1. Gemini 3 Pro              (current)  Effort: high
            › 2. Gemini 4 Carbon                      Effort: max
              3. Claude 4.5 Sonnet

            ↑/↓ Navigate · ←/→ Change effort · Enter Select · Esc to close
        """.trimIndent()
        val modelState = AgyInteractiveParser.inspectRaw(modelScreen)
        assertEquals(AgyUiPhase.SLASH_MODAL, modelState.phase)
        val modelModal = modelState.activeSlashModal!!
        assertEquals(AgySlashModalKind.MODEL, modelModal.kind)
        assertEquals("gemini", modelModal.searchQuery)
        assertEquals(3, modelModal.items.size)
        assertEquals(1, modelModal.selectedIndex)
        assertTrue(modelModal.items[0].isCurrentActive)
        assertTrue(modelModal.supportsLeftRightAdjust)

        val configScreen = """
            Settings
            [TUI]   Remote Agent   Keybindings

            › agentMode                 plan
              artifactReviewPolicy      request-review
              verboseToolOutput         off

            Tab Switch Tab · ←/→ Change · Esc to close
        """.trimIndent()
        val configModal = AgyInteractiveParser.inspectRaw(configScreen).activeSlashModal!!
        assertEquals(AgySlashModalKind.CONFIG, configModal.kind)
        assertEquals(listOf("TUI", "Remote Agent", "Keybindings"), configModal.tabs)
        assertEquals(0, configModal.activeTabIndex)
        assertTrue(configModal.supportsTabSwitch)
        assertTrue(configModal.supportsLeftRightAdjust)

        val resumeScreen = """
            Conversations
            Filter: refactor
            > Refactored SSH keepalive   2m ago
              Added image upload         1h ago
            ↑/↓ Navigate · Enter Resume · Esc to close
        """.trimIndent()
        assertEquals(AgySlashModalKind.RESUME, AgyInteractiveParser.inspectRaw(resumeScreen).activeSlashModal?.kind)

        val tasksScreen = """
            Running Background Tasks
            > gradle-build               running   42s
            ↑/↓ Navigate · x Kill · Esc to close
        """.trimIndent()
        assertEquals(AgySlashModalKind.TASKS, AgyInteractiveParser.inspectRaw(tasksScreen).activeSlashModal?.kind)

        val agentsScreen = """
            Available Agents
            > subagent-reviewer          working
            ↑/↓ Navigate · Enter Switch · Esc to close
        """.trimIndent()
        assertEquals(AgySlashModalKind.AGENTS, AgyInteractiveParser.inspectRaw(agentsScreen).activeSlashModal?.kind)

        val artifactScreen = """
            Action required (implementation_plan.md)
            > Approve and proceed
              Reject with feedback
            ↑/↓ Navigate · Enter Confirm · Esc to close
        """.trimIndent()
        assertEquals(AgySlashModalKind.ARTIFACT, AgyInteractiveParser.inspectRaw(artifactScreen).activeSlashModal?.kind)

        val diffScreen = """
            All Changes (change 1/4)
            [All Changes]   Commit Tree
            > app/src/Main.kt   +24 -6
            Tab Switch view · ←/→ Navigate · Esc to close
        """.trimIndent()
        val diffModal = AgyInteractiveParser.inspectRaw(diffScreen).activeSlashModal!!
        assertEquals(AgySlashModalKind.DIFF, diffModal.kind)
        assertTrue(diffModal.supportsTabSwitch)
        assertTrue(diffModal.supportsLeftRightAdjust)

        // Idle `agy 1.5.14` screen with `esc to cancel` in footer MUST stay COMPOSER_EMPTY (not WORKING!)
        val idle1514Screen = """
            ╭────────────────────────────────────────────╮
            │ › Plan next optimization                   │
            ╰────────────────────────────────────────────╯
            esc to cancel   plan · Orchestrator · Gemini 4 Carbon · xhigh
        """.trimIndent()
        val draftState = AgyInteractiveParser.inspectRaw(idle1514Screen)
        assertEquals(AgyUiPhase.COMPOSER_HAS_DRAFT, draftState.phase)
        assertEquals("Plan next optimization", draftState.composerDraft)
        assertEquals(AgyAgentMode.PLAN, draftState.agentMode)
        assertFalse(draftState.hasWorkingHint)

        val trueEmptyIdle1514 = """
            ╭────────────────────────────────────────────╮
            │ ›                                          │
            ╰────────────────────────────────────────────╯
            esc to cancel   auto · Orchestrator · Gemini 4 Carbon · high
        """.trimIndent()
        val emptyState = AgyInteractiveParser.inspectRaw(trueEmptyIdle1514)
        assertEquals(AgyUiPhase.COMPOSER_EMPTY, emptyState.phase)
        assertEquals(AgyAgentMode.AUTO, emptyState.agentMode)
        assertFalse(emptyState.hasWorkingHint)
    }

    @Test(timeout = 15_000)
    fun realAgy1514WriteInBlankPaddingAndQuestionPreviewTrimCorrectly() {
        val realCapture = """
            ▸ Thought for 12s, 1.4k tokens
              Some thinking summary.

            ? 请选择发布通道
            ? 请指定灰度批次标签
            ? 请选择要启用的校验项

            Question
            ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

            Question 2/3: 请指定灰度批次标签

              1. batch-a
              2. batch-b
            > 3. Write-in...

              Your answer:




              enter Submit · esc Back
            esc to cancel                                                                                                                                               auto · Orchestrator · Gemini 4 Carbon · high
        """.trimIndent()

        val state = AgyInteractiveParser.inspectRaw(realCapture)
        assertEquals(AgyUiPhase.ASK_QUESTION_MODAL, state.phase)
        val card = state.activeAskCard!!
        assertEquals(2, card.questionIndex)
        assertEquals(3, card.totalQuestions)
        assertFalse(card.isMultiSelect)
        assertTrue("Expected Your answer: with enter Submit · esc Back to mark write-in focused", card.isWriteInFocused)
        assertEquals("", card.currentWriteInDraft)

        val summary = TerminalSummary(address = "t3x", agentApp = "antigravity", agentStatus = AgentStateWord.BLOCKED, state = "live")
        val snapshot = StructuredTerminalReader.analyzeWorkbench(realCapture, summary)
        // Preview `? 请选择发布通道` + `Question` must NOT leak above the AskCardBlock
        assertFalse(
            snapshot.blocks.any {
                it is TerminalSemanticBlock.AgentProseBlock && ("Question" in it.text || "请选择发布通道" in it.text)
            }
        )
        assertTrue(snapshot.blocks.last() is TerminalSemanticBlock.AskCardBlock)
        assertFalse(snapshot.quickActions.any { "批准" in it.label })

        val nextStep = AgySequencePlanner.nextAction(
            SequenceOperation.SubmitAskAnswer(questionIndex = 2, writeInText = "canary-wave-07"),
            state
        )
        assertEquals(SequenceStepVerdict.EXECUTE_TEXT, nextStep.verdict)
        val rawCmd = nextStep.command as SequenceAtomicCommand.RawText
        assertEquals("canary-wave-07", rawCmd.text)
        assertTrue(rawCmd.pressEnterAfter)
    }

    @Test(timeout = 15_000)
    fun sequenceClearsDirtyDraftCyclesModeToPlanAndAutoTakesOver() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connState = MutableStateFlow<ConnectionState>(fakeConnectedState())
            val session = FakeAgySession(
                address = "t1q",
                initialMode = AttachMode.OBSERVE,
                initialScreen = composerScreen(mode = "auto", draft = "half typed prompt")
            )
            session.onKeyOrText = { event ->
                when (event) {
                    "KEY:ctrl+u" -> session.pushScreen(composerScreen(mode = session.curMode, draft = ""))
                    "KEY:shift+tab" -> {
                        session.curMode = when (session.curMode) {
                            "auto" -> "accept-edits"
                            "accept-edits" -> "plan"
                            "plan" -> "best-of-n"
                            else -> "auto"
                        }
                        session.pushScreen(composerScreen(mode = session.curMode, draft = ""))
                    }
                    "TEXT:/effort high" -> session.pushScreen(composerScreen(mode = session.curMode, draft = "/effort high"))
                    "RAW_ENTER" -> {
                        if (session.curDraft == "/effort high") {
                            session.pushScreen(composerScreen(mode = session.curMode, draft = ""))
                        }
                    }
                }
            }

            val coordinator = TerminalSequenceCoordinator(
                scope = scope,
                connectionState = connState,
                sessionProvider = { addr -> if (addr == "t1q") session else null },
                onToast = {}
            )

            val res = coordinator.executeSuspending(
                address = "t1q",
                label = "切换 Plan 并设 high",
                operations = listOf(
                    SequenceOperation.EnsureComposerReady,
                    SequenceOperation.CycleAgentModeTo(AgyAgentMode.PLAN),
                    SequenceOperation.SendSlashCommand("/effort high", expectModal = null)
                )
            )

            assertTrue("Expected success but got ${res.exceptionOrNull()}", res.isSuccess)
            assertEquals(AttachMode.TAKEOVER, session.mode.value)
            assertEquals(
                listOf("KEY:ctrl+u", "KEY:shift+tab", "KEY:shift+tab", "TEXT:/effort high", "RAW_ENTER"),
                session.events
            )
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 15_000)
    fun multiSelectPlusWriteInSendsExactCheckboxDeltasThenUtf8AndSeparateEnter() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connState = MutableStateFlow<ConnectionState>(fakeConnectedState())
            val session = FakeAgySession(
                address = "t1q",
                initialMode = AttachMode.TAKEOVER,
                initialScreen = ""
            )
            var opt1 = false
            var opt2 = true
            var cursor = 1
            var writeFocused = false
            var writeBuf = ""

            fun renderAsk() = buildString {
                appendLine("Question 1/2: Which targets should run?")
                appendLine("Select all that apply")
                appendLine()
                appendLine("${if (cursor == 1) "›" else " "} 1. [${if (opt1) "x" else " "}] Unit tests")
                appendLine("${if (cursor == 2) "›" else " "} 2. [${if (opt2) "x" else " "}] E2E suite")
                appendLine("${if (cursor == 3) "›" else " "} 3. Write-in...")
                if (writeFocused) {
                    appendLine()
                    appendLine("Your answer: $writeBuf")
                }
                append("↑/↓ Navigate · Space to toggle · ← Prev · → Next")
            }

            session.pushScreen(renderAsk())
            session.onKeyOrText = { event ->
                when (event) {
                    "TEXT:1" -> { cursor = 1; opt1 = !opt1; session.pushScreen(renderAsk()) }
                    "TEXT:2" -> { cursor = 2; opt2 = !opt2; session.pushScreen(renderAsk()) }
                    "TEXT:3" -> { cursor = 3; writeFocused = true; session.pushScreen(renderAsk()) }
                    "KEY:space" -> {
                        if (cursor == 1) opt1 = !opt1
                        if (cursor == 2) opt2 = !opt2
                        session.pushScreen(renderAsk())
                    }
                    "TEXT:保留兼容层" -> {
                        writeBuf = "保留兼容层"
                        session.pushScreen(renderAsk())
                    }
                    "RAW_ENTER", "KEY:enter" -> {
                        session.pushScreen("Question 2/2: Confirm rollout?\n\n› 1. Yes\n  2. No\nEnter to submit")
                    }
                }
            }

            val coordinator = TerminalSequenceCoordinator(
                scope = scope,
                connectionState = connState,
                sessionProvider = { session },
                onToast = {}
            )

            val res = coordinator.executeSuspending(
                address = "t1q",
                label = "多选加自定义",
                operations = listOf(
                    SequenceOperation.SubmitAskAnswer(
                        questionIndex = 1,
                        selectedNumbers = setOf(1), // toggle 1 on, toggle 2 off!
                        writeInText = "保留兼容层"
                    )
                )
            )
            assertTrue(res.isSuccess)
            // Cursor started at 1 so `1` may toggle directly with space; then `2` + space; then `3` focuses Write-in; then UTF-8 + separate enter
            assertTrue(session.events.contains("TEXT:保留兼容层"))
            assertTrue(session.events.last() == "RAW_ENTER")
            assertEquals(true, opt1)
            assertEquals(false, opt2)
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 15_000)
    fun reconnectOnSameAskQuestionResumesIdempotentlyWithoutDoubleTogglingCheckedOption() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connState = MutableStateFlow<ConnectionState>(fakeConnectedState())
            val session = FakeAgySession(address = "t1q", initialMode = AttachMode.TAKEOVER, initialScreen = "")
            var opt1 = false
            var opt2 = false
            var cursor = 1

            fun renderAsk() = """
                Question 1/2: Enable features?
                Select all that apply

                ${if (cursor == 1) "›" else " "} 1. [${if (opt1) "x" else " "}] Feature A
                ${if (cursor == 2) "›" else " "} 2. [${if (opt2) "x" else " "}] Feature B
                ↑/↓ Navigate · Space to toggle
            """.trimIndent()

            session.pushScreen(renderAsk())
            var dropOnce = true
            session.onKeyOrText = { event ->
                when (event) {
                    "TEXT:1" -> {
                        cursor = 1
                        opt1 = !opt1
                        session.pushScreen(renderAsk())
                        if (dropOnce && opt1) {
                            dropOnce = false
                            session.setAttached(false)
                            connState.value = ConnectionState.Disconnected
                        }
                    }
                    "TEXT:2" -> {
                        cursor = 2
                        opt2 = !opt2
                        session.pushScreen(renderAsk())
                    }
                    "KEY:down" -> {
                        cursor = 2
                        session.pushScreen(renderAsk())
                    }
                    "KEY:space" -> {
                        if (cursor == 1) opt1 = !opt1
                        if (cursor == 2) opt2 = !opt2
                        session.pushScreen(renderAsk())
                        if (dropOnce && opt1) {
                            dropOnce = false
                            session.setAttached(false)
                            connState.value = ConnectionState.Disconnected
                        }
                    }
                    "KEY:enter", "RAW_ENTER" -> {
                        session.pushScreen(composerScreen(mode = "auto", draft = ""))
                    }
                }
            }

            // Background reconnect restores after 180ms with the STILL SAME Q1/2 screen (`opt1 == true`)
            val reconnectJob = launch(Dispatchers.Default) {
                while (connState.value is ConnectionState.Connected) delay(10)
                delay(180)
                connState.value = fakeConnectedState(host = "10.147.17.100")
                session.setAttached(true)
            }

            try {
                val coordinator = TerminalSequenceCoordinator(
                    scope = scope,
                    connectionState = connState,
                    sessionProvider = { if (session.isAttached.value) session else null },
                    onToast = {},
                    reconnectTimeoutMs = 3_000L
                )
                val res = coordinator.executeSuspending(
                    address = "t1q",
                    label = "断点续传多选",
                    operations = listOf(
                        SequenceOperation.SubmitAskAnswer(
                            questionIndex = 1,
                            selectedNumbers = setOf(1, 2)
                        )
                    )
                )
                reconnectJob.join()

                assertTrue("Expected idempotent resume success: ${res.exceptionOrNull()}", res.isSuccess)
                assertTrue("Feature A must stay checked", opt1)
                assertTrue("Feature B must become checked", opt2)
                assertEquals(1, session.events.count { it == "TEXT:1" })
                assertEquals(1, session.events.count { it == "TEXT:2" })
            } finally {
                reconnectJob.cancel()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 15_000)
    fun reconnectToDivergedScreenAbortsSafelyWithZeroEnterSent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val connState = MutableStateFlow<ConnectionState>(fakeConnectedState())
            val session = FakeAgySession(address = "t1q", initialMode = AttachMode.TAKEOVER, initialScreen = "")
            val q1Screen = """
                Question 1/2: Deploy to staging?
                Select all that apply

                › 1. [ ] Staging A
                  2. [ ] Staging B
                ↑/↓ Navigate · Space to toggle
            """.trimIndent()
            val divergedQ2Screen = """
                Question 2/2: Completely different question!

                › 1. Wipe database
                  2. Keep data
                Enter to submit
            """.trimIndent()
            session.pushScreen(q1Screen)

            session.onKeyOrText = { event ->
                if (event == "TEXT:1" || event == "KEY:space") {
                    session.setAttached(false)
                    connState.value = ConnectionState.Disconnected
                }
            }

            val toasts = CopyOnWriteArrayList<String>()
            val reconnectJob = launch(Dispatchers.Default) {
                while (connState.value is ConnectionState.Connected) delay(10)
                delay(160)
                session.pushScreen(divergedQ2Screen)
                connState.value = fakeConnectedState()
                session.setAttached(true)
            }

            try {
                val coordinator = TerminalSequenceCoordinator(
                    scope = scope,
                    connectionState = connState,
                    sessionProvider = { if (session.isAttached.value) session else null },
                    onToast = { toasts += it },
                    reconnectTimeoutMs = 3_000L
                )

                val res = coordinator.executeSuspending(
                    address = "t1q",
                    label = "提交 Q1",
                    operations = listOf(
                        SequenceOperation.SubmitAskAnswer(
                            questionIndex = 1,
                            selectedNumbers = setOf(1, 2)
                        )
                    )
                )
                reconnectJob.join()

                assertTrue("Expected failure on diverged screen", res.isFailure)
                assertFalse("Must NOT send Enter onto diverged Q2", session.events.any { it == "KEY:enter" || it == "RAW_ENTER" })
                assertTrue(toasts.any { "变化" in it || "中止" in it })
            } finally {
                reconnectJob.cancel()
            }
        } finally {
            scope.cancel()
        }
    }

    private fun fakeConnectedState(host: String = "192.168.1.100"): ConnectionState.Connected =
        ConnectionState.Connected(
            profile = ConnectionProfile(host = host),
            hello = HcHelloInfo(version = "0.1.18", coreId = "c1", coreName = "win"),
            serverStatus = HcServerStatus(version = "0.1.18", liveTerminals = 1, finishedTerminals = 0),
            stages = emptyList()
        )

    private fun composerScreen(mode: String, draft: String): String = """
        ╭────────────────────────────────────────────╮
        │ › ${draft.padEnd(40)} │
        ╰────────────────────────────────────────────╯
        esc to cancel   $mode · Orchestrator · Gemini 4 Carbon · high
    """.trimIndent()

    private class FakeAgySession(
        override val address: String,
        initialMode: AttachMode,
        initialScreen: String
    ) : TerminalSession {
        private val _mode = MutableStateFlow(initialMode)
        override val mode: StateFlow<AttachMode> = _mode.asStateFlow()

        private val _outputFrames = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
        override val outputFrames: Flow<ByteArray> = _outputFrames.asSharedFlow()

        private val _screenSnapshot = MutableStateFlow(initialScreen)
        override val screenSnapshot: StateFlow<String> = _screenSnapshot.asStateFlow()

        private val _dimensions = MutableStateFlow(120 to 40)
        override val dimensions: StateFlow<Pair<Int, Int>> = _dimensions.asStateFlow()

        private val _isAttached = MutableStateFlow(true)
        override val isAttached: StateFlow<Boolean> = _isAttached.asStateFlow()

        private val epoch = AtomicLong(0L)
        override val outputEpochCount: Long get() = epoch.get()

        var curMode: String = "auto"
        var curDraft: String = ""
        val events = CopyOnWriteArrayList<String>()
        var onKeyOrText: ((String) -> Unit)? = null

        fun setAttached(attached: Boolean) {
            _isAttached.value = attached
        }

        fun pushScreen(screen: String) {
            _screenSnapshot.value = screen
            val parsed = AgyInteractiveParser.inspectRaw(screen)
            curDraft = parsed.composerDraft
            epoch.incrementAndGet()
            _outputFrames.tryEmit(screen.toByteArray())
        }

        override suspend fun setMode(newMode: AttachMode) {
            _mode.value = newMode
        }

        override suspend fun sendRawInput(bytes: ByteArray): Result<Unit> {
            val tag = if (bytes.size == 1 && bytes[0].toInt() == 13) {
                "RAW_ENTER"
            } else {
                "TEXT:${bytes.toString(Charsets.UTF_8)}"
            }
            events += tag
            onKeyOrText?.invoke(tag)
            return Result.success(Unit)
        }

        override suspend fun sendLine(text: String): Result<Unit> {
            events += "LINE:$text"
            onKeyOrText?.invoke("LINE:$text")
            return Result.success(Unit)
        }

        override suspend fun putFile(fileName: String, bytes: ByteArray): Result<HcPutResult> =
            Result.success(HcPutResult(fileName, bytes.size.toLong()))

        override suspend fun sendSubmission(
            text: String,
            attachmentPaths: List<String>,
            isAgentTui: Boolean,
            submitWithEnter: Boolean
        ): Result<Unit> = Result.success(Unit)

        override suspend fun sendSpecialKey(keyName: String): Result<Unit> {
            val tag = "KEY:$keyName"
            events += tag
            onKeyOrText?.invoke(tag)
            return Result.success(Unit)
        }

        override suspend fun interrupt(): Result<Unit> = sendSpecialKey("ctrl+c")

        override suspend fun requestResize(cols: Int, rows: Int): Result<Unit> = Result.success(Unit)

        override suspend fun refreshScreenNow(screenOnly: Boolean): Result<String> =
            Result.success(_screenSnapshot.value)

        override suspend fun awaitOutputSettle(
            previousEpoch: Long,
            waitFirstByteMs: Long,
            quietWindowMs: Long,
            maxTotalWaitMs: Long
        ): Boolean = outputEpochCount > previousEpoch

        override suspend fun detach() {
            _isAttached.value = false
        }
    }
}
