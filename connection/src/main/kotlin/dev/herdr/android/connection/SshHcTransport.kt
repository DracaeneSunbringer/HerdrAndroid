package dev.herdr.android.connection

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Injectable boundary for unit tests: no Android context, real host or private key is required. */
interface SshHcConnector {
    /** Prepare the selected transport before the TCP probe; existing direct connectors need no setup. */
    suspend fun prepareTransport(profile: ConnectionProfile, onProgress: (String) -> Unit = {}) {}
    suspend fun probe(profile: ConnectionProfile): Long
    suspend fun authenticate(profile: ConnectionProfile): AuthenticatedSshConnection
    fun cancelPending()
}

interface AuthenticatedSshConnection : Closeable {
    val hostKeyFingerprint: String
    suspend fun exec(command: String): SshHcExecChannel
    suspend fun openSftp(): SshSftpChannel =
        throw UnsupportedOperationException("SFTP channel is not supported by this connection")
}

interface SshHcExecChannel : Closeable {
    val input: InputStream
    val output: OutputStream
    val stderr: InputStream
}

data class SftpEntryInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isRegularFile: Boolean,
    val size: Long,
    val mtimeSeconds: Long
)

interface SshSftpFile : Closeable {
    fun length(): Long
    fun readAt(fileOffset: Long, dst: ByteArray, dstOffset: Int, len: Int): Int
}

interface SshSftpChannel : Closeable {
    fun homeDirectory(): String
    fun statOrNull(path: String): SftpEntryInfo?
    fun listDir(path: String): List<SftpEntryInfo>
    fun openRead(path: String): SshSftpFile
}

class HostKeyNotTrustedException(val fingerprint: String) : java.io.IOException(
    "尚未信任 SSH 主机密钥，服务端指纹：$fingerprint"
)

class HostKeyChangedException(val expected: String, val actual: String) : java.io.IOException(
    "SSH 主机密钥与保存的指纹不一致。预期 $expected，实际 $actual"
)

class PrivateKeyUnavailableException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)
