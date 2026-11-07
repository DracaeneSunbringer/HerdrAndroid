package dev.herdr.android.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Protocol generation spoken by Herdr Core (`hc connect`).
 */
const val HC_PROTOCOL_GENERATION: Int = 1

/**
 * Binary frame kinds for Herdr Core framed multiplexing (`HC_API.md` § Transport).
 */
object HcFrameKind {
    const val DATA: Int = 0
    const val CREDIT: Int = 1
    const val CLOSE: Int = 2
    const val RESIZE: Int = 3
    const val FOCUS: Int = 4
    const val FACTS: Int = 5
    const val AUTOMATIC_INPUT: Int = 6
}

data class HcHelloInfo(
    val generation: Int = HC_PROTOCOL_GENERATION,
    val version: String,
    val coreId: String,
    val coreName: String,
    val features: List<String> = emptyList()
)

data class HcServerStatus(
    val version: String,
    val liveTerminals: Int,
    val finishedTerminals: Int
)

enum class AgentStateWord(val wireName: String) {
    WORKING("working"),
    BLOCKED("blocked"),
    DONE("done"),
    IDLE("idle"),
    ERROR("error"),
    NONE("none");

    companion object {
        fun fromWire(raw: String?): AgentStateWord = when (raw?.lowercase()?.trim()) {
            "working" -> WORKING
            "blocked" -> BLOCKED
            "done" -> DONE
            "idle" -> IDLE
            "error" -> ERROR
            else -> NONE
        }
    }
}

data class TerminalSummary(
    val address: String,
    val name: String? = null,
    val parent: String? = null,
    val state: String = "live",
    val agentApp: String? = null,
    val agentStatus: AgentStateWord = AgentStateWord.NONE,
    val agentMessage: String? = null,
    val cwd: String = "",
    val whereLabel: String? = null,
    val spaceId: String? = null,
    val tabId: String? = null,
    val title: String = "",
    val cols: Int = 120,
    val rows: Int = 40,
    val turn: Int = 0,
    val labels: Map<String, String> = emptyMap()
) {
    val displayTitle: String
        get() = when {
            !name.isNullOrBlank() -> "$name ($address)"
            !whereLabel.isNullOrBlank() -> whereLabel
            title.isNotBlank() -> title
            else -> address
        }

    val workspaceSubtitle: String
        get() = whereLabel?.takeIf { it.isNotBlank() } ?: cwd.ifBlank { "~" }
}

data class MachineSummary(
    val name: String,
    val state: String
)

enum class AttachMode {
    /**
     * Read-only viewer: preserves remote terminal dimensions and blocks accidental key input.
     */
    OBSERVE,

    /**
     * Interactive takeover: enables raw input, special keys, IME composition, and optional resize.
     */
    TAKEOVER
}

/**
 * Active attached terminal stream over a framed `hc connect` channel.
 * Closing/detaching this session ONLY sends CLOSE on the attach channel and never kills the remote process.
 */
interface TerminalSession {
    val address: String
    val mode: StateFlow<AttachMode>
    val outputFrames: Flow<ByteArray>
    val screenSnapshot: StateFlow<String>
    val dimensions: StateFlow<Pair<Int, Int>>
    val isAttached: StateFlow<Boolean>

    suspend fun setMode(newMode: AttachMode)
    suspend fun sendRawInput(bytes: ByteArray): Result<Unit>
    suspend fun sendLine(text: String): Result<Unit>
    suspend fun sendSpecialKey(keyName: String): Result<Unit>
    suspend fun interrupt(): Result<Unit>
    suspend fun requestResize(cols: Int, rows: Int): Result<Unit>
    suspend fun detach()
}
