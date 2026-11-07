package dev.herdr.android.connection

import dev.herdr.android.protocol.AttachMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ConnectException

class SshHcConnectionControllerTest {
    @Test fun exhaustedPeerWarmupIsDiagnosedSeparatelyFromNodeAuthorization() {
        val failure = ConnectionDiagnostics.describe(java.net.SocketTimeoutException("ZeroTier 目标仍不可达，虚拟 IP 已分配"),
            ConnectionStage.NETWORK_REACHABILITY, "")
        assertEquals("zerotier_peer_timeout", failure.kind)
        assertTrue(failure.hint.contains("SSH"))
        assertFalse(failure.hint.contains("授权"))
        val refused = ConnectionDiagnostics.describe(ConnectException("ZeroTier connect failed (errno=111)"),
            ConnectionStage.NETWORK_REACHABILITY, "")
        assertEquals("ssh_port_refused", refused.kind)
    }
    @Test fun overlayPreparationFailureStaysInStageOneAndDoesNotProbeOrAuthenticate() = runBlocking {
        val fake = FakeConnector().apply { onPrepare = { throw java.io.IOException("ZeroTier Planet unavailable") } }
        withController(fake) { controller ->
            assertTrue(controller.connect(ConnectionProfile(useBuiltInZeroTier = true)).isFailure)
            val failure = controller.connectionState.value as ConnectionState.Failed
            assertEquals(ConnectionStage.NETWORK_REACHABILITY, failure.failedStage)
            assertEquals("zerotier_unavailable", failure.errorKind)
            assertEquals(0, fake.probeCalls)
            assertEquals(0, fake.authCalls)
        }
    }
    @Test fun transportProgressIsVisibleInStageOneBeforeTheTcpProbe() = runBlocking {
        val transport = ConnectionProtocolExec()
        val fake = FakeConnector().apply { execFactory = { transport } }
        withController(fake) { controller ->
            val details = mutableListOf<String?>()
            fake.onPrepare = { progress ->
                progress("Node 0123456789 · 等待授权")
                details += (controller.connectionState.value as ConnectionState.Connecting).stages[0].detail
                progress("虚拟 IPv4 10.147.17.99")
                details += (controller.connectionState.value as ConnectionState.Connecting).stages[0].detail
                assertEquals(0, fake.probeCalls)
            }
            controller.connect(ConnectionProfile(useBuiltInZeroTier = true)).getOrThrow()
            assertEquals(listOf("Node 0123456789 · 等待授权", "虚拟 IPv4 10.147.17.99"), details)
            assertEquals(1, fake.prepareCalls)
        }
    }
    @Test fun factoryCreatesRealBackendAndRealFailuresNeverTurnIntoDemo() = runBlocking {
        assertTrue(HerdrConnectionControllerFactory.create() is SshHcConnectionController)
        val fake = FakeConnector().apply { probeFailure = ConnectException("refused") }
        withController(fake) { controller ->
            assertTrue(controller.connect(ConnectionProfile()).isFailure)
            val state = controller.connectionState.value as ConnectionState.Failed
            assertEquals(ConnectionStage.NETWORK_REACHABILITY, state.failedStage)
            assertEquals("port_unreachable", state.errorKind)
            assertTrue(controller.terminals.value.isEmpty())
            assertEquals(0, fake.authCalls)
        }
    }

    @Test fun untrustedHostReportsFingerprintAtStageTwoAndNeverExecutes() = runBlocking {
        val fingerprint = "SHA256:bbXpuKG6zhzdmnxq256TlqzFBzRl2f6OOg722cYNbU8"
        val fake = FakeConnector().apply { authFailure = HostKeyNotTrustedException(fingerprint) }
        withController(fake) { controller ->
            assertTrue(controller.connect(ConnectionProfile()).isFailure)
            val state = controller.connectionState.value as ConnectionState.Failed
            assertEquals(ConnectionStage.SSH_HANDSHAKE_AND_AUTH, state.failedStage)
            assertEquals("host_key_untrusted", state.errorKind)
            assertTrue(state.message.contains(fingerprint))
            assertEquals(StageCheckState.PASSED, state.stages[0].state)
            assertEquals(StageCheckState.FAILED, state.stages[1].state)
            assertTrue(fake.commands.isEmpty())
        }
    }

    @Test fun remoteMissingCoreIsStageThreeFailureWithNoAutoInstallation() = runBlocking {
        val fake = FakeConnector().apply {
            execFactory = { failedExec("HERDR_HC_MISSING: executable was not found") }
        }
        withController(fake) { controller ->
            assertTrue(controller.connect(ConnectionProfile()).isFailure)
            val state = controller.connectionState.value as ConnectionState.Failed
            assertEquals(ConnectionStage.HC_DISCOVERY_AND_EXEC, state.failedStage)
            assertEquals("hc_missing", state.errorKind)
            assertEquals(1, fake.commands.size)
            assertTrue(fake.closed)
        }
    }

    @Test fun successfulConnectionSynchronizesInventoryAllowsSessionsAndClosesResourcesOnDisconnect() = runBlocking {
        val transport = ConnectionProtocolExec()
        val fake = FakeConnector().apply { execFactory = { transport } }
        withController(fake) { controller ->
            val result = controller.connect(ConnectionProfile())
            assertTrue("${result.exceptionOrNull()} / ${transport.serverError}", result.isSuccess)
            val state = controller.connectionState.value as ConnectionState.Connected
            assertEquals("desktop-id", state.hello.coreId)
            assertTrue(state.stages.all { it.state == StageCheckState.PASSED })
            assertEquals("t1q", controller.terminals.value.single().address)
            assertEquals("linux-node", controller.machines.value.single().name)
            assertEquals(1, controller.refreshTerminals().getOrThrow().size)
            val session = controller.openTerminalSession("t1q", AttachMode.OBSERVE).getOrThrow()
            assertEquals("desktop screen", withTimeout(1500) { session.screenSnapshot.first { it.isNotBlank() } })
            assertTrue(session.sendLine("observe must refuse").isFailure)
            session.detach()
            assertTrue(controller.sendMessageToTerminal("t1q", "explicit message").isSuccess)
            controller.disconnect()
            assertSame(ConnectionState.Disconnected, controller.connectionState.value)
            assertTrue(controller.terminals.value.isEmpty())
            assertTrue(transport.closed.get())
            assertTrue(fake.closed)
            assertFalse(transport.methods.contains("terminal.kill"))
        }
    }

    @Test fun unexpectedRemoteEofIsVisibleAndDoesNotReconnectOrReplayInput() = runBlocking {
        val transport = ConnectionProtocolExec()
        val fake = FakeConnector().apply { execFactory = { transport } }
        withController(fake) { controller ->
            controller.connect(ConnectionProfile()).getOrThrow()
            transport.remoteEof()
            val failed = withTimeout(1500) { controller.connectionState.first { it is ConnectionState.Failed } } as ConnectionState.Failed
            assertEquals(ConnectionStage.PROTOCOL_HELLO_AND_SYNC, failed.failedStage)
            assertTrue(controller.terminals.value.isEmpty())
            assertEquals(1, fake.commands.size)
        }
    }

    @Test fun autoTriesUnixOnlyAfterDefiniteMissingPowerShell() = runBlocking {
        val transport = ConnectionProtocolExec()
        val fake = FakeConnector().apply {
            execFactory = { index -> if (index == 1) failedExec("sh: powershell.exe: not found") else transport }
        }
        withController(fake) { controller ->
            controller.connect(ConnectionProfile(platform = HostPlatform.AUTO)).getOrThrow()
            assertEquals(2, fake.commands.size)
            assertTrue(fake.commands[0].startsWith("powershell.exe"))
            assertTrue(fake.commands[1].startsWith("sh -c"))
        }
    }

    @Test fun explicitDemoDoesNotTouchNetworkOrSsh() = runBlocking {
        val fake = FakeConnector().apply { probeFailure = AssertionError("demo must not touch connector") }
        withController(fake) { controller ->
            controller.connect(ConnectionProfile(host = "demo://preview")).getOrThrow()
            withTimeout(1500) { controller.connectionState.first { it is ConnectionState.Connected } }
            assertTrue(controller.terminals.value.isNotEmpty())
            assertEquals(0, fake.probeCalls)
            assertEquals(0, fake.authCalls)
        }
    }

    @Test fun windowsPermissionDiagnosticsExplainAccountAndIntegrityBoundaries() {
        val problem = ConnectionDiagnostics.describe(java.io.EOFException("ended"),
            ConnectionStage.PROTOCOL_HELLO_AND_SYNC, "access is denied: elevated Windows core")
        assertEquals(ConnectionStage.HC_DISCOVERY_AND_EXEC, problem.stage)
        assertEquals("windows_session_permission", problem.kind)
        assertTrue(problem.hint.contains("普通权限"))
    }

    private suspend fun withController(fake: FakeConnector, block: suspend (SshHcConnectionController) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val controller = SshHcConnectionController(fake, scope, pollIntervalMs = 60_000, rpcTimeoutMs = 1500)
        try { block(controller) } finally { controller.disconnect(); scope.cancel() }
    }

    private fun failedExec(message: String) = object : SshHcExecChannel {
        override val input = ByteArrayInputStream(byteArrayOf())
        override val output = ByteArrayOutputStream()
        override val stderr = message.byteInputStream()
        override fun close() { input.close(); output.close(); stderr.close() }
    }

    private class FakeConnector : SshHcConnector {
        var prepareCalls = 0
        var onPrepare: ((String) -> Unit) -> Unit = {}
        var probeCalls = 0
        var authCalls = 0
        var probeFailure: Throwable? = null
        var authFailure: Throwable? = null
        var closed = false
        val commands = mutableListOf<String>()
        var execFactory: (Int) -> SshHcExecChannel = { throw AssertionError("unexpected exec") }
        override suspend fun prepareTransport(profile: ConnectionProfile, onProgress: (String) -> Unit) {
            prepareCalls++; onPrepare(onProgress)
        }
        override suspend fun probe(profile: ConnectionProfile): Long {
            probeCalls++
            probeFailure?.let { throw it }
            return 12
        }
        override suspend fun authenticate(profile: ConnectionProfile): AuthenticatedSshConnection {
            authCalls++
            authFailure?.let { throw it }
            return object : AuthenticatedSshConnection {
                override val hostKeyFingerprint = "SHA256:bbXpuKG6zhzdmnxq256TlqzFBzRl2f6OOg722cYNbU8"
                override suspend fun exec(command: String): SshHcExecChannel {
                    commands += command
                    return execFactory(commands.size)
                }
                override fun close() { closed = true }
            }
        }
        override fun cancelPending() = Unit
    }
}
