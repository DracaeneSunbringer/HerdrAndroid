package dev.herdr.android.zerotier

/** The handles below identify lwIP sockets. They are never Android OS file descriptors. */
internal interface ZtSocketApi {
    fun createSocket(): Long
    fun connectSocket(handle: Long, ip: String, port: Int, timeoutMs: Int)
    fun readSocket(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int
    fun writeSocket(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int
    fun closeSocket(handle: Long)
    fun shutdownSocket(handle: Long, how: Int)
    fun setReadTimeout(handle: Long, timeoutMs: Int)
    fun setNoDelay(handle: Long, enabled: Boolean)
    fun setKeepAlive(handle: Long, enabled: Boolean)
    fun localEndpoint(handle: Long): Array<String>
}

internal object HerdrZeroTierNative : ZtSocketApi {
    init { System.loadLibrary("herdr_zt_jni") }

    external fun validatePlanet(bytes: ByteArray): Int
    external fun validateIdentity(secret: String): Boolean
    external fun startNode(storagePath: String, planet: ByteArray)
    external fun stopNode()
    external fun joinNetwork(networkId: Long)
    /** nodeId, online, network status, transport ready, IPv4, last peer path, last event. */
    external fun snapshot(networkId: Long): Array<String>
    external fun exportNodeIdentity(): String
    external override fun createSocket(): Long
    external override fun connectSocket(handle: Long, ip: String, port: Int, timeoutMs: Int)
    external override fun readSocket(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int
    external override fun writeSocket(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int
    external override fun closeSocket(handle: Long)
    external override fun shutdownSocket(handle: Long, how: Int)
    external override fun setReadTimeout(handle: Long, timeoutMs: Int)
    external override fun setNoDelay(handle: Long, enabled: Boolean)
    external override fun setKeepAlive(handle: Long, enabled: Boolean)
    external override fun localEndpoint(handle: Long): Array<String>
}
