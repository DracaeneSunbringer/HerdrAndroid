package dev.herdr.android.connection

import dev.herdr.android.protocol.AttachMode
import dev.herdr.android.protocol.HcFramedClient
import dev.herdr.android.protocol.HcProtocolException
import dev.herdr.android.protocol.HcRpcException
import dev.herdr.android.protocol.MachineSummary
import dev.herdr.android.protocol.TerminalSession
import dev.herdr.android.protocol.TerminalSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong

/** Real connections by default; demo is opt-in ONLY through demo://preview. */
class SshHcConnectionController(
    private val connector: SshHcConnector = SshjHcConnector(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pollIntervalMs: Long = 3_000,
    private val rpcTimeoutMs: Long = 15_000
) : HerdrConnectionController {
    private val lifecycle = Mutex()
    private val attempt = AtomicLong()
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    private val _terminals = MutableStateFlow<List<TerminalSummary>>(emptyList())
    override val terminals: StateFlow<List<TerminalSummary>> = _terminals.asStateFlow()
    private val _machines = MutableStateFlow<List<MachineSummary>>(emptyList())
    override val machines: StateFlow<List<MachineSummary>> = _machines.asStateFlow()
    @Volatile private var ssh: AuthenticatedSshConnection? = null
    @Volatile private var exec: SshHcExecChannel? = null
    @Volatile private var client: HcFramedClient? = null
    @Volatile private var demo: PreviewDemoConnectionController? = null
    private var pollJob: Job? = null
    private var monitorJob: Job? = null
    private var stderrJob: Job? = null
    private var demoJobs: List<Job> = emptyList()
    private var stderr = StderrTail()

    override suspend fun connect(profile: ConnectionProfile): Result<Unit> {
        disconnect()
        return lifecycle.withLock {
            val epoch = attempt.incrementAndGet()
            if (profile.host == "demo://preview") return@withLock connectDemo(profile)
            val stages = ConnectionStage.entries.map { DiagnosticStageStatus(it) }.toMutableList()
            var stage = ConnectionStage.NETWORK_REACHABILITY
            fun update(next: ConnectionStage, state: StageCheckState, detail: String) {
                stage = next
                stages[next.ordinal] = DiagnosticStageStatus(next, state, detail)
                _connectionState.value = ConnectionState.Connecting(profile, next, stages.toList())
            }
            try {
                update(stage, StageCheckState.IN_PROGRESS, "连接 ${profile.host}:${profile.port}")
                validateProfile(profile)
                connector.prepareTransport(profile) { detail ->
                    if (attempt.get() == epoch) update(ConnectionStage.NETWORK_REACHABILITY, StageCheckState.IN_PROGRESS, detail)
                }
                checkEpoch(epoch)
                val rtt = connector.probe(profile)
                checkEpoch(epoch)
                update(stage, StageCheckState.PASSED, "TCP 连接成功，RTT ${rtt}ms")

                update(ConnectionStage.SSH_HANDSHAKE_AND_AUTH, StageCheckState.IN_PROGRESS, "校验 SSH 服务端指纹并加载手机私钥")
                ssh = connector.authenticate(profile)
                checkEpoch(epoch)
                update(stage, StageCheckState.PASSED, "SSH 密钥认证成功，服务端 ${ssh!!.hostKeyFingerprint}")

                val commands = RemoteHcCommandBuilder.candidates(profile)
                var selectedClient: HcFramedClient? = null
                var lastError: Exception? = null
                for ((index, command) in commands.withIndex()) {
                    checkEpoch(epoch)
                    update(ConnectionStage.HC_DISCOVERY_AND_EXEC, StageCheckState.IN_PROGRESS,
                        "通过 ${command.first.displayName} 的无 PTY exec 启动 hc connect")
                    stderr = StderrTail()
                    exec = ssh!!.exec(command.second)
                    val channel = exec!!
                    stderrJob = stderr.drain(channel.stderr, scope)
                    checkEpoch(epoch)
                    update(stage, StageCheckState.PASSED, "SSH exec 已接受；标准输出专用于 HC 原始协议")
                    update(ConnectionStage.PROTOCOL_HELLO_AND_SYNC, StageCheckState.IN_PROGRESS, "协商 generation 1 framed 协议")
                    val candidate = HcFramedClient(channel.input, channel.output, scope, rpcTimeoutMs)
                    client = candidate
                    try {
                        candidate.start()
                        selectedClient = candidate
                        break
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        lastError = error
                        // Only an explicitly AUTO profile can fall back, and only when the host
                        // actually says PowerShell does not exist. Never retry a permission or key error.
                        delay(50)
                        val canFallback = profile.platform == HostPlatform.AUTO && index + 1 < commands.size &&
                            RemoteHcCommandBuilder.canFallbackToUnix(stderr.text())
                        candidate.close()
                        client = null
                        runCatching { channel.close() }
                        exec = null
                        stderrJob?.cancel()
                        if (!canFallback) throw error
                    }
                }
                val connected = selectedClient ?: throw (lastError ?: IllegalStateException("No HC command was available"))
                val hello = connected.helloInfo ?: throw HcProtocolException("HC hello was not completed")
                val status = connected.serverStatus()
                val inventory = connected.listTerminals()
                val remoteMachines = readMachines(connected)
                checkEpoch(epoch)
                _terminals.value = inventory
                _machines.value = remoteMachines
                stages[ConnectionStage.PROTOCOL_HELLO_AND_SYNC.ordinal] = DiagnosticStageStatus(
                    ConnectionStage.PROTOCOL_HELLO_AND_SYNC, StageCheckState.PASSED,
                    "HC ${hello.version} · ${hello.coreName} · ${inventory.size} 个运行中终端"
                )
                _connectionState.value = ConnectionState.Connected(profile, hello, status, stages.toList())
                startMonitoring(profile, epoch, connected, stages.toList())
                Result.success(Unit)
            } catch (error: CancellationException) {
                closeResources()
                if (attempt.get() == epoch) _connectionState.value = ConnectionState.Disconnected
                throw error
            } catch (error: Exception) {
                val problem = ConnectionDiagnostics.describe(error, stage, stderr.text())
                closeResources()
                if (attempt.get() == epoch) {
                    val failures = stages.map {
                        if (it.stage == problem.stage) it.copy(state = StageCheckState.FAILED, detail = problem.message, hint = problem.hint)
                        else if (it.state == StageCheckState.IN_PROGRESS) it.copy(state = StageCheckState.PENDING)
                        else it
                    }
                    _connectionState.value = ConnectionState.Failed(profile, problem.stage, problem.kind,
                        problem.message, problem.hint, failures)
                }
                Result.failure(error)
            }
        }
    }

    private suspend fun connectDemo(profile: ConnectionProfile): Result<Unit> {
        val preview = PreviewDemoConnectionController()
        demo = preview
        demoJobs = listOf(
            scope.launch(start = CoroutineStart.UNDISPATCHED) { preview.connectionState.collect { _connectionState.value = it } },
            scope.launch(start = CoroutineStart.UNDISPATCHED) { preview.terminals.collect { _terminals.value = it } },
            scope.launch(start = CoroutineStart.UNDISPATCHED) { preview.machines.collect { _machines.value = it } }
        )
        return try {
            preview.connect(profile)
        } catch (error: CancellationException) {
            demoJobs.forEach { it.cancel() }
            demoJobs = emptyList()
            preview.disconnect()
            demo = null
            _connectionState.value = ConnectionState.Disconnected
            throw error
        }
    }

    private fun startMonitoring(profile: ConnectionProfile, epoch: Long, connected: HcFramedClient, stages: List<DiagnosticStageStatus>) {
        monitorJob = scope.launch {
            connected.isConnected.first { !it }
            if (attempt.get() != epoch) return@launch
            lifecycle.withLock {
                if (attempt.get() != epoch) return@withLock
                val error = connected.failure.value ?: java.io.IOException("HC 连接已断开")
                val problem = ConnectionDiagnostics.describe(error, ConnectionStage.PROTOCOL_HELLO_AND_SYNC, stderr.text())
                closeResources()
                _terminals.value = emptyList()
                _machines.value = emptyList()
                _connectionState.value = ConnectionState.Failed(profile, problem.stage, problem.kind,
                    problem.message, problem.hint, stages.map {
                        if (it.stage == problem.stage) it.copy(state = StageCheckState.FAILED, detail = problem.message, hint = problem.hint) else it
                    })
            }
        }
        pollJob = scope.launch {
            while (isActive && attempt.get() == epoch) {
                delay(pollIntervalMs)
                try {
                    val inventory = connected.listTerminals()
                    val remoteMachines = readMachines(connected)
                    if (attempt.get() == epoch) {
                        _terminals.value = inventory
                        _machines.value = remoteMachines
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    connected.close() // monitor reports the loss; mutating calls are never replayed
                    break
                }
            }
        }
    }

    override suspend fun disconnect() {
        attempt.incrementAndGet()
        // Break blocking connect/read before waiting for the lifecycle lock.
        connector.cancelPending()
        runCatching { client?.close() }
        runCatching { exec?.close() }
        lifecycle.withLock {
            closeResources()
            demo?.disconnect()
            demo = null
            _terminals.value = emptyList()
            _machines.value = emptyList()
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    private fun closeResources() {
        pollJob?.cancel(); pollJob = null
        monitorJob?.cancel(); monitorJob = null
        stderrJob?.cancel(); stderrJob = null
        demoJobs.forEach { it.cancel() }; demoJobs = emptyList()
        runCatching { client?.close() }; client = null
        runCatching { exec?.close() }; exec = null
        runCatching { ssh?.close() }; ssh = null
    }

    override suspend fun refreshTerminals(): Result<List<TerminalSummary>> {
        demo?.let { return it.refreshTerminals() }
        return capture {
            val inventory = requireClient().listTerminals()
            _terminals.value = inventory
            inventory
        }
    }

    override suspend fun openTerminalSession(address: String, initialMode: AttachMode): Result<TerminalSession> {
        demo?.let { return it.openTerminalSession(address, initialMode) }
        return capture { requireClient().openTerminalSession(address, initialMode) }
    }

    override suspend fun sendMessageToTerminal(address: String, message: String): Result<Unit> {
        demo?.let { return it.sendMessageToTerminal(address, message) }
        return capture { requireClient().sendTerminal(address, message) }
    }

    private fun requireClient(): HcFramedClient = client?.takeIf { it.isConnected.value }
        ?: throw IllegalStateException("尚未连接 HC")

    private suspend fun readMachines(connected: HcFramedClient): List<MachineSummary> = try {
        connected.listMachines()
    } catch (error: HcRpcException) {
        if (error.kind == "unknown_method" || error.kind == "method_not_found") emptyList() else throw error
    }

    private fun checkEpoch(epoch: Long) {
        if (attempt.get() != epoch) throw CancellationException("Connection cancelled or replaced")
    }

    private fun validateProfile(profile: ConnectionProfile) {
        require(profile.host.isNotBlank() && profile.host.none { it == '\u0000' || it == '\r' || it == '\n' }) { "请填写主机地址" }
        require(profile.port in 1..65535) { "SSH 端口必须在 1..65535 之间" }
        require(profile.username.isNotBlank()) { "请填写 SSH 用户名" }
    }

    private suspend fun <T> capture(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
}

internal data class ConnectionProblem(val stage: ConnectionStage, val kind: String, val message: String, val hint: String)

internal object ConnectionDiagnostics {
    fun describe(error: Throwable, stage: ConnectionStage, stderr: String): ConnectionProblem {
        val combined = ((error.message ?: "") + "\n" + stderr).lowercase()
        fun problem(kind: String, message: String, hint: String, at: ConnectionStage = stage) =
            ConnectionProblem(at, kind, message, hint)
        return when {
            error is HostKeyNotTrustedException -> problem("host_key_untrusted", error.message.orEmpty(),
                "先在 Windows 核对 ssh-keygen -lf C:\\ProgramData\\ssh\\ssh_host_ed25519_key.pub 的指纹，再将服务端指纹填入连接配置；不要填手机用户密钥的指纹。", ConnectionStage.SSH_HANDSHAKE_AND_AUTH)
            error is HostKeyChangedException -> problem("host_key_changed", error.message.orEmpty(),
                "已停止认证。请在主机上核对是否重装或更换了主机密钥，确认后再更新保存的指纹。", ConnectionStage.SSH_HANDSHAKE_AND_AUTH)
            error is PrivateKeyUnavailableException -> problem("private_key_unavailable", error.message.orEmpty(),
                "导入手机本地的 OpenSSH 私钥；加密私钥需填写正确口令。Windows 上的文件路径不等于手机本地路径。", ConnectionStage.SSH_HANDSHAKE_AND_AUTH)
            "herdr_hc_missing" in combined -> problem("hc_missing", "SSH 已连接，但未找到原生 Herdr Core。",
                "核对 hc.exe/herdr-core.exe 的实际路径；不要安装旧版 herdr 来代替 core。", ConnectionStage.HC_DISCOVERY_AND_EXEC)
            "herdr_hc_not_running" in combined -> problem("hc_not_running", "当前 SSH 账户下没有可连接的 HC 会话。",
                "在 Windows 桌面以相同账户正常打开 Herdr，再连接；检查是否使用了不同的 HC_SESSION。", ConnectionStage.HC_DISCOVERY_AND_EXEC)
            stage.ordinal >= ConnectionStage.HC_DISCOVERY_AND_EXEC.ordinal &&
                listOf("elevated windows core", "access is denied", "access denied", "permission denied", "integrity", "desktop session").any { it in combined } ->
                problem("windows_session_permission", "Windows 桌面会话或权限级别阻止了 HC 连接。",
                    "SSH 账户必须与桌面 HC 一致。先在普通权限桌面打开 Herdr；避免一个以管理员权限运行、另一个以普通权限运行。", ConnectionStage.HC_DISCOVERY_AND_EXEC)
            stage == ConnectionStage.NETWORK_REACHABILITY && error is SocketTimeoutException && "zerotier 目标" in combined ->
                problem("zerotier_peer_timeout", error.message.orEmpty(),
                    "虚拟 IP 已分配；检查对端 ZeroTier 是否在线、目标 SSH 端口及防火墙是否放行。")
            stage == ConnectionStage.NETWORK_REACHABILITY && error is ConnectException && "zerotier" in combined ->
                problem("ssh_port_refused", "ZeroTier 目标已响应，但拒绝了 SSH 端口连接。",
                    "在目标主机启动 sshd，并核对 SSH 监听端口。")
            stage == ConnectionStage.NETWORK_REACHABILITY && ("zerotier" in combined || "planet" in combined) ->
                problem("zerotier_unavailable", error.message ?: "ZeroTier 虚拟链路不可用",
                    "检查 Planet 配置、Network ID 和控制器上的节点授权；虚拟 IP 就绪后再核对目标 SSH 地址与端口。")
            error is UnknownHostException -> problem("unknown_host", "无法解析主机地址。", "检查 ZeroTier 地址或主机名是否填写正确。")
            error is ConnectException -> problem("port_unreachable", "SSH 端口无法连接。", "确认手机和 Windows 在同一个 ZeroTier 网络，Windows 已安装并启动 sshd，防火墙允许该网络访问指定端口。")
            error is SocketTimeoutException -> problem("network_timeout", "连接超时。", "检查 ZeroTier 在线状态、路由、防火墙和目标端口。")
            error is HcRpcException -> problem(error.kind, error.message.orEmpty(), error.hint.ifBlank { "核对客户端与 HC generation 是否兼容；超时的输入不要直接重发。" })
            error is HcProtocolException -> problem("protocol_error", error.message.orEmpty(), "使用无 PTY 的 SSH exec；禁止 Shell 横幅或文本管道污染协议标准输出。")
            stage == ConnectionStage.SSH_HANDSHAKE_AND_AUTH -> problem("ssh_authentication", "SSH 握手或密钥认证失败。",
                "核对用户名、手机私钥和服务端授权。Windows 管理员账户默认使用 C:\\ProgramData\\ssh\\administrators_authorized_keys，并要求正确的文件权限。")
            error is IllegalArgumentException -> problem("invalid_profile", error.message ?: "连接配置无效", "检查地址、端口、用户名及程序路径。")
            else -> problem("connection_lost", error.message ?: "连接已断开", "重新连接后重新获取终端列表；不要自动重发尚未确认的输入。")
        }
    }
}

private class StderrTail {
    private var data = byteArrayOf()
    @Synchronized fun text(): String = data.toString(Charsets.UTF_8)
    @Synchronized private fun append(bytes: ByteArray, count: Int) {
        val combined = data + bytes.copyOf(count)
        data = if (combined.size <= 16_384) combined else combined.copyOfRange(combined.size - 16_384, combined.size)
    }
    fun drain(input: InputStream, scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        val buffer = ByteArray(2048)
        try {
            while (isActive) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) append(buffer, count)
            }
        } catch (_: Exception) { /* exec close is expected to end this blocking reader */ }
    }
}
