package dev.herdr.android.connection

import dev.herdr.android.protocol.TranscriptAgentKind
import dev.herdr.android.protocol.TranscriptChunkResult
import dev.herdr.android.protocol.TranscriptLocateHints
import dev.herdr.android.protocol.TranscriptLocation
import dev.herdr.android.protocol.TranscriptRawEvent
import dev.herdr.android.protocol.TranscriptToolCallSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lazy, read-only persistent SFTP transcript reader for incremental `.jsonl` reads
 * (`agy`, `codex`, `claude`).
 *
 * - Reuses the existing [AuthenticatedSshConnection] via SSHJ's built-in `SFTPClient`.
 * - Executes zero remote scripts/shells and writes zero temporary files on the remote host.
 * - Uses remote `stat` (`0`-byte idle check when file size equals `offset`), byte-offset range
 *   reads, line-aligned windowing, and Kotlin-side JSONL projection.
 */
class RemoteTranscriptSidecar(
    private val ssh: AuthenticatedSshConnection,
    private val platform: HostPlatform,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val initTimeoutMs: Long = 18_000L,
    private val callTimeoutMs: Long = 12_000L
) : Closeable {

    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)

    @Volatile private var sftpChannel: SshSftpChannel? = null
    private val hydratedAgyStepCache = object : LinkedHashMap<String, TranscriptRawEvent>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranscriptRawEvent>?): Boolean =
            size > 512
    }

    suspend fun locate(
        agentKind: TranscriptAgentKind,
        pid: Long?,
        cwd: String,
        hints: TranscriptLocateHints = TranscriptLocateHints()
    ): TranscriptLocation? = withSftp { sftp ->
        locateInternal(sftp, agentKind, pid, cwd, hints)
    }

    suspend fun readTail(
        agentKind: TranscriptAgentKind,
        path: String,
        offset: Long,
        windowBytes: Long = DEFAULT_WINDOW_BYTES,
        minInitialTurns: Int = MIN_INITIAL_USER_TURNS
    ): TranscriptChunkResult = withSftp { sftp ->
        handleTail(
            sftp = sftp,
            agentKind = agentKind,
            path = path,
            offset = offset,
            windowBytes = windowBytes.coerceIn(65_536L, 8_388_608L),
            minInitialTurns = minInitialTurns
        )
    }

    suspend fun readHead(
        agentKind: TranscriptAgentKind,
        path: String,
        beforeOffset: Long,
        windowBytes: Long = DEFAULT_WINDOW_BYTES
    ): TranscriptChunkResult = withSftp { sftp ->
        handleHead(sftp, agentKind, path, beforeOffset, windowBytes.coerceIn(65_536L, 8_388_608L))
    }

    suspend fun readStepOutput(
        agentKind: TranscriptAgentKind,
        path: String,
        stepIndex: Int
    ): String = withSftp { sftp ->
        handleStep(sftp, agentKind, path, stepIndex)
    }

    private suspend fun <T> withSftp(block: (SshSftpChannel) -> T): T = mutex.withLock {
        check(!closed.get()) { "Transcript sidecar is closed" }
        var wasWarm = sftpChannel != null
        var attempt = 0
        while (true) {
            attempt++
            val channel = ensureSftpLocked()
            val watchdog = scope.launch(Dispatchers.IO) {
                delay(callTimeoutMs)
                runCatching { channel.close() }
            }
            try {
                return@withLock withContext(Dispatchers.IO) {
                    block(channel)
                }
            } catch (err: CancellationException) {
                closeChannelLocked()
                throw err
            } catch (err: Exception) {
                closeChannelLocked()
                if (wasWarm && attempt == 1 && !closed.get()) {
                    wasWarm = false
                    continue
                }
                throw err
            } finally {
                watchdog.cancel()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("Unreachable")
    }

    private suspend fun ensureSftpLocked(): SshSftpChannel {
        sftpChannel?.let { return it }
        val opener = scope.launch(Dispatchers.IO) {
            delay(initTimeoutMs)
            closeChannelLocked()
        }
        return try {
            val ch = ssh.openSftp()
            if (closed.get()) {
                runCatching { ch.close() }
                throw IOException("Transcript sidecar is closed")
            }
            sftpChannel = ch
            ch
        } finally {
            opener.cancel()
        }
    }

    private fun closeChannelLocked() {
        runCatching { sftpChannel?.close() }
        sftpChannel = null
    }

    override fun close() {
        closed.set(true)
        closeChannelLocked()
    }

    // -------------------------------------------------------------------------
    // Pure-SFTP transcript session discovery (`agy`, `codex`, `claude`)
    // -------------------------------------------------------------------------

    private val locateTagStripRegex = Regex("</?(?:USER_REQUEST|hc)(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val locateWhitespaceRegex = Regex("\\s+")

    private fun locateInternal(
        sftp: SshSftpChannel,
        agentKind: TranscriptAgentKind,
        pid: Long?,
        cwd: String,
        hints: TranscriptLocateHints
    ): TranscriptLocation? {
        val home = sftp.homeDirectory()
        val normCwd = normalizeComparePath(cwd)

        return when (agentKind) {
            TranscriptAgentKind.ANTIGRAVITY -> locateAgy(sftp, home, cwd, normCwd, hints)
            TranscriptAgentKind.CODEX -> locateCodex(sftp, home, normCwd, hints)
            TranscriptAgentKind.CLAUDE -> locateClaude(sftp, home, pid, cwd, normCwd, hints)
        }
    }

    private fun normalizeMatchText(raw: String): String {
        if (raw.isEmpty()) return ""
        return raw
            .replace("\\r\\n", " ")
            .replace("\\n", " ")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace(locateTagStripRegex, " ")
            .replace("**", "")
            .replace("`", "")
            .replace(locateWhitespaceRegex, " ")
            .trim()
            .lowercase()
    }

    private fun probeAgyPresenceLocked(
        sftp: SshSftpChannel,
        agyRoot: String,
        uuid: String,
        hasPresenceDir: Boolean
    ): Boolean? {
        if (!hasPresenceDir) return null
        val lockStat = sftp.statOrNull(joinRemote(agyRoot, "presence", "$uuid.lock"))
            ?: return if (platform == HostPlatform.WINDOWS) false else null
        return try {
            sftp.openRead(lockStat.path).use { rf ->
                val b = ByteArray(1)
                rf.readAt(0L, b, 0, 1)
            }
            if (platform == HostPlatform.WINDOWS) false else null
        } catch (_: IOException) {
            true
        } catch (_: Exception) {
            null
        }
    }

    private fun scoreCandidateFromTail(
        sftp: SshSftpChannel,
        path: String,
        hints: TranscriptLocateHints,
        matchedPromptIndices: MutableSet<Int>,
        fallbackFullPath: String? = null
    ): Int {
        if (!hints.hasContentAnchors) return 0

        fun readNormalizedTail(targetPath: String): String {
            return try {
                sftp.openRead(targetPath).use { rf ->
                    val len = rf.length()
                    if (len <= 0L) return@use ""
                    val win = minOf(len, 131_072L).toInt()
                    normalizeMatchText(String(readBytesAt(rf, len - win, win), StandardCharsets.UTF_8))
                }
            } catch (_: Exception) {
                ""
            }
        }

        var score = 0
        var assistBonus = 0
        val normTail = readNormalizedTail(path)
        if (normTail.isNotEmpty()) {
            hints.userPrompts.forEachIndexed { pIdx, rawPrompt ->
                if (pIdx !in matchedPromptIndices) {
                    val normP = normalizeMatchText(rawPrompt)
                    if (normP.length >= 6 && normTail.contains(normP)) {
                        score += if (pIdx == 0) 480 else 240
                        matchedPromptIndices.add(pIdx)
                    }
                }
            }
            hints.assistantAnchors.forEachIndexed { aIdx, rawAnchor ->
                val normA = normalizeMatchText(rawAnchor)
                if (normA.length >= 8 && normTail.contains(normA)) {
                    assistBonus += if (aIdx == 0) 350 else 180
                }
            }
        }

        if (assistBonus == 0 && fallbackFullPath != null && normTail.contains("truncated_fields")) {
            val fullStat = sftp.statOrNull(fallbackFullPath)
            if (fullStat != null && fullStat.isRegularFile && fullStat.size > 0L) {
                val normFullTail = readNormalizedTail(fullStat.path)
                if (normFullTail.isNotEmpty()) {
                    hints.userPrompts.forEachIndexed { pIdx, rawPrompt ->
                        if (pIdx !in matchedPromptIndices) {
                            val normP = normalizeMatchText(rawPrompt)
                            if (normP.length >= 6 && normFullTail.contains(normP)) {
                                score += if (pIdx == 0) 480 else 240
                                matchedPromptIndices.add(pIdx)
                            }
                        }
                    }
                    hints.assistantAnchors.forEachIndexed { aIdx, rawAnchor ->
                        val normA = normalizeMatchText(rawAnchor)
                        if (normA.length >= 8 && normFullTail.contains(normA)) {
                            assistBonus += if (aIdx == 0) 350 else 180
                        }
                    }
                }
            }
        }

        return score + minOf(assistBonus, 800)
    }

    private fun locateAgy(
        sftp: SshSftpChannel,
        home: String,
        rawCwd: String,
        normCwd: String,
        hints: TranscriptLocateHints
    ): TranscriptLocation? {
        val agyRoot = joinRemote(home, ".gemini", "antigravity-cli")

        // Collect live SQLite WAL / DB signals from `conversations/`
        val convEntries = sftp.listDir(joinRemote(agyRoot, "conversations"))
        val walMtimeByUuid = HashMap<String, Long>()
        val dbMtimeByUuid = HashMap<String, Long>()
        for (entry in convEntries) {
            if (!entry.isRegularFile) continue
            val name = entry.name
            when {
                name.endsWith(".db-wal") || name.endsWith(".db-shm") -> {
                    val uuid = name.substringBefore(".db-")
                    if (isUuid36(uuid)) {
                        val prev = walMtimeByUuid[uuid] ?: 0L
                        if (entry.mtimeSeconds >= prev) walMtimeByUuid[uuid] = entry.mtimeSeconds
                    }
                }
                name.endsWith(".db") -> {
                    val uuid = name.removeSuffix(".db")
                    if (isUuid36(uuid)) {
                        dbMtimeByUuid[uuid] = entry.mtimeSeconds
                    }
                }
            }
        }
        val activeWalSet = walMtimeByUuid.keys
        val activeWalUuids = walMtimeByUuid.entries.sortedByDescending { it.value }.map { it.key }
        val recentDbUuids = dbMtimeByUuid.entries.sortedByDescending { it.value }.map { it.key }

        if (normCwd.isEmpty()) {
            if (activeWalUuids.size == 1) {
                val uuid = activeWalUuids.single()
                resolveAgyLog(sftp, agyRoot, uuid)?.let { logStat ->
                    return TranscriptLocation(TranscriptAgentKind.ANTIGRAVITY, uuid, logStat.path, logStat.size)
                }
            }
            return null
        }

        val orderedCandidates = LinkedHashMap<String, SftpEntryInfo>()
        val historyPromptsByCid = HashMap<String, MutableList<String>>()

        // Tier 1: Fast `history.jsonl` lookup (tail 512 KB, collect ALL matching sessions for `normCwd`)
        val historyStat = sftp.statOrNull(joinRemote(agyRoot, "history.jsonl"))
        if (historyStat != null && historyStat.isRegularFile && historyStat.size > 0L) {
            val readLen = minOf(historyStat.size, 524_288L).toInt()
            val startOff = historyStat.size - readLen
            val buf = sftp.openRead(historyStat.path).use { rf -> readBytesAt(rf, startOff, readLen) }
            val lines = decodeUtf8Lines(buf, skipFirstPartial = startOff > 0L)
            for (i in lines.lastIndex downTo 0) {
                val ln = lines[i].trim()
                if (ln.isEmpty()) continue
                val obj = try {
                    JSONObject(ln)
                } catch (_: Exception) {
                    continue
                }
                val ws = normalizeComparePath(obj.str("workspace"))
                val cid = obj.str("conversationId")
                if (ws != normCwd || !isUuid36(cid)) continue
                val displayText = obj.str("display").trim()
                if (displayText.isNotEmpty()) {
                    val promptList = historyPromptsByCid.getOrPut(cid) { ArrayList(6) }
                    if (promptList.size < 6) promptList.add(displayText)
                }
                if (cid !in orderedCandidates && orderedCandidates.size < 16) {
                    val logStat = resolveAgyLog(sftp, agyRoot, cid) ?: continue
                    if (logStat.size > 0L || cid in activeWalSet) {
                        orderedCandidates[cid] = logStat
                    }
                }
            }
        }

        // Tier 2: Active WAL sessions whose prompts haven't landed in `history.jsonl` yet
        val extraWalUuids = activeWalUuids.filter { it !in orderedCandidates }
        if (extraWalUuids.isNotEmpty()) {
            for ((cid, logStat) in filterAgyUuidsByCwd(sftp, agyRoot, extraWalUuids, rawCwd, normCwd)) {
                orderedCandidates.putIfAbsent(cid, logStat)
            }
        }

        // Tier 3: Fallback scan over `conversations/*.db`, then `brain/<uuid>` when still empty
        if (orderedCandidates.isEmpty()) {
            val remainingDbUuids = recentDbUuids.filter { it !in activeWalSet }
            if (remainingDbUuids.isNotEmpty()) {
                for ((cid, logStat) in filterAgyUuidsByCwd(sftp, agyRoot, remainingDbUuids, rawCwd, normCwd)) {
                    orderedCandidates.putIfAbsent(cid, logStat)
                }
            }
        }
        if (orderedCandidates.isEmpty()) {
            val brainDirEntries = sftp.listDir(joinRemote(agyRoot, "brain"))
            val brainUuids = brainDirEntries
                .filter { it.isDirectory && isUuid36(it.name) && it.name !in activeWalSet && it.name !in dbMtimeByUuid }
                .map { it.name }
            if (brainUuids.isNotEmpty()) {
                for ((cid, logStat) in filterAgyUuidsByCwd(sftp, agyRoot, brainUuids, rawCwd, normCwd)) {
                    orderedCandidates.putIfAbsent(cid, logStat)
                }
            }
        }

        if (orderedCandidates.isEmpty()) return null
        if (orderedCandidates.size == 1) {
            val only = orderedCandidates.entries.first()
            return TranscriptLocation(TranscriptAgentKind.ANTIGRAVITY, only.key, only.value.path, only.value.size)
        }

        // Multi-candidate disambiguation: liveness lock + sibling claims + screen prompt/assistant match
        val hasPresenceDir = sftp.statOrNull(joinRemote(agyRoot, "presence"))?.isDirectory == true
        val topCandidates = orderedCandidates.entries.sortedByDescending { it.value.mtimeSeconds }.take(10)

        var bestEntry = topCandidates.first()
        var bestScore = Int.MIN_VALUE

        topCandidates.forEachIndexed { rankIdx, entry ->
            val cid = entry.key
            val logStat = entry.value
            var score = maxOf(0, 30 - rankIdx * 3)

            val locked = probeAgyPresenceLocked(sftp, agyRoot, cid, hasPresenceDir)
            when (locked) {
                true -> score += 300
                false -> score -= 220
                null -> if (cid in activeWalSet) score += 150
            }

            if (logStat.path in hints.claimedPaths) {
                score -= 180
            }

            val matchedPrompts = HashSet<Int>()
            val histPrompts = historyPromptsByCid[cid].orEmpty().map(::normalizeMatchText)
            if (histPrompts.isNotEmpty() && hints.userPrompts.isNotEmpty()) {
                hints.userPrompts.forEachIndexed { pIdx, rawPrompt ->
                    val normP = normalizeMatchText(rawPrompt)
                    if (normP.length >= 6) {
                        if (histPrompts.any { it.contains(normP) || (it.length >= 6 && normP.contains(it)) }) {
                            score += if (pIdx == 0) 520 else 260
                            matchedPrompts.add(pIdx)
                        }
                    } else if (normP.length in 2..5) {
                        if (histPrompts.firstOrNull() == normP) {
                            score += if (pIdx == 0) 90 else 30
                            matchedPrompts.add(pIdx)
                        }
                    }
                }
            }

            if (hints.hasContentAnchors) {
                val logsDir = parentRemotePath(logStat.path)
                val fullFallback = if (logsDir != null && fileNameOf(logStat.path).equals("transcript.jsonl", ignoreCase = true)) {
                    joinRemote(logsDir, "transcript_full.jsonl")
                } else {
                    null
                }
                score += scoreCandidateFromTail(
                    sftp = sftp,
                    path = logStat.path,
                    hints = hints,
                    matchedPromptIndices = matchedPrompts,
                    fallbackFullPath = fullFallback
                )
            }

            if (score > bestScore || (score == bestScore && logStat.mtimeSeconds > bestEntry.value.mtimeSeconds)) {
                bestScore = score
                bestEntry = entry
            }
        }

        return TranscriptLocation(TranscriptAgentKind.ANTIGRAVITY, bestEntry.key, bestEntry.value.path, bestEntry.value.size)
    }

    private fun resolveAgyLog(sftp: SshSftpChannel, agyRoot: String, uuid: String): SftpEntryInfo? {
        if (uuid.isBlank()) return null
        val logsDir = joinRemote(agyRoot, "brain", uuid, ".system_generated", "logs")
        val compact = sftp.statOrNull(joinRemote(logsDir, "transcript.jsonl"))
        if (compact != null && compact.isRegularFile) return compact
        val full = sftp.statOrNull(joinRemote(logsDir, "transcript_full.jsonl"))
        if (full != null && full.isRegularFile) return full
        return null
    }

    private fun filterAgyUuidsByCwd(
        sftp: SshSftpChannel,
        agyRoot: String,
        uuids: List<String>,
        rawCwd: String,
        normCwd: String,
        maxMatches: Int = 8
    ): List<Pair<String, SftpEntryInfo>> {
        val candidates = ArrayList<Pair<String, SftpEntryInfo>>(uuids.size)
        for (uuid in uuids) {
            val logStat = resolveAgyLog(sftp, agyRoot, uuid) ?: continue
            if (logStat.size > 0L) candidates.add(uuid to logStat)
        }
        // Sort strictly by actual transcript log mtime descending (never brain dir mtime)
        candidates.sortByDescending { it.second.mtimeSeconds }

        val variants = buildCwdBoundaryVariants(rawCwd, normCwd)
        val out = ArrayList<Pair<String, SftpEntryInfo>>()
        val limit = minOf(candidates.size, 24)
        for (i in 0 until limit) {
            val pair = candidates[i]
            val logStat = pair.second
            val matched = try {
                sftp.openRead(logStat.path).use { rf ->
                    val len = rf.length()
                    val tailWin = minOf(len, 131_072L).toInt()
                    val tailTxt = String(readBytesAt(rf, len - tailWin, tailWin), StandardCharsets.UTF_8)
                    if (variants.any { containsCwdBoundary(tailTxt, it) }) {
                        true
                    } else if (len > 131_072L) {
                        val headWin = minOf(len, 65_536L).toInt()
                        val headTxt = String(readBytesAt(rf, 0L, headWin), StandardCharsets.UTF_8)
                        variants.any { containsCwdBoundary(headTxt, it) }
                    } else {
                        false
                    }
                }
            } catch (_: Exception) {
                false
            }
            if (matched) {
                out.add(pair)
                if (out.size >= maxMatches) break
            }
        }
        return out
    }

    private fun resolveCodexRolloutByIdAndDate(
        sftp: SshSftpChannel,
        sessionsDir: String,
        id: String,
        updatedAtIso: String
    ): SftpEntryInfo? {
        val datePrefix = if (updatedAtIso.length >= 10) updatedAtIso.substring(0, 10) else ""
        val date = runCatching { LocalDate.parse(datePrefix) }.getOrNull() ?: return null
        val targetSuffix = "-$id.jsonl"
        val exactName = "$id.jsonl"
        // Account for UTC vs local timezone folder boundary shifts (e.g. UTC 16:26 -> local +1 day)
        for (d in listOf(date.plusDays(1), date, date.minusDays(1))) {
            val dayDir = joinRemote(
                sessionsDir,
                d.year.toString().padStart(4, '0'),
                d.monthValue.toString().padStart(2, '0'),
                d.dayOfMonth.toString().padStart(2, '0')
            )
            val hit = sftp.listDir(dayDir).firstOrNull {
                it.isRegularFile && (it.name.endsWith(targetSuffix) || it.name == exactName)
            }
            if (hit != null) return hit
        }
        return null
    }

    private val codexSessionMetaTypeRegex = Regex("\"type\"\\s*:\\s*\"session_meta\"")
    private val codexSessionMetaCwdRegex = Regex("\"cwd\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
    private val codexSessionMetaIdRegex = Regex("\"(?:id|session_id)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

    private fun unescapeJsonStringLiteral(escapedBody: String): String =
        runCatching { JSONTokener("\"$escapedBody\"").nextValue() as? String }.getOrNull() ?: escapedBody

    private fun readCodexSessionMeta(rf: SshSftpFile, fallbackFileName: String): Pair<String, String>? {
        val totalLen = rf.length()
        if (totalLen <= 0L) return null

        fun parseSessionMetaLine(buf: ByteArray, nlIndex: Int): Pair<String, String>? {
            val sliceLen = if (nlIndex >= 0) nlIndex else buf.size
            val line = String(buf, 0, sliceLen, StandardCharsets.UTF_8).trimStart('\uFEFF').trim()
            if (line.isEmpty()) return null
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: return null
            if (obj.str("type") != "session_meta") return null
            val payload = obj.dict("payload") ?: return null
            val cwd = payload.str("cwd")
            if (cwd.isBlank()) return null
            val sid = payload.str("id")
                .ifBlank { payload.str("session_id") }
                .ifBlank { fileNameWithoutExtension(fallbackFileName) }
            return sid to cwd
        }

        val fastLen = minOf(totalLen, 4_096L).toInt()
        val fastBuf = readBytesAt(rf, 0L, fastLen)
        val fastNl = fastBuf.indexOfFirst { it == 10.toByte() }
        if (fastNl >= 0 || totalLen <= 4_096L) {
            return parseSessionMetaLine(fastBuf, fastNl)
        }

        // Real Codex 0.164+ embeds a 22+ KB `base_instructions` block inside line 1 after `id` and `cwd`.
        // Parse from the initial 4 KB prefix so SFTP discovery avoids transferring the rest of line 1.
        val prefix = String(fastBuf, 0, fastBuf.size, StandardCharsets.UTF_8).trimStart('\uFEFF')
        if (codexSessionMetaTypeRegex.containsMatchIn(prefix.take(512))) {
            val cwdMatch = codexSessionMetaCwdRegex.find(prefix)
            if (cwdMatch != null) {
                val cwd = unescapeJsonStringLiteral(cwdMatch.groupValues[1])
                if (cwd.isNotBlank()) {
                    val sid = codexSessionMetaIdRegex.find(prefix)
                        ?.let { unescapeJsonStringLiteral(it.groupValues[1]) }
                        ?.ifBlank { fileNameWithoutExtension(fallbackFileName) }
                        ?: fileNameWithoutExtension(fallbackFileName)
                    return sid to cwd
                }
            }
        }

        // Full-line fallback (up to 256 KB) if `base_instructions` appears before `cwd`.
        val fullLen = minOf(totalLen, 262_144L).toInt()
        val fullBuf = ByteArray(fullLen)
        fastBuf.copyInto(fullBuf, destinationOffset = 0, startIndex = 0, endIndex = fastLen)
        if (fullLen > fastLen) {
            val rem = readBytesAt(rf, fastLen.toLong(), fullLen - fastLen)
            rem.copyInto(fullBuf, destinationOffset = fastLen, startIndex = 0, endIndex = minOf(rem.size, fullLen - fastLen))
        }
        val fullNl = fullBuf.indexOfFirst { it == 10.toByte() }
        return parseSessionMetaLine(fullBuf, fullNl)
    }

    private fun locateCodex(
        sftp: SshSftpChannel,
        home: String,
        normCwd: String,
        hints: TranscriptLocateHints
    ): TranscriptLocation? {
        if (normCwd.isEmpty()) return null
        val codexDir = joinRemote(home, ".codex")
        val sessionsDir = joinRemote(codexDir, "sessions")

        val normTitleHint = normalizeMatchText(hints.terminalTitle?.substringBeforeLast(" | ").orEmpty())
        val threadNameById = HashMap<String, String>()
        val titleMatchedIndex = ArrayList<Pair<String, String>>(8)
        val seenMatchedIds = HashSet<String>()

        val indexStat = sftp.statOrNull(joinRemote(codexDir, "session_index.jsonl"))
        if (indexStat != null && indexStat.isRegularFile && indexStat.size > 0L) {
            val readLen = minOf(indexStat.size, 262_144L).toInt()
            val startOff = indexStat.size - readLen
            val buf = sftp.openRead(indexStat.path).use { rf -> readBytesAt(rf, startOff, readLen) }
            val lines = decodeUtf8Lines(buf, skipFirstPartial = startOff > 0L)
            for (i in lines.lastIndex downTo 0) {
                val ln = lines[i].trim()
                if (ln.isEmpty()) continue
                val obj = try {
                    JSONObject(ln)
                } catch (_: Exception) {
                    continue
                }
                val id = obj.str("id").trim()
                if (id.isEmpty()) continue
                val threadName = obj.str("thread_name").trim()
                if (threadName.isNotEmpty()) {
                    threadNameById.putIfAbsent(id, threadName)
                    if (normTitleHint.length >= 2 && titleMatchedIndex.size < 8) {
                        val normThread = normalizeMatchText(threadName)
                        val titleMatch = normThread.isNotEmpty() && (
                            normThread == normTitleHint ||
                                (normTitleHint.length >= 6 && (normThread.contains(normTitleHint) || normTitleHint.contains(normThread)))
                            )
                        if (titleMatch && seenMatchedIds.add(id)) {
                            titleMatchedIndex.add(id to obj.str("updated_at").trim())
                        }
                    }
                }
            }
        }

        val candidatesByPath = LinkedHashMap<String, SftpEntryInfo>()
        for ((id, updatedAt) in titleMatchedIndex) {
            resolveCodexRolloutByIdAndDate(sftp, sessionsDir, id, updatedAt)?.let { entry ->
                candidatesByPath.putIfAbsent(entry.path, entry)
            }
        }
        for (entry in collectRecentCodexRollouts(sftp, sessionsDir, maxFiles = 40)) {
            candidatesByPath.putIfAbsent(entry.path, entry)
        }
        if (candidatesByPath.isEmpty()) return null

        val candidateFiles = candidatesByPath.values.take(32)
        val matches = ArrayList<Pair<String, SftpEntryInfo>>()
        for (fileEntry in candidateFiles) {
            val (sid, mcwd) = try {
                sftp.openRead(fileEntry.path).use { rf -> readCodexSessionMeta(rf, fileEntry.name) }
            } catch (_: Exception) {
                null
            } ?: continue
            if (normalizeComparePath(mcwd) == normCwd) {
                matches.add(sid to fileEntry)
            }
        }
        if (matches.isEmpty()) return null
        if (matches.size == 1) {
            val only = matches.first()
            return TranscriptLocation(TranscriptAgentKind.CODEX, only.first, only.second.path, only.second.size)
        }

        val topMatches = matches.take(12)
        var best = topMatches.first()
        var bestScore = Int.MIN_VALUE
        topMatches.forEachIndexed { idx, cand ->
            var score = maxOf(0, 30 - idx * 3)
            if (cand.second.path in hints.claimedPaths) score -= 180
            val normThread = normalizeMatchText(threadNameById[cand.first].orEmpty())
            if (normTitleHint.length >= 2 && normThread.isNotEmpty()) {
                when {
                    normThread == normTitleHint -> score += 650
                    normTitleHint.length >= 6 && (normThread.contains(normTitleHint) || normTitleHint.contains(normThread)) -> score += 380
                }
            }
            if (hints.hasContentAnchors) {
                score += scoreCandidateFromTail(sftp, cand.second.path, hints, HashSet())
            }
            if (score > bestScore || (score == bestScore && cand.second.mtimeSeconds > best.second.mtimeSeconds)) {
                bestScore = score
                best = cand
            }
        }
        return TranscriptLocation(TranscriptAgentKind.CODEX, best.first, best.second.path, best.second.size)
    }

    private fun collectRecentCodexRollouts(
        sftp: SshSftpChannel,
        sessionsDir: String,
        maxFiles: Int = 40,
        maxScanFiles: Int = 240
    ): List<SftpEntryInfo> {
        val collected = ArrayList<SftpEntryInfo>(maxScanFiles)
        val topEntries = sftp.listDir(sessionsDir)
        if (topEntries.isEmpty()) return emptyList()

        for (entry in topEntries.sortedByDescending { it.mtimeSeconds }) {
            if (entry.isRegularFile && entry.name.endsWith(".jsonl")) {
                collected.add(entry)
                if (collected.size >= maxScanFiles) break
            }
        }

        if (collected.size < maxScanFiles) {
            val years = topEntries.filter { it.isDirectory }.sortedByDescending { it.name }
            outer@ for (year in years) {
                val months = sftp.listDir(year.path).filter { it.isDirectory }.sortedByDescending { it.name }
                for (month in months) {
                    val days = sftp.listDir(month.path).filter { it.isDirectory }.sortedByDescending { it.name }
                    for (day in days) {
                        val files = sftp.listDir(day.path)
                            .filter { it.isRegularFile && it.name.startsWith("rollout-") && it.name.endsWith(".jsonl") }
                        for (f in files) {
                            collected.add(f)
                            if (collected.size >= maxScanFiles) break@outer
                        }
                    }
                }
            }
        }
        return collected.sortedByDescending { it.mtimeSeconds }.take(maxFiles)
    }

    private fun locateClaude(
        sftp: SshSftpChannel,
        home: String,
        pid: Long?,
        rawCwd: String,
        normCwd: String,
        hints: TranscriptLocateHints
    ): TranscriptLocation? {
        val claudeRoot = joinRemote(home, ".claude")
        val projectsDir = joinRemote(claudeRoot, "projects")

        // Tier 1: Direct `~/.claude/sessions/<pid>.json` -> sessionId lookup
        if (pid != null && pid > 0L) {
            val pidMeta = sftp.statOrNull(joinRemote(claudeRoot, "sessions", "$pid.json"))
            if (pidMeta != null && pidMeta.isRegularFile && pidMeta.size in 1L..65_536L) {
                val sid = try {
                    val bytes = sftp.openRead(pidMeta.path).use { rf -> readBytesAt(rf, 0L, pidMeta.size.toInt()) }
                    JSONObject(String(bytes, StandardCharsets.UTF_8)).str("sessionId")
                } catch (_: Exception) {
                    ""
                }
                if (sid.isNotEmpty()) {
                    for (projDir in sftp.listDir(projectsDir)) {
                        if (!projDir.isDirectory) continue
                        val hit = sftp.statOrNull(joinRemote(projDir.path, "$sid.jsonl"))
                        if (hit != null && hit.isRegularFile) {
                            return TranscriptLocation(TranscriptAgentKind.CLAUDE, sid, hit.path, hit.size)
                        }
                    }
                }
            }
        }

        if (normCwd.isEmpty()) return null
        val slug = encodeClaudeProjectSlug(rawCwd)
        val pdir = joinRemote(projectsDir, slug)
        val jsonls = sftp.listDir(pdir)
            .filter { it.isRegularFile && it.name.endsWith(".jsonl") }
            .sortedByDescending { it.mtimeSeconds }
        if (jsonls.isEmpty()) return null

        val bySessionId = HashMap<String, SftpEntryInfo>(jsonls.size)
        for (f in jsonls) {
            bySessionId[fileNameWithoutExtension(f.name)] = f
        }

        val orderedCandidates = LinkedHashMap<String, SftpEntryInfo>()
        val historyPromptsBySid = HashMap<String, MutableList<String>>()

        // Collect matching explicit sessions from `history.jsonl` tail (newest to oldest)
        val historyStat = sftp.statOrNull(joinRemote(claudeRoot, "history.jsonl"))
        if (historyStat != null && historyStat.isRegularFile && historyStat.size > 0L) {
            val readLen = minOf(historyStat.size, 131_072L).toInt()
            val startOff = historyStat.size - readLen
            val buf = sftp.openRead(historyStat.path).use { rf -> readBytesAt(rf, startOff, readLen) }
            val lines = decodeUtf8Lines(buf, skipFirstPartial = startOff > 0L)
            for (i in lines.lastIndex downTo 0) {
                val ln = lines[i].trim()
                if (ln.isEmpty()) continue
                val obj = try {
                    JSONObject(ln)
                } catch (_: Exception) {
                    continue
                }
                val proj = normalizeComparePath(obj.str("project"))
                val sid = obj.str("sessionId")
                if (proj == normCwd && sid.isNotEmpty()) {
                    val matched = bySessionId[sid] ?: continue
                    val displayText = obj.str("display").trim()
                    if (displayText.isNotEmpty()) {
                        val promptList = historyPromptsBySid.getOrPut(sid) { ArrayList(6) }
                        if (promptList.size < 6) promptList.add(displayText)
                    }
                    if (sid !in orderedCandidates && orderedCandidates.size < 10) {
                        orderedCandidates[sid] = matched
                    }
                }
            }
        }

        for (f in jsonls) {
            val sid = fileNameWithoutExtension(f.name)
            if (sid !in orderedCandidates && orderedCandidates.size < 10) {
                orderedCandidates[sid] = f
            }
        }

        if (orderedCandidates.size == 1 || (!hints.hasContentAnchors && hints.claimedPaths.isEmpty())) {
            val first = orderedCandidates.entries.first()
            return TranscriptLocation(TranscriptAgentKind.CLAUDE, first.key, first.value.path, first.value.size)
        }

        val topCandidates = orderedCandidates.entries.sortedByDescending { it.value.mtimeSeconds }.take(10)
        var best = topCandidates.first()
        var bestScore = Int.MIN_VALUE
        topCandidates.forEachIndexed { idx, entry ->
            val sid = entry.key
            val fileEntry = entry.value
            var score = maxOf(0, 30 - idx * 3)
            if (fileEntry.path in hints.claimedPaths) score -= 180
            val matchedPrompts = HashSet<Int>()
            val histPrompts = historyPromptsBySid[sid].orEmpty().map(::normalizeMatchText)
            if (histPrompts.isNotEmpty() && hints.userPrompts.isNotEmpty()) {
                hints.userPrompts.forEachIndexed { pIdx, rawPrompt ->
                    val normP = normalizeMatchText(rawPrompt)
                    if (normP.length >= 6 && histPrompts.any { it.contains(normP) || (it.length >= 6 && normP.contains(it)) }) {
                        score += if (pIdx == 0) 520 else 260
                        matchedPrompts.add(pIdx)
                    }
                }
            }
            if (hints.hasContentAnchors) {
                score += scoreCandidateFromTail(sftp, fileEntry.path, hints, matchedPrompts)
            }
            if (score > bestScore || (score == bestScore && fileEntry.mtimeSeconds > best.value.mtimeSeconds)) {
                bestScore = score
                best = entry
            }
        }
        return TranscriptLocation(TranscriptAgentKind.CLAUDE, best.key, best.value.path, best.value.size)
    }

    // -------------------------------------------------------------------------
    // Byte-offset `tail`, `head`, and `step` handlers over SFTP
    // -------------------------------------------------------------------------

    private fun handleTail(
        sftp: SshSftpChannel,
        agentKind: TranscriptAgentKind,
        path: String,
        offset: Long,
        windowBytes: Long,
        minInitialTurns: Int = MIN_INITIAL_USER_TURNS
    ): TranscriptChunkResult {
        if (path.isBlank()) return emptyChunk(path)
        val stat = sftp.statOrNull(path)
        if (stat == null || !stat.isRegularFile) return emptyChunk(path)

        val reqOffset = offset.coerceAtLeast(0L)
        if (reqOffset > 0L && stat.size == reqOffset) {
            return TranscriptChunkResult(
                path = path,
                startOffset = reqOffset,
                nextOffset = reqOffset,
                fileSize = stat.size,
                hasEarlier = false,
                resetRequired = false,
                events = emptyList()
            )
        }

        sftp.openRead(path).use { file ->
            val fileSize = file.length()
            var reset = false
            var effOffset = reqOffset
            if (effOffset > fileSize) {
                reset = true
                effOffset = 0L
            }

            var startOffset: Long
            var hasEarlier: Boolean
            if (effOffset <= 0L) {
                if (fileSize <= windowBytes) {
                    startOffset = 0L
                    hasEarlier = false
                } else {
                    val rawStart = fileSize - windowBytes
                    var aligned = alignForwardAfterNewline(file, rawStart, fileSize)
                    if (aligned < 0L || aligned >= fileSize) {
                        aligned = alignBackToLineStart(file, rawStart)
                    }
                    startOffset = aligned
                    hasEarlier = startOffset > 0L
                }
            } else {
                startOffset = effOffset
                hasEarlier = false
            }

            if (startOffset >= fileSize) {
                return TranscriptChunkResult(
                    path = path,
                    startOffset = startOffset,
                    nextOffset = startOffset,
                    fileSize = fileSize,
                    hasEarlier = hasEarlier,
                    resetRequired = reset,
                    events = emptyList()
                )
            }

            val toRead = minOf(fileSize - startOffset, MAX_CHUNK_READ_BYTES.toLong()).toInt()
            var buf = readBytesAt(file, startOffset, toRead)
            var lastLf = lastIndexOfNewline(buf, buf.size)
            if (lastLf < 0 && startOffset + buf.size < fileSize) {
                val extCap = minOf(fileSize - startOffset, MAX_SINGLE_LINE_BYTES.toLong()).toInt()
                if (extCap > buf.size) {
                    buf = readBytesAt(file, startOffset, extCap)
                    lastLf = lastIndexOfNewline(buf, buf.size)
                }
            }
            if (lastLf < 0) {
                return TranscriptChunkResult(
                    path = path,
                    startOffset = startOffset,
                    nextOffset = startOffset,
                    fileSize = fileSize,
                    hasEarlier = hasEarlier,
                    resetRequired = reset,
                    events = emptyList()
                )
            }

            val nextOffset = startOffset + lastLf + 1L
            var events = projectLines(sftp, agentKind, path, buf, lastLf + 1, startOffset)

            if (effOffset <= 0L && startOffset > 0L && minInitialTurns > 0) {
                var userTurns = events.count { it is TranscriptRawEvent.UserMessage }
                while (userTurns < minInitialTurns &&
                    startOffset > 0L &&
                    (nextOffset - startOffset) < MAX_INITIAL_BACKFILL_BYTES
                ) {
                    val rawPrev = maxOf(0L, startOffset - windowBytes)
                    val prevStart = if (rawPrev == 0L) {
                        0L
                    } else {
                        var aligned = alignForwardAfterNewline(file, rawPrev, startOffset)
                        if (aligned < 0L || aligned >= startOffset) {
                            aligned = alignBackToLineStart(file, rawPrev)
                        }
                        aligned
                    }
                    if (prevStart >= startOffset) break

                    val prevLen = minOf(startOffset - prevStart, MAX_SINGLE_LINE_BYTES.toLong()).toInt()
                    val prevBuf = readBytesAt(file, prevStart, prevLen)
                    val prevLf = lastIndexOfNewline(prevBuf, prevBuf.size)
                    val prevValidLen = if (prevLf >= 0) prevLf + 1 else prevBuf.size
                    if (prevValidLen <= 0) break

                    val olderEvents = projectLines(sftp, agentKind, path, prevBuf, prevValidLen, prevStart)
                    if (olderEvents.isNotEmpty()) {
                        val combined = ArrayList<TranscriptRawEvent>(olderEvents.size + events.size)
                        combined.addAll(olderEvents)
                        combined.addAll(events)
                        events = combined
                        userTurns = events.count { it is TranscriptRawEvent.UserMessage }
                    }
                    startOffset = prevStart
                }
                hasEarlier = startOffset > 0L
            }

            return TranscriptChunkResult(
                path = path,
                startOffset = startOffset,
                nextOffset = nextOffset,
                fileSize = fileSize,
                hasEarlier = hasEarlier,
                resetRequired = reset,
                events = events
            )
        }
    }

    private fun handleHead(
        sftp: SshSftpChannel,
        agentKind: TranscriptAgentKind,
        path: String,
        beforeOffset: Long,
        windowBytes: Long
    ): TranscriptChunkResult {
        if (path.isBlank() || beforeOffset <= 0L) {
            val sz = if (path.isNotBlank()) sftp.statOrNull(path)?.size ?: 0L else 0L
            return emptyChunk(path, fileSize = sz)
        }
        val stat = sftp.statOrNull(path)
        if (stat == null || !stat.isRegularFile) return emptyChunk(path)

        sftp.openRead(path).use { file ->
            val fileSize = file.length()
            val limit = minOf(beforeOffset, fileSize)
            if (limit <= 0L) return emptyChunk(path, fileSize = fileSize)

            val rawStart = maxOf(0L, limit - windowBytes)
            val startOffset = if (rawStart == 0L) {
                0L
            } else {
                var aligned = alignForwardAfterNewline(file, rawStart, limit)
                if (aligned < 0L || aligned >= limit) {
                    aligned = alignBackToLineStart(file, rawStart)
                }
                aligned
            }

            val len = minOf(limit - startOffset, MAX_SINGLE_LINE_BYTES.toLong()).toInt()
            val buf = readBytesAt(file, startOffset, len)
            val lastLf = lastIndexOfNewline(buf, buf.size)
            val validLen = if (lastLf >= 0) lastLf + 1 else buf.size
            val actualEnd = startOffset + validLen
            val events = projectLines(sftp, agentKind, path, buf, validLen, startOffset)

            return TranscriptChunkResult(
                path = path,
                startOffset = startOffset,
                nextOffset = actualEnd,
                fileSize = fileSize,
                hasEarlier = startOffset > 0L,
                resetRequired = false,
                events = events
            )
        }
    }

    private fun handleStep(
        sftp: SshSftpChannel,
        agentKind: TranscriptAgentKind,
        path: String,
        stepIndex: Int
    ): String {
        if (stepIndex < 0 || path.isBlank() || agentKind != TranscriptAgentKind.ANTIGRAVITY) return ""

        val logsDir = parentRemotePath(path)
        val sysGenDir = logsDir?.let { parentRemotePath(it) }
        if (!sysGenDir.isNullOrEmpty()) {
            val stepFile = joinRemote(sysGenDir, "steps", stepIndex.toString(), "output.txt")
            val stepStat = sftp.statOrNull(stepFile)
            if (stepStat != null && stepStat.isRegularFile) {
                return sftp.openRead(stepStat.path).use { rf ->
                    val total = rf.length()
                    val count = minOf(total, MAX_STEP_OUTPUT_BYTES.toLong()).toInt()
                    val txt = String(readBytesAt(rf, 0L, count), StandardCharsets.UTF_8)
                    if (total > MAX_STEP_OUTPUT_BYTES) "$txt\n… (已截断，仅展示前 32 KB)" else txt
                }
            }
        }

        var scanPath = path
        if (fileNameOf(path).equals("transcript.jsonl", ignoreCase = true) && !logsDir.isNullOrEmpty()) {
            val fullSibling = joinRemote(logsDir, "transcript_full.jsonl")
            if (sftp.statOrNull(fullSibling)?.isRegularFile == true) {
                scanPath = fullSibling
            }
        }

        val scanStat = sftp.statOrNull(scanPath) ?: return ""
        if (!scanStat.isRegularFile || scanStat.size <= 0L) return ""

        val needleCompact = "\"step_index\":$stepIndex"
        val needleSpaced = "\"step_index\": $stepIndex"

        sftp.openRead(scanStat.path).use { rf ->
            var cursor = rf.length()
            val chunkWin = 524_288L
            while (cursor > 0L) {
                val rawStart = maxOf(0L, cursor - chunkWin)
                val chunkStart = if (rawStart == 0L) {
                    0L
                } else {
                    var aligned = alignForwardAfterNewline(rf, rawStart, cursor)
                    if (aligned < 0L || aligned >= cursor) {
                        aligned = alignBackToLineStart(rf, rawStart)
                    }
                    aligned
                }
                if (chunkStart >= cursor) break

                val len = minOf(cursor - chunkStart, MAX_SINGLE_LINE_BYTES.toLong()).toInt()
                val cbuf = readBytesAt(rf, chunkStart, len)
                var ls = 0
                for (i in cbuf.indices) {
                    if (cbuf[i] == 10.toByte()) {
                        var llen = i - ls
                        if (llen > 0 && cbuf[ls + llen - 1] == 13.toByte()) llen--
                        if (llen > 0) {
                            var lineStr = String(cbuf, ls, llen, StandardCharsets.UTF_8)
                            if (chunkStart == 0L && ls == 0 && lineStr.startsWith("\uFEFF")) {
                                lineStr = lineStr.substring(1)
                            }
                            if (lineStr.contains(needleCompact) || lineStr.contains(needleSpaced)) {
                                val obj = try {
                                    JSONObject(lineStr)
                                } catch (_: Exception) {
                                    null
                                }
                                if (obj != null && obj.optInt("step_index", -1) == stepIndex && obj.str("type") == "GENERIC") {
                                    val c = obj.str("content").ifEmpty { obj.str("output") }
                                    return capText(c, MAX_STEP_OUTPUT_BYTES)
                                }
                            }
                        }
                        ls = i + 1
                    }
                }
                cursor = chunkStart
            }
        }
        return ""
    }

    // -------------------------------------------------------------------------
    // Selective `transcript_full.jsonl` hydration & JSONL projection
    // -------------------------------------------------------------------------

    private fun projectLines(
        sftp: SshSftpChannel,
        agentKind: TranscriptAgentKind,
        sourcePath: String,
        buf: ByteArray,
        length: Int,
        baseOffset: Long
    ): List<TranscriptRawEvent> {
        val outEvents = ArrayList<TranscriptRawEvent?>()
        val truncTargets = LinkedHashMap<Int, Int>()
        val truncOffsets = HashMap<Int, Long>()

        var lineStart = 0
        for (i in 0 until length) {
            if (buf[i] == 10.toByte()) {
                var lineLen = i - lineStart
                if (lineLen > 0 && buf[lineStart + lineLen - 1] == 13.toByte()) lineLen--
                if (lineLen > 0) {
                    val absOff = baseOffset + lineStart
                    var jsonStr = String(buf, lineStart, lineLen, StandardCharsets.UTF_8)
                    if (absOff == 0L && jsonStr.startsWith("\uFEFF")) {
                        jsonStr = jsonStr.substring(1)
                    }
                    if (jsonStr.isNotBlank()) {
                        if (agentKind == TranscriptAgentKind.ANTIGRAVITY &&
                            jsonStr.contains("\"type\":\"GENERIC\"") &&
                            !jsonStr.contains("\"ERROR\"") &&
                            !jsonStr.contains("\"error\":")
                        ) {
                            lineStart = i + 1
                            continue
                        }
                        val startIdx = outEvents.size
                        val emitted = ArrayList<TranscriptRawEvent>(2)
                        val truncStep = projectSingleLine(agentKind, jsonStr, absOff, emitted)
                        outEvents.addAll(emitted)
                        if (truncStep >= 0 && truncStep !in truncTargets) {
                            if (emitted.isNotEmpty()) {
                                truncTargets[truncStep] = startIdx
                                truncOffsets[truncStep] = absOff
                            } else {
                                outEvents.add(null)
                                truncTargets[truncStep] = startIdx
                                truncOffsets[truncStep] = absOff
                            }
                        }
                    }
                }
                lineStart = i + 1
            }
        }

        if (agentKind == TranscriptAgentKind.ANTIGRAVITY && truncTargets.isNotEmpty()) {
            hydrateTruncatedAgySteps(sftp, sourcePath, outEvents, truncTargets, truncOffsets)
        }

        return outEvents.filterNotNull()
    }

    private fun hydrateTruncatedAgySteps(
        sftp: SshSftpChannel,
        sourcePath: String,
        outEvents: MutableList<TranscriptRawEvent?>,
        eventIdxByStep: Map<Int, Int>,
        offsetByStep: Map<Int, Long>
    ) {
        if (sourcePath.isBlank() || eventIdxByStep.isEmpty()) return
        if (!fileNameOf(sourcePath).equals("transcript.jsonl", ignoreCase = true)) return
        val dir = parentRemotePath(sourcePath) ?: return
        val fullPath = joinRemote(dir, "transcript_full.jsonl")
        val fullStat = sftp.statOrNull(fullPath) ?: return
        if (!fullStat.isRegularFile || fullStat.size <= 0L) return

        val needed = eventIdxByStep.keys.toHashSet()
        synchronized(hydratedAgyStepCache) {
            val iter = needed.iterator()
            while (iter.hasNext()) {
                val sidx = iter.next()
                val cached = hydratedAgyStepCache["${fullStat.path}#$sidx"] ?: continue
                val idx = eventIdxByStep[sidx]
                if (idx != null && idx in outEvents.indices) {
                    outEvents[idx] = cached.withLineOffset(offsetByStep[sidx] ?: 0L)
                }
                iter.remove()
            }
        }
        if (needed.isEmpty()) return
        val minNeeded = needed.minOrNull() ?: return

        try {
            sftp.openRead(fullStat.path).use { file ->
                var cursor = file.length()
                var scanned = 0L
                val chunkWin = 524_288L
                while (cursor > 0L && needed.isNotEmpty() && scanned < MAX_FULL_HYDRATE_SCAN_BYTES) {
                    val rawStart = maxOf(0L, cursor - chunkWin)
                    val chunkStart = if (rawStart == 0L) {
                        0L
                    } else {
                        var aligned = alignForwardAfterNewline(file, rawStart, cursor)
                        if (aligned < 0L || aligned >= cursor) {
                            aligned = alignBackToLineStart(file, rawStart)
                        }
                        aligned
                    }
                    if (chunkStart >= cursor) break

                    val len = minOf(cursor - chunkStart, MAX_SINGLE_LINE_BYTES.toLong()).toInt()
                    val cbuf = readBytesAt(file, chunkStart, len)
                    scanned += cbuf.size

                    var minChunkStep = Int.MAX_VALUE
                    var ls = 0
                    for (i in cbuf.indices) {
                        if (cbuf[i] == 10.toByte()) {
                            var llen = i - ls
                            if (llen > 0 && cbuf[ls + llen - 1] == 13.toByte()) llen--
                            if (llen > 0) {
                                var lineStr = String(cbuf, ls, llen, StandardCharsets.UTF_8)
                                if (chunkStart == 0L && ls == 0 && lineStr.startsWith("\uFEFF")) {
                                    lineStr = lineStr.substring(1)
                                }
                                if (!lineStr.contains("\"type\":\"GENERIC\"") && lineStr.contains("\"step_index\"")) {
                                    try {
                                        val obj = JSONObject(lineStr)
                                        val sidx = obj.optInt("step_index", -1)
                                        if (sidx >= 0) {
                                            if (sidx < minChunkStep) minChunkStep = sidx
                                            if (sidx in needed) {
                                                val tmp = ArrayList<TranscriptRawEvent>(1)
                                                projectAgy(obj, offsetByStep[sidx] ?: 0L, tmp)
                                                val idx = eventIdxByStep[sidx]
                                                if (tmp.isNotEmpty() && idx != null && idx in outEvents.indices) {
                                                    val hydrated = tmp[0]
                                                    outEvents[idx] = hydrated
                                                    val isFinalized = hydrated !is TranscriptRawEvent.AssistantStep ||
                                                        hydrated.status == null ||
                                                        hydrated.status == "DONE" ||
                                                        hydrated.status == "ERROR"
                                                    if (isFinalized) {
                                                        synchronized(hydratedAgyStepCache) {
                                                            hydratedAgyStepCache["${fullStat.path}#$sidx"] = hydrated
                                                        }
                                                    }
                                                }
                                                needed.remove(sidx)
                                            }
                                        }
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                            ls = i + 1
                        }
                    }
                    if (needed.isEmpty() || (minChunkStep < Int.MAX_VALUE && minChunkStep < minNeeded)) break
                    cursor = chunkStart
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun TranscriptRawEvent.withLineOffset(offset: Long): TranscriptRawEvent = when (this) {
        is TranscriptRawEvent.UserMessage -> copy(lineByteOffset = offset)
        is TranscriptRawEvent.AssistantStep -> copy(lineByteOffset = offset)
        is TranscriptRawEvent.TokenUpdate -> copy(lineByteOffset = offset)
        is TranscriptRawEvent.ToolStatusUpdate -> copy(lineByteOffset = offset)
    }

    private fun projectSingleLine(
        agentKind: TranscriptAgentKind,
        jsonStr: String,
        lineOffset: Long,
        outEvents: MutableList<TranscriptRawEvent>
    ): Int {
        val obj = try {
            JSONObject(jsonStr)
        } catch (_: Exception) {
            return -1
        }
        return when (agentKind) {
            TranscriptAgentKind.ANTIGRAVITY -> projectAgy(obj, lineOffset, outEvents)
            TranscriptAgentKind.CODEX -> {
                projectCodex(obj, lineOffset, outEvents)
                -1
            }
            TranscriptAgentKind.CLAUDE -> {
                projectClaude(obj, lineOffset, outEvents)
                -1
            }
        }
    }

    private fun projectAgy(
        obj: JSONObject,
        lineOffset: Long,
        outEvents: MutableList<TranscriptRawEvent>
    ): Int {
        val type = obj.str("type")
        if (type == "SYSTEM_MESSAGE") return -1
        val truncList = obj.arr("truncated_fields")
        val hasTrunc = truncList != null && truncList.length() > 0

        if (type == "GENERIC") {
            val status = obj.str("status")
            val errText = obj.str("error")
            if (status == "ERROR" || errText.isNotEmpty()) {
                val sidx = obj.optInt("step_index", -1)
                if (sidx >= 0) {
                    outEvents.add(
                        TranscriptRawEvent.ToolStatusUpdate(
                            lineByteOffset = lineOffset,
                            callId = "step_$sidx",
                            hasError = true
                        )
                    )
                }
            }
            return -1
        }

        if (type == "USER_INPUT") {
            val source = obj.str("source")
            val raw = obj.str("content")
            val modelChange = extractAgyModelChange(raw)
            var reqBody = extractTagBody(raw, "<USER_REQUEST>", "</USER_REQUEST>")
            if (reqBody == null) {
                if (source.isNotEmpty() && source != "USER_EXPLICIT") {
                    if (modelChange.isNotEmpty()) {
                        outEvents.add(
                            TranscriptRawEvent.TokenUpdate(
                                lineByteOffset = lineOffset,
                                model = modelChange
                            )
                        )
                    }
                    return -1
                }
                reqBody = stripXmlTagsBlock(raw)
            }
            val cleanBody = reqBody.trim()
            val userStepIdx = obj.optInt("step_index", -1).takeIf { it >= 0 }
            if (cleanBody.isEmpty() && modelChange.isEmpty()) {
                return if (hasTrunc && userStepIdx != null) userStepIdx else -1
            }
            outEvents.add(
                TranscriptRawEvent.UserMessage(
                    lineByteOffset = lineOffset,
                    stepIndex = userStepIdx,
                    createdAt = obj.strOrNull("created_at"),
                    text = capText(cleanBody, 32_768),
                    modelChange = modelChange.ifEmpty { null }
                )
            )
            return if (hasTrunc && userStepIdx != null) userStepIdx else -1
        }

        if (type == "PLANNER_RESPONSE") {
            val stepIdx = obj.optInt("step_index", -1).takeIf { it >= 0 }
            val thinking = capText(obj.str("thinking"), 24_576)
            val mdRaw = obj.str("content").ifEmpty { obj.str("response") }
            val md = capText(mdRaw, 262_144)
            val errMsg = obj.str("error")

            val tcOut = ArrayList<TranscriptToolCallSummary>()
            val rawCalls = obj.arr("tool_calls")
            if (rawCalls != null) {
                for (i in 0 until rawCalls.length()) {
                    val tc = rawCalls.optJSONObject(i) ?: continue
                    val name = tc.str("name")
                    if (name.isEmpty()) continue
                    val args = tc.dict("args")
                    val rStep = stepIdx?.let { it + 1 + i }
                    val cid = rStep?.let { "step_$it" }
                    tcOut.add(projectToolCall(name, args, cid, rStep))
                }
            }

            if (thinking.isEmpty() && md.isEmpty() && tcOut.isEmpty() && errMsg.isEmpty()) {
                return if (hasTrunc && stepIdx != null) stepIdx else -1
            }

            outEvents.add(
                TranscriptRawEvent.AssistantStep(
                    lineByteOffset = lineOffset,
                    stepIndex = stepIdx,
                    createdAt = obj.strOrNull("created_at"),
                    status = obj.strOrNull("status"),
                    model = obj.strOrNull("model"),
                    thinking = thinking,
                    markdownContent = md,
                    toolCalls = tcOut,
                    inputTokens = obj.optLong("input_tokens", 0L).coerceAtLeast(0L),
                    cacheReadTokens = obj.optLong("cache_read_tokens", 0L).coerceAtLeast(0L),
                    outputTokens = obj.optLong("output_tokens", 0L).coerceAtLeast(0L),
                    errorText = if (errMsg.isNotEmpty()) capText(errMsg, 2_048) else null
                )
            )
            return if (hasTrunc && stepIdx != null) stepIdx else -1
        }
        return -1
    }

    private fun projectCodex(
        obj: JSONObject,
        lineOffset: Long,
        outEvents: MutableList<TranscriptRawEvent>
    ) {
        val type = obj.str("type")
        val payload = obj.dict("payload") ?: return
        val ts = obj.strOrNull("timestamp")

        if (type == "turn_context") {
            val model = payload.strOrNull("model")
            val effort = payload.strOrNull("effort")
            if (model != null || effort != null) {
                outEvents.add(
                    TranscriptRawEvent.TokenUpdate(
                        lineByteOffset = lineOffset,
                        model = model,
                        effort = effort
                    )
                )
            }
            return
        }

        if (type == "event_msg") {
            if (payload.str("type") == "token_count") {
                val info = payload.dict("info") ?: return
                val usage = info.dict("last_token_usage") ?: info.dict("total_token_usage") ?: return
                outEvents.add(
                    TranscriptRawEvent.TokenUpdate(
                        lineByteOffset = lineOffset,
                        inputTokens = usage.optLong("input_tokens", 0L).coerceAtLeast(0L),
                        cachedInputTokens = usage.optLong("cached_input_tokens", 0L).coerceAtLeast(0L),
                        outputTokens = usage.optLong("output_tokens", 0L).coerceAtLeast(0L)
                    )
                )
            }
            return
        }

        if (type != "response_item") return

        when (val ptype = payload.str("type")) {
            "message" -> {
                val role = payload.str("role")
                val contentList = payload.arr("content")
                val sb = StringBuilder()
                if (contentList != null) {
                    for (i in 0 until contentList.length()) {
                        val item = contentList.optJSONObject(i) ?: continue
                        val itype = item.str("type")
                        if (role == "user") {
                            if (itype == "input_text" || itype == "text") {
                                val t = item.str("text")
                                if (t.isNotEmpty()) {
                                    if (sb.isNotEmpty()) sb.append('\n')
                                    sb.append(t)
                                }
                            } else if (itype == "input_image") {
                                if (sb.isNotEmpty()) sb.append(' ')
                                sb.append("[📎 图片]")
                            }
                        } else if (role == "assistant") {
                            if (itype == "output_text" || itype == "text") {
                                val t = item.str("text")
                                if (t.isNotEmpty()) {
                                    if (sb.isNotEmpty()) sb.append("\n\n")
                                    sb.append(t)
                                }
                            }
                        }
                    }
                }
                var body = sb.toString().trim()
                if (body.isEmpty()) return

                if (role == "user") {
                    if (body.startsWith("# AGENTS.md") ||
                        body.startsWith("<environment_context>") ||
                        body.startsWith("<permissions instructions>") ||
                        body.startsWith("<collaboration_mode>") ||
                        body.startsWith("<INSTRUCTIONS>")
                    ) {
                        return
                    }
                    val reqMarker = body.indexOf("## My request:")
                    if (reqMarker >= 0 && body.startsWith("# Files mentioned")) {
                        body = body.substring(reqMarker + "## My request:".length).trim()
                    }
                    if (body.isEmpty()) return
                    outEvents.add(
                        TranscriptRawEvent.UserMessage(
                            lineByteOffset = lineOffset,
                            createdAt = ts,
                            text = capText(body, 32_768)
                        )
                    )
                } else if (role == "assistant") {
                    outEvents.add(
                        TranscriptRawEvent.AssistantStep(
                            lineByteOffset = lineOffset,
                            createdAt = ts,
                            markdownContent = capText(body, 262_144)
                        )
                    )
                }
            }

            "reasoning" -> {
                val sumList = payload.arr("summary")
                val th = StringBuilder()
                if (sumList != null) {
                    for (i in 0 until sumList.length()) {
                        val elem = sumList.opt(i)
                        val t = when (elem) {
                            is JSONObject -> elem.str("text")
                            is String -> elem
                            else -> ""
                        }
                        if (t.isNotEmpty()) {
                            if (th.isNotEmpty()) th.append("\n\n")
                            th.append(t)
                        }
                    }
                }
                val thText = th.toString().trim()
                if (thText.isNotEmpty()) {
                    outEvents.add(
                        TranscriptRawEvent.AssistantStep(
                            lineByteOffset = lineOffset,
                            createdAt = ts,
                            thinking = capText(thText, 24_576)
                        )
                    )
                }
            }

            "function_call", "custom_tool_call" -> {
                val name = payload.str("name")
                if (name.isEmpty()) return
                val callId = payload.strOrNull("call_id")
                val rawArgs = if (ptype == "function_call") payload.str("arguments") else payload.str("input")
                val args: JSONObject? = when {
                    ptype == "function_call" && rawArgs.startsWith("{") ->
                        try {
                            JSONObject(rawArgs)
                        } catch (_: Exception) {
                            null
                        }
                    ptype == "custom_tool_call" && rawArgs.isNotEmpty() ->
                        JSONObject().put("patch", rawArgs)
                    else -> null
                }
                outEvents.add(
                    TranscriptRawEvent.AssistantStep(
                        lineByteOffset = lineOffset,
                        createdAt = ts,
                        toolCalls = listOf(projectToolCall(name, args, callId, null))
                    )
                )
            }

            "function_call_output", "custom_tool_call_output" -> {
                val callId = payload.str("call_id")
                val status = payload.str("status")
                val outStr = payload.str("output")
                val failed = status == "failed" || isNonZeroCodexExit(outStr)
                if (failed && callId.isNotEmpty()) {
                    outEvents.add(
                        TranscriptRawEvent.ToolStatusUpdate(
                            lineByteOffset = lineOffset,
                            callId = callId,
                            hasError = true
                        )
                    )
                }
            }
        }
    }

    private fun isNonZeroCodexExit(output: String): Boolean {
        val marker = "Process exited with code "
        val idx = output.indexOf(marker)
        if (idx < 0) return false
        val start = idx + marker.length
        var end = start
        while (end < output.length && output[end].isDigit()) end++
        return end > start && output.substring(start, end) != "0"
    }

    private fun projectClaude(
        obj: JSONObject,
        lineOffset: Long,
        outEvents: MutableList<TranscriptRawEvent>
    ) {
        if (obj.optBoolean("isMeta", false) || obj.optBoolean("isCompactSummary", false)) return
        val type = obj.str("type")
        val ts = obj.strOrNull("timestamp")
        val msg = obj.dict("message") ?: return

        if (type == "user") {
            val rawContent = if (msg.has("content") && !msg.isNull("content")) msg.opt("content") else null
            val userTextSb = StringBuilder()
            when (rawContent) {
                is String -> userTextSb.append(rawContent)
                is JSONArray -> {
                    for (i in 0 until rawContent.length()) {
                        val item = rawContent.optJSONObject(i) ?: continue
                        when (item.str("type")) {
                            "tool_result" -> {
                                if (item.optBoolean("is_error", false)) {
                                    val tid = item.str("tool_use_id")
                                    if (tid.isNotEmpty()) {
                                        outEvents.add(
                                            TranscriptRawEvent.ToolStatusUpdate(
                                                lineByteOffset = lineOffset,
                                                callId = tid,
                                                hasError = true
                                            )
                                        )
                                    }
                                }
                            }
                            "text" -> {
                                val t = item.str("text")
                                if (t.isNotEmpty()) {
                                    if (userTextSb.isNotEmpty()) userTextSb.append('\n')
                                    userTextSb.append(t)
                                }
                            }
                            "image" -> {
                                if (userTextSb.isNotEmpty()) userTextSb.append(' ')
                                userTextSb.append("[📎 图片]")
                            }
                        }
                    }
                }
            }
            var cleanUser = stripTagPairs(userTextSb.toString(), "<system-reminder>", "</system-reminder>")
            cleanUser = stripTagPairs(cleanUser, "<local-command-caveat>", "</local-command-caveat>").trim()
            if (cleanUser.isNotEmpty()) {
                outEvents.add(
                    TranscriptRawEvent.UserMessage(
                        lineByteOffset = lineOffset,
                        createdAt = ts,
                        text = capText(cleanUser, 32_768)
                    )
                )
            }
            return
        }

        if (type == "assistant") {
            val model = msg.strOrNull("model")
            val usage = msg.dict("usage")
            val inTok = usage?.optLong("input_tokens", 0L)?.coerceAtLeast(0L) ?: 0L
            val cacheTok = usage?.optLong("cache_read_input_tokens", 0L)?.coerceAtLeast(0L) ?: 0L
            val outTok = usage?.optLong("output_tokens", 0L)?.coerceAtLeast(0L) ?: 0L

            val thSb = StringBuilder()
            val mdSb = StringBuilder()
            val tcList = ArrayList<TranscriptToolCallSummary>()

            val contentList = msg.arr("content")
            if (contentList != null) {
                for (i in 0 until contentList.length()) {
                    val item = contentList.optJSONObject(i) ?: continue
                    when (item.str("type")) {
                        "thinking" -> {
                            val th = item.str("thinking")
                            if (th.isNotEmpty()) {
                                if (thSb.isNotEmpty()) thSb.append("\n\n")
                                thSb.append(th)
                            }
                        }
                        "text" -> {
                            val tx = item.str("text")
                            if (tx.isNotEmpty()) {
                                if (mdSb.isNotEmpty()) mdSb.append("\n\n")
                                mdSb.append(tx)
                            }
                        }
                        "tool_use" -> {
                            val tid = item.strOrNull("id")
                            val tname = item.str("name")
                            val input = item.dict("input")
                            if (tname.isNotEmpty()) {
                                tcList.add(projectToolCall(tname, input, tid, null))
                            }
                        }
                    }
                }
            }

            val thText = thSb.toString().trim()
            val mdText = mdSb.toString().trim()
            if (thText.isEmpty() && mdText.isEmpty() && tcList.isEmpty()) return

            outEvents.add(
                TranscriptRawEvent.AssistantStep(
                    lineByteOffset = lineOffset,
                    createdAt = ts,
                    model = model,
                    thinking = capText(thText, 24_576),
                    markdownContent = capText(mdText, 262_144),
                    toolCalls = tcList,
                    inputTokens = inTok,
                    cacheReadTokens = cacheTok,
                    outputTokens = outTok
                )
            )
        }
    }

    private fun projectToolCall(
        name: String,
        args: JSONObject?,
        callId: String?,
        resultStep: Int?
    ): TranscriptToolCallSummary {
        var primaryTarget = ""
        var detail = ""
        var changedFile: String? = null
        var diffPreview: String? = null

        when (name.lowercase()) {
            "read_file", "view_file", "read" -> {
                val fp = cleanArg(args.firstStr("Path", "file_path", "path", "AbsolutePath"))
                primaryTarget = baseName(fp)
                val s = cleanArg(args.firstStr("StartLine", "offset"))
                val e = cleanArg(args.firstStr("EndLine", "limit"))
                if (s.isNotEmpty() || e.isNotEmpty()) {
                    detail = "L$s" + if (e.isNotEmpty()) "..$e" else ""
                }
            }

            "edit_file", "replace_file_content", "multi_replace_file_content",
            "write_file", "write_to_file", "edit", "multiedit", "write" -> {
                val fp = cleanArg(args.firstStr("Path", "TargetFile", "file_path", "path"))
                primaryTarget = baseName(fp)
                if (primaryTarget.isNotEmpty()) changedFile = primaryTarget
                val rep = cleanArg(args.firstStr("Replacement", "ReplacementContent", "new_string", "Content", "content"))
                val tgt = cleanArg(args.firstStr("Target", "TargetContent", "old_string"))
                if (tgt.isNotEmpty() || rep.isNotEmpty()) {
                    val delLines = if (tgt.isNotEmpty()) countLines(tgt) else 0
                    val addLines = if (rep.isNotEmpty()) countLines(rep) else 0
                    detail = "+$addLines" + (if (delLines > 0) " -$delLines" else "") + " 行"
                    diffPreview = when {
                        tgt.isNotEmpty() && rep.isNotEmpty() -> buildEditDiff(tgt, rep, 2800, 48)
                        rep.isNotEmpty() -> buildWriteDiff(rep, 1800, 24)
                        else -> null
                    }
                }
            }

            "apply_patch" -> {
                val patch = cleanArg(args.firstStr("patch", "input"))
                val pf = extractPatchFile(patch)
                primaryTarget = if (pf.isNotEmpty()) baseName(pf) else "patch"
                if (pf.isNotEmpty()) changedFile = primaryTarget
                val (add, del) = countPatchDiff(patch)
                if (add > 0 || del > 0) detail = "+$add -$del 行"
                diffPreview = buildPatchDiff(patch, 2800, 48)
            }

            "run_command", "exec_command", "bash", "shell", "shell_command" -> {
                val summary = cleanArg(args.firstStr("toolSummary", "description"))
                val cmd = cleanArg(args.firstStr("CommandLine", "cmd", "command"))
                primaryTarget = if (summary.isNotEmpty()) trunc(firstLine(summary), 90) else trunc(firstLine(cmd), 90)
                if (cmd.isNotEmpty() && summary.isNotEmpty()) detail = trunc(firstLine(cmd), 140)
            }

            "grep_search", "find_by_name", "grep", "glob", "search_web", "websearch" -> {
                val q = cleanArg(args.firstStr("Query", "Pattern", "pattern", "query"))
                val sp = cleanArg(args.firstStr("SearchPath", "SearchDirectory", "path"))
                primaryTarget = trunc(q, 80)
                if (sp.isNotEmpty()) detail = baseName(sp)
            }

            "list_dir", "ls" -> {
                val dp = cleanArg(args.firstStr("DirectoryPath", "path"))
                primaryTarget = baseName(dp)
            }

            "invoke_subagent", "agent", "task", "send_message" -> {
                primaryTarget = trunc(cleanArg(args.firstStr("Role", "subagent_type", "TypeName", "Recipient", "description")), 80)
            }

            else -> {
                if (args != null) {
                    val keys = args.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (args.isNull(k)) continue
                        val rawVal = args.opt(k)
                        if (rawVal is String) {
                            val v = cleanArg(rawVal)
                            if (v.length in 1..160) {
                                primaryTarget = trunc(firstLine(v), 80)
                                break
                            }
                        }
                    }
                }
            }
        }

        return TranscriptToolCallSummary(
            callId = callId,
            name = name,
            primaryTarget = primaryTarget,
            detailPreview = detail,
            changedFile = changedFile,
            resultStepIndex = resultStep,
            hasError = false,
            diffPreview = diffPreview
        )
    }

    // -------------------------------------------------------------------------
    // Diff generation, text extraction & SFTP byte-reading helpers
    // -------------------------------------------------------------------------

    private fun buildWriteDiff(content: String, maxChars: Int, maxLines: Int): String? {
        val lines = splitNormLines(content)
        if (lines.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("@@ -0,0 +1,").append(lines.size).append(" @@\n")
        val emit = minOf(lines.size, maxLines)
        for (i in 0 until emit) {
            val nextLn = "+${lines[i]}\n"
            if (sb.length + nextLn.length > maxChars) break
            sb.append(nextLn)
        }
        if (lines.size > emit) {
            sb.append("… (共 ").append(lines.size).append(" 行)")
        }
        return sb.toString().trimEnd('\r', '\n').ifEmpty { null }
    }

    private fun buildPatchDiff(patch: String, maxChars: Int, maxLines: Int): String? {
        if (patch.isEmpty()) return null
        val raw = splitNormLines(patch)
        val sb = StringBuilder()
        var count = 0
        for (ln in raw) {
            if (ln.startsWith("*** Begin Patch") ||
                ln.startsWith("*** End Patch") ||
                ln.startsWith("*** Update File:") ||
                ln.startsWith("*** Add File:") ||
                ln.startsWith("*** Delete File:")
            ) {
                continue
            }
            if (count >= maxLines || sb.length + ln.length + 1 > maxChars) {
                sb.append("… (已截断)")
                break
            }
            sb.append(ln).append('\n')
            count++
        }
        return sb.toString().trimEnd('\r', '\n').ifEmpty { null }
    }

    private fun buildEditDiff(targetText: String, replText: String, maxChars: Int, maxLines: Int): String? {
        val oldL = splitNormLines(targetText)
        val newL = splitNormLines(replText)
        var prefix = 0
        while (prefix < oldL.size && prefix < newL.size && oldL[prefix] == newL[prefix]) prefix++
        var suffix = 0
        while (oldL.size - suffix > prefix && newL.size - suffix > prefix &&
            oldL[oldL.size - 1 - suffix] == newL[newL.size - 1 - suffix]
        ) {
            suffix++
        }

        val oldMidStart = prefix
        val oldMidEnd = oldL.size - suffix
        val newMidStart = prefix
        val newMidEnd = newL.size - suffix

        val ctxBefore = minOf(2, prefix)
        val ctxAfter = minOf(2, suffix)
        val hunkOldStart = (oldMidStart - ctxBefore) + 1
        val hunkNewStart = (newMidStart - ctxBefore) + 1
        val hunkOldLen = (oldMidEnd - oldMidStart) + ctxBefore + ctxAfter
        val hunkNewLen = (newMidEnd - newMidStart) + ctxBefore + ctxAfter

        val sb = StringBuilder()
        sb.append("@@ -").append(hunkOldStart).append(',').append(hunkOldLen)
            .append(" +").append(hunkNewStart).append(',').append(hunkNewLen).append(" @@\n")
        var lineCount = 1

        for (i in (oldMidStart - ctxBefore) until oldMidStart) {
            val ln = " ${oldL[i]}\n"
            if (sb.length + ln.length > maxChars || lineCount >= maxLines) break
            sb.append(ln)
            lineCount++
        }

        val oldMidCount = oldMidEnd - oldMidStart
        val newMidCount = newMidEnd - newMidStart
        if (oldMidCount <= 40 && newMidCount <= 40 && (oldMidCount > 0 || newMidCount > 0)) {
            val dp = Array(oldMidCount + 1) { IntArray(newMidCount + 1) }
            for (i in oldMidCount - 1 downTo 0) {
                for (j in newMidCount - 1 downTo 0) {
                    dp[i][j] = if (oldL[oldMidStart + i] == newL[newMidStart + j]) {
                        dp[i + 1][j + 1] + 1
                    } else {
                        maxOf(dp[i + 1][j], dp[i][j + 1])
                    }
                }
            }
            var x = 0
            var y = 0
            while ((x < oldMidCount || y < newMidCount) && lineCount < maxLines) {
                val emit: String
                if (x < oldMidCount && y < newMidCount && oldL[oldMidStart + x] == newL[newMidStart + y]) {
                    emit = " ${oldL[oldMidStart + x]}\n"; x++; y++
                } else if (y < newMidCount && (x == oldMidCount || dp[x][y + 1] >= dp[x + 1][y])) {
                    emit = "+${newL[newMidStart + y]}\n"; y++
                } else {
                    emit = "-${oldL[oldMidStart + x]}\n"; x++
                }
                if (sb.length + emit.length > maxChars) {
                    sb.append("…\n")
                    break
                }
                sb.append(emit)
                lineCount++
            }
        } else {
            val delEmit = minOf(oldMidCount, 20)
            for (i in 0 until delEmit) {
                if (lineCount >= maxLines) break
                val ln = "-${oldL[oldMidStart + i]}\n"
                if (sb.length + ln.length > maxChars) break
                sb.append(ln)
                lineCount++
            }
            if (oldMidCount > delEmit) sb.append("… (-").append(oldMidCount - delEmit).append(" 行)\n")

            val addEmit = minOf(newMidCount, 20)
            for (i in 0 until addEmit) {
                if (lineCount >= maxLines) break
                val ln = "+${newL[newMidStart + i]}\n"
                if (sb.length + ln.length > maxChars) break
                sb.append(ln)
                lineCount++
            }
            if (newMidCount > addEmit) sb.append("… (+").append(newMidCount - addEmit).append(" 行)\n")
        }

        for (i in 0 until ctxAfter) {
            if (lineCount >= maxLines) break
            val ln = " ${oldL[oldMidEnd + i]}\n"
            if (sb.length + ln.length > maxChars) break
            sb.append(ln)
            lineCount++
        }

        return sb.toString().trimEnd('\r', '\n').ifEmpty { null }
    }

    private fun readBytesAt(file: SshSftpFile, offset: Long, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        val buf = ByteArray(count)
        var totalRead = 0
        while (totalRead < count) {
            val r = file.readAt(offset + totalRead, buf, totalRead, count - totalRead)
            if (r <= 0) break
            totalRead += r
        }
        return if (totalRead < count) buf.copyOf(totalRead) else buf
    }

    private fun alignForwardAfterNewline(file: SshSftpFile, fromOffset: Long, limit: Long): Long {
        val chunk = ByteArray(8192)
        var pos = fromOffset
        while (pos < limit) {
            val toRead = minOf(chunk.size.toLong(), limit - pos).toInt()
            val r = file.readAt(pos, chunk, 0, toRead)
            if (r <= 0) break
            for (i in 0 until r) {
                if (chunk[i] == 10.toByte()) return pos + i + 1L
            }
            pos += r
        }
        return -1L
    }

    private fun alignBackToLineStart(file: SshSftpFile, fromOffset: Long): Long {
        if (fromOffset <= 0L) return 0L
        var pos = fromOffset
        while (pos > 0L) {
            val start = maxOf(0L, pos - 65_536L)
            val len = (pos - start).toInt()
            val chunk = readBytesAt(file, start, len)
            if (chunk.isEmpty()) break
            for (i in chunk.size - 1 downTo 0) {
                if (chunk[i] == 10.toByte()) return start + i + 1L
            }
            pos = start
        }
        return 0L
    }

    private fun lastIndexOfNewline(buf: ByteArray, limit: Int): Int {
        for (i in (minOf(limit, buf.size) - 1) downTo 0) {
            if (buf[i] == 10.toByte()) return i
        }
        return -1
    }

    private fun decodeUtf8Lines(buf: ByteArray, skipFirstPartial: Boolean): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        if (skipFirstPartial) {
            val firstNl = buf.indexOfFirst { it == 10.toByte() }
            if (firstNl < 0) return emptyList()
            start = firstNl + 1
        }
        var lineStart = start
        for (i in start until buf.size) {
            if (buf[i] == 10.toByte()) {
                var len = i - lineStart
                if (len > 0 && buf[lineStart + len - 1] == 13.toByte()) len--
                if (len > 0) {
                    var s = String(buf, lineStart, len, StandardCharsets.UTF_8)
                    if (lineStart == 0 && s.startsWith("\uFEFF")) s = s.substring(1)
                    lines.add(s)
                }
                lineStart = i + 1
            }
        }
        if (lineStart < buf.size) {
            var len = buf.size - lineStart
            if (len > 0 && buf[lineStart + len - 1] == 13.toByte()) len--
            if (len > 0) lines.add(String(buf, lineStart, len, StandardCharsets.UTF_8))
        }
        return lines
    }

    private fun emptyChunk(path: String, fileSize: Long = 0L) = TranscriptChunkResult(
        path = path,
        startOffset = 0L,
        nextOffset = 0L,
        fileSize = fileSize,
        hasEarlier = false,
        resetRequired = false,
        events = emptyList()
    )

    // -------------------------------------------------------------------------
    // String, JSON, and path helpers (safe against Android `org.json` nulls)
    // -------------------------------------------------------------------------

    private fun JSONObject.str(key: String): String =
        if (!has(key) || isNull(key)) "" else opt(key)?.toString() ?: ""

    private fun JSONObject.strOrNull(key: String): String? =
        str(key).ifEmpty { null }

    private fun JSONObject.dict(key: String): JSONObject? =
        if (!has(key) || isNull(key)) null else optJSONObject(key)

    private fun JSONObject.arr(key: String): JSONArray? =
        if (!has(key) || isNull(key)) null else optJSONArray(key)

    private fun JSONObject?.firstStr(vararg keys: String): String {
        if (this == null) return ""
        for (k in keys) {
            val s = str(k)
            if (s.isNotEmpty()) return s
        }
        return ""
    }

    private fun cleanArg(raw: String): String {
        if (raw.isEmpty()) return ""
        val t = raw.trim()
        if (t.length >= 2 && t.first() == '"' && t.last() == '"') {
            val unquoted = try {
                JSONTokener(t).nextValue()
            } catch (_: Exception) {
                null
            }
            if (unquoted is String) return unquoted.trim()
        }
        return t
    }

    private fun splitNormLines(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    private fun baseName(path: String): String {
        if (path.isEmpty()) return ""
        val norm = path.trimEnd('\\', '/')
        val idx = maxOf(norm.lastIndexOf('/'), norm.lastIndexOf('\\'))
        return if (idx >= 0 && idx + 1 < norm.length) norm.substring(idx + 1) else norm
    }

    private fun firstLine(s: String): String {
        if (s.isEmpty()) return ""
        val idx = s.indexOfAny(charArrayOf('\r', '\n'))
        return (if (idx >= 0) s.substring(0, idx) else s).trim()
    }

    private fun trunc(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max - 1) + "…"

    private fun countLines(s: String): Int {
        if (s.isEmpty()) return 0
        var n = 1
        for (ch in s) if (ch == '\n') n++
        return n
    }

    private fun extractPatchFile(patch: String): String {
        if (patch.isEmpty()) return ""
        val markers = arrayOf("*** Update File: ", "*** Add File: ", "*** Delete File: ")
        for (marker in markers) {
            val idx = patch.indexOf(marker)
            if (idx >= 0) {
                val s = idx + marker.length
                val e = patch.indexOfAny(charArrayOf('\r', '\n'), s)
                return (if (e > s) patch.substring(s, e) else patch.substring(s)).trim()
            }
        }
        return ""
    }

    private fun countPatchDiff(patch: String): Pair<Int, Int> {
        if (patch.isEmpty()) return 0 to 0
        var add = 0
        var del = 0
        for (ln in splitNormLines(patch)) {
            if (ln.startsWith("+") && !ln.startsWith("+++")) add++
            else if (ln.startsWith("-") && !ln.startsWith("---")) del++
        }
        return add to del
    }

    private fun extractAgyModelChange(raw: String): String {
        if (raw.isEmpty()) return ""
        val body = extractTagBody(raw, "<USER_SETTINGS_CHANGE>", "</USER_SETTINGS_CHANGE>") ?: return ""
        val toIdx = body.indexOf(" to ")
        if (toIdx < 0) return ""
        val endIdx = body.indexOf('.', toIdx + 4)
        val cand = if (endIdx > toIdx) body.substring(toIdx + 4, endIdx) else body.substring(toIdx + 4)
        return cand.trim().trim('\'', '"', '`')
    }

    private fun extractTagBody(raw: String, openTag: String, closeTag: String): String? {
        val s = raw.indexOf(openTag)
        if (s < 0) return null
        val contentStart = s + openTag.length
        val e = raw.indexOf(closeTag, contentStart)
        return if (e < 0) raw.substring(contentStart) else raw.substring(contentStart, e)
    }

    private fun stripTagPairs(raw: String, openTag: String, closeTag: String): String {
        var cur = raw
        while (true) {
            val s = cur.indexOf(openTag)
            if (s < 0) break
            val e = cur.indexOf(closeTag, s + openTag.length)
            cur = if (e < 0) {
                cur.substring(0, s)
            } else {
                cur.substring(0, s) + cur.substring(e + closeTag.length)
            }
            if (e < 0) break
        }
        return cur
    }

    private fun stripXmlTagsBlock(raw: String): String {
        var s = stripTagPairs(raw, "<ADDITIONAL_METADATA>", "</ADDITIONAL_METADATA>")
        s = stripTagPairs(s, "<USER_SETTINGS_CHANGE>", "</USER_SETTINGS_CHANGE>")
        return s
    }

    private fun capText(s: String, maxChars: Int): String =
        if (s.length <= maxChars) s else s.substring(0, maxChars) + "\n… (内容已截断)"

    private fun joinRemote(base: String, vararg segments: String): String {
        val cleanBase = base.replace('\\', '/').let {
            if (it == "/") "/" else it.trimEnd('/')
        }
        val sb = StringBuilder(cleanBase)
        for (seg in segments) {
            val cleanSeg = seg.replace('\\', '/').trim('/')
            if (cleanSeg.isEmpty()) continue
            if (sb.isNotEmpty() && sb.last() != '/') sb.append('/')
            sb.append(cleanSeg)
        }
        return sb.toString()
    }

    private fun parentRemotePath(path: String): String? {
        val norm = path.trimEnd('/', '\\')
        val idx = maxOf(norm.lastIndexOf('/'), norm.lastIndexOf('\\'))
        if (idx <= 0) return null
        return norm.substring(0, idx)
    }

    private fun fileNameOf(path: String): String = baseName(path)

    private fun fileNameWithoutExtension(name: String): String {
        val base = baseName(name)
        val dot = base.lastIndexOf('.')
        return if (dot > 0) base.substring(0, dot) else base
    }

    private fun normalizeComparePath(p: String): String {
        if (p.isBlank()) return ""
        var norm = p.trim().replace('\\', '/')
        if (norm.length >= 3 && norm[0] == '/' && norm[1].isLetter() && norm[2] == ':') {
            norm = norm.substring(1)
        }
        while (norm.length > 3 && norm.endsWith('/')) {
            norm = norm.dropLast(1)
        }
        val isWindowsPath = (norm.length >= 2 && norm[0].isLetter() && norm[1] == ':') ||
            norm.startsWith("//") ||
            platform == HostPlatform.WINDOWS
        return if (isWindowsPath) norm.lowercase() else norm
    }

    private fun buildCwdBoundaryVariants(rawCwd: String, normCwd: String): List<String> {
        val slash = rawCwd.trim().replace('\\', '/').let {
            if (it.length >= 3 && it[0] == '/' && it[1].isLetter() && it[2] == ':') it.substring(1) else it
        }.let { if (it.length > 3) it.trimEnd('/') else it }
        val backslash = slash.replace('/', '\\')
        val jsonEscaped = backslash.replace("\\", "\\\\")
        return linkedSetOf(jsonEscaped, backslash, slash, normCwd).filter { it.isNotEmpty() }
    }

    private fun containsCwdBoundary(text: String, needle: String): Boolean {
        if (text.isEmpty() || needle.isEmpty()) return false
        var pos = 0
        while (pos < text.length) {
            val idx = text.indexOf(needle, pos, ignoreCase = true)
            if (idx < 0) return false
            val after = idx + needle.length
            if (after >= text.length) return true
            val next = text[after]
            if (next == '\\' || next == '/' || next == '"' || next == '\'' ||
                next == '\r' || next == '\n' || next == ' ' || next == '`' || next == '<'
            ) {
                return true
            }
            pos = after
        }
        return false
    }

    private fun isUuid36(s: String): Boolean {
        if (s.length != 36) return false
        for (i in 0 until 36) {
            val c = s[i]
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') return false
            } else {
                val hex = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
                if (!hex) return false
            }
        }
        return true
    }

    private fun encodeClaudeProjectSlug(cwd: String): String {
        val norm = cwd.trim().let {
            if (it.length >= 3 && (it[0] == '/' || it[0] == '\\') && it[1].isLetter() && it[2] == ':') {
                it.substring(1)
            } else {
                it
            }
        }
        return buildString(norm.length) {
            for (ch in norm) {
                append(if (ch.isLetterOrDigit()) ch else '-')
            }
        }
    }

    companion object {
        const val DEFAULT_WINDOW_BYTES: Long = 2_097_152L
        const val MIN_INITIAL_USER_TURNS: Int = 24
        private const val MAX_CHUNK_READ_BYTES: Int = 4_194_304
        private const val MAX_SINGLE_LINE_BYTES: Int = 16_777_216
        private const val MAX_STEP_OUTPUT_BYTES: Int = 32_768
        private const val MAX_INITIAL_BACKFILL_BYTES: Long = 10_485_760L
        private const val MAX_FULL_HYDRATE_SCAN_BYTES: Long = 20_971_520L
        private const val HISTORY_TAIL_BYTES: Long = 262_144L
    }
}
