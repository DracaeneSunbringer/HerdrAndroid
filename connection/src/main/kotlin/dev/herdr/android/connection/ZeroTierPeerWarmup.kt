package dev.herdr.android.connection

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory

/** Address assignment precedes peer discovery/ARP. Retry only the pre-SSH TCP probe. */
internal class ZeroTierPeerWarmup(
    private val budgetMs: Long = 40_000,
    private val attemptTimeoutsMs: List<Int> = listOf(2_000, 3_000, 5_000, 8_000),
    private val retryDelaysMs: List<Long> = listOf(250, 500, 750, 1_000),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: suspend (Long) -> Unit = { delay(it) }
) {
    init {
        require(budgetMs > 0 && attemptTimeoutsMs.isNotEmpty() && attemptTimeoutsMs.all { it > 0 })
        require(retryDelaysMs.isNotEmpty() && retryDelaysMs.all { it >= 0 })
    }

    suspend fun probe(
        factory: SocketFactory,
        endpoint: InetSocketAddress,
        onSocket: (Socket) -> Unit,
        onSocketClosed: (Socket) -> Unit,
        checkAttempt: () -> Unit,
        onProgress: (String) -> Unit
    ): Long {
        val started = nowMs()
        var attempt = 0
        var lastFailure: IOException? = null
        fun remaining() = (budgetMs - (nowMs() - started)).coerceAtLeast(0)
        fun expired(): SocketTimeoutException = SocketTimeoutException(
            "ZeroTier 目标 ${endpoint.hostString}:${endpoint.port} 在 ${budgetMs}ms 内仍不可达；虚拟 IP 已分配，请检查对端在线状态、SSH 端口与防火墙。"
        ).apply { lastFailure?.let { initCause(it) } }

        while (true) {
            currentCoroutineContext().ensureActive()
            checkAttempt()
            if (remaining() == 0L) throw expired()
            // A timed-out lwIP TCP PCB must not be reused. The node/neighbor caches stay alive.
            val socket = factory.createSocket()
            try {
                onSocket(socket)
                currentCoroutineContext().ensureActive()
                checkAttempt()
                val left = remaining()
                if (left == 0L) throw expired()
                val timeout = minOf(attemptTimeoutsMs[minOf(attempt, attemptTimeoutsMs.lastIndex)].toLong(), left).toInt()
                onProgress("ZeroTier 正在连接 ${endpoint.hostString}:${endpoint.port}（第 ${attempt + 1} 次，剩余 ${left}ms）")
                val connectStarted = nowMs()
                socket.connect(endpoint, timeout)
                currentCoroutineContext().ensureActive()
                checkAttempt()
                val rtt = nowMs() - connectStarted
                onProgress("ZeroTier 目标 TCP 已连通（本次 ${rtt}ms，总计 ${nowMs() - started}ms）")
                return rtt
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                checkAttempt()
                // Refused ports, bad addresses, permission errors and closed/reconfigured nodes
                // are not peer-discovery delays. SSH authentication never enters this policy.
                if (error !is SocketTimeoutException && error !is NoRouteToHostException) throw error
                lastFailure = error
            } finally {
                runCatching { socket.close() }
                onSocketClosed(socket)
            }
            val rest = remaining()
            if (rest == 0L) throw expired()
            val wait = minOf(retryDelaysMs[minOf(attempt, retryDelaysMs.lastIndex)], rest)
            attempt++
            onProgress("ZeroTier 目标路径尚未就绪，正在重试（已等待 ${nowMs() - started}ms）")
            pause(wait)
        }
    }
}
