package dev.herdr.android.connection

import dev.herdr.android.protocol.AgentStateWord
import dev.herdr.android.protocol.AttachMode
import dev.herdr.android.protocol.HcHelloInfo
import dev.herdr.android.protocol.HcServerStatus
import dev.herdr.android.protocol.MachineSummary
import dev.herdr.android.protocol.TerminalSession
import dev.herdr.android.protocol.TerminalSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HostPlatform(val displayName: String) {
    WINDOWS("Windows (Native OpenSSH -> hc.exe connect)"),
    UNIX("Linux / macOS (SSH -> hc connect)"),
    AUTO("Auto-detect (Windows first, fallback Unix)")
}

data class ConnectionProfile(
    val id: String = "win-workstation",
    val displayName: String = "Windows 主力机 (ZeroTier)",
    val host: String = "10.147.17.100",
    val port: Int = 22,
    val username: String = "herdr",
    val privateKeyPem: String = "",
    val privateKeyPathHint: String = "/sdcard/Android/data/dev.herdr.android/files/id_ed25519_herdr_android",
    val privateKeyPassphrase: String? = null,
    val expectedHostKeyFingerprint: String? = null,
    val platform: HostPlatform = HostPlatform.WINDOWS,
    val hcExecutablePath: String = """C:\Users\herdr\AppData\Local\Programs\herdr-core\hc.exe""",
    val useBuiltInZeroTier: Boolean = false,
    val zeroTierNetworkId: String = "",
    val zeroTierIdentitySecret: String = "",
    val zeroTierCustomPlanetPath: String = ""
)

enum class ZeroTierNodePhase(val label: String) {
    STOPPED("待命"),
    STARTING("初始化节点与 Planet"),
    ONLINE("Planet 已连通"),
    JOINING_NETWORK("正在加入网络"),
    WAITING_AUTH("等待控制器授权节点"),
    WAITING_IP("正在获取虚拟 IPv4"),
    READY("虚拟链路就绪"),
    ERROR("异常")
}

data class ZeroTierOverlayState(
    val phase: ZeroTierNodePhase = ZeroTierNodePhase.STOPPED,
    val nodeIdHex: String = "",
    val networkIdHex: String = "",
    val assignedIpv4: String = "",
    val planetSummary: String = "内置/默认 Planet",
    val peerPathMode: String = "",
    val identitySecretExport: String = "",
    val statusDetail: String = "应用内用户态协议栈（不占用系统 VPN 槽位，与现有代理共存）"
)

interface ZeroTierOverlayTransport {
    val state: StateFlow<ZeroTierOverlayState>
    suspend fun ensureReadyAndCreateSocketFactory(
        profile: ConnectionProfile,
        onProgress: (String) -> Unit = {}
    ): javax.net.SocketFactory
    suspend fun importIdentitySecret(identitySecretText: String): Result<String>
    suspend fun exportIdentitySecret(): Result<String>
    suspend fun importCustomPlanet(planetBytes: ByteArray, label: String): Result<Unit>
    suspend fun resetToBuiltInPlanet(): Result<Unit>
    fun cancelPending()
}

object ZeroTierOverlayRegistry {
    @Volatile
    var transport: ZeroTierOverlayTransport? = null
}

enum class ConnectionStage(val stepNumber: Int, val title: String, val description: String) {
    NETWORK_REACHABILITY(
        1,
        "网络与端口可达",
        "TCP 连接目标主机 22 端口（ZeroTier / 局域网）"
    ),
    SSH_HANDSHAKE_AND_AUTH(
        2,
        "SSH 密钥认证与主机指纹",
        "校验服务端 HostKey 并使用 Ed25519 私钥完成无口令/口令认证"
    ),
    HC_DISCOVERY_AND_EXEC(
        3,
        "拉起原生 hc connect 通道",
        "通过 SSH exec（禁用 PTY、禁用 Shell 横幅污染）启动原生 herdr-core connect"
    ),
    PROTOCOL_HELLO_AND_SYNC(
        4,
        "HC Generation 1 协议握手",
        "完成 framed hello / server.status / terminal.list 同步并检查 Windows 桌面会话权限"
    )
}

enum class StageCheckState {
    PENDING,
    IN_PROGRESS,
    PASSED,
    FAILED
}

data class DiagnosticStageStatus(
    val stage: ConnectionStage,
    val state: StageCheckState = StageCheckState.PENDING,
    val detail: String? = null,
    val hint: String? = null
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data class Connecting(
        val profile: ConnectionProfile,
        val activeStage: ConnectionStage,
        val stages: List<DiagnosticStageStatus>
    ) : ConnectionState

    data class Connected(
        val profile: ConnectionProfile,
        val hello: HcHelloInfo,
        val serverStatus: HcServerStatus,
        val stages: List<DiagnosticStageStatus>
    ) : ConnectionState

    data class Failed(
        val profile: ConnectionProfile,
        val failedStage: ConnectionStage,
        val errorKind: String,
        val message: String,
        val actionableHint: String,
        val stages: List<DiagnosticStageStatus>
    ) : ConnectionState
}

/**
 * Main controller contract between `:app` (UI/ViewModel) and `:connection` + `:hc-protocol`.
 */
interface HerdrConnectionController {
    val connectionState: StateFlow<ConnectionState>
    val terminals: StateFlow<List<TerminalSummary>>
    val machines: StateFlow<List<MachineSummary>>

    suspend fun connect(profile: ConnectionProfile): Result<Unit>
    suspend fun disconnect()
    suspend fun refreshTerminals(): Result<List<TerminalSummary>>
    suspend fun openTerminalSession(
        address: String,
        initialMode: AttachMode = AttachMode.OBSERVE
    ): Result<TerminalSession>
    suspend fun sendMessageToTerminal(address: String, message: String): Result<Unit>
}

/**
 * Factory hook. `partner` (`t1r`) can replace or delegate the default implementation here
 * once `SshHcConnectionController` in `:connection` is ready, without `:app` needing build changes.
 */
object HerdrConnectionControllerFactory {
    @Volatile
    var provider: () -> HerdrConnectionController = { SshHcConnectionController() }

    fun create(): HerdrConnectionController = provider()
}

/**
 * Built-in diagnostic + preview controller so the UI can demonstrate both real-world topology
 * (`t1q`, `t1r/partner`, `t19`, `linux-node/t7`, etc.) and stage-by-stage diagnostics out of the box,
 * while `:connection` & `:hc-protocol` wire in the live SSH + framed `hc connect` client.
 */
class PreviewDemoConnectionController : HerdrConnectionController {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _terminals = MutableStateFlow<List<TerminalSummary>>(sampleLiveTopology())
    override val terminals: StateFlow<List<TerminalSummary>> = _terminals.asStateFlow()

    private val _machines = MutableStateFlow(
        listOf(MachineSummary(name = "linux-node", state = "connected"))
    )
    override val machines: StateFlow<List<MachineSummary>> = _machines.asStateFlow()

    override suspend fun connect(profile: ConnectionProfile): Result<Unit> {
        val baseStages = ConnectionStage.entries.map { DiagnosticStageStatus(it) }.toMutableList()

        for (i in baseStages.indices) {
            val currentStage = baseStages[i].stage
            baseStages[i] = baseStages[i].copy(
                state = StageCheckState.IN_PROGRESS,
                detail = "正在执行：${currentStage.description}"
            )
            _connectionState.value = ConnectionState.Connecting(
                profile = profile,
                activeStage = currentStage,
                stages = baseStages.toList()
            )
            delay(180)
            baseStages[i] = baseStages[i].copy(
                state = StageCheckState.PASSED,
                detail = when (currentStage) {
                    ConnectionStage.NETWORK_REACHABILITY -> "${profile.host}:${profile.port} TCP 握手成功 (RTT 6ms)"
                    ConnectionStage.SSH_HANDSHAKE_AND_AUTH -> "Ed25519 公钥 SHA256:TeKk2nPX... 认证通过"
                    ConnectionStage.HC_DISCOVERY_AND_EXEC -> "SSH exec 无 PTY 启动 ${profile.hcExecutablePath} connect"
                    ConnectionStage.PROTOCOL_HELLO_AND_SYNC -> "HC 0.1.18 (gen 1) 命名管道桌面会话握手完成"
                }
            )
        }

        _connectionState.value = ConnectionState.Connected(
            profile = profile,
            hello = HcHelloInfo(
                generation = 1,
                version = "0.1.18",
                coreId = "win-workstation-core",
                coreName = "herdr-windows",
                features = listOf("framed", "attach", "resize_notifications", "automatic_input")
            ),
            serverStatus = HcServerStatus(
                version = "0.1.18",
                liveTerminals = _terminals.value.size,
                finishedTerminals = 11
            ),
            stages = baseStages.toList()
        )
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun refreshTerminals(): Result<List<TerminalSummary>> {
        return Result.success(_terminals.value)
    }

    override suspend fun openTerminalSession(
        address: String,
        initialMode: AttachMode
    ): Result<TerminalSession> {
        val summary = _terminals.value.find { it.address == address }
            ?: return Result.failure(IllegalArgumentException("未找到终端: $address"))
        return Result.success(PreviewTerminalSession(summary, initialMode))
    }

    override suspend fun sendMessageToTerminal(address: String, message: String): Result<Unit> {
        return Result.success(Unit)
    }

    companion object {
        fun sampleLiveTopology(): List<TerminalSummary> = listOf(
            TerminalSummary(
                address = "t1q",
                name = null,
                parent = null,
                agentApp = "antigravity",
                agentStatus = AgentStateWord.WORKING,
                agentMessage = "HerdrAndroid Frontend & Multi-Agent Architecture",
                cwd = """D:\Workspaces\HerdrAndroid""",
                whereLabel = "HerdrAndroid / 调研和前端",
                spaceId = "spc2acadb64e6bfb8c6990022d4a108d64",
                tabId = "tb91afa40326fe42e8fc7377d4b15998cf",
                title = "Antigravity · HerdrAndroid",
                cols = 206,
                rows = 44,
                turn = 8
            ),
            TerminalSummary(
                address = "t1r",
                name = "partner",
                parent = "t1q",
                agentApp = "codex",
                agentStatus = AgentStateWord.WORKING,
                agentMessage = "Implementing :hc-protocol & :connection for Windows SSH hc connect",
                cwd = """D:\Workspaces\HerdrAndroid""",
                whereLabel = "HerdrAndroid / 调研和前端",
                spaceId = "spc2acadb64e6bfb8c6990022d4a108d64",
                tabId = "tb91afa40326fe42e8fc7377d4b15998cf",
                title = "GPT-6.1-Sol xhigh · HerdrAndroid",
                cols = 200,
                rows = 120,
                turn = 5
            ),
            TerminalSummary(
                address = "t19",
                name = null,
                parent = null,
                agentApp = "antigravity",
                agentStatus = AgentStateWord.DONE,
                agentMessage = "集群巡检已完成",
                cwd = """D:\Workspaces\OpsMonitor""",
                whereLabel = "OpsMonitor / 集群巡检",
                spaceId = "sp3831f3450370525659b41b82e1a481fa",
                tabId = "tb4b16dec5e7bd711bb93efa3527907be6",
                title = "Antigravity · 集群巡检",
                cols = 206,
                rows = 44,
                turn = 76
            ),
            TerminalSummary(
                address = "linux-node/t7",
                name = "worker",
                parent = "t19",
                agentApp = "pi",
                agentStatus = AgentStateWord.DONE,
                agentMessage = "Remote helper on linux-node",
                cwd = "/home/dev/Workspaces/OpsNote",
                whereLabel = "OpsMonitor / 集群巡检",
                title = "π - OpsNote",
                cols = 200,
                rows = 120,
                turn = 25
            ),
            TerminalSummary(
                address = "t14",
                name = null,
                parent = null,
                agentApp = "antigravity",
                agentStatus = AgentStateWord.WORKING,
                cwd = """D:\Workspaces\AudioEngine""",
                whereLabel = "AudioEngine / 语音转写",
                title = "AudioEngine / 语音转写",
                cols = 206,
                rows = 44,
                turn = 60
            ),
            TerminalSummary(
                address = "t18",
                name = null,
                parent = null,
                agentApp = "codex",
                agentStatus = AgentStateWord.IDLE,
                cwd = """D:\Workspaces\OpsMonitor""",
                whereLabel = "OpsMonitor / Codex",
                title = "Codex · OpsMonitor",
                cols = 200,
                rows = 44,
                turn = 11
            )
        )
    }
}

private class PreviewTerminalSession(
    private val summary: TerminalSummary,
    initialMode: AttachMode
) : TerminalSession {
    override val address: String = summary.address
    private val _mode = MutableStateFlow(initialMode)
    override val mode: StateFlow<AttachMode> = _mode.asStateFlow()

    private val _outputFrames = MutableSharedFlow<ByteArray>(replay = 1)
    override val outputFrames: Flow<ByteArray> = _outputFrames.asSharedFlow()

    private val _screenSnapshot = MutableStateFlow(buildInitialScreen(summary))
    override val screenSnapshot: StateFlow<String> = _screenSnapshot.asStateFlow()

    private val _dimensions = MutableStateFlow(summary.cols to summary.rows)
    override val dimensions: StateFlow<Pair<Int, Int>> = _dimensions.asStateFlow()

    private val _isAttached = MutableStateFlow(true)
    override val isAttached: StateFlow<Boolean> = _isAttached.asStateFlow()

    override suspend fun setMode(newMode: AttachMode) {
        _mode.value = newMode
    }

    override suspend fun sendRawInput(bytes: ByteArray): Result<Unit> {
        if (_mode.value == AttachMode.OBSERVE) {
            return Result.failure(IllegalStateException("当前处于 Observe 只读观看模式，请先切换到 Takeover 接管模式再发送输入。"))
        }
        val text = bytes.decodeToString()
        _screenSnapshot.value = _screenSnapshot.value + text
        _outputFrames.emit(bytes)
        return Result.success(Unit)
    }

    override suspend fun sendLine(text: String): Result<Unit> {
        if (_mode.value == AttachMode.OBSERVE) {
            return Result.failure(IllegalStateException("当前处于 Observe 只读观看模式，请先切换到 Takeover 接管模式再发送指令。"))
        }
        val appended = "\n› $text\n• [hc send ${summary.address}] 已投递到远端终端 (turn ${summary.turn + 1})\n"
        _screenSnapshot.value = _screenSnapshot.value + appended
        _outputFrames.emit(appended.encodeToByteArray())
        return Result.success(Unit)
    }

    override suspend fun sendSpecialKey(keyName: String): Result<Unit> {
        if (_mode.value == AttachMode.OBSERVE) {
            return Result.failure(IllegalStateException("Observe 模式下已锁定按键输入"))
        }
        val note = "\n[hc keys ${summary.address} $keyName]"
        _screenSnapshot.value = _screenSnapshot.value + note
        return Result.success(Unit)
    }

    override suspend fun interrupt(): Result<Unit> {
        val note = "\n[hc interrupt ${summary.address}] 已发送中断键\n"
        _screenSnapshot.value = _screenSnapshot.value + note
        return Result.success(Unit)
    }

    override suspend fun requestResize(cols: Int, rows: Int): Result<Unit> {
        if (_mode.value == AttachMode.OBSERVE) {
            return Result.failure(IllegalStateException("Observe 模式不改变远端终端尺寸"))
        }
        _dimensions.value = cols to rows
        return Result.success(Unit)
    }

    override suspend fun detach() {
        _isAttached.value = false
    }

    private fun buildInitialScreen(item: TerminalSummary): String = buildString {
        appendLine("╭──────────────────────────────────────────────────────────────╮")
        appendLine("│ Herdr Core Stream · ${item.address.padEnd(10)} · ${(item.agentApp ?: "shell").padEnd(14)} (${item.cols}×${item.rows}) │")
        appendLine("╰──────────────────────────────────────────────────────────────╯")
        appendLine("cwd   : ${item.cwd}")
        appendLine("where : ${item.whereLabel ?: "-"}")
        if (item.parent != null) {
            appendLine("parent: ${item.parent}  (sub-agent tree node)")
        }
        appendLine("status: ${item.agentStatus.wireName.uppercase()} · turn ${item.turn}")
        appendLine("────────────────────────────────────────────────────────────────")
        if (item.address == "t1r") {
            appendLine("› <hc from=\"t1q\">请实现 :hc-protocol 与 :connection 模块</hc>")
            appendLine("• Codex (GPT-6.1-Sol xhigh) 正在实现 Windows SSH exec -> hc connect 协议栈...")
            appendLine("  - Framed multiplexing: DATA(0), CREDIT(1), CLOSE(2), RESIZE(3), FOCUS(4), FACTS(5)")
            appendLine("  - Windows UAC / Desktop session elevation guard diagnostics")
            appendLine("  - Zero-PTY raw byte stream over SSH exec channel")
        } else if (item.address == "t1q") {
            appendLine("• Antigravity (t1q) 正在构建 :app (Jetpack Compose 工业级终端控制台 UI)")
            appendLine("  - 四步连接诊断流水线 + Windows OpenSSH 指纹与私钥校验")
            appendLine("  - 父子 Agent 树状拓扑视图 (t1q ->└ t1r/partner, t19 ->└ linux-node/t7)")
            appendLine("  - Observe (只读防误触/保尺寸) ⇄ Takeover (接管输入/扩展按键/中文输入)")
        } else {
            appendLine("• Attached to live terminal ${item.address} (${item.title})")
        }
        append("\n› ")
    }
}
