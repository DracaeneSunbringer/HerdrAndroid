package dev.herdr.android.zerotier

import android.content.Context
import android.util.AtomicFile
import dev.herdr.android.connection.ConnectionProfile
import dev.herdr.android.connection.ZeroTierNodePhase
import dev.herdr.android.connection.ZeroTierOverlayState
import dev.herdr.android.connection.ZeroTierOverlayTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/** Userspace overlay only. Physical UDP sockets use libzt's unmodified system-default path. */
class AndroidZeroTierOverlayTransport(context: Context) : ZeroTierOverlayTransport {
    private val app = context.applicationContext
    private val folder = File(app.filesDir, "zerotier_node")
    private val secretFile get() = File(folder, "identity.secret")
    private val publicFile get() = File(folder, "identity.public")
    private val planetFile get() = File(folder, "planet.custom")
    private val rootsFile get() = File(folder, "roots")
    private val preferences = app.getSharedPreferences("herdr_zerotier", Context.MODE_PRIVATE)
    override val state: StateFlow<ZeroTierOverlayState> = Runtime.state.asStateFlow()

    override suspend fun ensureReadyAndCreateSocketFactory(
        profile: ConnectionProfile, onProgress: (String) -> Unit
    ): SocketFactory = withContext(Dispatchers.IO) {
        val attempt = Runtime.pending.get()
        var epoch = -1L
        try {
            val network = ZeroTierIdentity.networkId(profile.zeroTierNetworkId)
            val networkHex = network.toULong().toString(16).padStart(16, '0')
            val planet = Runtime.gate.withLock {
                checkPending(attempt)
                ensureFolder()
                if (profile.zeroTierIdentitySecret.isNotBlank()) {
                    val identity = validatedIdentity(profile.zeroTierIdentitySecret)
                    if (readSavedIdentity()?.secret != identity.secret) {
                        stopLocked()
                        saveIdentity(identity)
                        Runtime.state.value = Runtime.state.value.copy(nodeIdHex = identity.nodeId, identitySecretExport = "")
                    }
                }
                val selected = selectedPlanet(profile.zeroTierCustomPlanetPath)
                HerdrZeroTierNative.validatePlanet(selected.bytes)
                val hash = sha256(selected.bytes)
                if (Runtime.running.get() && (Runtime.planetHash != hash || Runtime.storage != folder.absolutePath)) stopLocked()
                publish(ZeroTierNodePhase.STARTING, networkHex, selected.label, "初始化用户态节点与 ${selected.label}", onProgress)
                if (!Runtime.running.get()) {
                    checkPending(attempt)
                    readSavedIdentity()?.let { writePrivate(publicFile, (it.public + "\n").toByteArray()) }
                    HerdrZeroTierNative.startNode(folder.absolutePath, selected.bytes)
                    Runtime.storage = folder.absolutePath
                    Runtime.planetHash = hash
                    Runtime.running.set(true)
                }
                epoch = Runtime.epoch.get()
                selected
            }
            // Do not keep the configuration mutex while waiting for authorization: the user
            // must still be able to export/import identity or change planet from Settings.
            val until = System.nanoTime() + 180_000_000_000L
            while (true) {
                checkPending(attempt)
                checkEpoch(epoch)
                if (System.nanoTime() >= until) throw SocketTimeoutException("ZeroTier 等待超时；请检查 Planet 连通性并在网络控制器授权当前 Node ID")
                val snapshot = HerdrZeroTierNative.snapshot(network)
                val node = snapshot[0]
                val online = snapshot[1] == "1"
                val status = snapshot[2].toIntOrNull() ?: -1
                val ready = snapshot[3] == "1"
                val ipv4 = snapshot[4].takeUnless { it == "0.0.0.0" }.orEmpty()
                checkPending(attempt)
                checkEpoch(epoch)
                Runtime.state.value = Runtime.state.value.copy(nodeIdHex = node, assignedIpv4 = ipv4)
                if (!online) {
                    publish(ZeroTierNodePhase.STARTING, networkHex, planet.label,
                        "Node ${node.ifBlank { "生成中" }} · 等待 Planet 连通", onProgress)
                } else if (!Runtime.networks.contains(network)) {
                    Runtime.gate.withLock {
                        checkPending(attempt); checkEpoch(epoch)
                        if (!Runtime.networks.contains(network)) {
                            publish(ZeroTierNodePhase.ONLINE, networkHex, planet.label, "Node $node · Planet 已连通", onProgress)
                            HerdrZeroTierNative.joinNetwork(network)
                            Runtime.networks.add(network)
                            publish(ZeroTierNodePhase.JOINING_NETWORK, networkHex, planet.label, "Node $node · 正在加入 $networkHex", onProgress)
                        }
                    }
                } else if (ready && ipv4.isNotBlank()) {
                    publish(ZeroTierNodePhase.READY, networkHex, planet.label, "Node $node · 虚拟 IPv4 $ipv4 已分配，准备建立目标连接", onProgress)
                    return@withContext OverlaySocketFactory(epoch)
                } else {
                    when (status) {
                        3 -> throw IOException("ZeroTier 网络 $networkHex 不存在")
                        4 -> throw IOException("ZeroTier 虚拟网络端口初始化失败")
                        5 -> throw IOException("ZeroTier 内核版本过旧，控制器拒绝此节点")
                    }
                    val waiting = status == 0 || status == 2 || status < 0
                    publish(if (waiting) ZeroTierNodePhase.WAITING_AUTH else ZeroTierNodePhase.WAITING_IP,
                        networkHex, planet.label,
                        if (waiting) "Node $node · 等待控制器授权 $networkHex" else "Node $node · 已授权，等待虚拟 IPv4", onProgress)
                }
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val reported = if (error is LinkageError) IOException("无法加载应用内 ZeroTier 原生库，请安装包含当前设备 ABI 的完整 APK", error) else error
            if (epoch < 0 || Runtime.epoch.get() == epoch) {
                Runtime.state.value = Runtime.state.value.copy(phase = ZeroTierNodePhase.ERROR,
                    statusDetail = reported.message ?: "ZeroTier 初始化失败")
            }
            throw reported
        }
    }

    override suspend fun importIdentitySecret(identitySecretText: String): Result<String> = action {
        val identity = validatedIdentity(identitySecretText)
        cancelPending()
        Runtime.gate.withLock {
            ensureFolder()
            stopLocked()
            saveIdentity(identity)
            Runtime.state.value = Runtime.state.value.copy(phase = ZeroTierNodePhase.STOPPED,
                nodeIdHex = identity.nodeId, assignedIpv4 = "", identitySecretExport = "",
                statusDetail = "节点身份已导入，下次连接时生效；Node ${identity.nodeId}")
            identity.nodeId
        }
    }

    override suspend fun exportIdentitySecret(): Result<String> = action {
        Runtime.gate.withLock {
            ensureFolder()
            val identity = readSavedIdentity() ?: if (Runtime.running.get()) {
                validatedIdentity(HerdrZeroTierNative.exportNodeIdentity())
            } else throw IOException("节点身份尚未生成，请先连接一次或导入 identity.secret")
            // Always export the matching public portion, replacing any stale identity.public.
            writePrivate(publicFile, (identity.public + "\n").toByteArray())
            Runtime.state.value = Runtime.state.value.copy(nodeIdHex = identity.nodeId, identitySecretExport = identity.secret)
            identity.secret
        }
    }

    override suspend fun importCustomPlanet(planetBytes: ByteArray, label: String): Result<Unit> = action {
        HerdrZeroTierNative.validatePlanet(planetBytes)
        cancelPending()
        Runtime.gate.withLock {
            ensureFolder(); stopLocked()
            writePrivate(planetFile, planetBytes)
            val name = label.filter { !it.isISOControl() }.take(100).ifBlank { "自定义 Planet" }
            preferences.edit().putString("planet_label", name).apply()
            Runtime.state.value = Runtime.state.value.copy(phase = ZeroTierNodePhase.STOPPED,
                planetSummary = name, assignedIpv4 = "", statusDetail = "Planet 已导入，下次连接时生效")
        }
    }

    override suspend fun resetToBuiltInPlanet(): Result<Unit> = action {
        val bytes = rootsFile.takeIf { it.isFile && it.canRead() }?.inputStream()?.use(::readPlanet)
            ?: planetFile.takeIf { it.isFile && it.canRead() }?.inputStream()?.use(::readPlanet)
            ?: throw IOException("当前设备上尚未保存 Planet 文件，请点击「导入 Planet」选择文件")
        HerdrZeroTierNative.validatePlanet(bytes)
        cancelPending()
        Runtime.gate.withLock {
            ensureFolder(); stopLocked()
            writePrivate(planetFile, bytes)
            preferences.edit().putString("planet_label", BUILT_IN_LABEL).apply()
            Runtime.state.value = Runtime.state.value.copy(phase = ZeroTierNodePhase.STOPPED,
                planetSummary = BUILT_IN_LABEL, assignedIpv4 = "", statusDetail = "已恢复已保存 Planet，下次连接时生效")
        }
    }

    override fun cancelPending() { Runtime.pending.incrementAndGet() }

    private fun publish(phase: ZeroTierNodePhase, network: String, planet: String, detail: String, progress: (String) -> Unit) {
        val previous = Runtime.state.value
        Runtime.state.value = previous.copy(phase = phase, networkIdHex = network, planetSummary = planet, statusDetail = detail)
        if (previous.statusDetail != detail || previous.phase != phase) progress(detail)
    }
    private fun ensureFolder() {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("无法创建 ZeroTier 节点私有目录")
    }
    private fun validatedIdentity(text: String): ZeroTierIdentity {
        val identity = ZeroTierIdentity.parse(text)
        require(HerdrZeroTierNative.validateIdentity(identity.secret)) { "ZeroTier identity.secret 的节点地址、公钥或私钥校验失败" }
        return identity
    }
    private fun readSavedIdentity(): ZeroTierIdentity? {
        if (!secretFile.isFile) return null
        if (secretFile.length() > 4096) throw IOException("identity.secret 文件过大")
        return validatedIdentity(secretFile.readText(Charsets.UTF_8))
    }
    private fun saveIdentity(identity: ZeroTierIdentity) {
        writePrivate(secretFile, (identity.secret + "\n").toByteArray())
        writePrivate(publicFile, (identity.public + "\n").toByteArray())
    }
    private fun writePrivate(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
        file.setReadable(false, false); file.setWritable(false, false)
        file.setReadable(true, true); file.setWritable(true, true)
    }
    private data class Planet(val bytes: ByteArray, val label: String)
    private fun selectedPlanet(path: String): Planet {
        if (path.isNotBlank()) {
            val file = File(path)
            require(file.isFile && file.canRead()) { "无法读取手机上的自定义 Planet 文件" }
            return Planet(file.inputStream().use(::readPlanet), file.name)
        }
        if (planetFile.isFile && planetFile.canRead()) {
            return Planet(planetFile.inputStream().use(::readPlanet), preferences.getString("planet_label", "自定义 Planet") ?: "自定义 Planet")
        }
        if (rootsFile.isFile && rootsFile.canRead()) {
            val savedBytes = rootsFile.inputStream().use(::readPlanet)
            writePrivate(planetFile, savedBytes)
            val label = preferences.getString("planet_label", BUILT_IN_LABEL) ?: BUILT_IN_LABEL
            return Planet(savedBytes, label)
        }
        val extPlanet = app.getExternalFilesDir(null)?.let { File(it, "planet") }
        if (extPlanet != null && extPlanet.isFile && extPlanet.canRead()) {
            val extBytes = extPlanet.inputStream().use(::readPlanet)
            writePrivate(planetFile, extBytes)
            return Planet(extBytes, "自定义 Planet (files/planet)")
        }
        throw IOException("未检测到已保存的 Planet 文件，请在「设置」中导入自定义 Planet 文件")
    }
    private fun readPlanet(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= 4096) { "Planet 文件不能超过 4096 字节" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun checkPending(attempt: Long) {
        if (Runtime.pending.get() != attempt) throw CancellationException("ZeroTier 连接准备已取消")
    }
    private fun checkEpoch(epoch: Long) {
        if (!Runtime.running.get() || Runtime.epoch.get() != epoch) throw CancellationException("ZeroTier 节点已重新配置")
    }
    private fun stopLocked() {
        Runtime.epoch.incrementAndGet()
        Runtime.running.set(false)
        Runtime.sockets.toList().forEach { runCatching { it.close() } }
        HerdrZeroTierNative.stopNode()
        Runtime.networks.clear()
        Runtime.planetHash = ""
        Runtime.storage = ""
    }
    private suspend fun <T> action(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try { Result.success(block()) }
        catch (error: CancellationException) { throw error }
        catch (error: Throwable) { Result.failure(error) }
    }

    private class OverlaySocketFactory(private val epoch: Long) : SocketFactory() {
        override fun createSocket(): Socket {
            checkLease()
            val socket = ZtProxySocket(onClosed = { Runtime.sockets.remove(it) })
            Runtime.sockets.add(socket)
            try { checkLease() } catch (error: Exception) { socket.close(); throw error }
            return socket
        }
        private fun checkLease() {
            if (!Runtime.running.get() || Runtime.epoch.get() != epoch) throw SocketException("ZeroTier 节点已重新配置，请重新连接")
        }
        override fun createSocket(host: String, port: Int): Socket = connected(InetSocketAddress(host, port))
        override fun createSocket(host: InetAddress, port: Int): Socket = connected(InetSocketAddress(host, port))
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = withLocal(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = withLocal(host.hostAddress!!, port, localHost, localPort)
        private fun withLocal(host: String, port: Int, local: InetAddress, localPort: Int): Socket {
            require(local.isAnyLocalAddress && localPort == 0) { "ZeroTier 目前只支持自动分配虚拟本地端点" }
            return connected(InetSocketAddress(host, port))
        }
        private fun connected(endpoint: InetSocketAddress): Socket {
            val socket = createSocket()
            try { socket.connect(endpoint, 8000); return socket }
            catch (error: Throwable) { socket.close(); throw error }
        }
    }

    private object Runtime {
        val gate = Mutex()
        val state = MutableStateFlow(ZeroTierOverlayState())
        val pending = AtomicLong()
        val epoch = AtomicLong()
        val running = AtomicBoolean()
        val sockets = ConcurrentHashMap.newKeySet<ZtProxySocket>()
        val networks = ConcurrentHashMap.newKeySet<Long>()
        var planetHash = ""
        var storage = ""
    }
    private companion object {
        const val BUILT_IN_LABEL = "内置自建 Planet"
        const val DEFAULT_PLANET_LABEL = "官方默认 Planet"
    }
}
