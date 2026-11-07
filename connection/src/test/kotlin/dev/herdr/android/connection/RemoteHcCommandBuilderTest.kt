package dev.herdr.android.connection

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RemoteHcCommandBuilderTest {
    @Test fun windowsConnectInheritsOsHandlesWithoutManagedReadersOrTextPipeline() {
        val command = RemoteHcCommandBuilder.windows("C:\\O'Brien\\Herdr Core\\hc.exe")
        assertTrue(command.startsWith("powershell.exe -NoLogo -NoProfile -NonInteractive"))
        val script = decode(command)
        assertTrue(script.contains("C:\\O''Brien\\Herdr Core\\hc.exe"))
        assertTrue(command.length < 8191)
        val connect = script.substringAfter("${'$'}connectStart=New-Object")
        assertTrue(connect.contains("${'$'}connectStart.FileName=${'$'}hc"))
        assertTrue(connect.contains("${'$'}connectStart.Arguments='connect'"))
        assertTrue(connect.contains("${'$'}connectStart.UseShellExecute=${'$'}false"))
        listOf("Input", "Output", "Error").forEach { stream ->
            assertTrue(connect.contains("${'$'}connectStart.RedirectStandard$stream=${'$'}false"))
            assertFalse(connect.contains("RedirectStandard$stream=${'$'}true"))
        }
        assertTrue(connect.contains("${'$'}connect.WaitForExit()"))
        assertTrue(connect.contains("exit ${'$'}connect.ExitCode"))
        assertFalse(connect.contains("CreateNoWindow"))
        assertFalse(script.contains("Add-Type"))
        assertFalse(script.contains("Console.OpenStandardInput"))
        assertFalse(script.contains("CopyToAsync"))
        assertFalse(script.contains("BaseStream"))
        assertTrue(script.contains("info.pid -gt 0"))
        assertFalse(script.contains("& ${'$'}hc connect")) // PS 5 would decode native stdout as text
        assertFalse(script.contains("/bin/sh"))
        assertFalse(script.contains("server start" + "\n"))
        assertFalse(script.contains("Invoke-Expression"))
    }

    @Test fun windowsProbesHaveSeparateClosedStdinAndDrainOutputBeforeReturning() {
        val script = decode(RemoteHcCommandBuilder.windows("C:/Herdr/hc.exe"))
        val probe = script.substringAfter("function Probe(").substringBefore("${'$'}hc=${'$'}null")
        assertTrue(probe.contains("${'$'}start.UseShellExecute=${'$'}false"))
        listOf("Input", "Output", "Error").forEach { stream ->
            assertTrue(probe.contains("${'$'}start.RedirectStandard$stream=${'$'}true"))
        }
        val started = probe.indexOf("[void]${'$'}probe.Start()")
        val stdinClosed = probe.indexOf("${'$'}probe.StandardInput.Close()")
        val outputRead = probe.indexOf("${'$'}probe.StandardOutput.ReadToEndAsync()")
        assertTrue(started >= 0 && stdinClosed > started && outputRead > stdinClosed)
        assertTrue(probe.contains("${'$'}probe.StandardError.ReadToEndAsync()"))
        assertTrue(probe.contains("${'$'}probe.WaitForExit(5000)"))
        assertTrue(script.contains("Probe ${'$'}p '--version'"))
        assertTrue(script.contains("Probe ${'$'}hc 'server status --json'"))
    }

    @Test fun rejectsLineBreakAndNulInjectionBeforeBuildingAnyCommand() {
        listOf("C:/hc.exe\nwhoami", "C:/hc.exe\rwhoami", "C:/hc\u0000.exe").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { RemoteHcCommandBuilder.windows(path) }
            assertThrows(IllegalArgumentException::class.java) { RemoteHcCommandBuilder.unix(path) }
        }
    }

    @Test fun unixSafelyQuotesPathsAndCanDiscoverCoreOutsideInteractivePath() {
        val command = RemoteHcCommandBuilder.unix("/home/a'b/hc")
        assertTrue(command.startsWith("sh -c '"))
        assertTrue(command.contains(".local/bin/herdr-core"))
        assertTrue(command.contains("exec"))
        assertFalse(RemoteHcCommandBuilder.unix("C:\\Users\\me\\hc.exe").contains("C:"))
        assertEquals(1, RemoteHcCommandBuilder.candidates(ConnectionProfile(platform = HostPlatform.WINDOWS)).size)
        assertEquals(listOf(HostPlatform.WINDOWS, HostPlatform.UNIX),
            RemoteHcCommandBuilder.candidates(ConnectionProfile(platform = HostPlatform.AUTO)).map { it.first })
    }

    @Test fun autoFallbackIsOnlyForAMissingPowerShellExecutable() {
        assertTrue(RemoteHcCommandBuilder.canFallbackToUnix("sh: powershell.exe: not found"))
        assertFalse(RemoteHcCommandBuilder.canFallbackToUnix("Permission denied"))
        assertFalse(RemoteHcCommandBuilder.canFallbackToUnix("hc.exe: not found"))
        assertFalse(RemoteHcCommandBuilder.canFallbackToUnix("powershell.exe: access denied"))
    }

    private fun decode(command: String): String {
        return Base64.getDecoder().decode(command.substringAfter("-EncodedCommand ")).toString(Charsets.UTF_16LE)
    }
}
