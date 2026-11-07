package dev.herdr.android.connection

import java.security.MessageDigest
import java.util.Base64

object SshHostKeyPin {
    /** Hash the SSH wire public-key blob, NOT PublicKey.encoded's X.509 representation. */
    fun fingerprint(wireKey: ByteArray): String = "SHA256:" +
        Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(wireKey))

    fun normalize(value: String): String {
        val token = Regex("(?:^|\\s)(SHA256:[A-Za-z0-9+/]{43}=?)(?:$|\\s)")
            .find(value.trim())?.groupValues?.get(1)
            ?: throw IllegalArgumentException("请填写 SSH 服务端 SHA256 指纹，例如 ssh-keygen -lf 输出中的 SHA256: 字段。")
        val raw = token.substringAfter(':').trimEnd('=')
        require(Base64.getDecoder().decode(raw).size == 32) { "Invalid SHA256 host fingerprint" }
        return "SHA256:$raw"
    }

    fun verify(expected: String?, actual: String) {
        if (expected.isNullOrBlank()) throw HostKeyNotTrustedException(actual)
        val wanted = normalize(expected)
        val found = normalize(actual)
        if (!MessageDigest.isEqual(wanted.toByteArray(Charsets.US_ASCII), found.toByteArray(Charsets.US_ASCII))) {
            throw HostKeyChangedException(wanted, found)
        }
    }
}
