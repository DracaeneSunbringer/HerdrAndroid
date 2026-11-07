package dev.herdr.android.connection

import dev.herdr.android.protocol.HcFrame
import dev.herdr.android.protocol.HcFrameCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Fake SSH exec speaking real HC bytes; intentionally never opens a real socket. */
internal class ConnectionProtocolExec : SshHcExecChannel {
    private val toClient = QueueInput()
    private val fromClient = QueueInput()
    override val input: InputStream = toClient
    override val output: OutputStream = fromClient.sink
    override val stderr: InputStream = "HERDR_HC_EXEC: C:/herdr-core.exe\n".byteInputStream()
    val closed = AtomicBoolean()
    val methods = CopyOnWriteArrayList<String>()
    @Volatile var serverError: Throwable? = null
    init {
        thread(name = "fake-ssh-hc", isDaemon = true) {
            try {
                val line = StringBuilder()
                while (true) { val b = fromClient.read(); if (b < 0) return@thread; if (b == 10) break; line.append(b.toChar()) }
                val hello = JSONObject(line.toString())
                val result = JSONObject("""{"generation":1,"version":"0.1.18","framed":true,"core":{"id":"desktop-id","name":"desktop"},"features":["framed","attach"]}""")
                toClient.sink.write((response(hello, result).toString() + "\n").toByteArray())
                while (true) {
                    val frame = HcFrameCodec.read(fromClient) ?: break
                    if (frame.channel != 0L) continue
                    val request = JSONObject(frame.payload.toString(Charsets.UTF_8))
                    val method = request.getString("method")
                    methods += method
                    val value = when (method) {
                        "server.status" -> JSONObject("""{"version":"0.1.18","live":1,"finished":2}""")
                        "terminal.list" -> JSONObject().put("terminals", JSONArray().put(terminal()))
                        "machine.list" -> JSONObject("""{"machines":[{"name":"linux-node","state":"connected"}]}""")
                        "terminal.get" -> terminal()
                        "terminal.attach" -> JSONObject().put("channel", 7).put("terminal", terminal()).put("resize_notifications", true)
                        "terminal.read" -> JSONObject().put("text", "desktop screen")
                        else -> JSONObject()
                    }
                    toClient.sink.write(HcFrameCodec.encode(HcFrame(0, 0, response(request, value).toString().toByteArray())))
                }
            } catch (error: Throwable) { if (!closed.get()) serverError = error }
        }
    }
    override fun close() { closed.set(true); toClient.close(); fromClient.close(); stderr.close() }
    fun remoteEof() = toClient.close()
    private fun terminal() = JSONObject("""{"address":"t1q","name":"parent","cols":120,"rows":40,"cwd":"C:/work","state":"live"}""")
    private fun response(request: JSONObject, result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", request.getLong("id")).put("result", result)
}

private class QueueInput : InputStream() {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean()
    private var current = byteArrayOf()
    private var index = 0
    val sink = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed.get())
            if (len > 0) chunks.put(b.copyOfRange(off, off + len))
        }
        override fun close() = this@QueueInput.close()
    }
    override fun read(): Int {
        while (index == current.size) {
            if (closed.get() && chunks.isEmpty()) return -1
            current = chunks.take(); index = 0
            if (current.isEmpty()) return -1
        }
        return current[index++].toInt() and 255
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val first = read()
        if (first < 0) return -1
        b[off] = first.toByte()
        val remaining = minOf(len - 1, current.size - index)
        current.copyInto(b, off + 1, index, index + remaining)
        index += remaining
        return remaining + 1
    }
    override fun close() { if (closed.compareAndSet(false, true)) chunks.offer(byteArrayOf()) }
}
