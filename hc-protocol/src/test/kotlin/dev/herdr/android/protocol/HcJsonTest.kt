package dev.herdr.android.protocol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HcJsonTest {
    @Test fun errorsKeepStableKindHintAndCode() {
        val response = JSONObject("""{"jsonrpc":"2.0","id":5,"error":{"code":-32000,"message":"busy","data":{"kind":"blocked","hint":"ask first"}}}""")
        val error = assertThrows(HcRpcException::class.java) { HcJson.result(response) }
        assertEquals("blocked", error.kind)
        assertEquals("ask first", error.hint)
        assertEquals("busy", error.message)
        assertEquals(-32000, error.code)
    }

    @Test fun rejectsWrongGenerationAndUnframedTransport() {
        val hello = JSONObject("""{"generation":2,"version":"9","framed":true,"core":{"id":"x","name":"desktop"}}""")
        assertEquals("unsupported_generation", assertThrows(HcRpcException::class.java) { HcJson.hello(hello) }.kind)
        hello.put("generation", 1).put("framed", false)
        assertThrows(HcProtocolException::class.java) { HcJson.hello(hello) }
        assertThrows(HcProtocolException::class.java) { HcJson.result(JSONObject("""{"result":{}}""")) }
    }

    @Test fun terminalMappingPreservesTopologyAndUsesForegroundWorkspace() {
        val summary = HcJson.terminal(JSONObject("""{
            "address":"linux-node/t7","name":"worker","parent":"t1q","state":"live",
            "cwd":"/old","foreground":{"cwd":"/current"},"cols":132,"rows":44,"turn":8,
            "labels":{"where":"backend","herdr.space":"s1","herdr.tab":"b1","ignored":7},
            "status":{"app":"codex","state":"blocked","msg":"needs input"}
        }"""))
        assertEquals("linux-node/t7", summary.address)
        assertEquals("t1q", summary.parent)
        assertEquals("/current", summary.cwd)
        assertEquals("backend", summary.whereLabel)
        assertEquals("s1", summary.spaceId)
        assertEquals("b1", summary.tabId)
        assertEquals("codex", summary.agentApp)
        assertEquals(AgentStateWord.BLOCKED, summary.agentStatus)
        assertEquals("needs input", summary.agentMessage)
        assertFalse(summary.labels.containsKey("ignored"))
        assertEquals(132, summary.cols)
        assertEquals(44, summary.rows)
        assertNull(summary.rootPid)
        assertNull(summary.foregroundPid)
    }

    @Test fun terminalGetPidAndForegroundPidAreExtracted() {
        val summary = HcJson.terminal(JSONObject("""{
            "address":"t1q","state":"live","pid":13592,
            "foreground":{"pid":10964,"name":"agy.exe","cwd":"D:\\Code"},
            "cols":120,"rows":40,"turn":3
        }"""))
        assertEquals(13592L, summary.rootPid)
        assertEquals(10964L, summary.foregroundPid)

        val fallback = HcJson.terminal(JSONObject("""{
            "address":"t1r","state":"live","pid":24680,
            "foreground":{"name":"pwsh.exe","cwd":"D:\\Code"}
        }"""))
        assertEquals(24680L, fallback.rootPid)
        assertEquals(24680L, fallback.foregroundPid)
    }

    @Test fun transcriptWireJsonParsesLocateChunkAndStepResponses() {
        val loc = TranscriptWireJson.parseLocateResponse(
            """{"ok":true,"id":1,"found":true,"agent":"agy","sessionId":"uuid-1","path":"C:\\logs\\transcript_full.jsonl","fileSize":4096}""",
            TranscriptAgentKind.ANTIGRAVITY
        )
        assertNotNull(loc)
        assertEquals(TranscriptAgentKind.ANTIGRAVITY, loc!!.agentKind)
        assertEquals("uuid-1", loc.sessionId)
        assertEquals(4096L, loc.fileSize)

        val notFound = TranscriptWireJson.parseLocateResponse(
            """{"ok":true,"id":2,"found":false}""",
            TranscriptAgentKind.CODEX
        )
        assertNull(notFound)

        val chunk = TranscriptWireJson.parseChunkResponse(
            """{"ok":true,"id":3,"path":"p","startOffset":100,"nextOffset":512,"fileSize":512,"hasEarlier":true,"reset":false,"events":[
                {"k":"user","o":100,"s":0,"ts":"2026-11-23T01:00:00Z","text":"hello","mc":"gpt-5"},
                {"k":"assistant","o":200,"s":1,"ts":"2026-11-23T01:00:05Z","st":"DONE","m":"gemini-2.5-pro","th":"plan","md":"# Title\n\nBody","in":100,"cin":50,"out":25,"tc":[{"id":"step_2","n":"edit_file","t":"A.kt","d":"+2 -1","f":"A.kt","rs":2,"err":false}]},
                {"k":"tokens","o":350,"m":"gpt-5","ef":"high","in":400,"cin":200,"out":90},
                {"k":"tool_err","o":420,"id":"step_2","err":true}
            ]}""",
            "fallback"
        )
        assertEquals("p", chunk.path)
        assertEquals(100L, chunk.startOffset)
        assertEquals(512L, chunk.nextOffset)
        assertTrue(chunk.hasEarlier)
        assertEquals(4, chunk.events.size)
        val asst = chunk.events[1] as TranscriptRawEvent.AssistantStep
        assertEquals("gemini-2.5-pro", asst.model)
        assertEquals(1, asst.toolCalls.size)
        assertEquals(2, asst.toolCalls[0].resultStepIndex)
        val err = chunk.events[3] as TranscriptRawEvent.ToolStatusUpdate
        assertEquals("step_2", err.callId)
        assertTrue(err.hasError)

        assertEquals(
            "step output text",
            TranscriptWireJson.parseStepResponse("""{"ok":true,"id":4,"output":"step output text"}""")
        )
    }

    @Test fun malformedInventoryDoesNotBecomeAValidEmptyConnection() {
        assertThrows(HcProtocolException::class.java) { HcJson.terminals(JSONObject()) }
        assertThrows(HcProtocolException::class.java) { HcJson.terminal(JSONObject()) }
        assertTrue(HcJson.terminals(JSONObject("""{"terminals":[]}""")).isEmpty())
        val status = HcJson.status(JSONObject("""{"version":"0.1.18","live":9,"finished":10}"""))
        assertEquals(9, status.liveTerminals)
        assertEquals(10, status.finishedTerminals)
    }
}
