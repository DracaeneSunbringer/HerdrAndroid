package dev.herdr.android.zerotier

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/** Per-connection adapter; never installs a global SocketImplFactory or changes system routing. */
class ZtProxySocket internal constructor(
    private val api: ZtSocketApi = HerdrZeroTierNative,
    private val onClosed: (ZtProxySocket) -> Unit = {}
) : Socket() {
    private val handle = api.createSocket()
    private val closed = AtomicBoolean()
    private val connectLock = Any()
    private val writeLock = Any()
    @Volatile private var connected = false
    @Volatile private var inputShutdown = false
    @Volatile private var outputShutdown = false
    @Volatile private var remote: InetSocketAddress? = null
    @Volatile private var timeout = 0
    @Volatile private var noDelay = false
    @Volatile private var keepAlive = false

    override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
    override fun connect(endpoint: SocketAddress, timeout: Int) = synchronized(connectLock) {
        checkOpen()
        require(timeout >= 0) { "Negative connect timeout" }
        if (connected) throw SocketException("Socket is already connected")
        val address = endpoint as? InetSocketAddress ?: throw IllegalArgumentException("Expected InetSocketAddress")
        val ip = address.address?.hostAddress ?: throw SocketException("ZeroTier 目标必须是可解析的虚拟 IP 地址")
        api.connectSocket(handle, ip, address.port, timeout)
        checkOpen()
        remote = address
        connected = true
    }

    private val input = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            checkRange(bytes, offset, length)
            checkConnected()
            if (length == 0) return 0
            if (inputShutdown) return -1
            val count = api.readSocket(handle, bytes, offset, length)
            if (count == 0 || count < -1 || count > length) throw IOException("Invalid ZeroTier read result")
            return count
        }
        override fun close() = this@ZtProxySocket.close()
    }
    private val output = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) = synchronized(writeLock) {
            checkRange(bytes, offset, length)
            checkConnected()
            if (outputShutdown) throw SocketException("Socket output is shut down")
            var sent = 0
            while (sent < length) {
                checkOpen()
                val count = api.writeSocket(handle, bytes, offset + sent, length - sent)
                if (count <= 0 || count > length - sent) throw IOException("Invalid ZeroTier write result")
                sent += count
            }
        }
        override fun close() = this@ZtProxySocket.close()
    }

    override fun getInputStream(): InputStream { checkConnected(); return input }
    override fun getOutputStream(): OutputStream { checkConnected(); return output }
    override fun isConnected() = connected
    override fun isClosed() = closed.get()
    override fun isBound() = connected
    override fun isInputShutdown() = inputShutdown
    override fun isOutputShutdown() = outputShutdown
    override fun getInetAddress(): InetAddress? = remote?.address
    override fun getPort(): Int = remote?.port ?: 0
    override fun getRemoteSocketAddress(): SocketAddress? = remote
    override fun getLocalAddress(): InetAddress? = local()?.address
    override fun getLocalPort(): Int = local()?.port ?: -1
    override fun getLocalSocketAddress(): SocketAddress? = local()
    private fun local(): InetSocketAddress? {
        if (!connected || closed.get()) return null
        val endpoint = api.localEndpoint(handle)
        return InetSocketAddress(InetAddress.getByName(endpoint[0]), endpoint[1].toInt())
    }
    override fun setSoTimeout(timeout: Int) {
        require(timeout >= 0) { "Negative read timeout" }; checkOpen()
        api.setReadTimeout(handle, timeout); this.timeout = timeout
    }
    override fun getSoTimeout(): Int { checkOpen(); return timeout }
    override fun setTcpNoDelay(on: Boolean) { checkOpen(); api.setNoDelay(handle, on); noDelay = on }
    override fun getTcpNoDelay(): Boolean { checkOpen(); return noDelay }
    override fun setKeepAlive(on: Boolean) { checkOpen(); api.setKeepAlive(handle, on); keepAlive = on }
    override fun getKeepAlive(): Boolean { checkOpen(); return keepAlive }
    override fun shutdownInput() { checkConnected(); api.shutdownSocket(handle, 0); inputShutdown = true }
    override fun shutdownOutput() { checkConnected(); api.shutdownSocket(handle, 1); outputShutdown = true }
    override fun bind(bindpoint: SocketAddress?) { throw SocketException("ZeroTier socket uses an automatically assigned virtual local endpoint") }
    override fun sendUrgentData(data: Int) { throw SocketException("ZeroTier urgent data is unsupported") }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { api.closeSocket(handle) } finally { onClosed(this) }
        }
    }
    private fun checkOpen() { if (closed.get()) throw SocketException("Socket is closed") }
    private fun checkConnected() { checkOpen(); if (!connected) throw SocketException("Socket is not connected") }
    private fun checkRange(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
    }
}
