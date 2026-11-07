package dev.herdr.android.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** In-memory duplex transport, not a preloaded file which would falsely EOF after hello. */
internal class ProtocolPeer : Closeable {
    private val fromClient = FeedInput()
    private val toClient = FeedInput()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val client = HcFramedClient(toClient, fromClient.sink, scope, 1500)
    val requests = LinkedBlockingQueue<JSONObject>()
    val frames = CopyOnWriteArrayList<HcFrame>()
    @Volatile var handler: ((JSONObject) -> Unit)? = null
    @Volatile var serverError: Throwable? = null
    private val writeLock = Any()

    init {
        thread(name = "fake-hc", isDaemon = true) {
            try {
                val hello = JSONObject(HcFramedClient.readHelloLine(fromClient))
                check(hello.getString("method") == "hello")
                check(hello.getJSONObject("params").getBoolean("framed"))
                check(hello.getJSONObject("params").getInt("generation") == 1)
                val result = JSONObject("""{"generation":1,"version":"0.1.18","framed":true,"core":{"id":"core-id","name":"Windows"},"features":["framed","attach"]}""")
                toClient.sink.write((response(hello, result).toString() + "\n").toByteArray())
                while (true) {
                    val frame = HcFrameCodec.read(fromClient) ?: break
                    frames += frame
                    if (frame.channel == 0L && frame.kind == HcFrameKind.DATA) {
                        val request = JSONObject(frame.payload.toString(Charsets.UTF_8))
                        requests.offer(request)
                        handler?.invoke(request)
                    }
                }
            } catch (error: Throwable) { if (!fromClient.closed.get()) serverError = error }
        }
    }

    fun nextRequest(): JSONObject = requests.poll(3, TimeUnit.SECONDS)
        ?: throw AssertionError("No HC request; fake server failure: $serverError")

    fun reply(request: JSONObject, result: JSONObject = JSONObject(), vararg following: HcFrame) {
        val bytes = HcFrameCodec.encode(HcFrame(0, 0, response(request, result).toString().toByteArray())) +
            following.fold(byteArrayOf()) { all, frame -> all + HcFrameCodec.encode(frame) }
        synchronized(writeLock) { toClient.sink.write(bytes) }
    }

    fun error(request: JSONObject, kind: String) {
        val error = JSONObject().put("code", -32000).put("message", "refused").put("data", JSONObject().put("kind", kind).put("hint", "do not force"))
        send(HcFrame(0, 0, JSONObject().put("jsonrpc", "2.0").put("id", request.getLong("id")).put("error", error).toString().toByteArray()))
    }

    fun send(frame: HcFrame) { synchronized(writeLock) { toClient.sink.write(HcFrameCodec.encode(frame)) } }
    fun eof() = toClient.close()
    override fun close() { client.close(); fromClient.close(); toClient.close(); scope.cancel() }

    companion object {
        fun terminal() = JSONObject("""{"address":"t7","cols":132,"rows":44,"cwd":"C:/work","state":"live"}""")
        private fun response(request: JSONObject, result: JSONObject) = JSONObject()
            .put("jsonrpc", "2.0").put("id", request.getLong("id")).put("result", result)
    }
}

private class FeedInput : InputStream() {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    val closed = AtomicBoolean()
    private var chunk = byteArrayOf()
    private var at = 0
    val sink = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()))
        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed.get()) { "pipe closed" }
            if (len > 0) chunks.put(b.copyOfRange(off, off + len))
        }
        override fun close() = this@FeedInput.close()
    }
    override fun read(): Int {
        while (at == chunk.size) {
            if (closed.get() && chunks.isEmpty()) return -1
            chunk = chunks.take(); at = 0
            if (chunk.isEmpty()) return -1
        }
        return chunk[at++].toInt() and 255
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val first = read()
        if (first < 0) return -1
        b[off] = first.toByte()
        val count = minOf(len - 1, chunk.size - at)
        chunk.copyInto(b, off + 1, at, at + count)
        at += count
        return count + 1
    }
    override fun close() { if (closed.compareAndSet(false, true)) chunks.offer(byteArrayOf()) }
}
