package dev.herdr.android.ui.terminal

/**
 * High-level closed-loop operations over an attached Antigravity (`agy`) TUI terminal.
 *
 * Unlike blind macro injection, every operation inspects the latest perceived [AgyTerminalState],
 * sends only the minimal keystrokes/text needed from the current UI anchor, and verifies each
 * transition before moving to the next step — making execution resilient and strictly idempotent
 * across network drops and reconnects.
 */
enum class AskNavDirection(val wireKey: String, val label: String) {
    PREV("left", "上一题"),
    NEXT("right", "下一题"),
    CANCEL("esc", "取消问答")
}

sealed interface SequenceOperation {
    val description: String

    /** Escapes any open slash modal or clears dirty unsubmitted composer text cleanly. */
    data object EnsureComposerReady : SequenceOperation {
        override val description: String = "清理输入框与弹窗"
    }

    /** Sends a direct slash command (e.g. `/effort high` or `/model`), optionally expecting a modal to open. */
    data class SendSlashCommand(
        val command: String,
        val expectModal: AgySlashModalKind? = null
    ) : SequenceOperation {
        override val description: String = "执行 $command"
    }

    /** Closed-loop `Shift+Tab` mode cycler stopping precisely on [targetMode]. */
    data class CycleAgentModeTo(val targetMode: AgyAgentMode) : SequenceOperation {
        override val description: String = "切换至 ${targetMode.label} 模式"
    }

    /**
     * Submits a single or multi-select answer (plus optional Write-in text) for [questionIndex]
     * inside an active [AskQuestion] card.
     */
    data class SubmitAskAnswer(
        val questionIndex: Int,
        val selectedNumbers: Set<Int> = emptySet(),
        val writeInText: String? = null
    ) : SequenceOperation {
        override val description: String = "提交问题 $questionIndex 的选择"
    }

    /** Navigates across multi-question Ask cards (`left` / `right`) or dismisses (`esc`). */
    data class NavigateAskQuestion(
        val direction: AskNavDirection,
        val expectedFromQuestionIndex: Int? = null
    ) : SequenceOperation {
        override val description: String = direction.label
    }

    /** Moves modal cursor to [targetIndex] and optionally confirms with `Enter`. */
    data class ModalSelectItem(
        val expectedKind: AgySlashModalKind,
        val targetIndex: Int,
        val confirmWithEnter: Boolean = true,
        val targetTitleHint: String? = null
    ) : SequenceOperation {
        override val description: String = targetTitleHint?.let { "选择 $it" } ?: "选择面板第 ${targetIndex + 1} 项"
    }

    /** Moves modal cursor to [targetIndex] and nudges its value left/right (`←` / `→`). */
    data class ModalStepItemValue(
        val expectedKind: AgySlashModalKind,
        val targetIndex: Int,
        val moveRight: Boolean,
        val itemLabelHint: String? = null
    ) : SequenceOperation {
        override val description: String = itemLabelHint?.let { "调整 $it" } ?: "调节设置项"
    }

    /** Switches tab inside an active modal (`Tab` or `Shift+Tab`). */
    data class ModalSwitchTab(
        val expectedKind: AgySlashModalKind,
        val forward: Boolean = true
    ) : SequenceOperation {
        override val description: String = "切换面板分页"
    }

    /** Sends raw filter/search characters into a searchable modal like `/resume` or `/model`. */
    data class ModalFilterText(
        val expectedKind: AgySlashModalKind,
        val query: String
    ) : SequenceOperation {
        override val description: String = "筛选 $query"
    }

    /** Sends a single special key inside an active slash modal (e.g. `enter`, `space`, `a`, `r`, `esc`). */
    data class ModalRawKey(
        val expectedKind: AgySlashModalKind,
        val keyOrText: String,
        val isSpecialKey: Boolean = true,
        override val description: String = "发送 $keyOrText"
    ) : SequenceOperation

    /** Closes any open slash modal (`Esc`). */
    data object CloseSlashModal : SequenceOperation {
        override val description: String = "退出当前面板"
    }

    /** Dismisses or cancels the active `/btw` side-question card (`Esc`). */
    data object DismissBtwCard : SequenceOperation {
        override val description: String = "关闭旁路快问"
    }

    /** Sends `/btw <question>` cleanly into an Antigravity session. */
    data class SendBtwQuestion(
        val question: String,
        val previousQuestion: String? = null,
        val previousTurnCount: Int = 0
    ) : SequenceOperation {
        override val description: String = "发起旁路快问"
    }
}

internal enum class SequenceStepVerdict {
    ALREADY_DONE,
    EXECUTE_KEY,
    EXECUTE_TEXT,
    WAIT_MORE,
    ABORT_DIVERGED
}

internal sealed interface SequenceAtomicCommand {
    data class SpecialKey(val wireKey: String) : SequenceAtomicCommand
    data class RawText(val text: String, val pressEnterAfter: Boolean = false) : SequenceAtomicCommand
}

internal data class SequenceStepInstruction(
    val verdict: SequenceStepVerdict,
    val command: SequenceAtomicCommand? = null,
    val settleWaitMs: Long = 450L,
    val postPauseMs: Long = 0L,
    val reason: String = ""
)

/**
 * Pure deterministic planner computing the *single next atomic terminal action* given an
 * operation and the current observed [AgyTerminalState]. Calling it repeatedly in a closed loop
 * after each frame settle naturally handles both normal execution and post-reconnect re-anchoring.
 */
internal object AgySequencePlanner {

    fun nextAction(
        op: SequenceOperation,
        state: AgyTerminalState,
        sentCommandThisOp: Boolean = false
    ): SequenceStepInstruction = when (op) {
        is SequenceOperation.EnsureComposerReady -> planEnsureComposerReady(state)
        is SequenceOperation.SendSlashCommand -> planSendSlashCommand(op, state, sentCommandThisOp)
        is SequenceOperation.CycleAgentModeTo -> planCycleAgentMode(op, state)
        is SequenceOperation.SubmitAskAnswer -> planSubmitAskAnswer(op, state)
        is SequenceOperation.NavigateAskQuestion -> planNavigateAskQuestion(op, state, sentCommandThisOp)
        is SequenceOperation.ModalSelectItem -> planModalSelectItem(op, state, sentCommandThisOp)
        is SequenceOperation.ModalStepItemValue -> planModalStepItemValue(op, state, sentCommandThisOp)
        is SequenceOperation.ModalSwitchTab -> planModalSwitchTab(op, state, sentCommandThisOp)
        is SequenceOperation.ModalFilterText -> planModalFilterText(op, state, sentCommandThisOp)
        is SequenceOperation.ModalRawKey -> planModalRawKey(op, state, sentCommandThisOp)
        is SequenceOperation.CloseSlashModal -> {
            if (state.phase == AgyUiPhase.SLASH_MODAL) {
                SequenceStepInstruction(
                    verdict = SequenceStepVerdict.EXECUTE_KEY,
                    command = SequenceAtomicCommand.SpecialKey("esc"),
                    settleWaitMs = 500L
                )
            } else {
                SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
            }
        }
        is SequenceOperation.DismissBtwCard -> planDismissBtwCard(state)
        is SequenceOperation.SendBtwQuestion -> planSendBtwQuestion(op, state, sentCommandThisOp)
    }

    /**
     * Checks after a reconnect whether it is strictly safe to re-anchor [op] onto [freshState].
     * Never returns safe if an irreversible command without clear feedback may already have run.
     */
    fun canIdempotentlyResumeAfterReconnect(
        op: SequenceOperation,
        freshState: AgyTerminalState,
        sentCommandBeforeDrop: Boolean
    ): Boolean = when (op) {
        is SequenceOperation.EnsureComposerReady -> true
        is SequenceOperation.CycleAgentModeTo ->
            freshState.phase == AgyUiPhase.COMPOSER_EMPTY || freshState.phase == AgyUiPhase.COMPOSER_HAS_DRAFT
        is SequenceOperation.SendSlashCommand -> {
            // If we hadn't sent it yet, safe; if we did send it and the expected modal opened, ALREADY_DONE!
            !sentCommandBeforeDrop ||
                (op.expectModal != null && freshState.phase == AgyUiPhase.SLASH_MODAL &&
                    freshState.activeSlashModal?.kind == op.expectModal)
        }
        is SequenceOperation.SubmitAskAnswer -> {
            val card = freshState.activeAskCard
            // Safe ONLY if we're still on the exact same question card!
            card != null && card.questionIndex == op.questionIndex
        }
        is SequenceOperation.NavigateAskQuestion -> {
            val card = freshState.activeAskCard
            when (op.direction) {
                AskNavDirection.CANCEL -> card == null || op.expectedFromQuestionIndex == null || card.questionIndex == op.expectedFromQuestionIndex
                else -> card != null && (op.expectedFromQuestionIndex == null || card.questionIndex == op.expectedFromQuestionIndex)
            }
        }
        is SequenceOperation.ModalSelectItem -> {
            val modal = freshState.activeSlashModal
            modal != null && modal.kind == op.expectedKind
        }
        is SequenceOperation.ModalStepItemValue -> {
            !sentCommandBeforeDrop && freshState.activeSlashModal?.kind == op.expectedKind
        }
        is SequenceOperation.ModalSwitchTab -> {
            !sentCommandBeforeDrop && freshState.activeSlashModal?.kind == op.expectedKind
        }
        is SequenceOperation.ModalFilterText -> {
            val modal = freshState.activeSlashModal
            modal != null && modal.kind == op.expectedKind &&
                (!sentCommandBeforeDrop || modal.searchQuery == op.query)
        }
        is SequenceOperation.ModalRawKey -> {
            !sentCommandBeforeDrop && freshState.activeSlashModal?.kind == op.expectedKind
        }
        is SequenceOperation.CloseSlashModal -> true
        is SequenceOperation.DismissBtwCard -> true
        is SequenceOperation.SendBtwQuestion -> !sentCommandBeforeDrop || freshState.activeBtwCard != null
    }

    private fun planEnsureComposerReady(state: AgyTerminalState): SequenceStepInstruction = when (state.phase) {
        AgyUiPhase.COMPOSER_EMPTY, AgyUiPhase.WORKING ->
            SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        AgyUiPhase.SLASH_MODAL ->
            SequenceStepInstruction(
                verdict = SequenceStepVerdict.EXECUTE_KEY,
                command = SequenceAtomicCommand.SpecialKey("esc"),
                settleWaitMs = 400L
            )
        AgyUiPhase.COMPOSER_HAS_DRAFT -> {
            val key = if (state.isSlashSuggestionOpen) "esc" else "ctrl+u"
            SequenceStepInstruction(
                verdict = SequenceStepVerdict.EXECUTE_KEY,
                command = SequenceAtomicCommand.SpecialKey(key),
                settleWaitMs = 350L
            )
        }
        AgyUiPhase.ASK_QUESTION_MODAL, AgyUiPhase.PERMISSION_MODAL ->
            SequenceStepInstruction(
                verdict = SequenceStepVerdict.ABORT_DIVERGED,
                reason = "当前有待处理弹窗，已暂停清空输入框"
            )
    }

    private fun planSendSlashCommand(
        op: SequenceOperation.SendSlashCommand,
        state: AgyTerminalState,
        sentAlready: Boolean
    ): SequenceStepInstruction {
        if (op.expectModal != null && state.phase == AgyUiPhase.SLASH_MODAL && state.activeSlashModal != null) {
            val curKind = state.activeSlashModal.kind
            if (sentAlready || curKind == op.expectModal || curKind == AgySlashModalKind.GENERIC) {
                return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
            }
        }
        if (sentAlready) {
            if (op.expectModal == AgySlashModalKind.BTW && state.activeBtwCard != null) {
                return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
            }
            if (state.phase == AgyUiPhase.COMPOSER_HAS_DRAFT) {
                val cmdHead = op.command.trim().substringBefore(' ')
                if (state.composerDraft.trimStart().startsWith(cmdHead, ignoreCase = true)) {
                    return SequenceStepInstruction(
                        verdict = SequenceStepVerdict.EXECUTE_KEY,
                        command = SequenceAtomicCommand.SpecialKey("enter"),
                        settleWaitMs = 600L
                    )
                }
            }
            if (op.expectModal != null && (state.hasWorkingHint || state.activeSlashModal?.isLoading == true)) {
                return SequenceStepInstruction(
                    verdict = SequenceStepVerdict.WAIT_MORE,
                    settleWaitMs = 350L
                )
            }
            return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        }
        if (state.phase == AgyUiPhase.ASK_QUESTION_MODAL || state.phase == AgyUiPhase.PERMISSION_MODAL) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.ABORT_DIVERGED,
                reason = "当前处于阻塞确认弹窗，无法插入斜杠命令"
            )
        }
        return SequenceStepInstruction(
            verdict = SequenceStepVerdict.EXECUTE_TEXT,
            command = SequenceAtomicCommand.RawText(op.command, pressEnterAfter = true),
            settleWaitMs = 650L,
            postPauseMs = 55L
        )
    }

    private fun planDismissBtwCard(state: AgyTerminalState): SequenceStepInstruction {
        if (state.activeBtwCard == null) {
            return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        }
        if (state.phase == AgyUiPhase.ASK_QUESTION_MODAL || state.phase == AgyUiPhase.PERMISSION_MODAL) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.ABORT_DIVERGED,
                reason = "当前处于阻塞确认弹窗，无法关闭 /btw 卡片"
            )
        }
        return SequenceStepInstruction(
            verdict = SequenceStepVerdict.EXECUTE_KEY,
            command = SequenceAtomicCommand.SpecialKey("esc"),
            settleWaitMs = 450L
        )
    }

    private fun planSendBtwQuestion(
        op: SequenceOperation.SendBtwQuestion,
        state: AgyTerminalState,
        sentAlready: Boolean
    ): SequenceStepInstruction {
        val cleanQuestion = op.question.trim()
        if (cleanQuestion.isEmpty()) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.ABORT_DIVERGED,
                reason = "请输入 /btw 问题内容"
            )
        }
        if (sentAlready) {
            val activeCard = state.activeBtwCard
            if (activeCard != null) {
                val isNewOrActive = activeCard.isRunning ||
                    (op.previousTurnCount > 0 && activeCard.turns.size > op.previousTurnCount) ||
                    op.previousQuestion == null ||
                    !activeCard.question.equals(op.previousQuestion.trim(), ignoreCase = true)
                return if (isNewOrActive) {
                    SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
                } else {
                    SequenceStepInstruction(
                        verdict = SequenceStepVerdict.WAIT_MORE,
                        settleWaitMs = 250L
                    )
                }
            }
            if (state.phase == AgyUiPhase.COMPOSER_HAS_DRAFT &&
                state.composerDraft.trimStart().startsWith("/btw", ignoreCase = true)
            ) {
                return SequenceStepInstruction(
                    verdict = SequenceStepVerdict.EXECUTE_KEY,
                    command = SequenceAtomicCommand.SpecialKey("enter"),
                    settleWaitMs = 550L
                )
            }
            if (state.hasWorkingHint) {
                return SequenceStepInstruction(
                    verdict = SequenceStepVerdict.WAIT_MORE,
                    settleWaitMs = 350L
                )
            }
            return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        }
        if (state.activeBtwCard?.isRunning == true) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.ABORT_DIVERGED,
                reason = "当前仍有 /btw 正在执行，请先等待完成或点击取消"
            )
        }
        if (state.phase == AgyUiPhase.ASK_QUESTION_MODAL || state.phase == AgyUiPhase.PERMISSION_MODAL) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.ABORT_DIVERGED,
                reason = "当前处于阻塞确认弹窗，无法发起 /btw"
            )
        }
        if (state.phase == AgyUiPhase.SLASH_MODAL) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.ABORT_DIVERGED,
                reason = "当前面板尚未关闭，请先关闭后再发起 /btw"
            )
        }
        return SequenceStepInstruction(
            verdict = SequenceStepVerdict.EXECUTE_TEXT,
            command = SequenceAtomicCommand.RawText("/btw $cleanQuestion", pressEnterAfter = true),
            settleWaitMs = 600L,
            postPauseMs = 55L
        )
    }

    private fun planCycleAgentMode(
        op: SequenceOperation.CycleAgentModeTo,
        state: AgyTerminalState
    ): SequenceStepInstruction {
        if (state.agentMode == op.targetMode) {
            return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        }
        if (state.phase == AgyUiPhase.ASK_QUESTION_MODAL || state.phase == AgyUiPhase.PERMISSION_MODAL) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.ABORT_DIVERGED,
                reason = "当前有审批或提问阻塞，请先响应弹窗"
            )
        }
        return SequenceStepInstruction(
            verdict = SequenceStepVerdict.EXECUTE_KEY,
            command = SequenceAtomicCommand.SpecialKey("shift+tab"),
            settleWaitMs = 450L
        )
    }

    private fun planSubmitAskAnswer(
        op: SequenceOperation.SubmitAskAnswer,
        state: AgyTerminalState
    ): SequenceStepInstruction {
        val card = state.activeAskCard
            ?: return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        if (card.questionIndex != op.questionIndex) {
            return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        }

        val cleanWriteIn = op.writeInText?.trim()?.takeIf { it.isNotEmpty() }
        val writeInOpt = card.options.firstOrNull { it.isWriteIn }
        val validOptionNums = card.options.filterNot { it.isWriteIn }.mapTo(HashSet()) { it.number }
        val desiredChecked = op.selectedNumbers.intersect(validOptionNums)

        if (!card.isMultiSelect) {
            if (cleanWriteIn != null) {
                val targetOpt = writeInOpt
                    ?: return SequenceStepInstruction(
                        verdict = SequenceStepVerdict.ABORT_DIVERGED,
                        reason = "当前问题未提供自定义输入项"
                    )
                if (!card.isWriteInFocused) {
                    return focusOptionCommand(card, targetOpt.number, pressEnterOnArrow = true)
                }
                if (card.currentWriteInDraft.isNotEmpty() && card.currentWriteInDraft != cleanWriteIn) {
                    return SequenceStepInstruction(
                        verdict = SequenceStepVerdict.EXECUTE_KEY,
                        command = SequenceAtomicCommand.SpecialKey("ctrl+u"),
                        settleWaitMs = 300L
                    )
                }
                return SequenceStepInstruction(
                    verdict = SequenceStepVerdict.EXECUTE_TEXT,
                    command = SequenceAtomicCommand.RawText(cleanWriteIn, pressEnterAfter = true),
                    settleWaitMs = 600L,
                    postPauseMs = 70L
                )
            }

            val singleChoice = desiredChecked.firstOrNull()
                ?: return SequenceStepInstruction(
                    verdict = SequenceStepVerdict.ABORT_DIVERGED,
                    reason = "请先选择一个选项或填写自定义回答"
                )
            if (card.isWriteInFocused) {
                return SequenceStepInstruction(
                    verdict = SequenceStepVerdict.EXECUTE_KEY,
                    command = SequenceAtomicCommand.SpecialKey("esc"),
                    settleWaitMs = 350L
                )
            }
            return selectSingleDigitOrMove(card, singleChoice, confirmEnter = true)
        }

        // Multi-select AskQuestionModel:
        // 1. Compute exact idempotent symmetric difference of checkboxes
        val currentlyChecked = card.options.filter { it.isChecked && !it.isWriteIn }.mapTo(HashSet()) { it.number }
        val toToggle = ((desiredChecked - currentlyChecked) + (currentlyChecked - desiredChecked)).sorted()
        if (toToggle.isNotEmpty()) {
            if (card.isWriteInFocused) {
                return SequenceStepInstruction(
                    verdict = SequenceStepVerdict.EXECUTE_KEY,
                    command = SequenceAtomicCommand.SpecialKey("esc"),
                    settleWaitMs = 350L
                )
            }
            val nextToggleNum = toToggle.first()
            return toggleMultiCheckbox(card, nextToggleNum)
        }

        // 2. If user also provided write-in text, focus Write-in... and submit text + Enter
        if (cleanWriteIn != null) {
            val targetOpt = writeInOpt
                ?: return SequenceStepInstruction(
                    verdict = SequenceStepVerdict.ABORT_DIVERGED,
                    reason = "当前问题未提供自定义输入项"
                )
            if (!card.isWriteInFocused) {
                return focusOptionCommand(card, targetOpt.number, pressEnterOnArrow = true)
            }
            if (card.currentWriteInDraft.isNotEmpty() && card.currentWriteInDraft != cleanWriteIn) {
                return SequenceStepInstruction(
                    verdict = SequenceStepVerdict.EXECUTE_KEY,
                    command = SequenceAtomicCommand.SpecialKey("ctrl+u"),
                    settleWaitMs = 300L
                )
            }
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.EXECUTE_TEXT,
                command = SequenceAtomicCommand.RawText(cleanWriteIn, pressEnterAfter = true),
                settleWaitMs = 600L,
                postPauseMs = 70L
            )
        }

        // 3. Otherwise verify desired options are non-empty, ensure cursor is not stuck on Write-in focus, and Enter!
        if (desiredChecked.isEmpty()) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.ABORT_DIVERGED,
                reason = "多选题请至少勾选一项或输入自定义内容"
            )
        }
        if (card.isWriteInFocused) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.EXECUTE_KEY,
                command = SequenceAtomicCommand.SpecialKey("esc"),
                settleWaitMs = 350L
            )
        }
        val curCursor = card.options.firstOrNull { it.isCursor }
        if (curCursor != null && curCursor.isWriteIn && card.options.size > 1) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.EXECUTE_KEY,
                command = SequenceAtomicCommand.SpecialKey("up"),
                settleWaitMs = 320L
            )
        }
        return SequenceStepInstruction(
            verdict = SequenceStepVerdict.EXECUTE_KEY,
            command = SequenceAtomicCommand.SpecialKey("enter"),
            settleWaitMs = 600L
        )
    }

    private fun focusOptionCommand(
        card: AgyAskCardState,
        optionNumber: Int,
        pressEnterOnArrow: Boolean
    ): SequenceStepInstruction {
        if (optionNumber in 1..9) {
            // In AskQuestionModel, pressing 1..9 jumps to that option (and opens Write-in input)
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.EXECUTE_TEXT,
                command = SequenceAtomicCommand.RawText(optionNumber.toString(), pressEnterAfter = false),
                settleWaitMs = 450L
            )
        }
        val curIdx = card.options.indexOfFirst { it.isCursor }.coerceAtLeast(0)
        val tgtIdx = card.options.indexOfFirst { it.number == optionNumber }.coerceAtLeast(0)
        if (curIdx != tgtIdx) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.EXECUTE_KEY,
                command = SequenceAtomicCommand.SpecialKey(if (tgtIdx > curIdx) "down" else "up"),
                settleWaitMs = 300L
            )
        }
        return if (pressEnterOnArrow) {
            SequenceStepInstruction(
                verdict = SequenceStepVerdict.EXECUTE_KEY,
                command = SequenceAtomicCommand.SpecialKey("enter"),
                settleWaitMs = 400L
            )
        } else {
            SequenceStepInstruction(
                verdict = SequenceStepVerdict.EXECUTE_KEY,
                command = SequenceAtomicCommand.SpecialKey("space"),
                settleWaitMs = 400L
            )
        }
    }

    private fun selectSingleDigitOrMove(
        card: AgyAskCardState,
        optionNumber: Int,
        confirmEnter: Boolean
    ): SequenceStepInstruction {
        if (optionNumber in 1..9) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.EXECUTE_TEXT,
                command = SequenceAtomicCommand.RawText(optionNumber.toString(), pressEnterAfter = false),
                settleWaitMs = 550L
            )
        }
        val curIdx = card.options.indexOfFirst { it.isCursor }.coerceAtLeast(0)
        val tgtIdx = card.options.indexOfFirst { it.number == optionNumber }.coerceAtLeast(0)
        if (curIdx != tgtIdx) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.EXECUTE_KEY,
                command = SequenceAtomicCommand.SpecialKey(if (tgtIdx > curIdx) "down" else "up"),
                settleWaitMs = 300L
            )
        }
        return SequenceStepInstruction(
            verdict = SequenceStepVerdict.EXECUTE_KEY,
            command = SequenceAtomicCommand.SpecialKey(if (confirmEnter) "enter" else "space"),
            settleWaitMs = 550L
        )
    }

    private fun toggleMultiCheckbox(card: AgyAskCardState, optionNumber: Int): SequenceStepInstruction {
        if (optionNumber in 1..9) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.EXECUTE_TEXT,
                command = SequenceAtomicCommand.RawText(optionNumber.toString(), pressEnterAfter = false),
                settleWaitMs = 400L
            )
        }
        val curIdx = card.options.indexOfFirst { it.isCursor }.coerceAtLeast(0)
        val tgtIdx = card.options.indexOfFirst { it.number == optionNumber }.coerceAtLeast(0)
        if (curIdx != tgtIdx) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.EXECUTE_KEY,
                command = SequenceAtomicCommand.SpecialKey(if (tgtIdx > curIdx) "down" else "up"),
                settleWaitMs = 300L
            )
        }
        return SequenceStepInstruction(
            verdict = SequenceStepVerdict.EXECUTE_KEY,
            command = SequenceAtomicCommand.SpecialKey("space"),
            settleWaitMs = 400L
        )
    }

    private fun planNavigateAskQuestion(
        op: SequenceOperation.NavigateAskQuestion,
        state: AgyTerminalState,
        sentAlready: Boolean
    ): SequenceStepInstruction {
        val card = state.activeAskCard
        if (card == null) {
            return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        }
        if (op.expectedFromQuestionIndex != null && card.questionIndex != op.expectedFromQuestionIndex) {
            return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        }
        if (sentAlready) {
            return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        }
        if (card.isWriteInFocused && op.direction != AskNavDirection.CANCEL) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.EXECUTE_KEY,
                command = SequenceAtomicCommand.SpecialKey("esc"),
                settleWaitMs = 350L
            )
        }
        return SequenceStepInstruction(
            verdict = SequenceStepVerdict.EXECUTE_KEY,
            command = SequenceAtomicCommand.SpecialKey(op.direction.wireKey),
            settleWaitMs = 500L
        )
    }

    private fun planModalSelectItem(
        op: SequenceOperation.ModalSelectItem,
        state: AgyTerminalState,
        sentAlready: Boolean
    ): SequenceStepInstruction {
        val modal = state.activeSlashModal
        if (modal == null) {
            return if (sentAlready) {
                SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
            } else {
                SequenceStepInstruction(
                    verdict = SequenceStepVerdict.ABORT_DIVERGED,
                    reason = "当前面板已关闭"
                )
            }
        }
        if (modal.kind != op.expectedKind && op.expectedKind != AgySlashModalKind.GENERIC && modal.kind != AgySlashModalKind.GENERIC) {
            return SequenceStepInstruction(
                verdict = SequenceStepVerdict.ABORT_DIVERGED,
                reason = "当前打开的是 ${modal.kind.displayTitle}，与预期不符"
            )
        }
        if (modal.items.isNotEmpty()) {
            val clampedTarget = op.targetIndex.coerceIn(0, modal.items.lastIndex)
            if (modal.selectedIndex != clampedTarget) {
                val key = if (clampedTarget > modal.selectedIndex) "down" else "up"
                return SequenceStepInstruction(
                    verdict = SequenceStepVerdict.EXECUTE_KEY,
                    command = SequenceAtomicCommand.SpecialKey(key),
                    settleWaitMs = 300L
                )
            }
        }
        if (!op.confirmWithEnter) {
            return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        }
        if (sentAlready) {
            return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        }
        return SequenceStepInstruction(
            verdict = SequenceStepVerdict.EXECUTE_KEY,
            command = SequenceAtomicCommand.SpecialKey("enter"),
            settleWaitMs = 600L
        )
    }

    private fun planModalStepItemValue(
        op: SequenceOperation.ModalStepItemValue,
        state: AgyTerminalState,
        sentAlready: Boolean
    ): SequenceStepInstruction {
        if (sentAlready) return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        val modal = state.activeSlashModal
            ?: return SequenceStepInstruction(SequenceStepVerdict.ABORT_DIVERGED, reason = "当前面板已关闭")
        if (modal.kind != op.expectedKind && op.expectedKind != AgySlashModalKind.GENERIC && modal.kind != AgySlashModalKind.GENERIC) {
            return SequenceStepInstruction(SequenceStepVerdict.ABORT_DIVERGED, reason = "当前面板已切换")
        }
        if (modal.items.isNotEmpty()) {
            val clampedTarget = op.targetIndex.coerceIn(0, modal.items.lastIndex)
            if (modal.selectedIndex != clampedTarget) {
                val key = if (clampedTarget > modal.selectedIndex) "down" else "up"
                return SequenceStepInstruction(
                    verdict = SequenceStepVerdict.EXECUTE_KEY,
                    command = SequenceAtomicCommand.SpecialKey(key),
                    settleWaitMs = 300L
                )
            }
        }
        return SequenceStepInstruction(
            verdict = SequenceStepVerdict.EXECUTE_KEY,
            command = SequenceAtomicCommand.SpecialKey(if (op.moveRight) "right" else "left"),
            settleWaitMs = 450L
        )
    }

    private fun planModalSwitchTab(
        op: SequenceOperation.ModalSwitchTab,
        state: AgyTerminalState,
        sentAlready: Boolean
    ): SequenceStepInstruction {
        if (sentAlready) return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        val modal = state.activeSlashModal
            ?: return SequenceStepInstruction(SequenceStepVerdict.ABORT_DIVERGED, reason = "当前面板已关闭")
        if (modal.kind != op.expectedKind && op.expectedKind != AgySlashModalKind.GENERIC && modal.kind != AgySlashModalKind.GENERIC) {
            return SequenceStepInstruction(SequenceStepVerdict.ABORT_DIVERGED, reason = "当前面板已切换")
        }
        return SequenceStepInstruction(
            verdict = SequenceStepVerdict.EXECUTE_KEY,
            command = SequenceAtomicCommand.SpecialKey(if (op.forward) "tab" else "shift+tab"),
            settleWaitMs = 450L
        )
    }

    private fun planModalFilterText(
        op: SequenceOperation.ModalFilterText,
        state: AgyTerminalState,
        sentAlready: Boolean
    ): SequenceStepInstruction {
        if (sentAlready) return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        val modal = state.activeSlashModal
            ?: return SequenceStepInstruction(SequenceStepVerdict.ABORT_DIVERGED, reason = "当前面板已关闭")
        if (modal.searchQuery == op.query) {
            return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        }
        return SequenceStepInstruction(
            verdict = SequenceStepVerdict.EXECUTE_TEXT,
            command = SequenceAtomicCommand.RawText(op.query, pressEnterAfter = false),
            settleWaitMs = 450L
        )
    }

    private fun planModalRawKey(
        op: SequenceOperation.ModalRawKey,
        state: AgyTerminalState,
        sentAlready: Boolean
    ): SequenceStepInstruction {
        if (sentAlready) return SequenceStepInstruction(SequenceStepVerdict.ALREADY_DONE)
        if (state.phase != AgyUiPhase.SLASH_MODAL) {
            return SequenceStepInstruction(SequenceStepVerdict.ABORT_DIVERGED, reason = "当前未处于面板状态")
        }
        val cmd = if (op.isSpecialKey) {
            SequenceAtomicCommand.SpecialKey(op.keyOrText)
        } else {
            SequenceAtomicCommand.RawText(op.keyOrText, pressEnterAfter = false)
        }
        return SequenceStepInstruction(
            verdict = SequenceStepVerdict.EXECUTE_KEY,
            command = cmd,
            settleWaitMs = 450L
        )
    }
}
