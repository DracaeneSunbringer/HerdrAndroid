package dev.herdr.android.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One native `hc connect` exec channel. Never put a PTY or a text Reader in front of it. */
class HcFramedClient(
    private val input: InputStream,
    private val output: OutputStream,
    parentScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val rpcTimeoutMs: Long = 15_000
) : Closeable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val writerLock = Any()
    private val nextId = AtomicLong(1)
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private data class Waiting(val answer: CompletableDeferred<JSONObject>, val install: ((JSONObject) -> Unit)?)
    private val waiting = ConcurrentHashMap<Long, Waiting>()
    private val streams = ConcurrentHashMap<Long, HcIncomingStream>()
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _failure = MutableStateFlow<Throwable?>(null)
    val failure: StateFlow<Throwable?> = _failure.asStateFlow()
    var helloInfo: HcHelloInfo? = null
        private set

    suspend fun start(): HcHelloInfo {
        check(started.compareAndSet(false, true)) { "HC client handshake already started" }
        val handshake = scope.async {
            val request = HcJson.request(nextId.getAndIncrement(), "hello", JSONObject()
                .put("generation", HC_PROTOCOL_GENERATION).put("framed", true))
            synchronized(writerLock) {
                output.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
            // Read ONLY through the LF: a binary frame may immediately follow the hello.
            val response = parseJson(readHelloLine(input))
            if (response.optLong("id", -1) != request.getLong("id")) throw HcProtocolException("HC hello response id mismatch")
            HcJson.hello(HcJson.result(response))
        }
        try {
            val hello = withTimeout(rpcTimeoutMs) { handshake.await() }
            helloInfo = hello
            _isConnected.value = true
            scope.launch { readLoop() }
            return hello
        } catch (error: Throwable) {
            terminate(error)
            throw error
        }
    }

    suspend fun request(method: String, params: JSONObject = JSONObject(), timeoutMs: Long = rpcTimeoutMs): JSONObject =
        requestInstalling(method, params, timeoutMs, null)

    private suspend fun requestInstalling(
        method: String, params: JSONObject, timeoutMs: Long, install: ((JSONObject) -> Unit)?
    ): JSONObject {
        ensureConnected()
        val id = nextId.getAndIncrement()
        val answer = CompletableDeferred<JSONObject>()
        waiting[id] = Waiting(answer, install)
        try {
            writeFrame(HcFrame(0, HcFrameKind.DATA, HcJson.request(id, method, params).toString().toByteArray(Charsets.UTF_8)))
            return withTimeoutOrNull(timeoutMs) { answer.await() }
                ?: throw HcRpcException("timeout", "$method timed out", "The result may be unknown; do not blindly resend input.")
        } finally {
            waiting.remove(id)
        }
    }

    suspend fun serverStatus(): HcServerStatus = HcJson.status(request("server.status"))
    suspend fun listTerminals(): List<TerminalSummary> = HcJson.terminals(request("terminal.list"))
    suspend fun getTerminal(address: String): TerminalSummary = HcJson.terminal(request("terminal.get", terminalParams(address)))
    suspend fun listMachines(): List<MachineSummary> = HcJson.machines(request("machine.list"))
    suspend fun readTerminal(address: String, lines: Int = 260): String = readTerminalSnapshot(address, lines).text

    internal suspend fun readTerminalSnapshot(address: String, lines: Int): HcTerminalRead {
        require(lines >= 0) { "History line count must not be negative" }
        val result = request("terminal.read", terminalParams(address).put("lines", lines))
        // A full-screen program can refuse active stitching and return only its screen,
        // even when history was requested. Keep that distinction for the session cache.
        return HcTerminalRead(result.optString("text"), lines == 0 || result.optString("how") == "screen")
    }

    suspend fun sendTerminal(address: String, text: String, force: Boolean = false) {
        request("terminal.send", terminalParams(address).put("text", text).put("force", force).put("wait", false), 65_000)
    }

    suspend fun pressKeys(address: String, keys: List<String>) {
        require(keys.isNotEmpty() && keys.all { it.isNotBlank() }) { "Key names must not be empty" }
        request("terminal.keys", terminalParams(address).put("keys", JSONArray(keys)), 65_000)
    }

    suspend fun interrupt(address: String) {
        request("terminal.interrupt", terminalParams(address).put("timeout", 3), 15_000)
    }

    suspend fun attach(address: String, mode: AttachMode, cols: Int, rows: Int): HcIncomingStream {
        require(cols in 1..65535 && rows in 1..65535)
        var installed: HcIncomingStream? = null
        try {
            requestInstalling("terminal.attach", terminalParams(address)
                .put("observe", mode == AttachMode.OBSERVE)
                .put("resize_notifications", mode == AttachMode.OBSERVE)
                .put("cols", cols).put("rows", rows).put("history", 2000), rpcTimeoutMs) { result ->
                val channel = result.optLong("channel", -1)
                if (channel !in 1..HcFrameCodec.MAX_CHANNEL) throw HcProtocolException("HC attach returned an invalid stream channel")
                val summary = result.optJSONObject("terminal")?.let(HcJson::terminal)
                val stream = HcIncomingStream(this, channel, summary, result.optBoolean("resize_notifications"))
                if (streams.putIfAbsent(channel, stream) != null) throw HcProtocolException("HC reused an open stream channel")
                installed = stream
                // Runs on the reader BEFORE completing the RPC. The very next frame may be DATA.
            }
            return installed ?: throw HcProtocolException("HC attach did not install its stream")
        } catch (error: Throwable) {
            installed?.let { runCatching { it.detach() } }
            throw error
        }
    }

    suspend fun openTerminalSession(address: String, mode: AttachMode = AttachMode.OBSERVE): TerminalSession {
        val terminal = getTerminal(address)
        val stream = attach(address, mode, terminal.cols, terminal.rows)
        return HcLiveTerminalSession(this, stream, terminal, mode, scope)
    }

    internal suspend fun writeFrame(frame: HcFrame) = withContext(Dispatchers.IO) {
        ensureConnected()
        try {
            synchronized(writerLock) {
                HcFrameCodec.write(output, frame)
                output.flush()
            }
        } catch (error: IOException) {
            terminate(error)
            throw error
        }
    }

    internal suspend fun detach(channel: Long) {
        val stream = streams.remove(channel) ?: return
        stream.finish(null)
        if (_isConnected.value) writeFrame(HcFrame(channel, HcFrameKind.CLOSE))
    }

    private suspend fun readLoop() {
        try {
            while (!closed.get()) {
                val frame = HcFrameCodec.read(input) ?: throw EOFException("HC connection ended")
                if (frame.channel == 0L) {
                    when (frame.kind) {
                        HcFrameKind.DATA -> acceptResponse(parseJson(frame.payload.toString(Charsets.UTF_8)))
                        HcFrameKind.CLOSE -> throw IOException("HC closed the control channel")
                    }
                } else {
                    val stream = streams[frame.channel] ?: continue // late frames after our detach
                    if (!stream.offer(frame)) {
                        streams.remove(frame.channel, stream)
                        stream.finish(HcProtocolException("HC stream exceeded its bounded receive queue"))
                        writeFrame(HcFrame(frame.channel, HcFrameKind.CLOSE))
                    } else if (frame.kind == HcFrameKind.CLOSE) {
                        streams.remove(frame.channel, stream)
                        stream.finish(null)
                    }
                }
            }
        } catch (error: Throwable) {
            if (!closed.get()) terminate(error)
        }
    }

    private fun acceptResponse(message: JSONObject) {
        if (!message.has("id") || message.isNull("id")) return
        val id = message.optLong("id", -1)
        val pending = waiting.remove(id) ?: return // timed-out response, never resend
        try {
            val result = HcJson.result(message)
            pending.install?.invoke(result)
            pending.answer.complete(result)
        } catch (error: Throwable) {
            pending.answer.completeExceptionally(error)
        }
    }

    private fun ensureConnected() {
        if (closed.get() || !_isConnected.value) throw IOException("HC connection is not open", _failure.value)
    }

    override fun close() = terminate(IOException("HC connection closed"))

    private fun terminate(error: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        _failure.value = error
        _isConnected.value = false
        // Closing blocking streams unblocks the handshake/reader even when interrupted IO does not.
        runCatching { input.close() }
        runCatching { output.close() }
        waiting.values.forEach { it.answer.completeExceptionally(error) }
        waiting.clear()
        streams.values.forEach { it.finish(error) }
        streams.clear()
        scope.cancel(CancellationException("HC connection closed", error))
    }

    companion object {
        private const val MAX_HELLO_BYTES = 1024 * 1024

        internal fun readHelloLine(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() <= MAX_HELLO_BYTES) {
                val next = input.read()
                if (next < 0) throw EOFException("hc connect exited before its hello; inspect SSH stderr")
                if (next == 10) return bytes.toString(Charsets.UTF_8.name()).trimEnd('\r')
                bytes.write(next)
            }
            throw HcProtocolException("HC hello exceeds $MAX_HELLO_BYTES bytes; check stdout banner pollution")
        }

        private fun parseJson(text: String): JSONObject = try {
            JSONObject(text)
        } catch (error: Exception) {
            throw HcProtocolException("HC stdout is not valid JSON; use a non-PTY exec channel without banners", error)
        }

        private fun terminalParams(address: String): JSONObject {
            require(address.isNotBlank()) { "Terminal address must not be empty" }
            return JSONObject().put("terminal", address)
        }
    }
}

internal data class HcTerminalRead(val text: String, val screenOnly: Boolean)

/** A bounded, single-consumer channel. Credits go back when DATA is consumed, not on socket receipt. */
class HcIncomingStream internal constructor(
    private val client: HcFramedClient,
    val channel: Long,
    val terminal: TerminalSummary?,
    val resizeNotifications: Boolean
) {
    private val queue = Channel<HcFrame>(Channel.UNLIMITED)
    private val queuedBytes = AtomicLong()
    private val queuedPackets = AtomicInteger()

    internal fun offer(frame: HcFrame): Boolean {
        val bytes = queuedBytes.addAndGet(frame.payload.size.toLong())
        val packets = queuedPackets.incrementAndGet()
        if (bytes > 1024 * 1024 || packets > 16_384) {
            queuedBytes.addAndGet(-frame.payload.size.toLong())
            queuedPackets.decrementAndGet()
            return false
        }
        if (queue.trySend(frame).isSuccess) return true
        queuedBytes.addAndGet(-frame.payload.size.toLong())
        queuedPackets.decrementAndGet()
        return false
    }

    suspend fun receive(): HcFrame? {
        val item = queue.receiveCatching()
        item.exceptionOrNull()?.let { throw it }
        val frame = item.getOrNull() ?: return null
        queuedBytes.addAndGet(-frame.payload.size.toLong())
        queuedPackets.decrementAndGet()
        if (frame.kind == HcFrameKind.DATA && frame.payload.isNotEmpty()) {
            client.writeFrame(HcFrameCodec.credit(channel, frame.payload.size))
        }
        return frame
    }

    suspend fun sendRaw(bytes: ByteArray) {
        var at = 0
        while (at < bytes.size) {
            val end = minOf(bytes.size, at + HcFrameCodec.INPUT_CHUNK_SIZE)
            client.writeFrame(HcFrame(channel, HcFrameKind.DATA, bytes.copyOfRange(at, end)))
            at = end
        }
    }

    suspend fun resize(cols: Int, rows: Int) = client.writeFrame(HcFrameCodec.resize(channel, cols, rows))
    suspend fun detach() = client.detach(channel)
    internal fun finish(error: Throwable?) { queue.close(error) }
}
