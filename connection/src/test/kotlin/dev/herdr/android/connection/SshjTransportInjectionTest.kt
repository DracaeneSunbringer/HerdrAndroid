package dev.herdr.android.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import javax.net.SocketFactory

class SshjTransportInjectionTest {
    @Test fun connectorCancelInterruptsOverlayWarmupBackoff() = runBlocking {
        val factory = RecordingFactory { throw SocketTimeoutException("ZeroTier socket timed out") }
        val overlay = FakeOverlay(factory)
        val inRetry = CompletableDeferred<Unit>()
        val connector = SshjHcConnector(systemSocketFactory = RecordingFactory(), overlayProvider = { overlay })
        val profile = ConnectionProfile(useBuiltInZeroTier = true)
        connector.prepareTransport(profile) { if (it.contains("正在重试")) inRetry.complete(Unit) }
        val probe = async(Dispatchers.IO) { runCatching { connector.probe(profile) } }
        withTimeout(1000) { inRetry.await() }
        connector.cancelPending()
        assertTrue(withTimeout(1000) { probe.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, factory.sockets.size)
        assertTrue(factory.sockets.single().wasClosed)
    }

    @Test fun overlayProbeAndSshHandshakeUseTheSameFactoryAndLogicalTarget() = runBlocking {
        val overlayFactory = RecordingFactory()
        val systemFactory = RecordingFactory()
        val overlay = FakeOverlay(overlayFactory)
        val connector = SshjHcConnector(systemSocketFactory = systemFactory, overlayProvider = { overlay })
        val profile = ConnectionProfile(useBuiltInZeroTier = true)
        val progress = mutableListOf<String>()
        try {
            connector.prepareTransport(profile) { progress += it }
            connector.probe(profile)
            assertTrue(runCatching { connector.authenticate(profile) }.isFailure) // fake socket stops before SSH KEX
            assertEquals(1, overlay.prepareCalls)
            assertEquals(0, systemFactory.sockets.size)
            assertEquals(2, overlayFactory.sockets.size)
            assertTrue(overlayFactory.sockets.all { it.endpoint?.address?.hostAddress == profile.host && it.endpoint?.port == profile.port })
            assertTrue(overlayFactory.sockets.all { it.wasClosed })
            assertEquals(listOf("Node 0123456789 · 等待授权", "虚拟 IPv4 10.147.17.99"), progress.take(2))
            assertTrue(progress.last().contains("TCP 已连通"))
        } finally { connector.cancelPending() }
    }

    @Test fun disabledOverlayUsesOnlyTheSystemFactory() = runBlocking {
        val system = RecordingFactory()
        var overlayLookups = 0
        val connector = SshjHcConnector(systemSocketFactory = system, overlayProvider = { overlayLookups++; null })
        try {
            val profile = ConnectionProfile(useBuiltInZeroTier = false)
            connector.prepareTransport(profile)
            connector.probe(profile)
            assertEquals(1, system.sockets.size)
            assertEquals(0, overlayLookups)
        } finally { connector.cancelPending() }
    }

    @Test fun missingOverlayNeverFallsBackToSystemSocket() = runBlocking {
        val system = RecordingFactory()
        val connector = SshjHcConnector(systemSocketFactory = system, overlayProvider = { null })
        try {
            assertTrue(runCatching { connector.probe(ConnectionProfile(useBuiltInZeroTier = true)) }.isFailure)
            assertTrue(system.sockets.isEmpty())
        } finally { connector.cancelPending() }
    }

    @Test fun cancelledPreparationCannotInstallALateFactory() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val system = RecordingFactory()
        val overlay = FakeOverlay(RecordingFactory()).apply { beforeReady = { entered.complete(Unit); release.await() } }
        val connector = SshjHcConnector(systemSocketFactory = system, overlayProvider = { overlay })
        val preparation = async(Dispatchers.IO) { runCatching { connector.prepareTransport(ConnectionProfile(useBuiltInZeroTier = true)) } }
        entered.await()
        connector.cancelPending()
        release.complete(Unit)
        assertTrue(preparation.await().exceptionOrNull() is CancellationException)
        assertEquals(1, overlay.cancelCalls)
        assertTrue(system.sockets.isEmpty())
    }

    private class FakeOverlay(private val factory: SocketFactory) : ZeroTierOverlayTransport {
        override val state = MutableStateFlow(ZeroTierOverlayState())
        var prepareCalls = 0
        var cancelCalls = 0
        var beforeReady: suspend () -> Unit = {}
        override suspend fun ensureReadyAndCreateSocketFactory(profile: ConnectionProfile, onProgress: (String) -> Unit): SocketFactory {
            prepareCalls++
            onProgress("Node 0123456789 · 等待授权")
            beforeReady()
            onProgress("虚拟 IPv4 10.147.17.99")
            return factory
        }
        override suspend fun importIdentitySecret(identitySecretText: String) = Result.success("0123456789")
        override suspend fun exportIdentitySecret() = Result.success("test-format-only")
        override suspend fun importCustomPlanet(planetBytes: ByteArray, label: String) = Result.success(Unit)
        override suspend fun resetToBuiltInPlanet() = Result.success(Unit)
        override fun cancelPending() { cancelCalls++ }
    }

    private class RecordingFactory(private val onConnect: () -> Unit = {}) : SocketFactory() {
        val sockets = mutableListOf<RecordingSocket>()
        override fun createSocket(): Socket = RecordingSocket(onConnect).also { sockets += it }
        override fun createSocket(host: String, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int) = createSocket(host, port)
        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int) = createSocket(host, port)
    }

    private class RecordingSocket(private val onConnect: () -> Unit = {}) : Socket() {
        var endpoint: InetSocketAddress? = null
        var wasClosed = false
        override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
        override fun connect(endpoint: SocketAddress, timeout: Int) { this.endpoint = endpoint as InetSocketAddress; onConnect() }
        override fun isConnected() = endpoint != null
        override fun getPort() = endpoint?.port ?: 0
        override fun getInetAddress(): InetAddress? = endpoint?.address
        override fun setSoTimeout(timeout: Int) {}
        override fun getInputStream(): java.io.InputStream = throw IOException("fake transport: no SSH server")
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun close() { wasClosed = true }
    }
}
