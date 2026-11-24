package dev.herdr.android.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

enum class TranscriptAgentKind(val wireName: String) {
    ANTIGRAVITY("agy"),
    CODEX("codex"),
    CLAUDE("claude");

    companion object {
        fun fromWire(raw: String?): TranscriptAgentKind? = when (raw?.lowercase()?.trim()) {
            "agy", "antigravity" -> ANTIGRAVITY
            "codex" -> CODEX
            "claude", "claude-code" -> CLAUDE
            else -> null
        }

        fun fromAgentHint(agentApp: String?, dialectName: String? = null): TranscriptAgentKind? {
            fun match(hint: String?): TranscriptAgentKind? = when (hint?.lowercase()?.trim()) {
                "antigravity", "agy", "gemini" -> ANTIGRAVITY
                "codex" -> CODEX
                "claude", "claude-code" -> CLAUDE
                else -> null
            }
            return match(agentApp) ?: match(dialectName)
        }
    }
}

data class TranscriptLocation(
    val agentKind: TranscriptAgentKind,
    val sessionId: String,
    val path: String,
    val fileSize: Long = 0L
)

data class TranscriptLocateHints(
    val userPrompts: List<String> = emptyList(),
    val assistantAnchors: List<String> = emptyList(),
    val claimedPaths: Set<String> = emptySet(),
    val expectedTurnCount: Int = 0,
    val terminalTitle: String? = null
) {
    val hasContentAnchors: Boolean
        get() = userPrompts.isNotEmpty() || assistantAnchors.isNotEmpty()
}

data class TranscriptToolCallSummary(
    val callId: String? = null,
    val name: String,
    val primaryTarget: String = "",
    val detailPreview: String = "",
    val changedFile: String? = null,
    val resultStepIndex: Int? = null,
    val hasError: Boolean = false,
    val diffPreview: String? = null
)

sealed interface TranscriptRawEvent {
    val lineByteOffset: Long

    data class UserMessage(
        override val lineByteOffset: Long,
        val stepIndex: Int? = null,
        val createdAt: String? = null,
        val text: String,
        val modelChange: String? = null
    ) : TranscriptRawEvent

    data class AssistantStep(
        override val lineByteOffset: Long,
        val stepIndex: Int? = null,
        val createdAt: String? = null,
        val status: String? = null,
        val model: String? = null,
        val thinking: String = "",
        val markdownContent: String = "",
        val toolCalls: List<TranscriptToolCallSummary> = emptyList(),
        val inputTokens: Long = 0L,
        val cacheReadTokens: Long = 0L,
        val outputTokens: Long = 0L,
        val errorText: String? = null
    ) : TranscriptRawEvent

    data class TokenUpdate(
        override val lineByteOffset: Long,
        val model: String? = null,
        val effort: String? = null,
        val inputTokens: Long = 0L,
        val cachedInputTokens: Long = 0L,
        val outputTokens: Long = 0L
    ) : TranscriptRawEvent

    data class ToolStatusUpdate(
        override val lineByteOffset: Long,
        val callId: String,
        val hasError: Boolean
    ) : TranscriptRawEvent
}

data class TranscriptChunkResult(
    val path: String,
    val startOffset: Long,
    val nextOffset: Long,
    val fileSize: Long,
    val hasEarlier: Boolean,
    val resetRequired: Boolean = false,
    val events: List<TranscriptRawEvent> = emptyList()
)

object TranscriptWireJson {

    fun parseLocateResponse(jsonLine: String, fallbackKind: TranscriptAgentKind): TranscriptLocation? {
        val obj = parseObjectOrThrow(jsonLine)
        ensureOk(obj, "locate")
        if (!obj.optBoolean("found", false)) return null
        val path = obj.optString("path")
        if (path.isBlank()) return null
        val kind = TranscriptAgentKind.fromWire(obj.nullableString("agent")) ?: fallbackKind
        return TranscriptLocation(
            agentKind = kind,
            sessionId = obj.optString("sessionId"),
            path = path,
            fileSize = obj.optLong("fileSize", 0L).coerceAtLeast(0L)
        )
    }

    fun parseChunkResponse(jsonLine: String, fallbackPath: String): TranscriptChunkResult {
        val obj = parseObjectOrThrow(jsonLine)
        ensureOk(obj, "chunk")
        val path = obj.nullableString("path") ?: fallbackPath
        val startOffset = when {
            obj.has("startOffset") -> obj.optLong("startOffset", 0L)
            obj.has("start") -> obj.optLong("start", 0L)
            else -> 0L
        }.coerceAtLeast(0L)
        val nextOffset = obj.optLong("nextOffset", startOffset).coerceAtLeast(0L)
        val fileSize = obj.optLong("fileSize", nextOffset).coerceAtLeast(0L)
        val hasEarlier = obj.optBoolean("hasEarlier", startOffset > 0L)
        val resetRequired = obj.optBoolean("reset", false) || obj.optBoolean("resetRequired", false)
        val events = parseEvents(obj.optJSONArray("events"))
        return TranscriptChunkResult(
            path = path,
            startOffset = startOffset,
            nextOffset = nextOffset,
            fileSize = fileSize,
            hasEarlier = hasEarlier,
            resetRequired = resetRequired,
            events = events
        )
    }

    fun parseStepResponse(jsonLine: String): String {
        val obj = parseObjectOrThrow(jsonLine)
        ensureOk(obj, "step")
        return obj.optString("output", "")
    }

    private fun parseEvents(arr: JSONArray?): List<TranscriptRawEvent> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<TranscriptRawEvent>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val offset = item.optLong("o", 0L).coerceAtLeast(0L)
            when (item.optString("k")) {
                "user" -> {
                    val text = item.optString("text", "")
                    val modelChange = item.nullableString("mc")
                    if (text.isNotEmpty() || modelChange != null) {
                        out.add(
                            TranscriptRawEvent.UserMessage(
                                lineByteOffset = offset,
                                stepIndex = item.optPositiveOrZeroInt("s"),
                                createdAt = item.nullableString("ts"),
                                text = text,
                                modelChange = modelChange
                            )
                        )
                    }
                }
                "assistant" -> {
                    val toolCalls = parseToolCalls(item.optJSONArray("tc"))
                    out.add(
                        TranscriptRawEvent.AssistantStep(
                            lineByteOffset = offset,
                            stepIndex = item.optPositiveOrZeroInt("s"),
                            createdAt = item.nullableString("ts"),
                            status = item.nullableString("st"),
                            model = item.nullableString("m"),
                            thinking = item.optString("th", ""),
                            markdownContent = item.optString("md", ""),
                            toolCalls = toolCalls,
                            inputTokens = item.optLong("in", 0L).coerceAtLeast(0L),
                            cacheReadTokens = item.optLong("cin", 0L).coerceAtLeast(0L),
                            outputTokens = item.optLong("out", 0L).coerceAtLeast(0L),
                            errorText = item.nullableString("error")
                        )
                    )
                }
                "tokens" -> {
                    out.add(
                        TranscriptRawEvent.TokenUpdate(
                            lineByteOffset = offset,
                            model = item.nullableString("m"),
                            effort = item.nullableString("ef"),
                            inputTokens = item.optLong("in", 0L).coerceAtLeast(0L),
                            cachedInputTokens = item.optLong("cin", 0L).coerceAtLeast(0L),
                            outputTokens = item.optLong("out", 0L).coerceAtLeast(0L)
                        )
                    )
                }
                "tool_err" -> {
                    val callId = item.nullableString("id") ?: continue
                    out.add(
                        TranscriptRawEvent.ToolStatusUpdate(
                            lineByteOffset = offset,
                            callId = callId,
                            hasError = item.optBoolean("err", false)
                        )
                    )
                }
            }
        }
        return out
    }

    private fun parseToolCalls(arr: JSONArray?): List<TranscriptToolCallSummary> {
        if (arr == null || arr.length() == 0) return emptyList()
        val out = ArrayList<TranscriptToolCallSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val tc = arr.optJSONObject(i) ?: continue
            val name = tc.nullableString("n") ?: continue
            out.add(
                TranscriptToolCallSummary(
                    callId = tc.nullableString("id"),
                    name = name,
                    primaryTarget = tc.optString("t", ""),
                    detailPreview = tc.optString("d", ""),
                    changedFile = tc.nullableString("f"),
                    resultStepIndex = tc.optPositiveOrZeroInt("rs"),
                    hasError = tc.optBoolean("err", false),
                    diffPreview = tc.nullableString("df")
                )
            )
        }
        return out
    }

    private fun parseObjectOrThrow(jsonLine: String): JSONObject {
        val trimmed = jsonLine.trim()
        if (trimmed.isEmpty()) throw IOException("Transcript sidecar returned an empty response")
        return try {
            JSONObject(trimmed)
        } catch (err: Exception) {
            throw IOException("Transcript sidecar returned malformed JSON: ${err.message}", err)
        }
    }

    private fun ensureOk(obj: JSONObject, op: String) {
        if (!obj.optBoolean("ok", false)) {
            val msg = obj.nullableString("error") ?: "Transcript sidecar $op failed"
            throw IOException(msg)
        }
    }

    private fun JSONObject.optPositiveOrZeroInt(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key, -1).takeIf { it >= 0 }
}
