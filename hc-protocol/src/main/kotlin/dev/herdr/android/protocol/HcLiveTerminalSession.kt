package dev.herdr.android.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

internal class HcLiveTerminalSession(
    private val client: HcFramedClient,
    initialStream: HcIncomingStream,
    summary: TerminalSummary,
    initialMode: AttachMode,
    parentScope: CoroutineScope
) : TerminalSession {
    override val address: String = summary.address
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val operation = Mutex()
    private val snapshotOperation = Mutex()
    private val gridRevision = AtomicLong()
    private var historyPrefix: List<String> = emptyList() // protected by snapshotOperation
    private var stream = initialStream
    private var pump: Job? = null
    private var streamEpoch = 0L
    private val _mode = MutableStateFlow(initialMode)
    override val mode: StateFlow<AttachMode> = _mode.asStateFlow()
    private val _outputFrames = MutableSharedFlow<ByteArray>(
        replay = 64,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val outputFrames: Flow<ByteArray> = _outputFrames.asSharedFlow()
    private val _screenSnapshot = MutableStateFlow("")
    override val screenSnapshot: StateFlow<String> = _screenSnapshot.asStateFlow()
    private val _dimensions = MutableStateFlow((initialStream.terminal ?: summary).let { it.cols to it.rows })
    override val dimensions: StateFlow<Pair<Int, Int>> = _dimensions.asStateFlow()
    private val _isAttached = MutableStateFlow(true)
    override val isAttached: StateFlow<Boolean> = _isAttached.asStateFlow()

    init {
        startPump(initialStream)
        scope.launch {
            while (isActive && _isAttached.value) {
                try {
                    // Authoritative text for a simple Compose preview, not concatenated ANSI paint.
                    // Full terminal renderers consume outputFrames instead.
                    refreshSnapshot()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (!client.isConnected.value) break
                }
                delay(1000)
            }
        }
    }

    private fun startPump(current: HcIncomingStream) {
        val epoch = ++streamEpoch
        pump = scope.launch {
            try {
                while (isActive) {
                    val frame = current.receive() ?: break
                    if (epoch != streamEpoch) return@launch
                    when (frame.kind) {
                        HcFrameKind.DATA -> _outputFrames.emit(frame.payload.copyOf())
                        HcFrameKind.RESIZE -> {
                            gridRevision.incrementAndGet()
                            _dimensions.value = HcFrameCodec.dimensions(frame)
                        }
                        HcFrameKind.CLOSE -> break
                        // CREDIT acknowledges viewer->core data on transports that send it.
                        // HC 1 currently flow-controls core->viewer output; no input credit wait.
                        HcFrameKind.CREDIT -> Unit
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Controller reports connection failures. A lost terminal stream only detaches it.
            } finally {
                if (epoch == streamEpoch) _isAttached.value = false
            }
        }
    }

    private suspend fun refreshSnapshot() = snapshotOperation.withLock {
        val revision = gridRevision.get()
        // Check the current status on every refresh, including Observe and post-resize reads.
        // History reads may actively scroll a full-screen app, so a blocked dialog gets lines=0.
        val terminal = client.getTerminal(address)
        val lines = if (terminal.agentStatus == AgentStateWord.BLOCKED) 0 else HISTORY_LINES
        val read = client.readTerminalSnapshot(address, lines)
        if (gridRevision.get() != revision || !_isAttached.value) return@withLock
        _dimensions.value = terminal.cols to terminal.rows
        if (read.screenOnly) {
            _screenSnapshot.value = if (historyPrefix.isEmpty()) read.text
                else historyPrefix.joinToString("\n") + "\n" + read.text
        } else {
            // Cache history separately from the visible rows. Replacing the screen while
            // blocked must not retain an old screen or repeatedly append duplicate history.
            val rows = if (read.text.isEmpty()) emptyList() else read.text.trimEnd('\n').split('\n')
            historyPrefix = rows.dropLast(minOf(terminal.rows, rows.size)).takeLast(HISTORY_LINES)
            _screenSnapshot.value = read.text
        }
    }

    override suspend fun setMode(newMode: AttachMode) = operation.withLock {
        check(_isAttached.value) { "Terminal is detached" }
        if (_mode.value == newMode) return@withLock
        // Observe is fixed at attach time on the server. Merely changing a UI boolean is unsafe.
        // Preserve the remote grid until the user explicitly requests a takeover resize.
        val current = client.getTerminal(address)
        val replacement = client.attach(address, newMode, current.cols, current.rows)
        val previous = stream
        ++streamEpoch
        pump?.cancel()
        stream = replacement
        _mode.value = newMode
        gridRevision.incrementAndGet()
        _dimensions.value = current.cols to current.rows
        startPump(replacement)
        previous.detach()
    }

    override suspend fun sendRawInput(bytes: ByteArray): Result<Unit> = inputOperation {
        stream.sendRaw(bytes)
    }

    override suspend fun sendLine(text: String): Result<Unit> = inputOperation {
        if (client.getTerminal(address).agentStatus == AgentStateWord.BLOCKED) {
            // This is a human answering the visible TUI's input field, not a new agent prompt.
            // terminal.send wraps a paste and uses the agent template's submit key; some question
            // widgets ignore paste events. Stream DATA bypasses that machinery and the RPC quiet
            // gate. Keep text and Enter separate so the widget processes text before submission.
            stream.sendRaw(text.toByteArray(Charsets.UTF_8))
            delay(200)
            stream.sendRaw(byteArrayOf(13))
        } else {
            // Never force an unseen/new dialog or retry after an ambiguous input result.
            client.sendTerminal(address, text, force = false)
        }
    }

    override suspend fun sendSpecialKey(keyName: String): Result<Unit> = inputOperation {
        client.pressKeys(address, listOf(keyName))
    }

    override suspend fun interrupt(): Result<Unit> = inputOperation { client.interrupt(address) }

    override suspend fun requestResize(cols: Int, rows: Int): Result<Unit> = inputOperation {
        stream.resize(cols, rows)
        gridRevision.incrementAndGet()
        _dimensions.value = cols to rows
        // Best-effort bounded synchronization: five individual RPC timeouts must not hold the
        // input mutex for a minute. Cancellation must also reach the caller during either read.
        withTimeoutOrNull(1200) {
            for (attempt in 1..5) {
                delay(140)
                val updated = readOrNull { client.getTerminal(address) }
                if (updated != null && updated.cols == cols && updated.rows == rows) {
                    _dimensions.value = updated.cols to updated.rows
                    break
                }
            }
        }
        withTimeoutOrNull(500) {
            readOrNull { refreshSnapshot() }
        }
    }

    private suspend fun <T> readOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun inputOperation(block: suspend () -> Unit): Result<Unit> = operation.withLock {
        if (!_isAttached.value) return@withLock Result.failure(IllegalStateException("Terminal is detached"))
        if (_mode.value != AttachMode.TAKEOVER) return@withLock Result.failure(
            IllegalStateException("Observe 模式不允许输入、中断或调整远端尺寸，请先切换到 Takeover。")
        )
        try {
            block()
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun detach() = operation.withLock {
        ++streamEpoch
        _isAttached.value = false
        pump?.cancel()
        try {
            // CLOSE is an attach-channel detach, never terminal.kill or terminal.interrupt.
            stream.detach()
        } finally {
            scope.cancel()
        }
    }

    private companion object { const val HISTORY_LINES = 260 }
}
