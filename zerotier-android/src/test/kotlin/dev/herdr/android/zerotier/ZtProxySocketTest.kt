package dev.herdr.android.zerotier

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ZtProxySocketTest {
    @Test fun sshLogicalEndpointAndTimeoutArePreserved() {
        val api = FakeApi()
        ZtProxySocket(api).use { socket ->
            socket.connect(InetSocketAddress("10.147.17.100", 22), 8123)
            assertEquals(8123, api.connectTimeout)
            assertEquals("10.147.17.100", socket.inetAddress?.hostAddress)
            assertEquals(22, socket.port)
            assertEquals(45000, socket.localPort)
            assertEquals("10.147.17.99", socket.localAddress?.hostAddress)
            socket.soTimeout = 1501
            assertEquals(1501, api.timeout)
            assertEquals(1501, socket.soTimeout)
            socket.soTimeout = 0
            assertEquals(0, api.timeout)
            assertTrue(socket.isConnected)
        }
    }

    @Test fun outputLoopsOverShortWritesWithoutLosingUnicodeOrBinaryBytes() {
        val api = FakeApi().apply { maxWrite = 2 }
        ZtProxySocket(api).use { socket ->
            socket.connect(InetSocketAddress("10.147.17.100", 22))
            val payload = byteArrayOf(0, -1) + "中文".toByteArray() + byteArrayOf(13)
            socket.getOutputStream().write(payload)
            assertArrayEquals(payload, api.written.toByteArray())
        }
    }

    @Test fun eofAndTimeoutHaveDifferentInputStreamResults() {
        val api = FakeApi()
        ZtProxySocket(api).use { socket ->
            socket.connect(InetSocketAddress("10.147.17.100", 22))
            assertEquals(-1, socket.getInputStream().read())
            api.readError = SocketTimeoutException("timeout")
            assertThrows(SocketTimeoutException::class.java) { socket.getInputStream().read(ByteArray(10)) }
            assertEquals(0, socket.getInputStream().read(ByteArray(0)))
        }
    }

    @Test fun closingSocketWakesBlockedReadAndOnlyClosesHandleOnce() {
        val api = FakeApi().apply { blockRead = true }
        var callbacks = 0
        val socket = ZtProxySocket(api) { callbacks++ }
        socket.connect(InetSocketAddress("10.147.17.100", 22))
        val failure = AtomicReference<Throwable>()
        val reader = thread(isDaemon = true) {
            try { socket.getInputStream().read() } catch (error: Throwable) { failure.set(error) }
        }
        assertTrue(api.entered.await(1, TimeUnit.SECONDS))
        socket.close(); socket.close()
        reader.join(1000)
        assertFalse(reader.isAlive)
        assertTrue(failure.get() is SocketException)
        assertEquals(1, api.closeCalls)
        assertEquals(1, callbacks)
        assertTrue(socket.isClosed)
        assertThrows(SocketException::class.java) { socket.getOutputStream() }
    }

    @Test fun invalidRangesAndZeroProgressWritesFailInsteadOfSpinning() {
        val api = FakeApi().apply { maxWrite = 0 }
        ZtProxySocket(api).use { socket ->
            socket.connect(InetSocketAddress("10.147.17.100", 22))
            assertThrows(IndexOutOfBoundsException::class.java) { socket.getOutputStream().write(ByteArray(1), 1, 1) }
            assertThrows(java.io.IOException::class.java) { socket.getOutputStream().write(byteArrayOf(1)) }
            assertThrows(IllegalArgumentException::class.java) { socket.soTimeout = -1 }
        }
    }

    private class FakeApi : ZtSocketApi {
        var connectTimeout = 0
        var timeout = 0
        var maxWrite = Int.MAX_VALUE
        var readError: Throwable? = null
        var blockRead = false
        var closeCalls = 0
        val written = ByteArrayOutputStream()
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        override fun createSocket() = 8L
        override fun connectSocket(handle: Long, ip: String, port: Int, timeoutMs: Int) { connectTimeout = timeoutMs }
        override fun readSocket(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int {
            entered.countDown()
            if (blockRead) { closed.await(); throw SocketException("closed") }
            readError?.let { throw it }
            return -1
        }
        override fun writeSocket(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int {
            val count = minOf(length, maxWrite)
            written.write(bytes, offset, count)
            return count
        }
        override fun closeSocket(handle: Long) { closeCalls++; closed.countDown() }
        override fun shutdownSocket(handle: Long, how: Int) {}
        override fun setReadTimeout(handle: Long, timeoutMs: Int) { timeout = timeoutMs }
        override fun setNoDelay(handle: Long, enabled: Boolean) {}
        override fun setKeepAlive(handle: Long, enabled: Boolean) {}
        override fun localEndpoint(handle: Long) = arrayOf("10.147.17.99", "45000")
    }
}
