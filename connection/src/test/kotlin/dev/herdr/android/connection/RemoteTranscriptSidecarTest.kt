package dev.herdr.android.connection

import dev.herdr.android.protocol.TranscriptAgentKind
import dev.herdr.android.protocol.TranscriptRawEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RemoteTranscriptSidecarTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun mockChannelHandlesHandshakeLocateTailHeadStepAndRespawnsOnWarmEof() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var execCount = 0
        val fakeSsh = object : AuthenticatedSshConnection {
            override val hostKeyFingerprint = "SHA256:test"
            override suspend fun exec(command: String): SshHcExecChannel {
                execCount++
                val idx = execCount
                return FakeSidecarExecChannel(dropSecondRequest = (idx == 1))
            }
            override fun close() = Unit
        }
        val sidecar = RemoteTranscriptSidecar(fakeSsh, HostPlatform.WINDOWS, scope)
        try {
            val loc = sidecar.locate(TranscriptAgentKind.ANTIGRAVITY, 1234L, "D:\\Work")
            assertNotNull(loc)
            assertEquals("uuid-abc", loc!!.sessionId)
            assertEquals(1, execCount)

            // Warm channel drops once on request 2 -> transparent idempotent retry respawns channel 2!
            val tail = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, loc.path, 0L)
            assertEquals(2, execCount)
            assertEquals(1, tail.events.size)
            val msg = tail.events.single() as TranscriptRawEvent.UserMessage
            assertEquals("停掉一切实例", msg.text)

            val head = sidecar.readHead(TranscriptAgentKind.ANTIGRAVITY, loc.path, 128L)
            assertFalse(head.hasEarlier)
            assertEquals(2, execCount) // Reuses warm channel 2 without respawning

            val stepOut = sidecar.readStepOutput(TranscriptAgentKind.ANTIGRAVITY, loc.path, 5)
            assertEquals("step 5 ok", stepOut)
        } finally {
            sidecar.close()
            scope.cancel()
        }
    }

    @Test
    fun realWindowsPowerShellSidecarStreamsIncrementalUtf8AndPreFiltersAllThreeAgents() = runBlocking {
        Assume.assumeTrue(System.getProperty("os.name").lowercase().contains("windows"))

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val cmdTokens = RemoteTranscriptSidecar.windowsBootstrapCommand().split(' ').filter { it.isNotEmpty() }
        val localSsh = object : AuthenticatedSshConnection {
            override val hostKeyFingerprint = "local-win"
            override suspend fun exec(command: String): SshHcExecChannel {
                val tokens = command.split(' ').filter { it.isNotEmpty() }
                val proc = ProcessBuilder(tokens).start()
                return object : SshHcExecChannel {
                    override val input: InputStream = proc.inputStream
                    override val output: OutputStream = proc.outputStream
                    override val stderr: InputStream = proc.errorStream
                    override fun close() {
                        runCatching { proc.outputStream.close() }
                        runCatching { proc.inputStream.close() }
                        runCatching { proc.errorStream.close() }
                        if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                            proc.destroyForcibly()
                        }
                    }
                }
            }
            override fun close() = Unit
        }
        assertTrue(cmdTokens[0].equals("powershell.exe", ignoreCase = true))

        val sidecar = RemoteTranscriptSidecar(localSsh, HostPlatform.WINDOWS, scope, initTimeoutMs = 20_000L)
        try {
            // 1. Antigravity transcript_full.jsonl + steps/2/output.txt
            val agyBrain = File(tempFolder.root, "brain-1/.system_generated").apply { mkdirs() }
            val agyLogs = File(agyBrain, "logs").apply { mkdirs() }
            val step2Dir = File(agyBrain, "steps/2").apply { mkdirs() }
            File(step2Dir, "output.txt").writeText("完整的第2步输出：停掉一切实例 OK", StandardCharsets.UTF_8)

            val agyLog = File(agyLogs, "transcript_full.jsonl")
            val hugeBody = "A".repeat(40_000)
            val largeReplacement = (1..20).joinToString("\\n") { "line $it" }
            val line1 = """{"step_index":0,"source":"USER_EXPLICIT","type":"USER_INPUT","status":"DONE","created_at":"2026-11-23T01:00:00Z","content":"<USER_REQUEST>\n停掉一切实例 🚀\n</USER_REQUEST>\n<USER_SETTINGS_CHANGE>\nThe user changed setting `Model Selection` from None to Gemini 4 Argon (High).\n</USER_SETTINGS_CHANGE>"}"""
            val line2 = """{"step_index":1,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","created_at":"2026-11-23T01:00:05Z","input_tokens":1200,"cache_read_tokens":600,"output_tokens":340,"thinking":"Analyze shutdown steps","content":"## 执行计划\n\n````kotlin\nfun stopAll() = \"停掉一切实例\"\n````\n\n| 模块 | 状态 |\n| :--- | :---: |\n| core | 完成 |","tool_calls":[{"name":"edit_file","args":{"Path":"\"D:\\\\Code\\\\Foo.kt\"","Target":"oldLine1\noldLine2","Replacement":"$largeReplacement"}}]}"""
            val line3GenericHuge = """{"step_index":2,"source":"MODEL","type":"GENERIC","status":"DONE","content":"$hugeBody"}"""
            val line4GenericErr = """{"step_index":9,"source":"MODEL","type":"GENERIC","status":"ERROR","error":"permission denied"}"""
            agyLog.writeText("$line1\n$line2\n$line3GenericHuge\n$line4GenericErr\n", StandardCharsets.UTF_8)

            val tail1 = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, agyLog.absolutePath, 0L)
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

            val err1 = tail1.events[2] as TranscriptRawEvent.ToolStatusUpdate
            assertEquals("step_9", err1.callId)
            assertTrue(err1.hasError)

            // Step output lookup
            val stepText = sidecar.readStepOutput(TranscriptAgentKind.ANTIGRAVITY, agyLog.absolutePath, 2)
            assertEquals("完整的第2步输出：停掉一切实例 OK", stepText)

            // Unchanged file -> 0 new events, same offset
            val tailSame = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, agyLog.absolutePath, tail1.nextOffset)
            assertEquals(tail1.nextOffset, tailSame.nextOffset)
            assertTrue(tailSame.events.isEmpty())

            // Append complete line + unterminated partial line -> only complete line emitted
            val line5 = """{"step_index":10,"source":"MODEL","type":"PLANNER_RESPONSE","status":"DONE","content":"全部已停掉"}"""
            val partial = """{"step_index":11,"source":"MOD"""
            agyLog.appendText("$line5\n$partial", StandardCharsets.UTF_8)

            val tailPartial = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, agyLog.absolutePath, tail1.nextOffset)
            assertEquals(1, tailPartial.events.size)
            assertEquals("全部已停掉", (tailPartial.events[0] as TranscriptRawEvent.AssistantStep).markdownContent)
            assertTrue(tailPartial.nextOffset < agyLog.length())

            // Finish partial line with \n
            agyLog.appendText("""EL","type":"PLANNER_RESPONSE","status":"DONE","content":"收尾完成"}""" + "\n", StandardCharsets.UTF_8)
            val tailComplete = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, agyLog.absolutePath, tailPartial.nextOffset)
            assertEquals(1, tailComplete.events.size)
            assertEquals("收尾完成", (tailComplete.events[0] as TranscriptRawEvent.AssistantStep).markdownContent)
            assertEquals(agyLog.length(), tailComplete.nextOffset)

            // Head upward pagination with small window (note sidecar clamps minimum window to 65_536; make >100KB file to test!)
            val bigLog = File(tempFolder.root, "big_transcript.jsonl")
            val sb = StringBuilder()
            for (k in 0 until 120) {
                val pad = "x".repeat(900)
                sb.append("""{"step_index":${k * 2},"source":"USER_EXPLICIT","type":"USER_INPUT","content":"<USER_REQUEST>\n第${k}轮消息 $pad\n</USER_REQUEST>"}""").append('\n')
            }
            bigLog.writeText(sb.toString(), StandardCharsets.UTF_8)
            val bigTail = sidecar.readTail(TranscriptAgentKind.ANTIGRAVITY, bigLog.absolutePath, 0L, windowBytes = 65_536L)
            assertTrue(bigTail.hasEarlier)
            assertTrue(bigTail.startOffset > 0L)
            val pageEarlier = sidecar.readHead(TranscriptAgentKind.ANTIGRAVITY, bigLog.absolutePath, bigTail.startOffset, windowBytes = 65_536L)
            assertFalse(pageEarlier.hasEarlier)
            assertEquals(0L, pageEarlier.startOffset)
            val totalRecovered = pageEarlier.events.size + bigTail.events.size
            assertEquals(120, totalRecovered)

            // 2. Codex rollout-*.jsonl
            val codexFile = File(tempFolder.root, "rollout-123.jsonl")
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
            val codexChunk = sidecar.readTail(TranscriptAgentKind.CODEX, codexFile.absolutePath, 0L)
            val codexUsers = codexChunk.events.filterIsInstance<TranscriptRawEvent.UserMessage>()
            assertEquals(1, codexUsers.size)
            assertEquals("请修复回归", codexUsers.single().text)
            assertTrue(codexChunk.events.any { it is TranscriptRawEvent.TokenUpdate && it.model == "gpt-5.2-codex" && it.effort == "high" })
            assertTrue(codexChunk.events.any { it is TranscriptRawEvent.ToolStatusUpdate && it.callId == "call_1" && it.hasError })
            val codexPatchEv = codexChunk.events.filterIsInstance<TranscriptRawEvent.AssistantStep>().first { it.toolCalls.isNotEmpty() }
            assertEquals("Foo.kt", codexPatchEv.toolCalls[0].changedFile)
            assertEquals("+1 -1 行", codexPatchEv.toolCalls[0].detailPreview)

            // 3. Claude Code .jsonl
            val claudeFile = File(tempFolder.root, "claude-session.jsonl")
            claudeFile.writeText(
                listOf(
                    """{"type":"user","isMeta":true,"message":{"role":"user","content":"ignored meta"}}""",
                    """{"type":"user","timestamp":"2026-11-23T03:00:00Z","message":{"role":"user","content":[{"type":"text","text":"<system-reminder>hide</system-reminder>\nCheck build"}]}}""",
                    """{"type":"assistant","timestamp":"2026-11-23T03:00:05Z","message":{"model":"claude-sonnet-4-5","usage":{"input_tokens":900,"cache_read_input_tokens":400,"output_tokens":120},"content":[{"type":"thinking","thinking":"Checking gradle"},{"type":"tool_use","id":"toolu_1","name":"Bash","input":{"command":"./gradlew test","description":"Run tests"}},{"type":"text","text":"测试全部通过。"}]}}""",
                    """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","is_error":true,"content":"fail"}]}}"""
                ).joinToString("\n") + "\n",
                StandardCharsets.UTF_8
            )
            val claudeChunk = sidecar.readTail(TranscriptAgentKind.CLAUDE, claudeFile.absolutePath, 0L)
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
        } finally {
            sidecar.close()
            scope.cancel()
        }
    }

    private class FakeSidecarExecChannel(private val dropSecondRequest: Boolean) : SshHcExecChannel {
        private val outQueue = LinkedBlockingQueue<ByteArray>()
        private val closed = AtomicBoolean(false)
        private val reqBuffer = ByteArrayOutputStream()

        override val input: InputStream = object : InputStream() {
            private var cur = byteArrayOf()
            private var pos = 0
            override fun read(): Int {
                while (pos >= cur.size) {
                    if (closed.get() && outQueue.isEmpty()) return -1
                    cur = outQueue.take()
                    pos = 0
                    if (cur.isEmpty()) return -1
                }
                return cur[pos++].toInt() and 255
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val first = read()
                if (first < 0) return -1
                b[off] = first.toByte()
                val rem = minOf(len - 1, cur.size - pos)
                cur.copyInto(b, off + 1, pos, pos + rem)
                pos += rem
                return rem + 1
            }
        }

        private var lineIndex = 0
        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) {
                if (closed.get()) throw IOException("closed")
                if (b == 10) {
                    val ln = reqBuffer.toString(StandardCharsets.UTF_8.name())
                    reqBuffer.reset()
                    handleLine(ln)
                } else if (b != 13) {
                    reqBuffer.write(b)
                }
            }
        }

        override val stderr: InputStream = ByteArrayInputStream(byteArrayOf())

        private fun handleLine(line: String) {
            if (lineIndex == 0) {
                lineIndex++
                send("""{"ok":true,"ready":true}""")
                return
            }
            lineIndex++
            if (dropSecondRequest && lineIndex == 3) {
                close()
                return
            }
            val req = JSONObject(line)
            val id = req.getLong("id")
            val resp = when (req.getString("op")) {
                "locate" -> """{"ok":true,"id":$id,"found":true,"agent":"agy","sessionId":"uuid-abc","path":"C:\\logs\\transcript_full.jsonl","fileSize":1024}"""
                "tail" -> """{"ok":true,"id":$id,"path":"C:\\logs\\transcript_full.jsonl","startOffset":0,"nextOffset":128,"fileSize":128,"hasEarlier":false,"reset":false,"events":[{"k":"user","o":0,"text":"停掉一切实例"}]}"""
                "head" -> """{"ok":true,"id":$id,"path":"C:\\logs\\transcript_full.jsonl","startOffset":0,"nextOffset":128,"fileSize":128,"hasEarlier":false,"reset":false,"events":[]}"""
                "step" -> """{"ok":true,"id":$id,"output":"step 5 ok"}"""
                else -> """{"ok":false,"id":$id,"error":"bad op"}"""
            }
            send(resp)
        }

        private fun send(s: String) {
            outQueue.put((s + "\n").toByteArray(StandardCharsets.UTF_8))
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                outQueue.offer(byteArrayOf())
            }
        }
    }
}
