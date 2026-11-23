package dev.herdr.android.ui.workbench

import dev.herdr.android.connection.ConnectionState
import dev.herdr.android.protocol.AttachMode
import dev.herdr.android.protocol.TerminalSession
import dev.herdr.android.ui.terminal.AgyInteractiveParser
import dev.herdr.android.ui.terminal.AgySequencePlanner
import dev.herdr.android.ui.terminal.AgySlashModalKind
import dev.herdr.android.ui.terminal.AgyTerminalState
import dev.herdr.android.ui.terminal.SequenceAtomicCommand
import dev.herdr.android.ui.terminal.SequenceOperation
import dev.herdr.android.ui.terminal.SequenceStepVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SequenceExecutionStatus(
    val address: String,
    val label: String,
    val stepIndex: Int,
    val totalSteps: Int,
    val currentStepDescription: String,
    val reconnecting: Boolean = false
)

/**
 * Executes multi-step [SequenceOperation] lists against a live [TerminalSession] using
 * reactive frame-settle closing loops (`outputEpochCount` + `refreshScreenNow`) and strict
 * idempotent re-anchoring across transient SSH / ZeroTier network drops.
 */
class TerminalSequenceCoordinator(
    private val scope: CoroutineScope,
    private val connectionState: StateFlow<ConnectionState>,
    private val sessionProvider: (String) -> TerminalSession?,
    private val onToast: (String) -> Unit,
    private val reconnectTimeoutMs: Long = 12_000L
) {
    private val _status = MutableStateFlow<SequenceExecutionStatus?>(null)
    val status: StateFlow<SequenceExecutionStatus?> = _status.asStateFlow()

    @Volatile
    private var activeJob: Job? = null

    fun execute(address: String, label: String, operations: List<SequenceOperation>) {
        if (operations.isEmpty()) return
        activeJob?.cancel()
        activeJob = scope.launch {
            executeSuspending(address, label, operations)
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        _status.value = null
    }

    suspend fun executeSuspending(
        address: String,
        label: String,
        operations: List<SequenceOperation>
    ): Result<Unit> {
        if (operations.isEmpty()) return Result.success(Unit)
        try {
            var lastSession: TerminalSession? = sessionProvider(address)
            for ((opIdx, op) in operations.withIndex()) {
                _status.value = SequenceExecutionStatus(
                    address = address,
                    label = label,
                    stepIndex = opIdx + 1,
                    totalSteps = operations.size,
                    currentStepDescription = op.description,
                    reconnecting = false
                )

                var sentTerminalMutation = false
                var atomicAttempts = 0
                val maxAtomicSteps = 18

                while (atomicAttempts < maxAtomicSteps) {
                    if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                        throw CancellationException("Sequence cancelled")
                    }

                    var session = sessionProvider(address)
                    val disconnected = session == null ||
                        !session.isAttached.value ||
                        connectionState.value !is ConnectionState.Connected

                    if (disconnected) {
                        val reanchored = recoverAndReanchor(
                            address = address,
                            label = label,
                            stepIndex = opIdx + 1,
                            totalSteps = operations.size,
                            op = op,
                            sentBeforeDrop = sentTerminalMutation
                        )
                        if (reanchored.isFailure) {
                            val err = reanchored.exceptionOrNull() ?: IllegalStateException("网络恢复失败，序列已安全中止")
                            onToast(err.message ?: "序列已中止")
                            return Result.failure(err)
                        }
                        session = reanchored.getOrThrow()
                    }
                    lastSession = session!!

                    if (session.mode.value != AttachMode.TAKEOVER) {
                        val modeRes = runCatching { session.setMode(AttachMode.TAKEOVER) }
                        if (modeRes.isFailure) continue
                    }

                    val screenRes = session.refreshScreenNow(screenOnly = true)
                    if (screenRes.isFailure) {
                        // Retry through loop so disconnect check triggers if connection dropped
                        delay(40)
                        continue
                    }

                    val state = AgyInteractiveParser.inspectRaw(screenRes.getOrThrow())
                    val instruction = AgySequencePlanner.nextAction(op, state, sentTerminalMutation)

                    when (instruction.verdict) {
                        SequenceStepVerdict.ALREADY_DONE -> break
                        SequenceStepVerdict.ABORT_DIVERGED -> {
                            onToast(instruction.reason.ifBlank { "界面状态已变化，无法继续当前操作" })
                            return Result.failure(IllegalStateException(instruction.reason))
                        }
                        SequenceStepVerdict.EXECUTE_KEY, SequenceStepVerdict.EXECUTE_TEXT -> {
                            val prevEpoch = session.outputEpochCount
                            val cmd = instruction.command ?: break
                            val sendRes = sendAtomicCommand(session, cmd)
                            if (sendRes.isFailure) {
                                // Do NOT fail directly on IO drop: let the next iteration enter recoverAndReanchor!
                                delay(60)
                                atomicAttempts++
                                continue
                            }

                            if (isTerminalMutationForOp(op, cmd)) {
                                sentTerminalMutation = true
                            }
                            session.awaitOutputSettle(
                                previousEpoch = prevEpoch,
                                waitFirstByteMs = instruction.settleWaitMs
                            )
                            atomicAttempts++
                        }
                    }
                }
            }

            // Refresh full stitched view when closing a modal or finishing non-modal command
            val finalOp = operations.lastOrNull()
            if (finalOp is SequenceOperation.CloseSlashModal ||
                finalOp is SequenceOperation.SubmitAskAnswer ||
                (finalOp is SequenceOperation.SendSlashCommand && finalOp.expectModal == null)
            ) {
                runCatching { lastSession?.refreshScreenNow(screenOnly = false) }
            }
            return Result.success(Unit)
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            _status.value = null
        }
    }

    private suspend fun sendAtomicCommand(
        session: TerminalSession,
        cmd: SequenceAtomicCommand
    ): Result<Unit> = when (cmd) {
        is SequenceAtomicCommand.SpecialKey -> session.sendSpecialKey(cmd.wireKey)
        is SequenceAtomicCommand.RawText -> {
            val firstRes = session.sendRawInput(cmd.text.toByteArray(Charsets.UTF_8))
            if (firstRes.isFailure || !cmd.pressEnterAfter) {
                firstRes
            } else {
                delay(55)
                if (!session.isAttached.value || connectionState.value !is ConnectionState.Connected) {
                    Result.failure(IllegalStateException("Disconnected before Enter"))
                } else {
                    session.sendRawInput(byteArrayOf(13))
                }
            }
        }
    }

    private fun isTerminalMutationForOp(op: SequenceOperation, cmd: SequenceAtomicCommand): Boolean = when (op) {
        is SequenceOperation.SendSlashCommand -> cmd is SequenceAtomicCommand.RawText
        is SequenceOperation.ModalSelectItem -> cmd is SequenceAtomicCommand.SpecialKey && cmd.wireKey == "enter"
        is SequenceOperation.ModalStepItemValue ->
            cmd is SequenceAtomicCommand.SpecialKey && (cmd.wireKey == "left" || cmd.wireKey == "right")
        is SequenceOperation.ModalSwitchTab -> true
        is SequenceOperation.ModalFilterText -> true
        is SequenceOperation.ModalRawKey -> true
        is SequenceOperation.NavigateAskQuestion ->
            cmd is SequenceAtomicCommand.SpecialKey && cmd.wireKey == op.direction.wireKey
        else -> false
    }

    private suspend fun recoverAndReanchor(
        address: String,
        label: String,
        stepIndex: Int,
        totalSteps: Int,
        op: SequenceOperation,
        sentBeforeDrop: Boolean
    ): Result<TerminalSession> {
        _status.value = SequenceExecutionStatus(
            address = address,
            label = label,
            stepIndex = stepIndex,
            totalSteps = totalSteps,
            currentStepDescription = "网络闪断，等待恢复连接并重新锚定界面…",
            reconnecting = true
        )

        val deadlineNs = System.nanoTime() + reconnectTimeoutMs * 1_000_000L
        var recovered: TerminalSession? = null
        while (System.nanoTime() < deadlineNs) {
            val candidate = sessionProvider(address)
            if (connectionState.value is ConnectionState.Connected &&
                candidate != null &&
                candidate.isAttached.value
            ) {
                recovered = candidate
                break
            }
            delay(150)
        }

        val session = recovered ?: return Result.failure(
            IllegalStateException("连接恢复超时，已安全终止序列（请确认当前画面后再操作）")
        )

        if (session.mode.value != AttachMode.TAKEOVER) {
            runCatching { session.setMode(AttachMode.TAKEOVER) }
        }

        val freshScreenRes = session.refreshScreenNow(screenOnly = true)
        if (freshScreenRes.isFailure) {
            return Result.failure(IllegalStateException("重连后无法读取最新终端画面，已中止序列"))
        }
        val freshState: AgyTerminalState = AgyInteractiveParser.inspectRaw(freshScreenRes.getOrThrow())
        if (!AgySequencePlanner.canIdempotentlyResumeAfterReconnect(op, freshState, sentBeforeDrop)) {
            return Result.failure(
                IllegalStateException("网络恢复后终端画面已变化，已安全中止序列以防误操作")
            )
        }

        _status.value = SequenceExecutionStatus(
            address = address,
            label = label,
            stepIndex = stepIndex,
            totalSteps = totalSteps,
            currentStepDescription = op.description,
            reconnecting = false
        )
        return Result.success(session)
    }
}
