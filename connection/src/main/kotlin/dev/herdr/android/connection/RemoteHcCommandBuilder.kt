package dev.herdr.android.connection

import java.util.Base64

/** Only builds commands. Never installs/upgrades a runtime or changes the remote default shell. */
object RemoteHcCommandBuilder {
    fun candidates(profile: ConnectionProfile): List<Pair<HostPlatform, String>> = when (profile.platform) {
        HostPlatform.WINDOWS -> listOf(HostPlatform.WINDOWS to windows(profile.hcExecutablePath))
        HostPlatform.UNIX -> listOf(HostPlatform.UNIX to unix(profile.hcExecutablePath))
        HostPlatform.AUTO -> listOf(
            HostPlatform.WINDOWS to windows(profile.hcExecutablePath),
            HostPlatform.UNIX to unix(profile.hcExecutablePath)
        )
    }

    fun windows(executable: String): String {
        safePath(executable)
        val supplied = executable.replace("'", "''")
        // Probes get separate, immediately closed stdin so they cannot drain the queued HC hello.
        // The actual connect process MUST inherit OS handles under Windows ssh-shellhost.exe:
        // a managed Console stdin pump can read hello then stall on subsequent binary frames.
        val script = """
            ${'$'}ErrorActionPreference='Stop'
            ${'$'}ProgressPreference='SilentlyContinue'
            function Probe([string]${'$'}file, [string]${'$'}arguments) {
                ${'$'}start=New-Object System.Diagnostics.ProcessStartInfo
                ${'$'}start.FileName=${'$'}file
                ${'$'}start.Arguments=${'$'}arguments
                ${'$'}start.UseShellExecute=${'$'}false
                ${'$'}start.CreateNoWindow=${'$'}true
                ${'$'}start.RedirectStandardInput=${'$'}true
                ${'$'}start.RedirectStandardOutput=${'$'}true
                ${'$'}start.RedirectStandardError=${'$'}true
                ${'$'}probe=New-Object System.Diagnostics.Process
                ${'$'}probe.StartInfo=${'$'}start
                try {
                    [void]${'$'}probe.Start()
                    ${'$'}probe.StandardInput.Close()
                    ${'$'}stdout=${'$'}probe.StandardOutput.ReadToEndAsync()
                    ${'$'}stderr=${'$'}probe.StandardError.ReadToEndAsync()
                    if (-not ${'$'}probe.WaitForExit(5000)) { ${'$'}probe.Kill(); throw 'HC probe timeout' }
                    [Console]::Error.Write(${'$'}stderr.GetAwaiter().GetResult())
                    if (${'$'}probe.ExitCode -eq 0) { return ${'$'}stdout.GetAwaiter().GetResult() }
                    return ''
                } finally { ${'$'}probe.Dispose() }
            }
            ${'$'}hc=${'$'}null
            foreach (${'$'}p in @('$supplied',"${'$'}env:LOCALAPPDATA\Programs\herdr-core\herdr-core.exe","${'$'}env:LOCALAPPDATA\Programs\herdr-core\hc.exe")) {
                if (${'$'}p -and (Test-Path -LiteralPath ${'$'}p -PathType Leaf)) {
                    if ((Probe ${'$'}p '--version') -match '^hc\s') { ${'$'}hc=${'$'}p; break }
                }
            }
            if (-not ${'$'}hc) { [Console]::Error.WriteLine('HERDR_HC_MISSING'); exit 23 }
            ${'$'}info=Probe ${'$'}hc 'server status --json' | ConvertFrom-Json
            if (-not (${'$'}info.pid -gt 0)) { [Console]::Error.WriteLine('HERDR_HC_NOT_RUNNING'); exit 24 }
            ${'$'}connectStart=New-Object System.Diagnostics.ProcessStartInfo
            ${'$'}connectStart.FileName=${'$'}hc
            ${'$'}connectStart.Arguments='connect'
            ${'$'}connectStart.UseShellExecute=${'$'}false
            ${'$'}connectStart.RedirectStandardInput=${'$'}false
            ${'$'}connectStart.RedirectStandardOutput=${'$'}false
            ${'$'}connectStart.RedirectStandardError=${'$'}false
            ${'$'}connect=New-Object System.Diagnostics.Process
            ${'$'}connect.StartInfo=${'$'}connectStart
            try {
                [void]${'$'}connect.Start()
                ${'$'}connect.WaitForExit()
                exit ${'$'}connect.ExitCode
            } finally { ${'$'}connect.Dispose() }
        """.trimIndent()
        // UTF-16LE EncodedCommand is accepted by both common Windows OpenSSH default shells.
        // Keep this transparent script below cmd.exe's 8191-character command-line limit.
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        return "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encoded"
    }

    fun unix(executable: String): String {
        safePath(executable)
        // A newly selected Unix profile may still contain the contract's Windows path hint.
        val hint = executable.takeUnless { Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(it) }.orEmpty()
        val script = """
            hc=${quotePosix(hint)}
            if [ -z "${'$'}hc" ] || [ ! -x "${'$'}hc" ]; then
              hc=${'$'}(command -v herdr-core 2>/dev/null || true)
              if [ -z "${'$'}hc" ] && [ -x "${'$'}HOME/.local/bin/herdr-core" ]; then hc="${'$'}HOME/.local/bin/herdr-core"; fi
              if [ -z "${'$'}hc" ]; then hc=${'$'}(command -v hc 2>/dev/null || true); fi
            fi
            if [ -z "${'$'}hc" ]; then printf '%s\n' 'HERDR_HC_MISSING: herdr-core was not found.' >&2; exit 23; fi
            version=${'$'}("${'$'}hc" --version 2>/dev/null)
            case "${'$'}version" in 'hc '*) ;; *) printf '%s\n' 'HERDR_HC_MISSING: executable is not Herdr Core.' >&2; exit 23;; esac
            status=${'$'}("${'$'}hc" server status --json 2>/dev/null) || status=''
            if ! printf '%s' "${'$'}status" | grep -Eq '"pid"[[:space:]]*:[[:space:]]*[1-9][0-9]*'; then printf '%s\n' 'HERDR_HC_NOT_RUNNING: Start the intended hc session as this SSH user first.' >&2; exit 24; fi
            exec "${'$'}hc" connect
        """.trimIndent()
        return "sh -c ${quotePosix(script)}"
    }

    internal fun canFallbackToUnix(stderr: String): Boolean {
        val lower = stderr.lowercase()
        return "powershell.exe" in lower &&
            listOf("not found", "no such file", "command not found").any { it in lower }
    }

    private fun quotePosix(text: String) = "'" + text.replace("'", "'\"'\"'") + "'"
    private fun safePath(path: String) {
        require(path.none { it == '\u0000' || it == '\r' || it == '\n' }) { "Executable path contains control characters" }
    }
}
