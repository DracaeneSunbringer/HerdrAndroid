package dev.herdr.android.zerotier

import org.junit.Assert.*
import org.junit.Test

class ZeroTierIdentityTest {
    // Formatting fixtures only, deliberately not valid real ZeroTier credentials.
    private val public = "0123456789:0:" + "ab".repeat(64)
    private val secret = public + ":" + "cd".repeat(64)

    @Test fun fullSecretDerivesMatchingPublicIdentityAndNeverPrintsSecret() {
        val parsed = ZeroTierIdentity.parse("\uFEFF  ${secret.uppercase()}\r\n")
        assertEquals(secret, parsed.secret)
        assertEquals(public, parsed.public)
        assertEquals("0123456789", parsed.nodeId)
        assertFalse(parsed.toString().contains("cdcd"))
    }
    @Test fun optionalPublicLineMustMatchTheSecret() {
        assertEquals(public, ZeroTierIdentity.parse("$public\n$secret").public)
        assertThrows(IllegalArgumentException::class.java) { ZeroTierIdentity.parse("other public\n$secret") }
    }
    @Test fun sshKeysPublicOnlyAndMultipleSecretsAreRejected() {
        listOf(public, "-----BEGIN OPENSSH PRIVATE KEY-----", "cd".repeat(64), "$secret\n$secret").forEach {
            assertThrows(IllegalArgumentException::class.java) { ZeroTierIdentity.parse(it) }
        }
    }
    @Test fun networkIdKeepsUnsignedBitsAndRejectsMalformedValues() {
        assertEquals(0x123456789abcdef0L, ZeroTierIdentity.networkId("123456789abcdef0"))
        assertEquals(-1L, ZeroTierIdentity.networkId("0xffffffffffffffff"))
        listOf("0000000000000000", "1234", "123456789abcdefg").forEach {
            assertThrows(IllegalArgumentException::class.java) { ZeroTierIdentity.networkId(it) }
        }
    }
}
