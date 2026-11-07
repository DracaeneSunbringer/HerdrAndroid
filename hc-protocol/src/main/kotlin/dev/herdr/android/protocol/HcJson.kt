package dev.herdr.android.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class HcRpcException(
    val kind: String,
    message: String,
    val hint: String = "",
    val code: Int = -32000
) : IOException(message)

internal fun JSONObject.nullableString(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotBlank() }

object HcJson {
    fun request(id: Long, method: String, params: JSONObject = JSONObject()): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)

    fun result(response: JSONObject): JSONObject {
        if (response.optString("jsonrpc") != "2.0") throw HcProtocolException("Expected JSON-RPC 2.0 response")
        response.optJSONObject("error")?.let { error ->
            val data = error.optJSONObject("data")
            throw HcRpcException(
                data?.optString("kind")?.ifBlank { "rpc_error" } ?: "rpc_error",
                error.optString("message", "HC RPC failed"),
                data?.optString("hint") ?: "",
                error.optInt("code", -32000)
            )
        }
        return response.optJSONObject("result") ?: throw HcProtocolException("HC response has no object result")
    }

    fun hello(result: JSONObject): HcHelloInfo {
        val generation = result.optInt("generation", -1)
        if (generation != HC_PROTOCOL_GENERATION) throw HcRpcException(
            "unsupported_generation", "HC protocol generation $generation is not supported", "Update the client and core to compatible builds."
        )
        if (!result.optBoolean("framed", false)) throw HcProtocolException("HC did not enter framed mode")
        val core = result.optJSONObject("core") ?: throw HcProtocolException("HC hello has no core identity")
        val coreId = core.optString("id")
        if (coreId.isBlank()) throw HcProtocolException("HC hello has an empty core identity")
        return HcHelloInfo(generation, result.optString("version"), coreId, core.optString("name"), strings(result.optJSONArray("features")))
    }

    fun status(result: JSONObject) = HcServerStatus(
        result.optString("version"), result.optInt("live"), result.optInt("finished")
    )

    fun terminal(item: JSONObject): TerminalSummary {
        val address = item.optString("address")
        if (address.isBlank()) throw HcProtocolException("HC terminal has no address")
        val labelObject = item.optJSONObject("labels") ?: JSONObject()
        val labels = buildMap {
            val keys = labelObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = labelObject.opt(key)
                if (value is String) put(key, value)
            }
        }
        val status = item.optJSONObject("status")
        val foreground = item.optJSONObject("foreground")
        return TerminalSummary(
            address = address,
            name = item.nullableString("name"), parent = item.nullableString("parent"),
            state = item.optString("state", "live"), agentApp = status?.nullableString("app"),
            agentStatus = AgentStateWord.fromWire(status?.nullableString("state")),
            agentMessage = status?.nullableString("msg"),
            cwd = foreground?.nullableString("cwd") ?: item.optString("cwd"),
            whereLabel = labels["where"], spaceId = labels["herdr.space"], tabId = labels["herdr.tab"],
            title = item.optString("title"), cols = item.optInt("cols", 120).coerceIn(1, 65535),
            rows = item.optInt("rows", 40).coerceIn(1, 65535), turn = item.optInt("turn"), labels = labels
        )
    }

    fun terminals(result: JSONObject): List<TerminalSummary> {
        val items = result.optJSONArray("terminals") ?: throw HcProtocolException("HC terminal.list has no terminals array")
        return (0 until items.length()).map { terminal(items.getJSONObject(it)) }
    }

    fun machines(result: JSONObject): List<MachineSummary> {
        val items = result.optJSONArray("machines") ?: return emptyList()
        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            MachineSummary(item.optString("name"), item.optString("state", "unknown"))
        }
    }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.opt(it) as? String }
}
