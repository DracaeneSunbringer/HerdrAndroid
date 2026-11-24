package dev.herdr.android.connection

import dev.herdr.android.protocol.TranscriptAgentKind
import dev.herdr.android.protocol.TranscriptLocateHints
import dev.herdr.android.protocol.TranscriptRawEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class RemoteTranscriptSidecarTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun sftpSidecarHandlesLocateTailHeadIdleStatAndRespawnsOnWarmError() = runBlocking {
        val home = File(tempFolder.root, "home").apply { mkdirs() }
        val agyRoot = File(home, ".gemini/antigravity-cli").apply { mkdirs() }
        val uuid = "11111111-2222-3333-4444-555555555555"
        File(agyRoot, "conversations").apply { mkdirs() }
            .let { File(it, "$uuid.db-wal").writeBytes(byteArrayOf(1, 2, 3)) }
        File(agyRoot, "history.jsonl").writeText(
            """{"display":"hello","timestamp":1000,"workspace":"D:\\Work","conversationId":"$uuid"}""" + "\n",
            StandardCharsets.UTF_8
        )
        val logsDir = File(agyRoot, "brain/$uuid/.system_generated/logs").apply { mkdirs() }
        val compactLog = File(logsDir, "transcript.jsonl")
        compactLog.writeText(
            """{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","content":"<USER_REQUEST>\n停掉一切实例\n</USER_REQUEST>"}""" + "\n",
            StandardCharsets.UTF_8
        )
        val stepsDir = File(agyRoot, "brain/$uuid/.system_generated/steps/5").apply { mkdirs() }
        File(stepsDir, "output.txt").writeText("step 5 ok", StandardCharsets.UTF_8)

        val execCount = AtomicInteger(0)
        val openSftpCount = AtomicInteger(0)
        val openReadCount = AtomicInteger(0)
        var dropNextOpenRead = false

        val fakeSsh = object : AuthenticatedSshConnection {
            override val hostKeyFingerprint = "SHA256:test"
            override suspend fun exec(command: String): SshHcExecChannel {
                execCount.incrementAndGet()
                throw AssertionError("SFTP sidecar must never spawn exec()")
            }

            override suspend fun openSftp(): SshSftpChannel {
                val chIndex = openSftpCount.incrementAndGet()
                val delegate = LocalFsSftpChannel(home, maxPacketBytes = 32_768)
                return object : SshSftpChannel by delegate {
                    override fun openRead(path: String): SshSftpFile {
                        if (chIndex == 1 && dropNextOpenRead) {
                            dropNextOpenRead = false
                            throw IOException("simulated SFTP channel drop")
                        }
                        openReadCount.incrementAndGet()
                        return delegate.openRead(path)
                    }
                }
            }

            override fun close() = Unit
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sidecar = RemoteTranscriptSidecar(fakeSsh, HostPlatform.WINDOWS, scope)
        try {
            val loc = sidecar.locate(TranscriptAgentKind.ANTIGRAVITY, 1234L, "D:\\Work")
            assertNotNull(loc)
            assertEquals(uuid, loc!!.sessionId)
            assertEquals(0, execCount.get())
            assertEquals(1, openSftpCount.get())

            // Simulate the warm SFTP channel dropping right before readTail -> auto-reconnects channel 2
            dropNextOpenRead = true
            val readsBeforeTail = openReadCount.get()
            val tail = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, loc.path, 0L)
            assertEquals(2, openSftpCount.get())
            assertEquals(readsBeforeTail + 1, openReadCount.get())
            assertEquals(1, tail.events.size)
            val msg = tail.events.single() as TranscriptRawEvent.UserMessage
            assertEquals("停掉一切实例", msg.text)

            // Idle poll when offset == fileSize uses stat only and never opens remote file
            val idle = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, loc.path, tail.nextOffset)
            assertEquals(tail.nextOffset, idle.nextOffset)
            assertTrue(idle.events.isEmpty())
            assertEquals(readsBeforeTail + 1, openReadCount.get())

            val head = sidecar.readHead(TranscriptAgentKind.ANTIGRAVITY, loc.path, tail.nextOffset)
            assertFalse(head.hasEarlier)
            assertEquals(2, openSftpCount.get())

            val stepOut = sidecar.readStepOutput(TranscriptAgentKind.ANTIGRAVITY, loc.path, 5)
            assertEquals("step 5 ok", stepOut)
            assertEquals(0, execCount.get())
        } finally {
            sidecar.close()
            scope.cancel()
        }
    }

    @Test
    fun localSftpSidecarLocatesAndStreamsAllThreeAgentsWithHydrationAndBackfill() = runBlocking {
        val home = File(tempFolder.root, "home2").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val localSsh = object : AuthenticatedSshConnection {
            override val hostKeyFingerprint = "local-sftp"
            override suspend fun exec(command: String): SshHcExecChannel {
                throw AssertionError("exec() must not be called")
            }

            override suspend fun openSftp(): SshSftpChannel = LocalFsSftpChannel(home, maxPacketBytes = 32_768)
            override fun close() = Unit
        }

        val sidecar = RemoteTranscriptSidecar(localSsh, HostPlatform.WINDOWS, scope)
        try {
            // 1. Antigravity locate via brain fallback + transcript_full.jsonl + steps/2/output.txt
            val agyUuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
            val agyBrain = File(home, ".gemini/antigravity-cli/brain/$agyUuid/.system_generated").apply { mkdirs() }
            val agyLogs = File(agyBrain, "logs").apply { mkdirs() }
            val step2Dir = File(agyBrain, "steps/2").apply { mkdirs() }
            File(step2Dir, "output.txt").writeText("完整的第2步输出：停掉一切实例 OK", StandardCharsets.UTF_8)

            val agyLog = File(agyLogs, "transcript_full.jsonl")
            val hugeBody = "A".repeat(40_000)
            val largeReplacement = (1..20).joinToString("\\n") { "line $it" }
            val line1 = """{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"2026-11-23T01:00:00Z","content":"<USER_REQUEST>\n停掉一切实例 🚀\n</USER_REQUEST>\n<USER_SETTINGS_CHANGE>\nThe user changed setting `Model Selection` from None to Gemini 4 Argon (High).\n</USER_SETTINGS_CHANGE>\n<ADDITIONAL_METADATA>\nActive Workspace: D:\\Code\\Demo\n</ADDITIONAL_METADATA>"}"""
            val line2 = """{"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-11-23T01:00:05Z","input_tokens":1200,"cache_read_tokens":600,"output_tokens":340,"thinking":"Analyze shutdown steps","content":"## 执行计划\n\n````kotlin\nfun stopAll() = \"停掉一切实例\"\n````\n\n| 模块 | 状态 |\n| :--- | :---: |\n| core | 完成 |","tool_calls":[{"name":"edit_file","args":{"Path":"\"D:\\\\Code\\\\Foo.kt\"","Target":"oldLine1\noldLine2","Replacement":"$largeReplacement"}}]}"""
            val line3GenericHuge = """{"step_index":2,"source":"MODEL","type":"GENERIC","status":"DONE","content":"$hugeBody"}"""
            val line4GenericErr = """{"step_index":9,"source":"MODEL","type":"GENERIC","status":"ERROR","error":"permission denied"}"""
            agyLog.writeText("$line1\n$line2\n$line3GenericHuge\n$line4GenericErr\n", StandardCharsets.UTF_8)

            val locatedAgy = sidecar.locate(TranscriptAgentKind.ANTIGRAVITY, 999L, "D:\\Code\\Demo")
            assertNotNull(locatedAgy)
            assertEquals(agyUuid, locatedAgy!!.sessionId)

            val tail1 = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, locatedAgy.path, 0L)
            assertFalse(tail1.hasEarlier)
            assertEquals(agyLog.length(), tail1.nextOffset)
            assertEquals(3, tail1.events.size) // line3 GENERIC DONE stripped!
            val u1 = tail1.events[0] as TranscriptRawEvent.UserMessage
            assertEquals("停掉一切实例 🚀", u1.text)
            assertEquals("Gemini 4 Argon (High)", u1.modelChange)

            val a1 = tail1.events[1] as TranscriptRawEvent.AssistantStep
            assertTrue(a1.markdownContent.contains("````kotlin"))
            assertTrue(a1.markdownContent.contains("| 模块 | 状态 |"))
            assertEquals(1200L, a1.inputTokens)
            assertEquals(340L, a1.outputTokens)
            assertEquals(1, a1.toolCalls.size)
            assertEquals("Foo.kt", a1.toolCalls[0].changedFile)
            assertEquals("+20 -2 行", a1.toolCalls[0].detailPreview)
            assertEquals(2, a1.toolCalls[0].resultStepIndex)
            assertNotNull(a1.toolCalls[0].diffPreview)
            assertTrue(a1.toolCalls[0].diffPreview!!.contains("-oldLine1"))
            assertTrue(a1.toolCalls[0].diffPreview!!.contains("+line 1"))

            val err1 = tail1.events[2] as TranscriptRawEvent.ToolStatusUpdate
            assertEquals("step_9", err1.callId)
            assertTrue(err1.hasError)

            val stepText = sidecar.readStepOutput(TranscriptAgentKind.ANTIGRAVITY, locatedAgy.path, 2)
            assertEquals("完整的第2步输出：停掉一切实例 OK", stepText)

            // Compact transcript.jsonl + sibling transcript_full.jsonl selective hydration + step fallback
            val hydrateBrain = File(home, ".gemini/antigravity-cli/brain/22222222-3333-4444-5555-666666666666/.system_generated/logs").apply { mkdirs() }
            val hydrateCompact = File(hydrateBrain, "transcript.jsonl")
            val hydrateFull = File(hydrateBrain, "transcript_full.jsonl")
            val cLine1 = """{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","content":"<USER_REQUEST>\n第1轮提问\n</USER_REQUEST>"}"""
            val cLine2 = """{"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","content":"truncated preview\n<truncated 5000 bytes>","truncated_fields":["content","tool_calls"],"tool_calls":[{"name":"edit_file","args":{"Path":"\"D:\\\\Code\\\\Bar.kt\""}}]}"""
            val cLine3 = """{"step_index":2,"source":"MODEL","type":"GENERIC","status":"DONE","content":"short preview\n<truncated 9000 bytes>","truncated_fields":["content"]}"""
            val fLine2 = """{"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","content":"完整无损 Markdown 正文 ### 章节\n\n````kotlin\nval hydrated = true\n````","tool_calls":[{"name":"edit_file","args":{"Path":"\"D:\\\\Code\\\\Bar.kt\"","Target":"val old = 1\nval keep = 2","Replacement":"val old = 99\nval keep = 2"}}]}"""
            val fLine3 = """{"step_index":2,"source":"MODEL","type":"GENERIC","status":"DONE","content":"完整第2步输出：UNTRUNCATED_OK"}"""
            hydrateCompact.writeText("$cLine1\n$cLine2\n$cLine3\n", StandardCharsets.UTF_8)
            hydrateFull.writeText("$cLine1\n$fLine2\n$fLine3\n", StandardCharsets.UTF_8)

            val hCompactSftpPath = toTestSftpPath(hydrateCompact)
            val hTail = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, hCompactSftpPath, 0L)
            assertEquals(2, hTail.events.size)
            val hAsst = hTail.events[1] as TranscriptRawEvent.AssistantStep
            assertTrue(hAsst.markdownContent.contains("完整无损 Markdown 正文"))
            assertTrue(hAsst.toolCalls[0].diffPreview!!.contains("-val old = 1"))
            assertTrue(hAsst.toolCalls[0].diffPreview!!.contains("+val old = 99"))
            val hStep = sidecar.readStepOutput(TranscriptAgentKind.ANTIGRAVITY, hCompactSftpPath, 2)
            assertTrue(hStep.contains("UNTRUNCATED_OK"))

            // Unchanged file -> 0 new events, same offset
            val tailSame = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, locatedAgy.path, tail1.nextOffset)
            assertEquals(tail1.nextOffset, tailSame.nextOffset)
            assertTrue(tailSame.events.isEmpty())

            // Append complete line + unterminated partial line -> only complete line emitted
            val line5 = """{"step_index":10,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","content":"全部已停掉"}"""
            val partial = """{"step_index":11,"source":"MOD"""
            agyLog.appendText("$line5\n$partial", StandardCharsets.UTF_8)

            val tailPartial = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, locatedAgy.path, tail1.nextOffset)
            assertEquals(1, tailPartial.events.size)
            assertEquals("全部已停掉", (tailPartial.events[0] as TranscriptRawEvent.AssistantStep).markdownContent)
            assertTrue(tailPartial.nextOffset < agyLog.length())

            // Finish partial line with \n
            agyLog.appendText("""EL","type":"PLANNER_RESPONSE","status":"DONE","content":"收尾完成"}""" + "\n", StandardCharsets.UTF_8)
            val tailComplete = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, locatedAgy.path, tailPartial.nextOffset)
            assertEquals(1, tailComplete.events.size)
            assertEquals("收尾完成", (tailComplete.events[0] as TranscriptRawEvent.AssistantStep).markdownContent)
            assertEquals(agyLog.length(), tailComplete.nextOffset)

            // Head upward pagination + >= 24-turn initial backfill over 32 KB SFTP packet reads
            val bigLog = File(home, "big_transcript.jsonl")
            val sb = StringBuilder()
            for (k in 0 until 120) {
                val pad = "x".repeat(900)
                sb.append("""{"step_index":${k * 2},"source":"USER_EXPLICIT","type":"USER_INPUT","content":"<USER_REQUEST>\n第${k}轮消息 $pad\n</USER_REQUEST>"}""").append('\n')
            }
            bigLog.writeText(sb.toString(), StandardCharsets.UTF_8)
            val bigFastLog = File(home, "fast_paint_transcript.jsonl")
            val fastSb = StringBuilder()
            val widePad = "y".repeat(3_500)
            for (k in 0 until 32) {
                fastSb.append("""{"step_index":${k * 2},"source":"USER_EXPLICIT","type":"USER_INPUT","content":"<USER_REQUEST>\n第${k}轮消息 $widePad\n</USER_REQUEST>"}""").append('\n')
            }
            bigFastLog.writeText(fastSb.toString(), StandardCharsets.UTF_8)
            val fastSftpPath = toTestSftpPath(bigFastLog)
            val bigFastFirstPaint = sidecar.readTail(
                TranscriptAgentKind.ANTIGRAVITY,
                fastSftpPath,
                0L,
                windowBytes = 65_536L,
                minInitialTurns = 0
            )
            val fastTurns = bigFastFirstPaint.events.count { it is TranscriptRawEvent.UserMessage }
            assertTrue("Phase 1 fast first paint should return single window (< 24 turns), got $fastTurns", fastTurns in 1 until 24)
            assertTrue(bigFastFirstPaint.hasEarlier)
            assertTrue(bigFastFirstPaint.startOffset > 0L)

            val bigFastBackfilled = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, fastSftpPath, 0L, windowBytes = 65_536L)
            assertTrue(bigFastBackfilled.events.count { it is TranscriptRawEvent.UserMessage } >= 24)

            val bigSftpPath = toTestSftpPath(bigLog)
            val bigTail = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, bigSftpPath, 0L, windowBytes = 65_536L)
            assertTrue(bigTail.events.count { it is TranscriptRawEvent.UserMessage } >= 24)
            assertTrue(bigTail.hasEarlier)
            assertTrue(bigTail.startOffset > 0L)
            val pageEarlier = sidecar.readHead(TranscriptAgentKind.ANTIGRAVITY, bigSftpPath, bigTail.startOffset, windowBytes = 65_536L)
            assertFalse(pageEarlier.hasEarlier)
            assertEquals(0L, pageEarlier.startOffset)
            assertEquals(120, pageEarlier.events.size + bigTail.events.size)

            // 2. Codex rollout-*.jsonl locate + readTail
            val codexFile = File(home, ".codex/sessions/2026/11/24/rollout-123.jsonl").apply { parentFile.mkdirs() }
            codexFile.writeText(
                listOf(
                    """{"timestamp":"2026-11-23T02:00:00Z","type":"session_meta","payload":{"id":"cid-1","cwd":"D:\\Work"}}""",
                    """{"timestamp":"2026-11-23T02:00:00Z","type":"turn_context","payload":{"model":"gpt-5.2-codex","effort":"high"}}""",
                    """{"timestamp":"2026-11-23T02:00:01Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"# AGENTS.md instructions\nignored"}]}}""",
                    """{"timestamp":"2026-11-23T02:00:02Z","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"# Files mentioned by the user: Foo.kt\n## My request:\n请修复回归"}]}}""",
                    """{"timestamp":"2026-11-23T02:00:03Z","type":"response_item","payload":{"type":"reasoning","summary":[{"text":"Inspecting Foo.kt"}]}}""",
                    """{"timestamp":"2026-11-23T02:00:04Z","type":"response_item","payload":{"type":"custom_tool_call","call_id":"call_1","name":"apply_patch","input":"*** Begin Patch\n*** Update File: src/Foo.kt\n@@\n-old\n+new\n*** End Patch"}}""",
                    """{"timestamp":"2026-11-23T02:00:05Z","type":"response_item","payload":{"type":"function_call_output","call_id":"call_1","output":"Process exited with code 2\nfailed"}}""",
                    """{"timestamp":"2026-11-23T02:00:06Z","type":"event_msg","payload":{"type":"token_count","info":{"last_token_usage":{"input_tokens":500,"cached_input_tokens":200,"output_tokens":80}}}}""",
                    """{"timestamp":"2026-11-23T02:00:07Z","type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"已完成修复，请查看。"}]}}"""
                ).joinToString("\n") + "\n",
                StandardCharsets.UTF_8
            )
            val locatedCodex = sidecar.locate(TranscriptAgentKind.CODEX, null, "D:\\Work")
            assertNotNull(locatedCodex)
            assertEquals("cid-1", locatedCodex!!.sessionId)
            val codexChunk = sidecar.readTail(TranscriptAgentKind.CODEX, locatedCodex.path, 0L)
            val codexUsers = codexChunk.events.filterIsInstance<TranscriptRawEvent.UserMessage>()
            assertEquals(1, codexUsers.size)
            assertEquals("请修复回归", codexUsers.single().text)
            assertTrue(codexChunk.events.any { it is TranscriptRawEvent.TokenUpdate && it.model == "gpt-5.2-codex" && it.effort == "high" })
            assertTrue(codexChunk.events.any { it is TranscriptRawEvent.ToolStatusUpdate && it.callId == "call_1" && it.hasError })
            val codexPatchEv = codexChunk.events.filterIsInstance<TranscriptRawEvent.AssistantStep>().first { it.toolCalls.isNotEmpty() }
            assertEquals("Foo.kt", codexPatchEv.toolCalls[0].changedFile)
            assertEquals("+1 -1 行", codexPatchEv.toolCalls[0].detailPreview)

            // 3. Claude Code .jsonl locate via project slug + readTail
            val claudeFile = File(home, ".claude/projects/D--ClaudeProj/claude-session.jsonl").apply { parentFile.mkdirs() }
            claudeFile.writeText(
                listOf(
                    """{"type":"user","isMeta":true,"message":{"role":"user","content":"ignored meta"}}""",
                    """{"type":"user","timestamp":"2026-11-23T03:00:00Z","message":{"role":"user","content":[{"type":"text","text":"<system-reminder>hide</system-reminder>\nCheck build"}]}}""",
                    """{"type":"assistant","timestamp":"2026-11-23T03:00:05Z","message":{"model":"claude-sonnet-4-5","usage":{"input_tokens":900,"cache_read_input_tokens":400,"output_tokens":120},"content":[{"type":"thinking","thinking":"Checking gradle"},{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"./gradlew test","description":"Run tests"}},{"type":"text","text":"测试全部通过。"}]}}""",
                    """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","is_error":true,"content":"fail"}]}}"""
                ).joinToString("\n") + "\n",
                StandardCharsets.UTF_8
            )
            val locatedClaude = sidecar.locate(TranscriptAgentKind.CLAUDE, null, "D:\\ClaudeProj")
            assertNotNull(locatedClaude)
            assertEquals("claude-session", locatedClaude!!.sessionId)
            val claudeChunk = sidecar.readTail(TranscriptAgentKind.CLAUDE, locatedClaude.path, 0L)
            assertEquals(3, claudeChunk.events.size)
            val cUser = claudeChunk.events[0] as TranscriptRawEvent.UserMessage
            assertEquals("Check build", cUser.text)
            val cAsst = claudeChunk.events[1] as TranscriptRawEvent.AssistantStep
            assertEquals("claude-sonnet-4-5", cAsst.model)
            assertEquals(900L, cAsst.inputTokens)
            assertEquals("Run tests", cAsst.toolCalls.single().primaryTarget)
            assertEquals("测试全部通过。", cAsst.markdownContent)
            val cErr = claudeChunk.events[2] as TranscriptRawEvent.ToolStatusUpdate
            assertEquals("toolu_1", cErr.callId)
            assertTrue(cErr.hasError)

            // Tier 1 Claude session lookup via ~/.claude/sessions/<pid>.json
            val claudePidDir = File(home, ".claude/sessions").apply { mkdirs() }
            val pidSessionFile = File(home, ".claude/projects/D--OtherProj/pid-sess-42.jsonl").apply { parentFile.mkdirs() }
            pidSessionFile.writeText(
                """{"type":"user","timestamp":"2026-11-23T04:00:00Z","message":{"role":"user","content":"matched by pid"}}""" + "\n",
                StandardCharsets.UTF_8
            )
            File(claudePidDir, "4242.json").writeText("""{"pid":4242,"sessionId":"pid-sess-42"}""", StandardCharsets.UTF_8)
            val locatedClaudeByPid = sidecar.locate(TranscriptAgentKind.CLAUDE, 4242L, "D:\\UnrelatedCwd")
            assertNotNull(locatedClaudeByPid)
            assertEquals("pid-sess-42", locatedClaudeByPid!!.sessionId)
        } finally {
            sidecar.close()
            scope.cancel()
        }
    }

    @Test
    fun locateDisambiguatesConcurrentSameCwdSessionsAndIgnoresZombieWal() = runBlocking {
        val home = File(tempFolder.root, "home3").apply { mkdirs() }
        val agyRoot = File(home, ".gemini/antigravity-cli").apply { mkdirs() }
        val convDir = File(agyRoot, "conversations").apply { mkdirs() }
        val presDir = File(agyRoot, "presence").apply { mkdirs() }

        val zombieUuid = "11111111-1111-1111-1111-111111111111"
        val liveUuidA = "22222222-2222-2222-2222-222222222222"
        val liveUuidB = "33333333-3333-3333-3333-333333333333"

        // Crashed session left a stale .db-wal even though its presence.lock is no longer locked
        File(convDir, "$zombieUuid.db").writeBytes(byteArrayOf(1))
        File(convDir, "$zombieUuid.db-wal").writeBytes(byteArrayOf(1))
        File(presDir, "$zombieUuid.lock").createNewFile()

        // liveUuidA has checkpointed WAL (no .db-wal!) but active OS presence lock
        File(convDir, "$liveUuidA.db").writeBytes(byteArrayOf(1))
        File(presDir, "$liveUuidA.lock").createNewFile()

        // liveUuidB has both active .db-wal and active OS presence lock
        File(convDir, "$liveUuidB.db").writeBytes(byteArrayOf(1))
        File(convDir, "$liveUuidB.db-wal").writeBytes(byteArrayOf(1))
        File(presDir, "$liveUuidB.lock").createNewFile()

        // Newer mtimes go zombieUuid -> liveUuidB -> liveUuidA, intentionally making zombieUuid look newest
        File(agyRoot, "brain/$liveUuidA/.system_generated/logs").apply { mkdirs() }
            .let { File(it, "transcript.jsonl") }
            .apply {
                writeText(
                    """{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","content":"<USER_REQUEST>\n为什么苹果手机上使用 Clash Mi\n</USER_REQUEST>"}""" + "\n" +
                        """{"step_index":1,"type":"PLANNER_RESPONSE","status":"DONE","response":"已完成 路由器CF优选节点配置 并推送 v8.0.20"}""" + "\n",
                    StandardCharsets.UTF_8
                )
                setLastModified(1_700_000_010_000L)
            }
        File(agyRoot, "brain/$liveUuidB/.system_generated/logs").apply { mkdirs() }
            .let { File(it, "transcript.jsonl") }
            .apply {
                writeText(
                    """{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","content":"<USER_REQUEST>\n每次压缩都会重新阅读handoff\n</USER_REQUEST>"}""" + "\n" +
                        """{"step_index":1,"type":"PLANNER_RESPONSE","status":"DONE","response":"已接手 推箱子接手修复 并修正文档"}""" + "\n",
                    StandardCharsets.UTF_8
                )
                setLastModified(1_700_000_020_000L)
            }
        File(agyRoot, "brain/$zombieUuid/.system_generated/logs").apply { mkdirs() }
            .let { File(it, "transcript.jsonl") }
            .apply {
                writeText(
                    """{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","content":"<USER_REQUEST>\n隐私信息和key\n</USER_REQUEST>"}""" + "\n",
                    StandardCharsets.UTF_8
                )
                setLastModified(1_700_000_030_000L)
            }

        File(agyRoot, "history.jsonl").writeText(
            listOf(
                """{"display":"为什么苹果手机上使用 Clash Mi","timestamp":1000,"workspace":"D:\\luoyu\\Documents\\Code\\DaliySolve","conversationId":"$liveUuidA"}""",
                """{"display":"每次压缩都会重新阅读handoff","timestamp":2000,"workspace":"D:\\luoyu\\Documents\\Code\\DaliySolve","conversationId":"$liveUuidB"}""",
                """{"display":"隐私信息和key","timestamp":3000,"workspace":"D:\\luoyu\\Documents\\Code\\DaliySolve","conversationId":"$zombieUuid"}"""
            ).joinToString("\n") + "\n",
            StandardCharsets.UTF_8
        )

        val lockedFileNames = setOf("$liveUuidA.lock", "$liveUuidB.lock")
        val fakeSsh = object : AuthenticatedSshConnection {
            override val hostKeyFingerprint = "local-sftp"
            override suspend fun exec(command: String): SshHcExecChannel =
                throw AssertionError("exec() must not be called")

            override suspend fun openSftp(): SshSftpChannel {
                val delegate = LocalFsSftpChannel(home, maxPacketBytes = 32_768)
                return object : SshSftpChannel by delegate {
                    override fun openRead(path: String): SshSftpFile {
                        val fileName = path.replace('\\', '/').substringAfterLast('/')
                        if (fileName in lockedFileNames) {
                            throw IOException("locked by live antigravity process")
                        }
                        return delegate.openRead(path)
                    }
                }
            }

            override fun close() = Unit
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sidecar = RemoteTranscriptSidecar(fakeSsh, HostPlatform.WINDOWS, scope)
        try {
            val locA = sidecar.locate(
                agentKind = TranscriptAgentKind.ANTIGRAVITY,
                pid = null,
                cwd = "D:\\luoyu\\Documents\\Code\\DaliySolve",
                hints = TranscriptLocateHints(
                    userPrompts = listOf("为什么苹果手机上使用 Clash Mi"),
                    assistantAnchors = listOf("路由器CF优选节点配置")
                )
            )
            assertNotNull(locA)
            assertEquals(liveUuidA, locA!!.sessionId)

            val locB = sidecar.locate(
                agentKind = TranscriptAgentKind.ANTIGRAVITY,
                pid = null,
                cwd = "D:\\luoyu\\Documents\\Code\\DaliySolve",
                hints = TranscriptLocateHints(
                    userPrompts = listOf("每次压缩都会重新阅读handoff"),
                    assistantAnchors = listOf("推箱子接手修复"),
                    claimedPaths = setOf(locA.path)
                )
            )
            assertNotNull(locB)
            assertEquals(liveUuidB, locB!!.sessionId)

            // Even with zero screen hints, live presence lock outranks zombie .db-wal
            val locNoHints = sidecar.locate(
                agentKind = TranscriptAgentKind.ANTIGRAVITY,
                pid = null,
                cwd = "D:\\luoyu\\Documents\\Code\\DaliySolve"
            )
            assertNotNull(locNoHints)
            assertTrue("Must not pick dead zombie session", locNoHints!!.sessionId != zombieUuid)
        } finally {
            sidecar.close()
            scope.cancel()
        }
    }

    @Test
    fun locateCodexFindsResumedOlderMonthRolloutAndDisambiguatesByThreadTitle() = runBlocking {
        val home = File(tempFolder.root, "home_codex").apply { mkdirs() }
        val codexDir = File(home, ".codex").apply { mkdirs() }
        val novDay = File(codexDir, "sessions/2026/11/20").apply { mkdirs() }
        val octDay29 = File(codexDir, "sessions/2026/10/29").apply { mkdirs() }
        val octDay26 = File(codexDir, "sessions/2026/10/26").apply { mkdirs() }

        // 35 newer files in 2026/11/20 for another workspace so 2026/10/* sits past the old 32-file cutoff
        for (i in 1..35) {
            val f = File(novDay, "rollout-2026-11-20T00-00-${i.toString().padStart(2, '0')}-99999999-0000-0000-0000-0000000000${i.toString().padStart(2, '0')}.jsonl")
            f.writeText(
                """{"type":"session_meta","payload":{"id":"other-$i","cwd":"D:\\OtherProject"}}""" + "\n",
                StandardCharsets.UTF_8
            )
            f.setLastModified(1_700_000_000_000L + i * 1_000L)
        }

        val targetIdA = "01a1768f-4c9d-7812-a064-627c30562349"
        val paddingA = "x".repeat(24_000)
        val fileA = File(octDay26, "rollout-2026-10-26T12-54-25-$targetIdA.jsonl").apply {
            writeText(
                """{"type":"session_meta","payload":{"id":"$targetIdA","cwd":"D:\\luoyu\\Documents\\Code\\DaliySolve","base_instructions":{"text":"$paddingA"}}}""" + "\n" +
                    """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"你先清了，这样搞我下次连不连得上都不一定"}]}}""" + "\n",
                StandardCharsets.UTF_8
            )
            setLastModified(1_700_050_000_000L)
        }

        val targetIdB = "01a18354-e424-73a0-9a62-88bda52757d7"
        val paddingB = "y".repeat(24_000)
        val fileB = File(octDay29, "rollout-2026-10-29T00-25-41-$targetIdB.jsonl").apply {
            writeText(
                """{"type":"session_meta","payload":{"id":"$targetIdB","base_instructions":{"text":"$paddingB"},"cwd":"D:\\luoyu\\Documents\\Code\\DaliySolve"}}""" + "\n" +
                    """{"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"继续同步上游 Actions"}]}}""" + "\n",
                StandardCharsets.UTF_8
            )
            // Intentionally make fileB slightly newer mtime than fileA so only threadTitle distinguishes them
            setLastModified(1_700_060_000_000L)
        }

        File(codexDir, "session_index.jsonl").writeText(
            listOf(
                """{"id":"$targetIdA","thread_name":"检查VPS漏洞、补丁需求与入侵痕迹","updated_at":"2026-10-26T04:55:36.160Z"}""",
                """{"id":"$targetIdB","thread_name":"同步 GFC 私有仓库并触发 Actions","updated_at":"2026-10-28T16:26:05.683Z"}"""
            ).joinToString("\n") + "\n",
            StandardCharsets.UTF_8
        )

        val fakeSsh = object : AuthenticatedSshConnection {
            override val hostKeyFingerprint = "local-sftp"
            override suspend fun exec(command: String): SshHcExecChannel =
                throw AssertionError("exec() must not be called")

            override suspend fun openSftp(): SshSftpChannel = LocalFsSftpChannel(home, maxPacketBytes = 32_768)
            override fun close() = Unit
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sidecar = RemoteTranscriptSidecar(fakeSsh, HostPlatform.WINDOWS, scope)
        try {
            val locA = sidecar.locate(
                agentKind = TranscriptAgentKind.CODEX,
                pid = null,
                cwd = "D:\\luoyu\\Documents\\Code\\DaliySolve",
                hints = TranscriptLocateHints(terminalTitle = "检查VPS漏洞、补丁需求与入侵痕迹 | DaliySolve")
            )
            assertNotNull("Resumed October rollout must be found past 35 November rollouts", locA)
            assertEquals(targetIdA, locA!!.sessionId)
            assertTrue(locA.path.endsWith(fileA.name))

            val locB = sidecar.locate(
                agentKind = TranscriptAgentKind.CODEX,
                pid = null,
                cwd = "D:\\luoyu\\Documents\\Code\\DaliySolve",
                hints = TranscriptLocateHints(terminalTitle = "同步 GFC 私有仓库并触发 Actions")
            )
            assertNotNull("UTC 2026-10-28T16:26Z rollout stored in 2026/10/29 must be resolved", locB)
            assertEquals(targetIdB, locB!!.sessionId)
            assertTrue(locB.path.endsWith(fileB.name))
        } finally {
            sidecar.close()
            scope.cancel()
        }
    }

    @Test
    fun editFileDiffPreservesFirstLineIndentationAndEmitsDeletionsBeforeAdditions() = runBlocking {
        val home = File(tempFolder.root, "home_diff").apply { mkdirs() }
        val agyRoot = File(home, ".gemini/antigravity-cli").apply { mkdirs() }
        val uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        File(agyRoot, "conversations").apply { mkdirs() }
            .let { File(it, "$uuid.db-wal").writeBytes(byteArrayOf(1)) }
        val logsDir = File(agyRoot, "brain/$uuid/.system_generated/logs").apply { mkdirs() }
        val compactLog = File(logsDir, "transcript.jsonl")
        val target = "        versionCode = 19\n        versionName = \"0.5.1\""
        val replacement = "        versionCode = 20\n        versionName = \"0.5.2\""
        val plannerJson = org.json.JSONObject()
            .put("step_index", 10)
            .put("type", "PLANNER_RESPONSE")
            .put("status", "DONE")
            .put(
                "tool_calls",
                org.json.JSONArray().put(
                    org.json.JSONObject()
                        .put("name", "edit_file")
                        .put(
                            "args",
                            org.json.JSONObject()
                                .put("Path", "D:\\repo\\app\\build.gradle.kts")
                                .put("Target", target)
                                .put("Replacement", replacement)
                        )
                )
            )
            .toString()
        compactLog.writeText(plannerJson + "\n", StandardCharsets.UTF_8)

        val fakeSsh = object : AuthenticatedSshConnection {
            override val hostKeyFingerprint = "local-sftp"
            override suspend fun exec(command: String): SshHcExecChannel =
                throw AssertionError("exec() must not be called")

            override suspend fun openSftp(): SshSftpChannel = LocalFsSftpChannel(home, maxPacketBytes = 32_768)
            override fun close() = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sidecar = RemoteTranscriptSidecar(fakeSsh, HostPlatform.WINDOWS, scope)
        try {
            val chunk = sidecar.readTail(
                agentKind = TranscriptAgentKind.ANTIGRAVITY,
                path = toTestSftpPath(compactLog),
                offset = 0L
            )
            val step = chunk.events.filterIsInstance<TranscriptRawEvent.AssistantStep>().single()
            val expectedDiff = listOf(
                "-        versionCode = 19",
                "-        versionName = \"0.5.1\"",
                "+        versionCode = 20",
                "+        versionName = \"0.5.2\""
            ).joinToString("\n")
            assertEquals(expectedDiff, step.toolCalls.single().diffPreview)
        } finally {
            sidecar.close()
            scope.cancel()
        }
    }

    @Test
    fun agyUserInputExtractsUploadedImageAndVideoBadges() = runBlocking {
        val home = File(tempFolder.root, "home_media").apply { mkdirs() }
        val agyRoot = File(home, ".gemini/antigravity-cli").apply { mkdirs() }
        val uuid = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff"
        File(agyRoot, "conversations").apply { mkdirs() }
            .let { File(it, "$uuid.db-wal").writeBytes(byteArrayOf(1)) }
        val logsDir = File(agyRoot, "brain/$uuid/.system_generated/logs").apply { mkdirs() }
        val compactLog = File(logsDir, "transcript.jsonl")

        val u1 = org.json.JSONObject()
            .put("step_index", 28)
            .put("type", "USER_INPUT")
            .put("source", "USER_EXPLICIT")
            .put(
                "content",
                """
                <ADDITIONAL_METADATA>
                The user has uploaded 3 image(s):
                - a.png
                - b.png
                - c.png
                </ADDITIONAL_METADATA>
                <USER_REQUEST>
                这是素材
                </USER_REQUEST>
                """.trimIndent()
            )
            .toString()
        val u2 = org.json.JSONObject()
            .put("step_index", 30)
            .put("type", "USER_INPUT")
            .put("source", "USER_EXPLICIT")
            .put(
                "content",
                """
                <ADDITIONAL_METADATA>
                The user has uploaded 2 video(s):
                - clip1.mp4
                </ADDITIONAL_METADATA>
                <USER_REQUEST>
                </USER_REQUEST>
                """.trimIndent()
            )
            .toString()
        compactLog.writeText("$u1\n$u2\n", StandardCharsets.UTF_8)

        val fakeSsh = object : AuthenticatedSshConnection {
            override val hostKeyFingerprint = "local-sftp"
            override suspend fun exec(command: String): SshHcExecChannel =
                throw AssertionError("exec() must not be called")

            override suspend fun openSftp(): SshSftpChannel = LocalFsSftpChannel(home, maxPacketBytes = 32_768)
            override fun close() = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val sidecar = RemoteTranscriptSidecar(fakeSsh, HostPlatform.WINDOWS, scope)
        try {
            val chunk = sidecar.readTail(
                agentKind = TranscriptAgentKind.ANTIGRAVITY,
                path = toTestSftpPath(compactLog),
                offset = 0L
            )
            val msgs = chunk.events.filterIsInstance<TranscriptRawEvent.UserMessage>()
            assertEquals(2, msgs.size)
            assertEquals("这是素材\n[📎 3 image(s)]", msgs[0].text)
            assertEquals("[📎 2 video(s)]", msgs[1].text)
        } finally {
            sidecar.close()
            scope.cancel()
        }
    }

    private fun toTestSftpPath(file: File): String {
        val slash = file.absolutePath.replace('\\', '/')
        return if (slash.length >= 2 && slash[0].isLetter() && slash[1] == ':') "/$slash" else slash
    }

    /**
     * Local filesystem SFTP test double that mimics Windows OpenSSH `sftp-server.exe` behavior:
     * - `homeDirectory()` returns `/C:/...` formatted canonical home
     * - `readAt()` caps each single read return at [maxPacketBytes] (default 32 KB)
     */
    private class LocalFsSftpChannel(
        private val homeRoot: File,
        private val maxPacketBytes: Int = 32_768
    ) : SshSftpChannel {

        private fun normalizeToSftpString(file: File): String {
            val slash = file.absolutePath.replace('\\', '/')
            return if (slash.length >= 2 && slash[0].isLetter() && slash[1] == ':') "/$slash" else slash
        }

        private fun resolveLocal(path: String): File {
            var p = path.trim().replace('\\', '/')
            if (p.length >= 3 && p[0] == '/' && p[1].isLetter() && p[2] == ':') {
                p = p.substring(1)
            }
            return File(p)
        }

        override fun homeDirectory(): String = normalizeToSftpString(homeRoot)

        override fun statOrNull(path: String): SftpEntryInfo? {
            val f = resolveLocal(path)
            if (!f.exists()) return null
            return SftpEntryInfo(
                name = f.name,
                path = normalizeToSftpString(f),
                isDirectory = f.isDirectory,
                isRegularFile = f.isFile,
                size = if (f.isFile) f.length() else 0L,
                mtimeSeconds = f.lastModified() / 1000L
            )
        }

        override fun listDir(path: String): List<SftpEntryInfo> {
            val dir = resolveLocal(path)
            if (!dir.isDirectory) return emptyList()
            val children = dir.listFiles() ?: return emptyList()
            return children.map { f ->
                SftpEntryInfo(
                    name = f.name,
                    path = normalizeToSftpString(f),
                    isDirectory = f.isDirectory,
                    isRegularFile = f.isFile,
                    size = if (f.isFile) f.length() else 0L,
                    mtimeSeconds = f.lastModified() / 1000L
                )
            }
        }

        override fun openRead(path: String): SshSftpFile {
            val f = resolveLocal(path)
            val raf = RandomAccessFile(f, "r")
            return object : SshSftpFile {
                override fun length(): Long = raf.length()

                override fun readAt(fileOffset: Long, dst: ByteArray, dstOffset: Int, len: Int): Int {
                    if (len <= 0) return 0
                    raf.seek(fileOffset)
                    val capped = minOf(len, maxPacketBytes)
                    return raf.read(dst, dstOffset, capped)
                }

                override fun close() {
                    raf.close()
                }
            }
        }

        override fun close() = Unit
    }
}
