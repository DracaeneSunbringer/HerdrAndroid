package dev.herdr.android.connection

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SshHostKeyPinTest {
    // RFC 8032 public test key, encoded as an SSH string algorithm + string raw key.
    private val wire = Base64.getDecoder().decode("AAAAC3NzaC1lZDI1NTE5AAAAINdamAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1Ea")
    private val expected = "SHA256:bbXpuKG6zhzdmnxq256TlqzFBzRl2f6OOg722cYNbU8"

    @Test fun fingerprintMatchesOpenSshSha256WireBlobFormat() {
        assertEquals(expected, SshHostKeyPin.fingerprint(wire))
        assertEquals(expected, SshHostKeyPin.normalize("256 $expected test-host (ED25519)"))
        assertEquals(expected, SshHostKeyPin.normalize("$expected="))
        SshHostKeyPin.verify(expected, expected)
    }

    @Test fun unpinnedAndChangedHostsStopBeforeAuthentication() {
        assertEquals(expected, assertThrows(HostKeyNotTrustedException::class.java) { SshHostKeyPin.verify(null, expected) }.fingerprint)
        val changed = SshHostKeyPin.fingerprint(byteArrayOf(1, 2, 3))
        val failure = assertThrows(HostKeyChangedException::class.java) { SshHostKeyPin.verify(expected, changed) }
        assertEquals(expected, failure.expected)
        assertEquals(changed, failure.actual)
        assertThrows(IllegalArgumentException::class.java) { SshHostKeyPin.normalize("ssh-ed25519 AAAAC3 public-key") }
        assertThrows(IllegalArgumentException::class.java) { SshHostKeyPin.normalize("SHA256:short") }
    }
}
