package dev.herdr.android.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.PublicKey
import java.security.Security
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import javax.net.SocketFactory

class SshjHcConnector(
    private val connectTimeoutMs: Int = 8_000,
    private val handshakeTimeoutMs: Int = 15_000,
    private val systemSocketFactory: SocketFactory = SocketFactory.getDefault(),
    private val overlayProvider: () -> ZeroTierOverlayTransport? = { ZeroTierOverlayRegistry.transport }
) : SshHcConnector {
    @Volatile private var probeSocket: Socket? = null
    @Volatile private var client: SSHClient? = null
    @Volatile private var probeJob: Job? = null
    private data class PreparedTransport(
        val profile: ConnectionProfile, val factory: SocketFactory, val attempt: Long,
        val onProgress: (String) -> Unit
    )
    @Volatile private var prepared: PreparedTransport? = null
    private val transportAttempt = AtomicLong()
    @Volatile private var pendingOverlay: ZeroTierOverlayTransport? = null

    override suspend fun prepareTransport(profile: ConnectionProfile, onProgress: (String) -> Unit) {
        val attempt = transportAttempt.incrementAndGet()
        prepared = null
        val factory = if (profile.useBuiltInZeroTier) {
            val overlay = overlayProvider() ?: throw java.io.IOException("应用内 ZeroTier 模块尚未初始化")
            pendingOverlay = overlay
            overlay.ensureReadyAndCreateSocketFactory(profile, onProgress)
        } else {
            pendingOverlay = null
            systemSocketFactory
        }
        if (transportAttempt.get() != attempt) throw CancellationException("SSH transport preparation cancelled")
        prepared = PreparedTransport(profile, factory, attempt, onProgress)
    }

    private suspend fun transport(profile: ConnectionProfile): PreparedTransport {
        prepared?.takeIf { it.profile == profile && it.attempt == transportAttempt.get() }?.let { return it }
        prepareTransport(profile)
        return prepared?.takeIf { it.profile == profile && it.attempt == transportAttempt.get() }
            ?: throw CancellationException("SSH transport preparation cancelled")
    }

    override suspend fun probe(profile: ConnectionProfile): Long = withContext(Dispatchers.IO) {
        val selected = transport(profile)
        val activeJob = currentCoroutineContext()[Job]
        probeJob = activeJob
        val endpoint = InetSocketAddress(profile.host.trim(), profile.port)
        fun checkAttempt() {
            if (transportAttempt.get() != selected.attempt) throw CancellationException("SSH TCP probe cancelled")
        }
        try {
            checkAttempt()
            if (profile.useBuiltInZeroTier) {
                ZeroTierPeerWarmup().probe(selected.factory, endpoint,
                    onSocket = { probeSocket = it },
                    onSocketClosed = { if (probeSocket === it) probeSocket = null },
                    checkAttempt = ::checkAttempt, onProgress = selected.onProgress)
            } else {
                val socket = selected.factory.createSocket()
                probeSocket = socket
                try {
                    currentCoroutineContext().ensureActive(); checkAttempt()
                    val start = System.nanoTime()
                    socket.connect(endpoint, connectTimeoutMs)
                    currentCoroutineContext().ensureActive(); checkAttempt()
                    (System.nanoTime() - start) / 1_000_000
                } finally { runCatching { socket.close() } }
            }
        } finally {
            probeSocket = null
            if (probeJob === activeJob) probeJob = null
        }
    }

    override suspend fun authenticate(profile: ConnectionProfile): AuthenticatedSshConnection = withContext(Dispatchers.IO) {
        // Android's built-in provider named BC is not the bundled modern BouncyCastle. Replace it
        // only inside this app process; never change OS settings or prefer an accept-all verifier.
        val selected = transport(profile)
        initializeCrypto()
        val ssh = SSHClient()
        client = ssh
        ssh.setSocketFactory(selected.factory)
        ssh.setConnectTimeout(connectTimeoutMs)
        ssh.setTimeout(handshakeTimeoutMs)
        var actualFingerprint = ""
        var verificationFailure: Exception? = null
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = try {
                actualFingerprint = SshHostKeyPin.fingerprint(Buffer.PlainBuffer().putPublicKey(key).compactData)
                SshHostKeyPin.verify(profile.expectedHostKeyFingerprint, actualFingerprint)
                true
            } catch (error: Exception) {
                verificationFailure = error
                false
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> = mutableListOf()
        })
        val passphrase = profile.privateKeyPassphrase?.toCharArray()
        try {
            currentCoroutineContext().ensureActive()
            if (transportAttempt.get() != selected.attempt) throw CancellationException("SSH transport preparation cancelled")
            ssh.connect(profile.host.trim(), profile.port)
            val finder: PasswordFinder? = passphrase?.let { PasswordUtils.createOneOff(it) }
            val key: KeyProvider = try {
                if (profile.privateKeyPem.isNotBlank()) {
                    ssh.loadKeys(profile.privateKeyPem.trim() + "\n", null, finder)
                } else {
                    val path = profile.privateKeyPathHint
                    if (path.isBlank() || !File(path).isFile || !File(path).canRead()) {
                        throw PrivateKeyUnavailableException("找不到手机本地私钥文件。请导入私钥文本，或填写手机上可读的私钥路径；电脑的 D:/ 路径不能直接在 Android 使用。")
                    }
                    ssh.loadKeys(path, finder)
                }
            } catch (error: PrivateKeyUnavailableException) {
                throw error
            } catch (error: Exception) {
                throw PrivateKeyUnavailableException("无法读取 SSH 私钥，请检查 OpenSSH 格式及私钥口令。", error)
            }
            ssh.authPublickey(profile.username, key)
            ssh.setTimeout(0)
            // SSHJ's setTimeout changes the setting used on the next connect, not this socket.
            ssh.socket.soTimeout = 0
            ssh.getConnection().getKeepAlive().setKeepAliveInterval(15)
            object : AuthenticatedSshConnection {
                override val hostKeyFingerprint: String = actualFingerprint
                override suspend fun exec(command: String): SshHcExecChannel = withContext(Dispatchers.IO) {
                    val session = ssh.startSession()
                    try {
                        // Deliberately no allocatePTY()/startShell(): keep stdin/stdout binary.
                        val exec = session.exec(command)
                        object : SshHcExecChannel {
                            override val input = exec.inputStream
                            override val output = exec.outputStream
                            override val stderr = exec.errorStream
                            override fun close() {
                                runCatching { exec.close() }
                                runCatching { session.close() }
                            }
                        }
                    } catch (error: Exception) {
                        runCatching { session.close() }
                        throw error
                    }
                }

                override fun close() {
                    if (client === ssh) client = null
                    runCatching { ssh.close() }
                }
            }
        } catch (error: Exception) {
            runCatching { ssh.close() }
            if (client === ssh) client = null
            throw (verificationFailure ?: error)
        } finally {
            passphrase?.fill('\u0000')
        }
    }

    override fun cancelPending() {
        transportAttempt.incrementAndGet()
        probeJob?.cancel(CancellationException("SSH TCP probe cancelled"))
        pendingOverlay?.cancelPending()
        pendingOverlay = null
        prepared = null
        runCatching { probeSocket?.close() }
        probeSocket = null
        runCatching { client?.close() }
        client = null
    }

    companion object {
        @Synchronized private fun initializeCrypto() {
            if (Security.getProvider("BC") !is BouncyCastleProvider) {
                Security.removeProvider("BC")
                Security.addProvider(BouncyCastleProvider())
            }
            if (Security.getProvider("EdDSA") == null) Security.addProvider(EdDSASecurityProvider())
            SecurityUtils.setSecurityProvider("BC")
        }
    }
}
