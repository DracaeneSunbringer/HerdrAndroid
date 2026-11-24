package dev.herdr.android.connection

import dev.herdr.android.protocol.TranscriptAgentKind
import dev.herdr.android.protocol.TranscriptChunkResult
import dev.herdr.android.protocol.TranscriptLocation
import dev.herdr.android.protocol.TranscriptWireJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Lazy, read-only persistent SSH exec sidecar for incremental transcript reads (`agy`, `codex`, `claude`).
 *
 * - Opens zero extra TCP/ZeroTier sockets: reuses the existing [AuthenticatedSshConnection].
 * - Writes zero temporary files on the remote host: bootstraps strictly via stdin.
 * - Uses byte-offset seeking + tail windowing + host-side pre-filtering so idle polling uses 0
 *   bytes and live streaming only transfers new Markdown deltas (~1-6 KB/step).
 */
class RemoteTranscriptSidecar(
    private val ssh: AuthenticatedSshConnection,
    private val platform: HostPlatform,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val initTimeoutMs: Long = 18_000L,
    private val callTimeoutMs: Long = 10_000L
) : Closeable {

    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val requestIds = AtomicLong(1L)

    @Volatile private var channel: SshHcExecChannel? = null
    @Volatile private var reader: BufferedReader? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var stderrDrainJob: Job? = null
    private val stderrTail = StringBuilder()

    suspend fun locate(
        agentKind: TranscriptAgentKind,
        pid: Long?,
        cwd: String
    ): TranscriptLocation? {
        val req = JSONObject()
            .put("op", "locate")
            .put("agent", agentKind.wireName)
            .put("pid", pid ?: 0L)
            .put("cwd", cwd)
        val line = executeJsonRequest(req)
        return TranscriptWireJson.parseLocateResponse(line, agentKind)
    }

    suspend fun readTail(
        agentKind: TranscriptAgentKind,
        path: String,
        offset: Long,
        windowBytes: Long = DEFAULT_WINDOW_BYTES
    ): TranscriptChunkResult {
        val req = JSONObject()
            .put("op", "tail")
            .put("agent", agentKind.wireName)
            .put("path", path)
            .put("offset", offset.coerceAtLeast(0L))
            .put("windowBytes", windowBytes.coerceIn(65_536L, 8_388_608L))
        val line = executeJsonRequest(req)
        return TranscriptWireJson.parseChunkResponse(line, path)
    }

    suspend fun readHead(
        agentKind: TranscriptAgentKind,
        path: String,
        beforeOffset: Long,
        windowBytes: Long = DEFAULT_WINDOW_BYTES
    ): TranscriptChunkResult {
        val req = JSONObject()
            .put("op", "head")
            .put("agent", agentKind.wireName)
            .put("path", path)
            .put("beforeOffset", beforeOffset.coerceAtLeast(0L))
            .put("windowBytes", windowBytes.coerceIn(65_536L, 8_388_608L))
        val line = executeJsonRequest(req)
        return TranscriptWireJson.parseChunkResponse(line, path)
    }

    suspend fun readStepOutput(
        agentKind: TranscriptAgentKind,
        path: String,
        stepIndex: Int
    ): String {
        val req = JSONObject()
            .put("op", "step")
            .put("agent", agentKind.wireName)
            .put("path", path)
            .put("stepIndex", stepIndex)
        val line = executeJsonRequest(req)
        return TranscriptWireJson.parseStepResponse(line)
    }

    private suspend fun executeJsonRequest(payload: JSONObject): String = mutex.withLock {
        check(!closed.get()) { "Transcript sidecar is closed" }
        val reqId = requestIds.getAndIncrement()
        payload.put("id", reqId)
        val lineOut = payload.toString()

        var wasWarm = channel != null
        var attempt = 0
        while (true) {
            attempt++
            val (ch, br, bw) = ensureStartedLocked()
            val watchdog = scope.launch(Dispatchers.IO) {
                delay(callTimeoutMs)
                runCatching { ch.close() }
            }
            try {
                return withContext(Dispatchers.IO) {
                    bw.write(lineOut)
                    bw.write('\n'.code)
                    bw.flush()
                    readResponseLineBlocking(br, reqId)
                }
            } catch (err: CancellationException) {
                closeChannelLocked()
                throw err
            } catch (err: Exception) {
                val diag = snapshotStderr()
                closeChannelLocked()
                // Read-only sidecar calls can safely respawn once if a warm channel died between turns.
                if (wasWarm && attempt == 1 && !closed.get()) {
                    wasWarm = false
                    continue
                }
                if (diag.isNotBlank()) {
                    throw IOException("${err.message} (stderr: $diag)", err)
                }
                throw err
            } finally {
                watchdog.cancel()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("Unreachable")
    }

    private fun readResponseLineBlocking(br: BufferedReader, expectedId: Long): String {
        var skipped = 0
        while (skipped < 32) {
            val line = br.readLine() ?: throw EOFException("Transcript sidecar stream ended unexpectedly")
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
                skipped++
                continue
            }
            val obj = try {
                JSONObject(trimmed)
            } catch (_: Exception) {
                skipped++
                continue
            }
            if (!obj.has("id") || obj.optLong("id", -1L) == expectedId || !obj.optBoolean("ok", true)) {
                return trimmed
            }
            skipped++
        }
        throw IOException("Transcript sidecar did not return response for request #$expectedId")
    }

    private suspend fun ensureStartedLocked(): Triple<SshHcExecChannel, BufferedReader, BufferedWriter> {
        val existingCh = channel
        val existingBr = reader
        val existingBw = writer
        if (existingCh != null && existingBr != null && existingBw != null) {
            return Triple(existingCh, existingBr, existingBw)
        }

        val useUnix = platform == HostPlatform.UNIX
        val cmd = if (useUnix) unixBootstrapCommand() else windowsBootstrapCommand()
        val initLine = if (useUnix) unixInitLine() else windowsInitLine()

        stderrTailLock { stderrTail.setLength(0) }
        val ch = ssh.exec(cmd)
        val br = BufferedReader(InputStreamReader(ch.input, StandardCharsets.UTF_8))
        val bw = BufferedWriter(OutputStreamWriter(ch.output, StandardCharsets.UTF_8))
        val errJob = drainStderr(ch.stderr)

        val watchdog = scope.launch(Dispatchers.IO) {
            delay(initTimeoutMs)
            runCatching { ch.close() }
        }
        try {
            withContext(Dispatchers.IO) {
                bw.write(initLine)
                bw.write('\n'.code)
                bw.flush()

                var lines = 0
                var ready = false
                while (lines < 32) {
                    val line = br.readLine() ?: throw EOFException("Transcript sidecar exited during handshake")
                    val trimmed = line.trim()
                    lines++
                    if (!trimmed.startsWith("{")) continue
                    val obj = try {
                        JSONObject(trimmed)
                    } catch (_: Exception) {
                        continue
                    }
                    if (!obj.optBoolean("ok", false)) {
                        throw IOException(obj.optString("error").ifBlank { "Transcript sidecar startup error" })
                    }
                    if (obj.optBoolean("ready", false)) {
                        ready = true
                        break
                    }
                }
                if (!ready) {
                    throw IOException("Transcript sidecar failed to signal ready")
                }
            }
            channel = ch
            reader = br
            writer = bw
            stderrDrainJob = errJob
            return Triple(ch, br, bw)
        } catch (err: Throwable) {
            val diag = snapshotStderr()
            errJob.cancel()
            runCatching { bw.close() }
            runCatching { br.close() }
            runCatching { ch.close() }
            if (err is CancellationException) throw err
            if (diag.isNotBlank()) {
                throw IOException("Transcript sidecar init failed: ${err.message} ($diag)", err)
            }
            throw err
        } finally {
            watchdog.cancel()
        }
    }

    private fun drainStderr(stderr: InputStream): Job = scope.launch(Dispatchers.IO) {
        val buf = ByteArray(1024)
        try {
            while (true) {
                val n = stderr.read(buf)
                if (n < 0) break
                if (n > 0) {
                    val chunk = String(buf, 0, n, StandardCharsets.UTF_8)
                    stderrTailLock {
                        stderrTail.append(chunk)
                        if (stderrTail.length > 4096) {
                            stderrTail.delete(0, stderrTail.length - 4096)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private inline fun <T> stderrTailLock(block: () -> T): T = synchronized(stderrTail) { block() }
    private fun snapshotStderr(): String = stderrTailLock { stderrTail.toString().trim() }

    private fun closeChannelLocked() {
        stderrDrainJob?.cancel()
        stderrDrainJob = null
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { channel?.close() }
        writer = null
        reader = null
        channel = null
    }

    override fun close() {
        closed.set(true)
        closeChannelLocked()
    }

    companion object {
        const val DEFAULT_WINDOW_BYTES: Long = 2_097_152L

        /**
         * Tiny PowerShell bootstrap (< 400 chars) executed via `-EncodedCommand` so cmd.exe's
         * 8,191-char command line limit is never approached. Reads base64 payload byte-by-byte
         * from raw StandardInput until LF so zero bytes of Request 1 are over-buffered.
         */
        fun windowsBootstrapCommand(): String {
            val dollar = "$"
            val ps = """
                ${dollar}inS = [Console]::OpenStandardInput()
                ${dollar}ms = New-Object System.IO.MemoryStream
                while (${dollar}true) {
                    ${dollar}b = ${dollar}inS.ReadByte()
                    if (${dollar}b -lt 0 -or ${dollar}b -eq 10) { break }
                    if (${dollar}b -ne 13) { ${dollar}ms.WriteByte([byte]${dollar}b) }
                }
                if (${dollar}ms.Length -eq 0) { exit 1 }
                ${dollar}b64 = [System.Text.Encoding]::ASCII.GetString(${dollar}ms.ToArray()).Trim()
                ${dollar}code = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(${dollar}b64))
                Invoke-Expression ${dollar}code
            """.trimIndent()
            val encoded = Base64.getEncoder().encodeToString(ps.toByteArray(StandardCharsets.UTF_16LE))
            return "powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $encoded"
        }

        fun windowsInitLine(): String =
            Base64.getEncoder().encodeToString(windowsPayloadScript().toByteArray(StandardCharsets.UTF_8))

        fun unixBootstrapCommand(): String =
            "sh -lc 'export PATH=\"\$PATH:/opt/homebrew/bin:/usr/local/bin\"; " +
                "PY=\$(command -v python3 || command -v python) || { printf \"{\\\"ok\\\":false,\\\"error\\\":\\\"python3 not found\\\"}\\n\"; exit 0; }; " +
                "exec \"\$PY\" -u -c \"import sys,base64;exec(base64.b64decode(sys.stdin.readline()).decode(\\\"utf-8\\\"))\"'"

        fun unixInitLine(): String =
            Base64.getEncoder().encodeToString(unixPayloadScript().toByteArray(StandardCharsets.UTF_8))

        internal fun windowsPayloadScript(): String {
            val csharp = windowsCSharpSource()
            return buildString {
                appendLine("Add-Type -Language CSharp -TypeDefinition @'")
                appendLine(csharp)
                appendLine("'@")
                appendLine("[HerdrTranscriptSidecar.Runner]::Run()")
            }
        }

        // Pure C# 5 source so both Windows PowerShell 5.1 (csc.exe .NET 4.x) and PowerShell 7 compile without warning.
        internal fun windowsCSharpSource(): String = """
using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

namespace HerdrTranscriptSidecar {

    public static class Runner {
        private static readonly UTF8Encoding Utf8NoBom = new UTF8Encoding(false);
        private const int MaxChunkReadBytes = 4194304;
        private const int MaxSingleLineBytes = 16777216;
        private const int MaxStepOutputBytes = 32768;

        public static void Run() {
            using (var reader = new StreamReader(Console.OpenStandardInput(), Utf8NoBom))
            using (var writer = new StreamWriter(Console.OpenStandardOutput(), Utf8NoBom)) {
                writer.AutoFlush = true;
                writer.WriteLine("{\"ok\":true,\"ready\":true}");
                string line;
                while ((line = reader.ReadLine()) != null) {
                    string trimmed = line.Trim();
                    if (trimmed.Length == 0) continue;
                    string idStr = "null";
                    try {
                        object parsed = MiniJson.Parse(trimmed);
                        var req = parsed as Dictionary<string, object>;
                        if (req == null) throw new Exception("Invalid JSON request");
                        if (req.ContainsKey("id") && req["id"] != null) {
                            idStr = MiniJson.Serialize(req["id"]);
                        }
                        string op = MiniJson.GetStr(req, "op");
                        string agent = MiniJson.GetStr(req, "agent");
                        object res;
                        if (op == "locate") {
                            long pid = MiniJson.GetLong(req, "pid", 0);
                            string cwd = MiniJson.GetStr(req, "cwd");
                            res = HandleLocate(agent, pid, cwd);
                        } else if (op == "tail") {
                            string path = MiniJson.GetStr(req, "path");
                            long offset = MiniJson.GetLong(req, "offset", 0);
                            long win = MiniJson.GetLong(req, "windowBytes", 2097152);
                            res = HandleTail(agent, path, offset, win);
                        } else if (op == "head") {
                            string path = MiniJson.GetStr(req, "path");
                            long beforeOffset = MiniJson.GetLong(req, "beforeOffset", 0);
                            long win = MiniJson.GetLong(req, "windowBytes", 2097152);
                            res = HandleHead(agent, path, beforeOffset, win);
                        } else if (op == "step") {
                            string path = MiniJson.GetStr(req, "path");
                            int stepIndex = MiniJson.GetInt(req, "stepIndex", -1);
                            res = HandleStep(agent, path, stepIndex);
                        } else {
                            throw new Exception("Unknown op: " + op);
                        }
                        var env = res as Dictionary<string, object>;
                        env["ok"] = true;
                        if (req.ContainsKey("id")) env["id"] = req["id"];
                        writer.WriteLine(MiniJson.Serialize(env));
                    } catch (Exception ex) {
                        var err = new Dictionary<string, object>();
                        err["ok"] = false;
                        err["error"] = ex.Message ?? "error";
                        if (idStr != "null") {
                            long parsedId;
                            if (long.TryParse(idStr, out parsedId)) err["id"] = parsedId;
                        }
                        writer.WriteLine(MiniJson.Serialize(err));
                    }
                }
            }
        }

        private static string UserHome() {
            return Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        }

        private static Dictionary<string, object> HandleLocate(string agent, long pid, string cwd) {
            var res = new Dictionary<string, object>();
            res["found"] = false;
            var desc = pid > 0 ? WinProc.GetDescendantPids(pid) : new HashSet<int>();
            string home = UserHome();

            if (agent == "agy") {
                string agyRoot = Path.Combine(home, ".gemini", "antigravity-cli");
                string presenceDir = Path.Combine(agyRoot, "presence");
                if (!Directory.Exists(presenceDir)) return res;

                var lockedPaths = new List<string>();
                string[] lockFiles = Directory.GetFiles(presenceDir, "*.lock");
                for (int i = 0; i < lockFiles.Length; i++) {
                    if (WinProc.IsFileLocked(lockFiles[i])) {
                        lockedPaths.Add(lockFiles[i]);
                    }
                }

                string matchedLock = null;
                if (desc.Count > 0) {
                    for (int i = 0; i < lockedPaths.Count; i++) {
                        List<int> holders = WinProc.GetLockHolderPids(lockedPaths[i]);
                        for (int j = 0; j < holders.Count; j++) {
                            if (desc.Contains(holders[j])) {
                                matchedLock = lockedPaths[i];
                                break;
                            }
                        }
                        if (matchedLock != null) break;
                    }
                } else if (lockedPaths.Count == 1) {
                    matchedLock = lockedPaths[0];
                }

                if (matchedLock != null) {
                    string uuid = Path.GetFileNameWithoutExtension(matchedLock);
                    string logsDir = Path.Combine(agyRoot, "brain", uuid, ".system_generated", "logs");
                    string fullPath = Path.Combine(logsDir, "transcript_full.jsonl");
                    string compactPath = Path.Combine(logsDir, "transcript.jsonl");
                    string chosen = File.Exists(fullPath) || !File.Exists(compactPath) ? fullPath : compactPath;
                    long fsize = File.Exists(chosen) ? new FileInfo(chosen).Length : 0;
                    res["found"] = true;
                    res["agent"] = "agy";
                    res["sessionId"] = uuid;
                    res["path"] = chosen;
                    res["fileSize"] = fsize;
                }
                return res;
            }

            if (agent == "codex") {
                string codexRoot = Path.Combine(home, ".codex");
                string locksDir = Path.Combine(codexRoot, "thread-writer-locks");
                string sessionsDir = Path.Combine(codexRoot, "sessions");
                if (!Directory.Exists(sessionsDir)) return res;

                var lockedIds = new List<string>();
                if (Directory.Exists(locksDir)) {
                    string[] locks = Directory.GetFiles(locksDir, "*.lock");
                    for (int i = 0; i < locks.Length; i++) {
                        if (!WinProc.IsFileLocked(locks[i])) continue;
                        string tid = Path.GetFileNameWithoutExtension(locks[i]);
                        if (desc.Count > 0) {
                            List<int> holders = WinProc.GetLockHolderPids(locks[i]);
                            for (int j = 0; j < holders.Count; j++) {
                                if (desc.Contains(holders[j])) {
                                    string directRollout = FindRolloutById(sessionsDir, tid);
                                    if (directRollout != null) {
                                        return FoundLoc("codex", tid, directRollout);
                                    }
                                }
                            }
                        }
                        lockedIds.Add(tid);
                    }
                }

                // Fallback when Codex runs behind shared app-server or PID wasn't direct lock holder
                string normTargetCwd = NormalizePath(cwd);
                if (normTargetCwd.Length > 0) {
                    var candidates = new List<FileInfo>();
                    for (int i = 0; i < lockedIds.Count; i++) {
                        string rp = FindRolloutById(sessionsDir, lockedIds[i]);
                        if (rp != null && File.Exists(rp)) candidates.Add(new FileInfo(rp));
                    }
                    if (candidates.Count == 0 && desc.Count == 0 && lockedIds.Count <= 1) {
                        string[] recent = Directory.GetFiles(sessionsDir, "rollout-*.jsonl", SearchOption.AllDirectories);
                        for (int i = 0; i < recent.Length; i++) candidates.Add(new FileInfo(recent[i]));
                    }
                    candidates.Sort(delegate(FileInfo a, FileInfo b) {
                        return b.LastWriteTimeUtc.CompareTo(a.LastWriteTimeUtc);
                    });
                    string matchedPath = null;
                    string matchedId = "";
                    for (int i = 0; i < candidates.Count && i < 24; i++) {
                        string firstLine = ReadFirstLineUtf8(candidates[i].FullName);
                        if (firstLine == null) continue;
                        try {
                            var obj = MiniJson.Parse(firstLine) as Dictionary<string, object>;
                            if (obj != null && MiniJson.GetStr(obj, "type") == "session_meta") {
                                var payload = MiniJson.GetDict(obj, "payload");
                                if (payload != null) {
                                    string mcwd = NormalizePath(MiniJson.GetStr(payload, "cwd"));
                                    if (string.Equals(mcwd, normTargetCwd, StringComparison.OrdinalIgnoreCase)) {
                                        if (matchedPath != null) {
                                            // Ambiguous multiple locked Codex sessions in same cwd unless this one is much newer? Avoid guessing.
                                            return res;
                                        }
                                        matchedPath = candidates[i].FullName;
                                        matchedId = MiniJson.GetStr(payload, "id");
                                        if (matchedId.Length == 0) matchedId = Path.GetFileNameWithoutExtension(matchedPath);
                                    }
                                }
                            }
                        } catch { }
                    }
                    if (matchedPath != null) {
                        return FoundLoc("codex", matchedId, matchedPath);
                    }
                }
                return res;
            }

            if (agent == "claude") {
                string claudeRoot = Path.Combine(home, ".claude");
                string sessionsDir = Path.Combine(claudeRoot, "sessions");
                string projectsDir = Path.Combine(claudeRoot, "projects");
                if (desc.Count > 0 && Directory.Exists(sessionsDir)) {
                    foreach (int p in desc) {
                        string sf = Path.Combine(sessionsDir, p.ToString() + ".json");
                        if (!File.Exists(sf)) continue;
                        try {
                            string txt = File.ReadAllText(sf, Utf8NoBom);
                            var obj = MiniJson.Parse(txt) as Dictionary<string, object>;
                            string sid = MiniJson.GetStr(obj, "sessionId");
                            if (sid.Length > 0 && Directory.Exists(projectsDir)) {
                                string[] hits = Directory.GetFiles(projectsDir, sid + ".jsonl", SearchOption.AllDirectories);
                                if (hits.Length > 0) {
                                    return FoundLoc("claude", sid, hits[0]);
                                }
                            }
                        } catch { }
                    }
                }
                if (Directory.Exists(projectsDir) && !string.IsNullOrEmpty(cwd)) {
                    string slug = EncodeClaudeProjectSlug(cwd);
                    string pdir = Path.Combine(projectsDir, slug);
                    if (Directory.Exists(pdir)) {
                        string[] jsonls = Directory.GetFiles(pdir, "*.jsonl");
                        if (jsonls.Length == 1) {
                            return FoundLoc("claude", Path.GetFileNameWithoutExtension(jsonls[0]), jsonls[0]);
                        }
                    }
                }
                return res;
            }

            return res;
        }

        private static Dictionary<string, object> FoundLoc(string agent, string sid, string path) {
            var res = new Dictionary<string, object>();
            res["found"] = true;
            res["agent"] = agent;
            res["sessionId"] = sid;
            res["path"] = path;
            res["fileSize"] = File.Exists(path) ? new FileInfo(path).Length : 0L;
            return res;
        }

        private static string FindRolloutById(string sessionsDir, string id) {
            try {
                string[] hits = Directory.GetFiles(sessionsDir, "*" + id + "*.jsonl", SearchOption.AllDirectories);
                return hits.Length > 0 ? hits[0] : null;
            } catch {
                return null;
            }
        }

        private static string EncodeClaudeProjectSlug(string cwd) {
            var sb = new StringBuilder();
            for (int i = 0; i < cwd.Length; i++) {
                char ch = cwd[i];
                if (char.IsLetterOrDigit(ch)) sb.Append(ch);
                else sb.Append('-');
            }
            return sb.ToString();
        }

        private static string NormalizePath(string p) {
            if (string.IsNullOrEmpty(p)) return "";
            string norm = p.Trim().Replace('/', '\\');
            while (norm.Length > 3 && norm.EndsWith("\\")) {
                norm = norm.Substring(0, norm.Length - 1);
            }
            return norm;
        }

        private static string ReadFirstLineUtf8(string path) {
            try {
                using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                using (var sr = new StreamReader(fs, Utf8NoBom)) {
                    return sr.ReadLine();
                }
            } catch {
                return null;
            }
        }

        private static Dictionary<string, object> HandleTail(string agent, string path, long offset, long windowBytes) {
            var res = new Dictionary<string, object>();
            res["path"] = path;
            var events = new List<object>();
            res["events"] = events;
            if (string.IsNullOrEmpty(path) || !File.Exists(path)) {
                res["startOffset"] = 0L;
                res["nextOffset"] = 0L;
                res["fileSize"] = 0L;
                res["hasEarlier"] = false;
                res["reset"] = false;
                return res;
            }

            using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete)) {
                long fileSize = fs.Length;
                bool reset = false;
                long effOffset = offset;
                if (effOffset > fileSize) {
                    reset = true;
                    effOffset = 0;
                }
                long startOffset;
                bool hasEarlier;
                if (effOffset <= 0) {
                    if (fileSize <= windowBytes) {
                        startOffset = 0;
                        hasEarlier = false;
                    } else {
                        long rawStart = fileSize - windowBytes;
                        long aligned = AlignForwardAfterNewline(fs, rawStart, fileSize);
                        if (aligned < 0 || aligned >= fileSize) {
                            aligned = AlignBackToLineStart(fs, rawStart);
                        }
                        startOffset = aligned;
                        hasEarlier = startOffset > 0;
                    }
                } else {
                    startOffset = effOffset;
                    hasEarlier = false;
                }

                res["startOffset"] = startOffset;
                res["fileSize"] = fileSize;
                res["hasEarlier"] = hasEarlier;
                res["reset"] = reset;

                if (startOffset >= fileSize) {
                    res["nextOffset"] = startOffset;
                    return res;
                }

                long toRead = Math.Min(fileSize - startOffset, MaxChunkReadBytes);
                byte[] buf = ReadBytesAt(fs, startOffset, (int)toRead);
                int lastLf = Array.LastIndexOf(buf, (byte)10);
                if (lastLf < 0 && startOffset + buf.Length < fileSize) {
                    // Single line larger than 4MB: extend until next LF up to 16MB
                    long extCap = Math.Min(fileSize - startOffset, MaxSingleLineBytes);
                    if (extCap > buf.Length) {
                        buf = ReadBytesAt(fs, startOffset, (int)extCap);
                        lastLf = Array.LastIndexOf(buf, (byte)10);
                    }
                }
                if (lastLf < 0) {
                    // Wait for newline termination so incomplete JSONL line at EOF is not lost
                    res["nextOffset"] = startOffset;
                    return res;
                }

                long nextOffset = startOffset + lastLf + 1;
                res["nextOffset"] = nextOffset;
                ProjectLines(agent, buf, lastLf + 1, startOffset, events);
                return res;
            }
        }

        private static Dictionary<string, object> HandleHead(string agent, string path, long beforeOffset, long windowBytes) {
            var res = new Dictionary<string, object>();
            res["path"] = path;
            var events = new List<object>();
            res["events"] = events;
            if (string.IsNullOrEmpty(path) || !File.Exists(path) || beforeOffset <= 0) {
                res["startOffset"] = 0L;
                res["nextOffset"] = 0L;
                res["fileSize"] = File.Exists(path) ? new FileInfo(path).Length : 0L;
                res["hasEarlier"] = false;
                res["reset"] = false;
                return res;
            }

            using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete)) {
                long fileSize = fs.Length;
                long limit = Math.Min(beforeOffset, fileSize);
                if (limit <= 0) {
                    res["startOffset"] = 0L;
                    res["nextOffset"] = 0L;
                    res["fileSize"] = fileSize;
                    res["hasEarlier"] = false;
                    res["reset"] = false;
                    return res;
                }

                long rawStart = Math.Max(0, limit - windowBytes);
                long startOffset;
                if (rawStart == 0) {
                    startOffset = 0;
                } else {
                    long aligned = AlignForwardAfterNewline(fs, rawStart, limit);
                    if (aligned < 0 || aligned >= limit) {
                        aligned = AlignBackToLineStart(fs, rawStart);
                    }
                    startOffset = aligned;
                }

                long len = limit - startOffset;
                if (len > MaxSingleLineBytes) {
                    len = MaxSingleLineBytes;
                }
                byte[] buf = ReadBytesAt(fs, startOffset, (int)len);
                int lastLf = Array.LastIndexOf(buf, (byte)10);
                int validLen = lastLf >= 0 ? lastLf + 1 : buf.Length;
                long actualEnd = startOffset + validLen;

                res["startOffset"] = startOffset;
                res["nextOffset"] = actualEnd;
                res["fileSize"] = fileSize;
                res["hasEarlier"] = startOffset > 0;
                res["reset"] = false;
                ProjectLines(agent, buf, validLen, startOffset, events);
                return res;
            }
        }

        private static Dictionary<string, object> HandleStep(string agent, string path, int stepIndex) {
            var res = new Dictionary<string, object>();
            res["output"] = "";
            if (stepIndex < 0 || string.IsNullOrEmpty(path)) return res;
            if (agent == "agy") {
                try {
                    DirectoryInfo logsDir = Directory.GetParent(path);
                    DirectoryInfo sysGenDir = logsDir != null ? Directory.GetParent(logsDir.FullName) : null;
                    if (sysGenDir != null) {
                        string stepFile = Path.Combine(sysGenDir.FullName, "steps", stepIndex.ToString(), "output.txt");
                        if (File.Exists(stepFile)) {
                            res["output"] = ReadCappedUtf8(stepFile, MaxStepOutputBytes);
                            return res;
                        }
                    }
                } catch { }

                if (File.Exists(path)) {
                    try {
                        using (var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                        using (var sr = new StreamReader(fs, Utf8NoBom)) {
                            string ln;
                            while ((ln = sr.ReadLine()) != null) {
                                if (ln.IndexOf("\"step_index\":" + stepIndex, StringComparison.Ordinal) < 0 &&
                                    ln.IndexOf("\"step_index\": " + stepIndex, StringComparison.Ordinal) < 0) continue;
                                var obj = MiniJson.Parse(ln) as Dictionary<string, object>;
                                if (obj != null && MiniJson.GetInt(obj, "step_index", -1) == stepIndex &&
                                    MiniJson.GetStr(obj, "type") == "GENERIC") {
                                    string c = MiniJson.GetStr(obj, "content");
                                    if (c.Length == 0) c = MiniJson.GetStr(obj, "output");
                                    res["output"] = CapText(c, MaxStepOutputBytes);
                                    return res;
                                }
                            }
                        }
                    } catch { }
                }
            }
            return res;
        }

        private static string ReadCappedUtf8(string filePath, int maxBytes) {
            using (var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete)) {
                long total = fs.Length;
                int count = (int)Math.Min(total, maxBytes);
                byte[] buf = ReadBytesAt(fs, 0, count);
                string txt = Utf8NoBom.GetString(buf, 0, buf.Length);
                if (total > maxBytes) txt = txt + "\n… (已截断，仅展示前 32 KB)";
                return txt;
            }
        }

        private static byte[] ReadBytesAt(FileStream fs, long offset, int count) {
            if (count <= 0) return new byte[0];
            fs.Seek(offset, SeekOrigin.Begin);
            byte[] buf = new byte[count];
            int totalRead = 0;
            while (totalRead < count) {
                int r = fs.Read(buf, totalRead, count - totalRead);
                if (r <= 0) break;
                totalRead += r;
            }
            if (totalRead < count) {
                var trimmed = new byte[totalRead];
                Buffer.BlockCopy(buf, 0, trimmed, 0, totalRead);
                return trimmed;
            }
            return buf;
        }

        private static long AlignForwardAfterNewline(FileStream fs, long fromOffset, long limit) {
            fs.Seek(fromOffset, SeekOrigin.Begin);
            byte[] chunk = new byte[8192];
            long pos = fromOffset;
            while (pos < limit) {
                int toRead = (int)Math.Min(chunk.Length, limit - pos);
                int r = fs.Read(chunk, 0, toRead);
                if (r <= 0) break;
                int lf = Array.IndexOf(chunk, (byte)10, 0, r);
                if (lf >= 0) return pos + lf + 1;
                pos += r;
            }
            return -1;
        }

        private static long AlignBackToLineStart(FileStream fs, long fromOffset) {
            if (fromOffset <= 0) return 0;
            long pos = fromOffset;
            byte[] chunk = new byte[65536];
            while (pos > 0) {
                long start = Math.Max(0, pos - chunk.Length);
                int len = (int)(pos - start);
                fs.Seek(start, SeekOrigin.Begin);
                int r = fs.Read(chunk, 0, len);
                if (r <= 0) break;
                int lf = Array.LastIndexOf(chunk, (byte)10, r - 1, r);
                if (lf >= 0) return start + lf + 1;
                pos = start;
            }
            return 0;
        }

        private static void ProjectLines(string agent, byte[] buf, int length, long baseOffset, List<object> outEvents) {
            int lineStart = 0;
            for (int i = 0; i < length; i++) {
                if (buf[i] == 10) {
                    int lineLen = i - lineStart;
                    if (lineLen > 0 && buf[lineStart + lineLen - 1] == 13) lineLen--;
                    if (lineLen > 0) {
                        long absOff = baseOffset + lineStart;
                        string jsonStr = Utf8NoBom.GetString(buf, lineStart, lineLen);
                        if (absOff == 0 && jsonStr.Length > 0 && jsonStr[0] == '\uFEFF') {
                            jsonStr = jsonStr.Substring(1);
                        }
                        if (jsonStr.Trim().Length > 0) {
                            ProjectSingleLine(agent, jsonStr, absOff, outEvents);
                        }
                    }
                    lineStart = i + 1;
                }
            }
        }

        private static void ProjectSingleLine(string agent, string jsonStr, long lineOffset, List<object> outEvents) {
            Dictionary<string, object> obj = null;
            try {
                obj = MiniJson.Parse(jsonStr) as Dictionary<string, object>;
            } catch {
                return;
            }
            if (obj == null) return;

            if (agent == "agy") {
                ProjectAgy(obj, lineOffset, outEvents);
            } else if (agent == "codex") {
                ProjectCodex(obj, lineOffset, outEvents);
            } else if (agent == "claude") {
                ProjectClaude(obj, lineOffset, outEvents);
            }
        }

        private static void ProjectAgy(Dictionary<string, object> obj, long lineOffset, List<object> outEvents) {
            string type = MiniJson.GetStr(obj, "type");
            if (type == "SYSTEM_MESSAGE") return;
            if (type == "GENERIC") {
                string status = MiniJson.GetStr(obj, "status");
                string errText = MiniJson.GetStr(obj, "error");
                if (status == "ERROR" || errText.Length > 0) {
                    int sidx = MiniJson.GetInt(obj, "step_index", -1);
                    if (sidx >= 0) {
                        var ev = new Dictionary<string, object>();
                        ev["k"] = "tool_err";
                        ev["o"] = lineOffset;
                        ev["id"] = "step_" + sidx.ToString();
                        ev["err"] = true;
                        outEvents.Add(ev);
                    }
                }
                return;
            }
            if (type == "USER_INPUT") {
                string source = MiniJson.GetStr(obj, "source");
                string raw = MiniJson.GetStr(obj, "content");
                string modelChange = ExtractAgyModelChange(raw);
                string reqBody = ExtractTagBody(raw, "<USER_REQUEST>", "</USER_REQUEST>");
                if (reqBody == null) {
                    if (source.Length > 0 && source != "USER_EXPLICIT") {
                        if (modelChange.Length > 0) {
                            var mcEv = new Dictionary<string, object>();
                            mcEv["k"] = "tokens";
                            mcEv["o"] = lineOffset;
                            mcEv["m"] = modelChange;
                            outEvents.Add(mcEv);
                        }
                        return;
                    }
                    reqBody = StripXmlTagsBlock(raw);
                }
                reqBody = reqBody.Trim();
                if (reqBody.Length == 0 && modelChange.Length == 0) return;

                var ev = new Dictionary<string, object>();
                ev["k"] = "user";
                ev["o"] = lineOffset;
                int stepIdx = MiniJson.GetInt(obj, "step_index", -1);
                if (stepIdx >= 0) ev["s"] = stepIdx;
                string ts = MiniJson.GetStr(obj, "created_at");
                if (ts.Length > 0) ev["ts"] = ts;
                ev["text"] = CapText(reqBody, 32768);
                if (modelChange.Length > 0) ev["mc"] = modelChange;
                outEvents.Add(ev);
                return;
            }
            if (type == "PLANNER_RESPONSE") {
                int stepIdx = MiniJson.GetInt(obj, "step_index", -1);
                string status = MiniJson.GetStr(obj, "status");
                string ts = MiniJson.GetStr(obj, "created_at");
                string model = MiniJson.GetStr(obj, "model");
                string thinking = CapText(MiniJson.GetStr(obj, "thinking"), 24576);
                string md = MiniJson.GetStr(obj, "content");
                if (md.Length == 0) md = MiniJson.GetStr(obj, "response");
                md = CapText(md, 262144);
                string errMsg = MiniJson.GetStr(obj, "error");
                long inTok = MiniJson.GetLong(obj, "input_tokens", 0);
                long cacheTok = MiniJson.GetLong(obj, "cache_read_tokens", 0);
                long outTok = MiniJson.GetLong(obj, "output_tokens", 0);

                var tcOut = new List<object>();
                var rawCalls = MiniJson.GetList(obj, "tool_calls");
                if (rawCalls != null) {
                    for (int i = 0; i < rawCalls.Count; i++) {
                        var tc = rawCalls[i] as Dictionary<string, object>;
                        if (tc == null) continue;
                        string name = MiniJson.GetStr(tc, "name");
                        if (name.Length == 0) continue;
                        var args = MiniJson.GetDict(tc, "args");
                        int rStep = stepIdx >= 0 ? stepIdx + 1 + i : -1;
                        tcOut.Add(ProjectToolCall(name, args, rStep >= 0 ? "step_" + rStep.ToString() : null, rStep));
                    }
                }

                if (thinking.Length == 0 && md.Length == 0 && tcOut.Count == 0 && errMsg.Length == 0) return;
                var ev = new Dictionary<string, object>();
                ev["k"] = "assistant";
                ev["o"] = lineOffset;
                if (stepIdx >= 0) ev["s"] = stepIdx;
                if (ts.Length > 0) ev["ts"] = ts;
                if (status.Length > 0) ev["st"] = status;
                if (model.Length > 0) ev["m"] = model;
                if (thinking.Length > 0) ev["th"] = thinking;
                if (md.Length > 0) ev["md"] = md;
                if (inTok > 0) ev["in"] = inTok;
                if (cacheTok > 0) ev["cin"] = cacheTok;
                if (outTok > 0) ev["out"] = outTok;
                if (errMsg.Length > 0) ev["error"] = CapText(errMsg, 2048);
                if (tcOut.Count > 0) ev["tc"] = tcOut;
                outEvents.Add(ev);
            }
        }

        private static void ProjectCodex(Dictionary<string, object> obj, long lineOffset, List<object> outEvents) {
            string type = MiniJson.GetStr(obj, "type");
            var payload = MiniJson.GetDict(obj, "payload");
            if (payload == null) return;
            string ts = MiniJson.GetStr(obj, "timestamp");

            if (type == "turn_context") {
                string model = MiniJson.GetStr(payload, "model");
                string effort = MiniJson.GetStr(payload, "effort");
                if (model.Length > 0 || effort.Length > 0) {
                    var ev = new Dictionary<string, object>();
                    ev["k"] = "tokens";
                    ev["o"] = lineOffset;
                    if (model.Length > 0) ev["m"] = model;
                    if (effort.Length > 0) ev["ef"] = effort;
                    outEvents.Add(ev);
                }
                return;
            }
            if (type == "event_msg") {
                if (MiniJson.GetStr(payload, "type") == "token_count") {
                    var info = MiniJson.GetDict(payload, "info");
                    if (info == null) return;
                    var usage = MiniJson.GetDict(info, "last_token_usage") ?? MiniJson.GetDict(info, "total_token_usage");
                    if (usage == null) return;
                    var ev = new Dictionary<string, object>();
                    ev["k"] = "tokens";
                    ev["o"] = lineOffset;
                    ev["in"] = MiniJson.GetLong(usage, "input_tokens", 0);
                    ev["cin"] = MiniJson.GetLong(usage, "cached_input_tokens", 0);
                    ev["out"] = MiniJson.GetLong(usage, "output_tokens", 0);
                    outEvents.Add(ev);
                }
                return;
            }
            if (type != "response_item") return;

            string ptype = MiniJson.GetStr(payload, "type");
            if (ptype == "message") {
                string role = MiniJson.GetStr(payload, "role");
                var contentList = MiniJson.GetList(payload, "content");
                var sb = new StringBuilder();
                if (contentList != null) {
                    for (int i = 0; i < contentList.Count; i++) {
                        var item = contentList[i] as Dictionary<string, object>;
                        if (item == null) continue;
                        string itype = MiniJson.GetStr(item, "type");
                        if (role == "user") {
                            if (itype == "input_text" || itype == "text") {
                                string t = MiniJson.GetStr(item, "text");
                                if (t.Length > 0) {
                                    if (sb.Length > 0) sb.Append('\n');
                                    sb.Append(t);
                                }
                            } else if (itype == "input_image") {
                                if (sb.Length > 0) sb.Append(' ');
                                sb.Append("[📎 图片]");
                            }
                        } else if (role == "assistant") {
                            if (itype == "output_text" || itype == "text") {
                                string t = MiniJson.GetStr(item, "text");
                                if (t.Length > 0) {
                                    if (sb.Length > 0) sb.Append("\n\n");
                                    sb.Append(t);
                                }
                            }
                        }
                    }
                }
                string body = sb.ToString().Trim();
                if (body.Length == 0) return;

                if (role == "user") {
                    if (body.StartsWith("# AGENTS.md", StringComparison.Ordinal) ||
                        body.StartsWith("<environment_context>", StringComparison.Ordinal) ||
                        body.StartsWith("<permissions instructions>", StringComparison.Ordinal) ||
                        body.StartsWith("<collaboration_mode>", StringComparison.Ordinal) ||
                        body.StartsWith("<INSTRUCTIONS>", StringComparison.Ordinal)) {
                        return;
                    }
                    int reqMarker = body.IndexOf("## My request:", StringComparison.Ordinal);
                    if (reqMarker >= 0 && body.StartsWith("# Files mentioned", StringComparison.Ordinal)) {
                        body = body.Substring(reqMarker + "## My request:".Length).Trim();
                    }
                    if (body.Length == 0) return;
                    var ev = new Dictionary<string, object>();
                    ev["k"] = "user";
                    ev["o"] = lineOffset;
                    if (ts.Length > 0) ev["ts"] = ts;
                    ev["text"] = CapText(body, 32768);
                    outEvents.Add(ev);
                } else if (role == "assistant") {
                    var ev = new Dictionary<string, object>();
                    ev["k"] = "assistant";
                    ev["o"] = lineOffset;
                    if (ts.Length > 0) ev["ts"] = ts;
                    ev["md"] = CapText(body, 262144);
                    outEvents.Add(ev);
                }
                return;
            }
            if (ptype == "reasoning") {
                var sumList = MiniJson.GetList(payload, "summary");
                var th = new StringBuilder();
                if (sumList != null) {
                    for (int i = 0; i < sumList.Count; i++) {
                        var sObj = sumList[i] as Dictionary<string, object>;
                        string t = sObj != null ? MiniJson.GetStr(sObj, "text") : (sumList[i] as string ?? "");
                        if (t.Length > 0) {
                            if (th.Length > 0) th.Append("\n\n");
                            th.Append(t);
                        }
                    }
                }
                string thText = th.ToString().Trim();
                if (thText.Length == 0) return;
                var ev = new Dictionary<string, object>();
                ev["k"] = "assistant";
                ev["o"] = lineOffset;
                if (ts.Length > 0) ev["ts"] = ts;
                ev["th"] = CapText(thText, 24576);
                outEvents.Add(ev);
                return;
            }
            if (ptype == "function_call" || ptype == "custom_tool_call") {
                string name = MiniJson.GetStr(payload, "name");
                string callId = MiniJson.GetStr(payload, "call_id");
                Dictionary<string, object> args = null;
                string rawArgs = ptype == "function_call" ? MiniJson.GetStr(payload, "arguments") : MiniJson.GetStr(payload, "input");
                if (ptype == "function_call" && rawArgs.StartsWith("{")) {
                    try { args = MiniJson.Parse(rawArgs) as Dictionary<string, object>; } catch { }
                } else if (ptype == "custom_tool_call" && rawArgs.Length > 0) {
                    args = new Dictionary<string, object>();
                    args["patch"] = rawArgs;
                }
                if (name.Length > 0) {
                    var ev = new Dictionary<string, object>();
                    ev["k"] = "assistant";
                    ev["o"] = lineOffset;
                    if (ts.Length > 0) ev["ts"] = ts;
                    var list = new List<object>();
                    list.Add(ProjectToolCall(name, args, callId.Length > 0 ? callId : null, -1));
                    ev["tc"] = list;
                    outEvents.Add(ev);
                }
                return;
            }
            if (ptype == "function_call_output" || ptype == "custom_tool_call_output") {
                string callId = MiniJson.GetStr(payload, "call_id");
                string status = MiniJson.GetStr(payload, "status");
                string outStr = MiniJson.GetStr(payload, "output");
                bool failed = status == "failed" || IsNonZeroCodexExit(outStr);
                if (failed && callId.Length > 0) {
                    var ev = new Dictionary<string, object>();
                    ev["k"] = "tool_err";
                    ev["o"] = lineOffset;
                    ev["id"] = callId;
                    ev["err"] = true;
                    outEvents.Add(ev);
                }
            }
        }

        private static bool IsNonZeroCodexExit(string output) {
            if (string.IsNullOrEmpty(output)) return false;
            int idx = output.IndexOf("Process exited with code ", StringComparison.Ordinal);
            if (idx >= 0) {
                int start = idx + "Process exited with code ".Length;
                int end = start;
                while (end < output.Length && char.IsDigit(output[end])) end++;
                if (end > start && output.Substring(start, end - start) != "0") return true;
            }
            return false;
        }

        private static void ProjectClaude(Dictionary<string, object> obj, long lineOffset, List<object> outEvents) {
            string type = MiniJson.GetStr(obj, "type");
            if (MiniJson.GetBool(obj, "isMeta") || MiniJson.GetBool(obj, "isCompactSummary")) return;
            string ts = MiniJson.GetStr(obj, "timestamp");
            var msg = MiniJson.GetDict(obj, "message");
            if (msg == null) return;

            if (type == "user") {
                object rawContent = msg.ContainsKey("content") ? msg["content"] : null;
                var userTextSb = new StringBuilder();
                if (rawContent is string) {
                    userTextSb.Append((string)rawContent);
                } else if (rawContent is List<object>) {
                    var arr = (List<object>)rawContent;
                    for (int i = 0; i < arr.Count; i++) {
                        var item = arr[i] as Dictionary<string, object>;
                        if (item == null) continue;
                        string itype = MiniJson.GetStr(item, "type");
                        if (itype == "tool_result") {
                            if (MiniJson.GetBool(item, "is_error")) {
                                string tid = MiniJson.GetStr(item, "tool_use_id");
                                if (tid.Length > 0) {
                                    var errEv = new Dictionary<string, object>();
                                    errEv["k"] = "tool_err";
                                    errEv["o"] = lineOffset;
                                    errEv["id"] = tid;
                                    errEv["err"] = true;
                                    outEvents.Add(errEv);
                                }
                            }
                        } else if (itype == "text") {
                            string t = MiniJson.GetStr(item, "text");
                            if (t.Length > 0) {
                                if (userTextSb.Length > 0) userTextSb.Append('\n');
                                userTextSb.Append(t);
                            }
                        } else if (itype == "image") {
                            if (userTextSb.Length > 0) userTextSb.Append(' ');
                            userTextSb.Append("[📎 图片]");
                        }
                    }
                }
                string cleanUser = StripTagPairs(userTextSb.ToString(), "<system-reminder>", "</system-reminder>");
                cleanUser = StripTagPairs(cleanUser, "<local-command-caveat>", "</local-command-caveat>").Trim();
                if (cleanUser.Length > 0) {
                    var ev = new Dictionary<string, object>();
                    ev["k"] = "user";
                    ev["o"] = lineOffset;
                    if (ts.Length > 0) ev["ts"] = ts;
                    ev["text"] = CapText(cleanUser, 32768);
                    outEvents.Add(ev);
                }
                return;
            }

            if (type == "assistant") {
                string model = MiniJson.GetStr(msg, "model");
                var usage = MiniJson.GetDict(msg, "usage");
                long inTok = usage != null ? MiniJson.GetLong(usage, "input_tokens", 0) : 0;
                long cacheTok = usage != null ? MiniJson.GetLong(usage, "cache_read_input_tokens", 0) : 0;
                long outTok = usage != null ? MiniJson.GetLong(usage, "output_tokens", 0) : 0;

                var thSb = new StringBuilder();
                var mdSb = new StringBuilder();
                var tcList = new List<object>();

                var contentList = MiniJson.GetList(msg, "content");
                if (contentList != null) {
                    for (int i = 0; i < contentList.Count; i++) {
                        var item = contentList[i] as Dictionary<string, object>;
                        if (item == null) continue;
                        string itype = MiniJson.GetStr(item, "type");
                        if (itype == "thinking") {
                            string th = MiniJson.GetStr(item, "thinking");
                            if (th.Length > 0) {
                                if (thSb.Length > 0) thSb.Append("\n\n");
                                thSb.Append(th);
                            }
                        } else if (itype == "text") {
                            string tx = MiniJson.GetStr(item, "text");
                            if (tx.Length > 0) {
                                if (mdSb.Length > 0) mdSb.Append("\n\n");
                                mdSb.Append(tx);
                            }
                        } else if (itype == "tool_use") {
                            string tid = MiniJson.GetStr(item, "id");
                            string tname = MiniJson.GetStr(item, "name");
                            var input = MiniJson.GetDict(item, "input");
                            if (tname.Length > 0) {
                                tcList.Add(ProjectToolCall(tname, input, tid.Length > 0 ? tid : null, -1));
                            }
                        }
                    }
                }
                string thText = thSb.ToString().Trim();
                string mdText = mdSb.ToString().Trim();
                if (thText.Length == 0 && mdText.Length == 0 && tcList.Count == 0) return;

                var ev = new Dictionary<string, object>();
                ev["k"] = "assistant";
                ev["o"] = lineOffset;
                if (ts.Length > 0) ev["ts"] = ts;
                if (model.Length > 0) ev["m"] = model;
                if (thText.Length > 0) ev["th"] = CapText(thText, 24576);
                if (mdText.Length > 0) ev["md"] = CapText(mdText, 262144);
                if (inTok > 0) ev["in"] = inTok;
                if (cacheTok > 0) ev["cin"] = cacheTok;
                if (outTok > 0) ev["out"] = outTok;
                if (tcList.Count > 0) ev["tc"] = tcList;
                outEvents.Add(ev);
            }
        }

        private static Dictionary<string, object> ProjectToolCall(string name, Dictionary<string, object> args, string callId, int resultStep) {
            var tc = new Dictionary<string, object>();
            if (!string.IsNullOrEmpty(callId)) tc["id"] = callId;
            tc["n"] = name;
            if (resultStep >= 0) tc["rs"] = resultStep;

            string primaryTarget = "";
            string detail = "";
            string changedFile = null;

            string lower = name.ToLowerInvariant();
            if (lower == "read_file" || lower == "view_file" || lower == "read") {
                string fp = CleanArg(MiniJson.GetFirstStr(args, "Path", "file_path", "path", "AbsolutePath"));
                primaryTarget = BaseName(fp);
                string s = CleanArg(MiniJson.GetFirstStr(args, "StartLine", "offset"));
                string e = CleanArg(MiniJson.GetFirstStr(args, "EndLine", "limit"));
                if (s.Length > 0 || e.Length > 0) detail = "L" + s + (e.Length > 0 ? ".." + e : "");
            } else if (lower == "edit_file" || lower == "replace_file_content" || lower == "multi_replace_file_content" ||
                       lower == "write_file" || lower == "write_to_file" || lower == "edit" || lower == "multiedit" || lower == "write") {
                string fp = CleanArg(MiniJson.GetFirstStr(args, "Path", "TargetFile", "file_path", "path"));
                primaryTarget = BaseName(fp);
                if (primaryTarget.Length > 0) changedFile = primaryTarget;
                string rep = CleanArg(MiniJson.GetFirstStr(args, "Replacement", "ReplacementContent", "new_string", "Content", "content"));
                string tgt = CleanArg(MiniJson.GetFirstStr(args, "Target", "TargetContent", "old_string"));
                if (tgt.Length > 0 || rep.Length > 0) {
                    int delLines = tgt.Length > 0 ? CountLines(tgt) : 0;
                    int addLines = rep.Length > 0 ? CountLines(rep) : 0;
                    detail = "+" + addLines + (delLines > 0 ? " -" + delLines : "") + " 行";
                }
            } else if (lower == "apply_patch") {
                string patch = CleanArg(MiniJson.GetFirstStr(args, "patch", "input"));
                string pf = ExtractPatchFile(patch);
                primaryTarget = pf.Length > 0 ? BaseName(pf) : "patch";
                if (pf.Length > 0) changedFile = primaryTarget;
                int add = 0; int del = 0;
                CountPatchDiff(patch, out add, out del);
                if (add > 0 || del > 0) detail = "+" + add + " -" + del + " 行";
            } else if (lower == "run_command" || lower == "exec_command" || lower == "bash" || lower == "shell" || lower == "shell_command") {
                string summary = CleanArg(MiniJson.GetFirstStr(args, "toolSummary", "description"));
                string cmd = CleanArg(MiniJson.GetFirstStr(args, "CommandLine", "cmd", "command"));
                primaryTarget = summary.Length > 0 ? Trunc(FirstLine(summary), 90) : Trunc(FirstLine(cmd), 90);
                if (cmd.Length > 0 && summary.Length > 0) detail = Trunc(FirstLine(cmd), 140);
            } else if (lower == "grep_search" || lower == "find_by_name" || lower == "grep" || lower == "glob" || lower == "search_web" || lower == "websearch") {
                string q = CleanArg(MiniJson.GetFirstStr(args, "Query", "Pattern", "pattern", "query"));
                string sp = CleanArg(MiniJson.GetFirstStr(args, "SearchPath", "SearchDirectory", "path"));
                primaryTarget = Trunc(q, 80);
                if (sp.Length > 0) detail = BaseName(sp);
            } else if (lower == "list_dir" || lower == "ls") {
                string dp = CleanArg(MiniJson.GetFirstStr(args, "DirectoryPath", "path"));
                primaryTarget = BaseName(dp);
            } else if (lower == "invoke_subagent" || lower == "agent" || lower == "task" || lower == "send_message") {
                primaryTarget = Trunc(CleanArg(MiniJson.GetFirstStr(args, "Role", "subagent_type", "TypeName", "Recipient", "description")), 80);
            } else if (args != null) {
                foreach (var kv in args) {
                    if (kv.Value is string) {
                        string v = CleanArg((string)kv.Value);
                        if (v.Length > 0 && v.Length <= 160) {
                            primaryTarget = Trunc(FirstLine(v), 80);
                            break;
                        }
                    }
                }
            }

            if (primaryTarget.Length > 0) tc["t"] = primaryTarget;
            if (detail.Length > 0) tc["d"] = detail;
            if (!string.IsNullOrEmpty(changedFile)) tc["f"] = changedFile;
            return tc;
        }

        private static string CleanArg(string raw) {
            if (string.IsNullOrEmpty(raw)) return "";
            string t = raw.Trim();
            if (t.Length >= 2 && t[0] == '"' && t[t.Length - 1] == '"') {
                try {
                    object unq = MiniJson.Parse(t);
                    if (unq is string) return ((string)unq).Trim();
                } catch { }
            }
            return t;
        }

        private static string BaseName(string path) {
            if (string.IsNullOrEmpty(path)) return "";
            string norm = path.TrimEnd('\\', '/');
            int idx = Math.Max(norm.LastIndexOf('/'), norm.LastIndexOf('\\'));
            return idx >= 0 && idx + 1 < norm.Length ? norm.Substring(idx + 1) : norm;
        }

        private static string FirstLine(string s) {
            if (string.IsNullOrEmpty(s)) return "";
            int idx = s.IndexOfAny(new char[] { '\r', '\n' });
            return (idx >= 0 ? s.Substring(0, idx) : s).Trim();
        }

        private static string Trunc(string s, int max) {
            if (string.IsNullOrEmpty(s) || s.Length <= max) return s ?? "";
            return s.Substring(0, max - 1) + "…";
        }

        private static int CountLines(string s) {
            if (string.IsNullOrEmpty(s)) return 0;
            int n = 1;
            for (int i = 0; i < s.Length; i++) if (s[i] == '\n') n++;
            return n;
        }

        private static string ExtractPatchFile(string patch) {
            if (string.IsNullOrEmpty(patch)) return "";
            string[] markers = new string[] { "*** Update File: ", "*** Add File: ", "*** Delete File: " };
            for (int m = 0; m < markers.Length; m++) {
                int idx = patch.IndexOf(markers[m], StringComparison.Ordinal);
                if (idx >= 0) {
                    int s = idx + markers[m].Length;
                    int e = patch.IndexOfAny(new char[] { '\r', '\n' }, s);
                    return (e > s ? patch.Substring(s, e - s) : patch.Substring(s)).Trim();
                }
            }
            return "";
        }

        private static void CountPatchDiff(string patch, out int add, out int del) {
            add = 0; del = 0;
            if (string.IsNullOrEmpty(patch)) return;
            using (var sr = new StringReader(patch)) {
                string ln;
                while ((ln = sr.ReadLine()) != null) {
                    if (ln.StartsWith("+") && !ln.StartsWith("+++")) add++;
                    else if (ln.StartsWith("-") && !ln.StartsWith("---")) del++;
                }
            }
        }

        private static string ExtractAgyModelChange(string raw) {
            if (string.IsNullOrEmpty(raw)) return "";
            string body = ExtractTagBody(raw, "<USER_SETTINGS_CHANGE>", "</USER_SETTINGS_CHANGE>");
            if (string.IsNullOrEmpty(body)) return "";
            int toIdx = body.IndexOf(" to ", StringComparison.Ordinal);
            if (toIdx < 0) return "";
            int endIdx = body.IndexOf('.', toIdx + 4);
            string cand = (endIdx > toIdx ? body.Substring(toIdx + 4, endIdx - toIdx - 4) : body.Substring(toIdx + 4)).Trim().Trim('\'', '"', '`');
            return cand;
        }

        private static string ExtractTagBody(string raw, string openTag, string closeTag) {
            int s = raw.IndexOf(openTag, StringComparison.Ordinal);
            if (s < 0) return null;
            int contentStart = s + openTag.Length;
            int e = raw.IndexOf(closeTag, contentStart, StringComparison.Ordinal);
            if (e < 0) return raw.Substring(contentStart);
            return raw.Substring(contentStart, e - contentStart);
        }

        private static string StripTagPairs(string raw, string openTag, string closeTag) {
            string cur = raw;
            while (true) {
                int s = cur.IndexOf(openTag, StringComparison.Ordinal);
                if (s < 0) break;
                int e = cur.IndexOf(closeTag, s + openTag.Length, StringComparison.Ordinal);
                if (e < 0) {
                    cur = cur.Substring(0, s);
                    break;
                }
                cur = cur.Substring(0, s) + cur.Substring(e + closeTag.Length);
            }
            return cur;
        }

        private static string StripXmlTagsBlock(string raw) {
            string s = StripTagPairs(raw, "<ADDITIONAL_METADATA>", "</ADDITIONAL_METADATA>");
            s = StripTagPairs(s, "<USER_SETTINGS_CHANGE>", "</USER_SETTINGS_CHANGE>");
            return s;
        }

        private static string CapText(string s, int maxChars) {
            if (string.IsNullOrEmpty(s)) return "";
            if (s.Length <= maxChars) return s;
            return s.Substring(0, maxChars) + "\n… (内容已截断)";
        }
    }

    public static class WinProc {
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr CreateToolhelp32Snapshot(uint dwFlags, uint th32ProcessID);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool Process32FirstW(IntPtr hSnapshot, ref PROCESSENTRY32 lppe);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool Process32NextW(IntPtr hSnapshot, ref PROCESSENTRY32 lppe);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool CloseHandle(IntPtr hObject);

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct PROCESSENTRY32 {
            public uint dwSize;
            public uint cntUsage;
            public uint th32ProcessID;
            public IntPtr th32DefaultHeapID;
            public uint th32ModuleID;
            public uint cntThreads;
            public uint th32ParentProcessID;
            public int pcPriClassBase;
            public uint dwFlags;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)]
            public string szExeFile;
        }

        [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)]
        private static extern int RmStartSession(out uint pSessionHandle, int dwSessionFlags, StringBuilder strSessionKey);

        [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)]
        private static extern int RmRegisterResources(
            uint pSessionHandle,
            uint nFiles,
            string[] rgsFilenames,
            uint nApplications,
            [In] RM_UNIQUE_PROCESS[] rgApplications,
            uint nServices,
            string[] rgsServiceNames);

        [DllImport("rstrtmgr.dll", CharSet = CharSet.Unicode)]
        private static extern int RmGetList(
            uint dwSessionHandle,
            out uint pnProcInfoNeeded,
            ref uint pnProcInfo,
            [In, Out] RM_PROCESS_INFO[] rgAffectedApps,
            ref uint lpdwRebootReasons);

        [DllImport("rstrtmgr.dll")]
        private static extern int RmEndSession(uint pSessionHandle);

        [StructLayout(LayoutKind.Sequential)]
        private struct RM_FILETIME {
            public uint dwLowDateTime;
            public uint dwHighDateTime;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct RM_UNIQUE_PROCESS {
            public int dwProcessId;
            public RM_FILETIME ProcessStartTime;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct RM_PROCESS_INFO {
            public RM_UNIQUE_PROCESS Process;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
            public string strAppName;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 64)]
            public string strServiceShortName;
            public int ApplicationType;
            public uint AppStatus;
            public uint TSSessionId;
            [MarshalAs(UnmanagedType.Bool)]
            public bool bRestartable;
        }

        public static bool IsFileLocked(string path) {
            try {
                using (var fs = new FileStream(path, FileMode.Open, FileAccess.ReadWrite, FileShare.None)) {
                    return false;
                }
            } catch (IOException) {
                return true;
            } catch (UnauthorizedAccessException) {
                return true;
            } catch {
                return false;
            }
        }

        public static HashSet<int> GetDescendantPids(long rootPid) {
            var result = new HashSet<int>();
            if (rootPid <= 0) return result;
            int root = (int)rootPid;
            result.Add(root);

            var childrenByParent = new Dictionary<int, List<int>>();
            IntPtr snap = CreateToolhelp32Snapshot(2, 0);
            if (snap == IntPtr.Zero || snap == new IntPtr(-1)) return result;
            try {
                var pe = new PROCESSENTRY32();
                pe.dwSize = (uint)Marshal.SizeOf(typeof(PROCESSENTRY32));
                if (Process32FirstW(snap, ref pe)) {
                    do {
                        int p = (int)pe.th32ProcessID;
                        int pp = (int)pe.th32ParentProcessID;
                        if (p > 0 && pp > 0 && p != pp) {
                            List<int> list;
                            if (!childrenByParent.TryGetValue(pp, out list)) {
                                list = new List<int>();
                                childrenByParent[pp] = list;
                            }
                            list.Add(p);
                        }
                    } while (Process32NextW(snap, ref pe));
                }
            } finally {
                CloseHandle(snap);
            }

            var queue = new Queue<int>();
            queue.Enqueue(root);
            while (queue.Count > 0) {
                int cur = queue.Dequeue();
                List<int> kids;
                if (childrenByParent.TryGetValue(cur, out kids)) {
                    for (int i = 0; i < kids.Count; i++) {
                        if (result.Add(kids[i])) {
                            queue.Enqueue(kids[i]);
                        }
                    }
                }
            }
            return result;
        }

        public static List<int> GetLockHolderPids(string path) {
            var pids = new List<int>();
            uint handle = 0;
            var key = new StringBuilder(Guid.NewGuid().ToString("N"));
            int startRc = RmStartSession(out handle, 0, key);
            if (startRc != 0) return pids;
            try {
                string[] files = new string[] { path };
                int regRc = RmRegisterResources(handle, 1, files, 0, null, 0, null);
                if (regRc != 0) return pids;

                uint needed = 0;
                uint count = 0;
                uint reasons = 0;
                int listRc = RmGetList(handle, out needed, ref count, null, ref reasons);
                if (listRc == 234 && needed > 0) {
                    var infos = new RM_PROCESS_INFO[needed];
                    count = needed;
                    listRc = RmGetList(handle, out needed, ref count, infos, ref reasons);
                    if (listRc == 0) {
                        for (int i = 0; i < (int)count; i++) {
                            int p = infos[i].Process.dwProcessId;
                            if (p > 0 && !pids.Contains(p)) pids.Add(p);
                        }
                    }
                }
            } catch {
            } finally {
                RmEndSession(handle);
            }
            return pids;
        }
    }

    internal static class MiniJson {
        public static object Parse(string json) {
            int pos = 0;
            return ParseValue(json, ref pos);
        }

        public static Dictionary<string, object> GetDict(Dictionary<string, object> d, string k) {
            object v;
            if (d != null && d.TryGetValue(k, out v)) return v as Dictionary<string, object>;
            return null;
        }

        public static List<object> GetList(Dictionary<string, object> d, string k) {
            object v;
            if (d != null && d.TryGetValue(k, out v)) return v as List<object>;
            return null;
        }

        public static string GetStr(Dictionary<string, object> d, string k) {
            object v;
            if (d == null || !d.TryGetValue(k, out v) || v == null) return "";
            if (v is string) return (string)v;
            return v.ToString();
        }

        public static string GetFirstStr(Dictionary<string, object> d, params string[] keys) {
            if (d == null) return "";
            for (int i = 0; i < keys.Length; i++) {
                string s = GetStr(d, keys[i]);
                if (s.Length > 0) return s;
            }
            return "";
        }

        public static long GetLong(Dictionary<string, object> d, string k, long defVal) {
            object v;
            if (d == null || !d.TryGetValue(k, out v) || v == null) return defVal;
            if (v is long) return (long)v;
            if (v is int) return (int)v;
            if (v is double) return (long)(double)v;
            long p;
            if (long.TryParse(v.ToString(), out p)) return p;
            return defVal;
        }

        public static int GetInt(Dictionary<string, object> d, string k, int defVal) {
            return (int)GetLong(d, k, defVal);
        }

        public static bool GetBool(Dictionary<string, object> d, string k) {
            object v;
            if (d == null || !d.TryGetValue(k, out v) || v == null) return false;
            if (v is bool) return (bool)v;
            return string.Equals(v.ToString(), "true", StringComparison.OrdinalIgnoreCase);
        }

        private static void SkipWs(string s, ref int pos) {
            while (pos < s.Length) {
                char c = s[pos];
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
                else break;
            }
        }

        private static object ParseValue(string s, ref int pos) {
            SkipWs(s, ref pos);
            if (pos >= s.Length) throw new Exception("Unexpected end of JSON");
            char c = s[pos];
            if (c == '{') return ParseObj(s, ref pos);
            if (c == '[') return ParseArr(s, ref pos);
            if (c == '"') return ParseString(s, ref pos);
            if (c == 't') { pos += 4; return true; }
            if (c == 'f') { pos += 5; return false; }
            if (c == 'n') { pos += 4; return null; }
            return ParseNumber(s, ref pos);
        }

        private static Dictionary<string, object> ParseObj(string s, ref int pos) {
            var dict = new Dictionary<string, object>();
            pos++;
            SkipWs(s, ref pos);
            if (pos < s.Length && s[pos] == '}') { pos++; return dict; }
            while (pos < s.Length) {
                SkipWs(s, ref pos);
                string key = ParseString(s, ref pos);
                SkipWs(s, ref pos);
                if (pos >= s.Length || s[pos] != ':') throw new Exception("Expected ':'");
                pos++;
                object val = ParseValue(s, ref pos);
                dict[key] = val;
                SkipWs(s, ref pos);
                if (pos < s.Length && s[pos] == ',') { pos++; continue; }
                if (pos < s.Length && s[pos] == '}') { pos++; return dict; }
                throw new Exception("Expected ',' or '}'");
            }
            return dict;
        }

        private static List<object> ParseArr(string s, ref int pos) {
            var list = new List<object>();
            pos++;
            SkipWs(s, ref pos);
            if (pos < s.Length && s[pos] == ']') { pos++; return list; }
            while (pos < s.Length) {
                list.Add(ParseValue(s, ref pos));
                SkipWs(s, ref pos);
                if (pos < s.Length && s[pos] == ',') { pos++; continue; }
                if (pos < s.Length && s[pos] == ']') { pos++; return list; }
                throw new Exception("Expected ',' or ']'");
            }
            return list;
        }

        private static string ParseString(string s, ref int pos) {
            pos++;
            var sb = new StringBuilder();
            while (pos < s.Length) {
                char c = s[pos++];
                if (c == '"') return sb.ToString();
                if (c == '\\') {
                    if (pos >= s.Length) break;
                    char esc = s[pos++];
                    if (esc == '"') sb.Append('"');
                    else if (esc == '\\') sb.Append('\\');
                    else if (esc == '/') sb.Append('/');
                    else if (esc == 'b') sb.Append('\b');
                    else if (esc == 'f') sb.Append('\f');
                    else if (esc == 'n') sb.Append('\n');
                    else if (esc == 'r') sb.Append('\r');
                    else if (esc == 't') sb.Append('\t');
                    else if (esc == 'u' && pos + 4 <= s.Length) {
                        int code = Convert.ToInt32(s.Substring(pos, 4), 16);
                        sb.Append((char)code);
                        pos += 4;
                    }
                } else {
                    sb.Append(c);
                }
            }
            throw new Exception("Unterminated string");
        }

        private static object ParseNumber(string s, ref int pos) {
            int start = pos;
            bool isFloat = false;
            while (pos < s.Length) {
                char c = s[pos];
                if (c == '.' || c == 'e' || c == 'E') isFloat = true;
                if (char.IsDigit(c) || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') pos++;
                else break;
            }
            string raw = s.Substring(start, pos - start);
            if (!isFloat) {
                long lv;
                if (long.TryParse(raw, out lv)) return lv;
            }
            double dv;
            if (double.TryParse(raw, System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out dv)) return dv;
            return 0L;
        }

        public static string Serialize(object val) {
            var sb = new StringBuilder();
            WriteVal(sb, val);
            return sb.ToString();
        }

        private static void WriteVal(StringBuilder sb, object val) {
            if (val == null) { sb.Append("null"); return; }
            if (val is bool) { sb.Append(((bool)val) ? "true" : "false"); return; }
            if (val is int || val is long) { sb.Append(val.ToString()); return; }
            if (val is double) {
                sb.Append(((double)val).ToString(System.Globalization.CultureInfo.InvariantCulture));
                return;
            }
            if (val is string) { WriteStr(sb, (string)val); return; }
            var dict = val as IDictionary;
            if (dict != null) {
                sb.Append('{');
                bool first = true;
                foreach (DictionaryEntry de in dict) {
                    if (!first) sb.Append(',');
                    first = false;
                    WriteStr(sb, de.Key.ToString());
                    sb.Append(':');
                    WriteVal(sb, de.Value);
                }
                sb.Append('}');
                return;
            }
            var list = val as IEnumerable;
            if (list != null) {
                sb.Append('[');
                bool first = true;
                foreach (object item in list) {
                    if (!first) sb.Append(',');
                    first = false;
                    WriteVal(sb, item);
                }
                sb.Append(']');
                return;
            }
            WriteStr(sb, val.ToString());
        }

        private static void WriteStr(StringBuilder sb, string s) {
            sb.Append('"');
            for (int i = 0; i < s.Length; i++) {
                char c = s[i];
                if (c == '"') sb.Append("\\\"");
                else if (c == '\\') sb.Append("\\\\");
                else if (c == '\b') sb.Append("\\b");
                else if (c == '\f') sb.Append("\\f");
                else if (c == '\n') sb.Append("\\n");
                else if (c == '\r') sb.Append("\\r");
                else if (c == '\t') sb.Append("\\t");
                else if (c < ' ') sb.Append(string.Format("\\u{0:x4}", (int)c));
                else sb.Append(c);
            }
            sb.Append('"');
        }
    }
}
""".trimIndent()

        internal fun unixPayloadScript(): String = """
import sys, os, re, json, glob, subprocess

MAX_CHUNK = 4194304
MAX_LINE = 16777216
MAX_STEP_BYTES = 32768

def emit(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False, separators=(",", ":")) + "\n")
    sys.stdout.flush()

def descendants(root_pid):
    if not root_pid or root_pid <= 0:
        return set()
    res = {int(root_pid)}
    children = {}
    try:
        out = subprocess.check_output(["ps", "-eo", "pid=,ppid="], text=True, stderr=subprocess.DEVNULL)
        for ln in out.splitlines():
            parts = ln.split()
            if len(parts) >= 2 and parts[0].isdigit() and parts[1].isdigit():
                p, pp = int(parts[0]), int(parts[1])
                children.setdefault(pp, []).append(p)
    except Exception:
        pass
    q = [int(root_pid)]
    while q:
        cur = q.pop(0)
        for ch in children.get(cur, ()):
            if ch not in res:
                res.add(ch)
                q.append(ch)
    return res

def open_files_for_pids(pids):
    paths = set()
    for p in pids:
        fddir = f"/proc/{p}/fd"
        if os.path.isdir(fddir):
            try:
                for fn in os.listdir(fddir):
                    try:
                        paths.add(os.path.realpath(os.path.join(fddir, fn)))
                    except Exception:
                        pass
            except Exception:
                pass
    if not paths and pids:
        try:
            pid_arg = ",".join(str(x) for x in sorted(pids))
            out = subprocess.check_output(["lsof", "-F", "n", "-p", pid_arg], text=True, stderr=subprocess.DEVNULL)
            for ln in out.splitlines():
                if ln.startswith("n/"):
                    paths.add(os.path.realpath(ln[1:]))
        except Exception:
            pass
    return paths

def cap_text(s, limit):
    if not s:
        return ""
    return s if len(s) <= limit else s[:limit] + "\n… (内容已截断)"

def clean_arg(raw):
    if raw is None:
        return ""
    s = str(raw).strip()
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        try:
            u = json.loads(s)
            if isinstance(u, str):
                return u.strip()
        except Exception:
            pass
    return s

def first_arg(args, *keys):
    if not isinstance(args, dict):
        return ""
    for k in keys:
        v = clean_arg(args.get(k))
        if v:
            return v
    return ""

def first_line(s):
    return s.splitlines()[0].strip() if s else ""

def trunc(s, m):
    return s if len(s) <= m else s[:m-1] + "…"

def project_tc(name, args, call_id=None, rs=None):
    tc = {"n": name}
    if call_id:
        tc["id"] = call_id
    if rs is not None and rs >= 0:
        tc["rs"] = rs
    low = name.lower()
    pt, det, chg = "", "", None
    if low in ("read_file", "view_file", "read"):
        fp = first_arg(args, "Path", "file_path", "path", "AbsolutePath")
        pt = os.path.basename(fp.rstrip("/\\"))
        s = first_arg(args, "StartLine", "offset")
        e = first_arg(args, "EndLine", "limit")
        if s or e:
            det = f"L{s}" + (f"..{e}" if e else "")
    elif low in ("edit_file", "replace_file_content", "multi_replace_file_content", "write_file", "write_to_file", "edit", "multiedit", "write"):
        fp = first_arg(args, "Path", "TargetFile", "file_path", "path")
        pt = os.path.basename(fp.rstrip("/\\"))
        chg = pt or None
        rep = first_arg(args, "Replacement", "ReplacementContent", "new_string", "Content", "content")
        tgt = first_arg(args, "Target", "TargetContent", "old_string")
        if tgt or rep:
            al = rep.count("\n") + 1 if rep else 0
            dl = tgt.count("\n") + 1 if tgt else 0
            det = f"+{al}" + (f" -{dl}" if dl else "") + " 行"
    elif low == "apply_patch":
        patch = first_arg(args, "patch", "input")
        m = re.search(r"\*\*\* (?:Update|Add|Delete) File:\s*(.+)", patch)
        pt = os.path.basename(m.group(1).strip().rstrip("/\\")) if m else "patch"
        if m:
            chg = pt
        add = sum(1 for ln in patch.splitlines() if ln.startswith("+") and not ln.startswith("+++"))
        dl = sum(1 for ln in patch.splitlines() if ln.startswith("-") and not ln.startswith("---"))
        if add or dl:
            det = f"+{add} -{dl} 行"
    elif low in ("run_command", "exec_command", "bash", "shell", "shell_command"):
        sm = first_arg(args, "toolSummary", "description")
        cmd = first_arg(args, "CommandLine", "cmd", "command")
        pt = trunc(first_line(sm or cmd), 90)
        if sm and cmd:
            det = trunc(first_line(cmd), 140)
    elif low in ("grep_search", "find_by_name", "grep", "glob", "search_web", "websearch"):
        pt = trunc(first_arg(args, "Query", "Pattern", "pattern", "query"), 80)
        sp = first_arg(args, "SearchPath", "SearchDirectory", "path")
        if sp:
            det = os.path.basename(sp.rstrip("/\\"))
    elif low in ("list_dir", "ls"):
        pt = os.path.basename(first_arg(args, "DirectoryPath", "path").rstrip("/\\"))
    elif low in ("invoke_subagent", "agent", "task", "send_message"):
        pt = trunc(first_arg(args, "Role", "subagent_type", "TypeName", "Recipient", "description"), 80)
    elif isinstance(args, dict):
        for v in args.values():
            if isinstance(v, str):
                cv = clean_arg(v)
                if 0 < len(cv) <= 160:
                    pt = trunc(first_line(cv), 80)
                    break
    if pt:
        tc["t"] = pt
    if det:
        tc["d"] = det
    if chg:
        tc["f"] = chg
    return tc

def project_line(agent, raw_str, offset, out):
    try:
        obj = json.loads(raw_str)
    except Exception:
        return
    if not isinstance(obj, dict):
        return
    if agent == "agy":
        t = obj.get("type", "")
        if t == "SYSTEM_MESSAGE":
            return
        if t == "GENERIC":
            if obj.get("status") == "ERROR" or obj.get("error"):
                s = obj.get("step_index")
                if isinstance(s, int):
                    out.append({"k": "tool_err", "o": offset, "id": f"step_{s}", "err": True})
            return
        if t == "USER_INPUT":
            raw = obj.get("content") or ""
            m_chg = re.search(r"<USER_SETTINGS_CHANGE>[\s\S]*?\bto\s+['`\"]?([^.'`\"<\n]+)", raw)
            mc = m_chg.group(1).strip() if m_chg else ""
            m_req = re.search(r"<USER_REQUEST>([\s\S]*?)</USER_REQUEST>", raw)
            if m_req:
                text = m_req.group(1).strip()
            else:
                src = obj.get("source") or ""
                if src and src != "USER_EXPLICIT":
                    if mc:
                        out.append({"k": "tokens", "o": offset, "m": mc})
                    return
                text = re.sub(r"<(?:ADDITIONAL_METADATA|USER_SETTINGS_CHANGE)>[\s\S]*?</(?:ADDITIONAL_METADATA|USER_SETTINGS_CHANGE)>", "", raw).strip()
            if text or mc:
                ev = {"k": "user", "o": offset, "text": cap_text(text, 32768)}
                if isinstance(obj.get("step_index"), int):
                    ev["s"] = obj["step_index"]
                if obj.get("created_at"):
                    ev["ts"] = obj["created_at"]
                if mc:
                    ev["mc"] = mc
                out.append(ev)
        elif t == "PLANNER_RESPONSE":
            s = obj.get("step_index")
            th = cap_text(obj.get("thinking") or "", 24576)
            md = cap_text(obj.get("content") or obj.get("response") or "", 262144)
            err = obj.get("error") or ""
            tcs = []
            for idx, tc in enumerate(obj.get("tool_calls") or []):
                if isinstance(tc, dict) and tc.get("name"):
                    rs = (s + 1 + idx) if isinstance(s, int) else None
                    cid = f"step_{rs}" if rs is not None else None
                    tcs.append(project_tc(tc["name"], tc.get("args"), cid, rs))
            if not (th or md or tcs or err):
                return
            ev = {"k": "assistant", "o": offset}
            if isinstance(s, int): ev["s"] = s
            if obj.get("created_at"): ev["ts"] = obj["created_at"]
            if obj.get("status"): ev["st"] = obj["status"]
            if obj.get("model"): ev["m"] = obj["model"]
            if th: ev["th"] = th
            if md: ev["md"] = md
            if obj.get("input_tokens"): ev["in"] = int(obj["input_tokens"])
            if obj.get("cache_read_tokens"): ev["cin"] = int(obj["cache_read_tokens"])
            if obj.get("output_tokens"): ev["out"] = int(obj["output_tokens"])
            if err: ev["error"] = cap_text(str(err), 2048)
            if tcs: ev["tc"] = tcs
            out.append(ev)
    elif agent == "codex":
        t = obj.get("type", "")
        p = obj.get("payload") or {}
        ts = obj.get("timestamp")
        if t == "turn_context":
            if p.get("model") or p.get("effort"):
                ev = {"k": "tokens", "o": offset}
                if p.get("model"): ev["m"] = p["model"]
                if p.get("effort"): ev["ef"] = p["effort"]
                out.append(ev)
        elif t == "event_msg" and p.get("type") == "token_count":
            info = p.get("info") or {}
            u = info.get("last_token_usage") or info.get("total_token_usage") or {}
            if u:
                out.append({"k": "tokens", "o": offset, "in": int(u.get("input_tokens") or 0), "cin": int(u.get("cached_input_tokens") or 0), "out": int(u.get("output_tokens") or 0)})
        elif t == "response_item":
            pt = p.get("type", "")
            if pt == "message":
                role = p.get("role", "")
                parts = []
                for c in (p.get("content") or []):
                    if not isinstance(c, dict): continue
                    ct = c.get("type")
                    if role == "user" and ct in ("input_text", "text") and c.get("text"):
                        parts.append(c["text"])
                    elif role == "user" and ct == "input_image":
                        parts.append("[📎 图片]")
                    elif role == "assistant" and ct in ("output_text", "text") and c.get("text"):
                        parts.append(c["text"])
                body = ("\n" if role == "user" else "\n\n").join(parts).strip()
                if not body:
                    return
                if role == "user":
                    if body.startswith(("# AGENTS.md", "<environment_context>", "<permissions instructions>", "<collaboration_mode>", "<INSTRUCTIONS>")):
                        return
                    if body.startswith("# Files mentioned") and "## My request:" in body:
                        body = body.split("## My request:", 1)[1].strip()
                    if body:
                        ev = {"k": "user", "o": offset, "text": cap_text(body, 32768)}
                        if ts: ev["ts"] = ts
                        out.append(ev)
                elif role == "assistant":
                    ev = {"k": "assistant", "o": offset, "md": cap_text(body, 262144)}
                    if ts: ev["ts"] = ts
                    out.append(ev)
            elif pt == "reasoning":
                th = "\n\n".join((x.get("text") if isinstance(x, dict) else str(x)) for x in (p.get("summary") or []) if x).strip()
                if th:
                    ev = {"k": "assistant", "o": offset, "th": cap_text(th, 24576)}
                    if ts: ev["ts"] = ts
                    out.append(ev)
            elif pt in ("function_call", "custom_tool_call") and p.get("name"):
                raw_args = p.get("arguments") if pt == "function_call" else p.get("input")
                args = None
                if pt == "function_call" and isinstance(raw_args, str) and raw_args.startswith("{"):
                    try: args = json.loads(raw_args)
                    except Exception: pass
                elif pt == "custom_tool_call" and isinstance(raw_args, str):
                    args = {"patch": raw_args}
                ev = {"k": "assistant", "o": offset, "tc": [project_tc(p["name"], args, p.get("call_id"))]}
                if ts: ev["ts"] = ts
                out.append(ev)
            elif pt in ("function_call_output", "custom_tool_call_output"):
                cid = p.get("call_id")
                o_str = str(p.get("output") or "")
                m = re.search(r"Process exited with code (\d+)", o_str)
                failed = p.get("status") == "failed" or (m is not None and m.group(1) != "0")
                if failed and cid:
                    out.append({"k": "tool_err", "o": offset, "id": cid, "err": True})
    elif agent == "claude":
        if obj.get("isMeta") or obj.get("isCompactSummary"):
            return
        t = obj.get("type")
        msg = obj.get("message") or {}
        ts = obj.get("timestamp")
        if t == "user":
            c = msg.get("content")
            texts = []
            if isinstance(c, str):
                texts.append(c)
            elif isinstance(c, list):
                for item in c:
                    if not isinstance(item, dict): continue
                    it = item.get("type")
                    if it == "tool_result" and item.get("is_error") and item.get("tool_use_id"):
                        out.append({"k": "tool_err", "o": offset, "id": item["tool_use_id"], "err": True})
                    elif it == "text" and item.get("text"):
                        texts.append(item["text"])
                    elif it == "image":
                        texts.append("[📎 图片]")
            clean = re.sub(r"<(?:system-reminder|local-command-caveat)>[\s\S]*?</(?:system-reminder|local-command-caveat)>", "", "\n".join(texts)).strip()
            if clean:
                ev = {"k": "user", "o": offset, "text": cap_text(clean, 32768)}
                if ts: ev["ts"] = ts
                out.append(ev)
        elif t == "assistant":
            th_parts, md_parts, tcs = [], [], []
            for item in (msg.get("content") or []):
                if not isinstance(item, dict): continue
                it = item.get("type")
                if it == "thinking" and item.get("thinking"): th_parts.append(item["thinking"])
                elif it == "text" and item.get("text"): md_parts.append(item["text"])
                elif it == "tool_use" and item.get("name"): tcs.append(project_tc(item["name"], item.get("input"), item.get("id")))
            th, md = "\n\n".join(th_parts).strip(), "\n\n".join(md_parts).strip()
            if not (th or md or tcs): return
            u = msg.get("usage") or {}
            ev = {"k": "assistant", "o": offset}
            if ts: ev["ts"] = ts
            if msg.get("model"): ev["m"] = msg["model"]
            if th: ev["th"] = cap_text(th, 24576)
            if md: ev["md"] = cap_text(md, 262144)
            if u.get("input_tokens"): ev["in"] = int(u["input_tokens"])
            if u.get("cache_read_input_tokens"): ev["cin"] = int(u["cache_read_input_tokens"])
            if u.get("output_tokens"): ev["out"] = int(u["output_tokens"])
            if tcs: ev["tc"] = tcs
            out.append(ev)

def align_forward(f, from_off, limit):
    f.seek(from_off)
    pos = from_off
    while pos < limit:
        buf = f.read(min(8192, limit - pos))
        if not buf: break
        idx = buf.find(b"\n")
        if idx >= 0: return pos + idx + 1
        pos += len(buf)
    return -1

def align_back(f, from_off):
    pos = from_off
    while pos > 0:
        st = max(0, pos - 65536)
        f.seek(st)
        buf = f.read(pos - st)
        if not buf: break
        idx = buf.rfind(b"\n")
        if idx >= 0: return st + idx + 1
        pos = st
    return 0

def process_buf(agent, buf, valid_len, base_off):
    events = []
    pos = 0
    while pos < valid_len:
        nl = buf.find(b"\n", pos, valid_len)
        if nl < 0: break
        line_bytes = buf[pos:nl].rstrip(b"\r")
        if line_bytes:
            s = line_bytes.decode("utf-8", errors="replace").lstrip("\ufeff")
            if s.strip():
                project_line(agent, s, base_off + pos, events)
        pos = nl + 1
    return events

def handle_tail(agent, path, offset, win):
    if not path or not os.path.isfile(path):
        return {"path": path or "", "startOffset": 0, "nextOffset": 0, "fileSize": 0, "hasEarlier": False, "reset": False, "events": []}
    with open(path, "rb") as f:
        f.seek(0, os.SEEK_END)
        size = f.tell()
        reset = False
        if offset > size:
            reset = True
            offset = 0
        if offset <= 0:
            if size <= win:
                start, has_earlier = 0, False
            else:
                raw_st = size - win
                start = align_forward(f, raw_st, size)
                if start < 0 or start >= size: start = align_back(f, raw_st)
                has_earlier = start > 0
        else:
            start, has_earlier = offset, False
        if start >= size:
            return {"path": path, "startOffset": start, "nextOffset": start, "fileSize": size, "hasEarlier": has_earlier, "reset": reset, "events": []}
        f.seek(start)
        buf = f.read(min(size - start, MAX_CHUNK))
        last_nl = buf.rfind(b"\n")
        if last_nl < 0 and start + len(buf) < size:
            f.seek(start)
            buf = f.read(min(size - start, MAX_LINE))
            last_nl = buf.rfind(b"\n")
        if last_nl < 0:
            return {"path": path, "startOffset": start, "nextOffset": start, "fileSize": size, "hasEarlier": has_earlier, "reset": reset, "events": []}
        events = process_buf(agent, buf, last_nl + 1, start)
        return {"path": path, "startOffset": start, "nextOffset": start + last_nl + 1, "fileSize": size, "hasEarlier": has_earlier, "reset": reset, "events": events}

def handle_head(agent, path, before_off, win):
    if not path or not os.path.isfile(path) or before_off <= 0:
        sz = os.path.getsize(path) if path and os.path.isfile(path) else 0
        return {"path": path or "", "startOffset": 0, "nextOffset": 0, "fileSize": sz, "hasEarlier": False, "reset": False, "events": []}
    with open(path, "rb") as f:
        f.seek(0, os.SEEK_END)
        size = f.tell()
        limit = min(before_off, size)
        if limit <= 0:
            return {"path": path, "startOffset": 0, "nextOffset": 0, "fileSize": size, "hasEarlier": False, "reset": False, "events": []}
        raw_st = max(0, limit - win)
        if raw_st == 0:
            start = 0
        else:
            start = align_forward(f, raw_st, limit)
            if start < 0 or start >= limit: start = align_back(f, raw_st)
        f.seek(start)
        buf = f.read(min(limit - start, MAX_LINE))
        last_nl = buf.rfind(b"\n")
        vlen = last_nl + 1 if last_nl >= 0 else len(buf)
        events = process_buf(agent, buf, vlen, start)
        return {"path": path, "startOffset": start, "nextOffset": start + vlen, "fileSize": size, "hasEarlier": start > 0, "reset": False, "events": events}

def handle_step(agent, path, sidx):
    if sidx < 0 or not path: return {"output": ""}
    if agent == "agy":
        sysgen = os.path.dirname(os.path.dirname(path))
        sf = os.path.join(sysgen, "steps", str(sidx), "output.txt")
        if os.path.isfile(sf):
            sz = os.path.getsize(sf)
            with open(sf, "rb") as f:
                txt = f.read(MAX_STEP_BYTES).decode("utf-8", errors="replace")
            if sz > MAX_STEP_BYTES: txt += "\n… (已截断，仅展示前 32 KB)"
            return {"output": txt}
        if os.path.isfile(path):
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                for ln in f:
                    try:
                        obj = json.loads(ln)
                        if obj.get("step_index") == sidx and obj.get("type") == "GENERIC":
                            return {"output": cap_text(obj.get("content") or obj.get("output") or "", MAX_STEP_BYTES)}
                    except Exception:
                        pass
    return {"output": ""}

def handle_locate(agent, pid, cwd):
    home = os.path.expanduser("~")
    pids = descendants(pid)
    open_paths = open_files_for_pids(pids) if pids else set()
    if agent == "agy":
        agy_root = os.path.join(home, ".gemini", "antigravity-cli")
        pdir = os.path.join(agy_root, "presence")
        if os.path.isdir(pdir):
            for op in open_paths:
                if op.startswith(os.path.realpath(pdir) + os.sep) and op.endswith(".lock"):
                    uuid = os.path.splitext(os.path.basename(op))[0]
                    logs = os.path.join(agy_root, "brain", uuid, ".system_generated", "logs")
                    chosen = os.path.join(logs, "transcript_full.jsonl")
                    sz = os.path.getsize(chosen) if os.path.isfile(chosen) else 0
                    return {"found": True, "agent": "agy", "sessionId": uuid, "path": chosen, "fileSize": sz}
            locks = glob.glob(os.path.join(pdir, "*.lock"))
            if not pids and len(locks) == 1:
                uuid = os.path.splitext(os.path.basename(locks[0]))[0]
                chosen = os.path.join(agy_root, "brain", uuid, ".system_generated", "logs", "transcript_full.jsonl")
                return {"found": True, "agent": "agy", "sessionId": uuid, "path": chosen, "fileSize": os.path.getsize(chosen) if os.path.isfile(chosen) else 0}
    elif agent == "codex":
        croot = os.path.join(home, ".codex")
        sdir = os.path.join(croot, "sessions")
        for op in open_paths:
            if op.startswith(os.path.realpath(sdir) + os.sep) and op.endswith(".jsonl"):
                return {"found": True, "agent": "codex", "sessionId": os.path.splitext(os.path.basename(op))[0], "path": op, "fileSize": os.path.getsize(op)}
        if os.path.isdir(sdir) and cwd:
            ncwd = os.path.normpath(cwd)
            matches = []
            for fp in sorted(glob.glob(os.path.join(sdir, "**", "rollout-*.jsonl"), recursive=True), key=os.path.getmtime, reverse=True)[:20]:
                try:
                    with open(fp, "r", encoding="utf-8", errors="replace") as f:
                        first = json.loads(f.readline())
                    if first.get("type") == "session_meta" and os.path.normpath((first.get("payload") or {}).get("cwd") or "") == ncwd:
                        matches.append((fp, (first.get("payload") or {}).get("id") or os.path.basename(fp)))
                except Exception:
                    pass
            if len(matches) == 1:
                fp, sid = matches[0]
                return {"found": True, "agent": "codex", "sessionId": sid, "path": fp, "fileSize": os.path.getsize(fp)}
    elif agent == "claude":
        clroot = os.path.join(home, ".claude")
        for p in pids:
            sf = os.path.join(clroot, "sessions", f"{p}.json")
            if os.path.isfile(sf):
                try:
                    sid = json.load(open(sf, encoding="utf-8")).get("sessionId")
                    hits = glob.glob(os.path.join(clroot, "projects", "*", f"{sid}.jsonl")) if sid else []
                    if hits:
                        return {"found": True, "agent": "claude", "sessionId": sid, "path": hits[0], "fileSize": os.path.getsize(hits[0])}
                except Exception:
                    pass
        if cwd:
            slug = re.sub(r"[^A-Za-z0-9]", "-", cwd)
            hits = glob.glob(os.path.join(clroot, "projects", slug, "*.jsonl"))
            if len(hits) == 1:
                return {"found": True, "agent": "claude", "sessionId": os.path.splitext(os.path.basename(hits[0]))[0], "path": hits[0], "fileSize": os.path.getsize(hits[0])}
    return {"found": False}

emit({"ok": True, "ready": True})
for line in sys.stdin:
    ln = line.strip()
    if not ln: continue
    req_id = None
    try:
        req = json.loads(ln)
        req_id = req.get("id")
        op, agent = req.get("op"), req.get("agent")
        if op == "locate": res = handle_locate(agent, req.get("pid") or 0, req.get("cwd") or "")
        elif op == "tail": res = handle_tail(agent, req.get("path") or "", int(req.get("offset") or 0), int(req.get("windowBytes") or 2097152))
        elif op == "head": res = handle_head(agent, req.get("path") or "", int(req.get("beforeOffset") or 0), int(req.get("windowBytes") or 2097152))
        elif op == "step": res = handle_step(agent, req.get("path") or "", int(req.get("stepIndex") if req.get("stepIndex") is not None else -1))
        else: raise ValueError(f"Unknown op: {op}")
        res["ok"] = True
        if req_id is not None: res["id"] = req_id
        emit(res)
    except Exception as e:
        emit({"ok": False, "id": req_id, "error": str(e)})
""".trimIndent()
    }
}
