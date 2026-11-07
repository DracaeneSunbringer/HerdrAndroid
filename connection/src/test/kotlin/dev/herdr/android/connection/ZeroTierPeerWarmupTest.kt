package dev.herdr.android.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.SocketFactory

class ZeroTierPeerWarmupTest {
    private val endpoint = InetSocketAddress("10.147.17.100", 22)

    @Test fun coldPeerThatAppearsAfterEightSecondsSucceedsWithoutAnotherUserTap() = runBlocking {
        var now = 0L
        val factory = Factory { _, timeout ->
            if (now + timeout < 8500) { now += timeout; throw SocketTimeoutException("ARP/path pending") }
            now = 8525 // peer becomes reachable after the first two short attempts
        }
        val warmup = ZeroTierPeerWarmup(nowMs = { now }, pause = { now += it })
        val progress = mutableListOf<String>()
        val rtt = warmup.probe(factory, endpoint, {}, {}, {}, { progress += it })
        assertEquals(listOf(2000, 3000, 5000), factory.created.map { it.timeout })
        assertEquals(2775L, rtt)
        assertEquals(8525L, now)
        assertTrue(factory.created.all { it.closed })
        assertTrue(factory.created.all { it.endpoint == endpoint })
        assertTrue(progress.last().contains("TCP 已连通"))
    }

    @Test fun everyTimedOutAttemptGetsANewSocketAndTotalBudgetIsNeverExtended() = runBlocking {
        var now = 0L
        val factory = Factory { _, timeout -> now += timeout; throw SocketTimeoutException("pending") }
        val warmup = ZeroTierPeerWarmup(nowMs = { now }, pause = { now += it })
        val error = runCatching { warmup.probe(factory, endpoint, {}, {}, {}, {}) }.exceptionOrNull()
        assertTrue(error is SocketTimeoutException)
        assertTrue(error?.cause is SocketTimeoutException)
        assertEquals(40_000L, now)
        assertEquals(listOf(2000, 3000, 5000, 8000, 8000, 8000, 1500), factory.created.map { it.timeout })
        assertTrue(factory.created.all { it.closed })
        assertEquals(factory.created.size, factory.created.toSet().size)
    }

    @Test fun transientNoRouteCanRecoverDuringPeerDiscovery() = runBlocking {
        var now = 0L
        val factory = Factory { index, _ -> if (index < 3) throw NoRouteToHostException("peer not known") else now += 7 }
        val warmup = ZeroTierPeerWarmup(nowMs = { now }, pause = { now += it })
        assertEquals(7L, warmup.probe(factory, endpoint, {}, {}, {}, {}))
        assertEquals(3, factory.created.size)
        assertEquals(757L, now)
    }

    @Test fun refusedPortAndPermissionErrorsAreNotRetried() = runBlocking {
        listOf(ConnectException("refused"), SocketException("permission denied"), SocketException("node reconfigured")).forEach { refusal ->
            val factory = Factory { _, _ -> throw refusal }
            val warmup = ZeroTierPeerWarmup(pause = { fail("non-transient errors must not wait") })
            assertSame(refusal, runCatching { warmup.probe(factory, endpoint, {}, {}, {}, {}) }.exceptionOrNull())
            assertEquals(1, factory.created.size)
            assertTrue(factory.created.single().closed)
        }
    }

    @Test fun cancellationDuringBackoffClosesTheAttemptAndPreventsAnotherSyn() = runBlocking {
        val inBackoff = CompletableDeferred<Unit>()
        val factory = Factory { _, _ -> throw NoRouteToHostException("pending") }
        val warmup = ZeroTierPeerWarmup(pause = { inBackoff.complete(Unit); awaitCancellation() })
        val probe = async(Dispatchers.Default) { warmup.probe(factory, endpoint, {}, {}, {}, {}) }
        withTimeout(1000) { inBackoff.await() }
        probe.cancelAndJoin()
        assertEquals(1, factory.created.size)
        assertTrue(factory.created.single().closed)
    }

    @Test fun invalidatedTransportCannotOpenAnotherSocket() = runBlocking {
        var now = 0L
        var cancelled = false
        val factory = Factory { _, timeout -> now += timeout; throw SocketTimeoutException("pending") }
        val warmup = ZeroTierPeerWarmup(nowMs = { now }, pause = { now += it })
        val error = runCatching {
            warmup.probe(factory, endpoint, {}, { cancelled = true },
                { if (cancelled) throw CancellationException("cancelled") }, {})
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, factory.created.size)
        assertTrue(factory.created.single().closed)
    }

    private class Factory(private val action: (Int, Int) -> Unit) : SocketFactory() {
        val created = mutableListOf<AttemptSocket>()
        override fun createSocket(): Socket = AttemptSocket { timeout -> action(created.size, timeout) }.also { created += it }
        override fun createSocket(host: String, port: Int): Socket = error("Only fresh unconnected sockets are used")
        override fun createSocket(host: InetAddress, port: Int): Socket = error("Only fresh unconnected sockets are used")
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = error("unused")
        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = error("unused")
    }
    private class AttemptSocket(private val action: (Int) -> Unit) : Socket() {
        var endpoint: SocketAddress? = null
        var timeout = 0
        var closed = false
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            this.endpoint = endpoint; this.timeout = timeout; action(timeout)
        }
        override fun close() { closed = true }
    }
}
