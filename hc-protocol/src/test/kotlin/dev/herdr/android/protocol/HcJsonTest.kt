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
