package dev.herdr.android.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CopyOnWriteArrayList

class HcTerminalInteractionTest {
    @Test fun historyReadDefaultsTo260AndSupportsExplicitScreenOnlyOrCustomCount() = runBlocking {
        ProtocolPeer().use { peer ->
            peer.handler = { peer.reply(it, JSONObject().put("text", "question on screen")) }
            peer.client.start()
            assertEquals("question on screen", peer.client.readTerminal("linux-node/t7"))
            peer.client.readTerminal("linux-node/t7", lines = 0)
            peer.client.readTerminal("linux-node/t7", lines = 80)
            val params = List(3) { peer.nextRequest().getJSONObject("params") }
            assertTrue(params.all { it.getString("terminal") == "linux-node/t7" })
            assertEquals(listOf(260, 0, 80), params.map { it.getInt("lines") })
            assertTrue(runCatching { peer.client.readTerminal("linux-node/t7", lines = -1) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(peer.requests.isEmpty())
        }
    }

    @Test fun blockedScreenRefreshesKeepHistoryWithoutRetainingOrDuplicatingOldScreens() = runBlocking {
        val frame = AtomicReference(HistoryFrame())
        val reads = CopyOnWriteArrayList<Pair<String, Int>>()
        historyPeer(frame) { state, lines -> reads += state.status to lines }.use { peer ->
            peer.client.start()
            val session = peer.client.openTerminalSession("t7", AttachMode.OBSERVE)
            val original = withTimeout(2500) { session.screenSnapshot.first { it.endsWith("old screen 43") } }
            assertEquals(304, original.lines().size)
            assertEquals("old history 0", original.lines().first())

            frame.set(HistoryFrame(status = "blocked", screenPrefix = "question A"))
            val firstQuestion = withTimeout(2500) { session.screenSnapshot.first { it.endsWith("question A 43") } }
            assertEquals(HistoryFrame().history() + frame.get().screen(), firstQuestion.lines())
            assertFalse(firstQuestion.contains("old screen"))

            frame.set(HistoryFrame(status = "blocked", screenPrefix = "question B"))
            val secondQuestion = withTimeout(2500) { session.screenSnapshot.first { it.endsWith("question B 43") } }
            assertEquals(304, secondQuestion.lines().size)
            assertEquals(HistoryFrame().history() + frame.get().screen(), secondQuestion.lines())
            assertFalse(secondQuestion.contains("question A"))

            frame.set(HistoryFrame(historyPrefix = "fresh history", screenPrefix = "fresh screen"))
            val refreshed = withTimeout(2500) { session.screenSnapshot.first { it.endsWith("fresh screen 43") } }
            assertEquals(frame.get().history() + frame.get().screen(), refreshed.lines())
            assertFalse(refreshed.contains("old history"))
            assertTrue(reads.filter { it.first == "blocked" }.all { it.second == 0 })
            assertTrue(reads.filter { it.first == "idle" }.all { it.second == 260 })
            session.detach()
        }
    }

    @Test fun firstOpenWhileBlockedUsesOnlyTheScreenUntilHistoryCanBeRead() = runBlocking {
        val frame = AtomicReference(HistoryFrame(status = "blocked", screenPrefix = "question"))
        val reads = CopyOnWriteArrayList<Int>()
        historyPeer(frame) { _, lines -> reads += lines }.use { peer ->
            peer.client.start()
            val session = peer.client.openTerminalSession("t7", AttachMode.OBSERVE)
            val snapshot = withTimeout(2500) { session.screenSnapshot.first { it.endsWith("question 43") } }
            assertEquals(frame.get().screen(), snapshot.lines())
            assertTrue(reads.isNotEmpty() && reads.all { it == 0 })
            frame.set(HistoryFrame(screenPrefix = "unblocked screen"))
            val history = withTimeout(2500) { session.screenSnapshot.first { it.endsWith("unblocked screen 43") } }
            assertEquals(304, history.lines().size)
            assertEquals(260, reads.last())
            session.detach()
        }
    }

    @Test fun resizeRefreshReads260HistoryLinesAtTheNewGridSize() = runBlocking {
        val frame = AtomicReference(HistoryFrame())
        val reads = CopyOnWriteArrayList<Int>()
        historyPeer(frame) { _, lines -> reads += lines }.use { peer ->
            peer.client.start()
            val session = peer.client.openTerminalSession("t7", AttachMode.TAKEOVER)
            withTimeout(2500) { session.screenSnapshot.first { it.endsWith("old screen 43") } }
            frame.set(HistoryFrame(historyPrefix = "resized history", screenPrefix = "resized screen", cols = 100, rows = 30))
            assertTrue(session.requestResize(100, 30).isSuccess)
            assertEquals(290, session.screenSnapshot.value.lines().size)
            assertEquals(frame.get().history() + frame.get().screen(), session.screenSnapshot.value.lines())
            assertTrue(reads.all { it == 260 })
            session.detach()
        }
    }

    @Test fun blockedResizeKeepsCachedHistoryAndReadsOnlyTheNewVisibleRows() = runBlocking {
        val frame = AtomicReference(HistoryFrame())
        val reads = CopyOnWriteArrayList<Pair<String, Int>>()
        historyPeer(frame) { state, lines -> reads += state.status to lines }.use { peer ->
            peer.client.start()
            val session = peer.client.openTerminalSession("t7", AttachMode.TAKEOVER)
            withTimeout(2500) { session.screenSnapshot.first { it.endsWith("old screen 43") } }
            frame.set(HistoryFrame(status = "blocked", screenPrefix = "resized question", cols = 100, rows = 30))
            assertTrue(session.requestResize(100, 30).isSuccess)
            assertEquals(HistoryFrame().history() + frame.get().screen(), session.screenSnapshot.value.lines())
            assertEquals(290, session.screenSnapshot.value.lines().size)
            assertEquals("blocked" to 0, reads.last())
            session.detach()
        }
    }

    @Test fun coreScreenOnlyFallbackDoesNotDiscardPreviouslyCachedHistory() = runBlocking {
        val frame = AtomicReference(HistoryFrame())
        val reads = CopyOnWriteArrayList<Int>()
        historyPeer(frame) { _, lines -> reads += lines }.use { peer ->
            peer.client.start()
            val session = peer.client.openTerminalSession("t7", AttachMode.OBSERVE)
            withTimeout(2500) { session.screenSnapshot.first { it.endsWith("old screen 43") } }
            // HC can decline active stitching for a working full-screen program too.
            frame.set(HistoryFrame(status = "working", screenPrefix = "working screen", screenOnly = true))
            val snapshot = withTimeout(2500) { session.screenSnapshot.first { it.endsWith("working screen 43") } }
            assertEquals(HistoryFrame().history() + frame.get().screen(), snapshot.lines())
            assertTrue(reads.all { it == 260 })
            session.detach()
        }
    }

    @Test fun agentMessageRequiresExplicitForceRatherThanAnsweringUnseenDialogsByDefault() = runBlocking {
        ProtocolPeer().use { peer ->
            peer.handler = { peer.reply(it) }
            peer.client.start()
            peer.client.sendTerminal("t7", "ordinary prompt")
            peer.client.sendTerminal("t7", "explicit override", force = true)
            assertFalse(peer.nextRequest().getJSONObject("params").getBoolean("force"))
            val forced = peer.nextRequest().getJSONObject("params")
            assertTrue(forced.getBoolean("force"))
            assertFalse(forced.getBoolean("wait"))
        }
    }

    @Test fun blockedOtherFieldReceivesUnwrappedUtf8ThenASeparateEnterWithoutAgentSend() = runBlocking {
        interactivePeer(blocked = true).use { peer ->
            peer.client.start()
            val session = peer.client.openTerminalSession("t7", AttachMode.TAKEOVER)
            assertTrue(session.sendLine("自定义 answer 🧪").isSuccess)
            awaitUntil { rawInput(peer).size == 2 }
            val writes = rawInput(peer)
            assertArrayEquals("自定义 answer 🧪".toByteArray(), writes[0].payload)
            assertArrayEquals(byteArrayOf(13), writes[1].payload)
            assertTrue(writes.all { it.channel == 41L })
            val calls = rpcRequests(peer)
            assertFalse(calls.any { it.optString("method") == "terminal.send" })
            assertFalse(calls.any { it.optString("method") == "terminal.keys" })
            assertTrue(calls.filter { it.optString("method") == "terminal.read" }
                .all { it.getJSONObject("params").getInt("lines") == 0 })
            session.detach()
        }
    }

    @Test fun aNewDialogBetweenStatusAndAgentSendIsRefusedWithoutRawFallbackOrReplay() = runBlocking {
        interactivePeer().use { peer ->
            val ordinary = peer.handler!!
            peer.handler = { request ->
                if (request.optString("method") == "terminal.send") peer.error(request, "blocked") else ordinary(request)
            }
            peer.client.start()
            val session = peer.client.openTerminalSession("t7", AttachMode.TAKEOVER)
            val failure = session.sendLine("do not submit to a different dialog").exceptionOrNull()
            assertTrue(failure is HcRpcException && failure.kind == "blocked")
            val sends = rpcRequests(peer).filter { it.optString("method") == "terminal.send" }
            assertEquals(1, sends.size)
            assertFalse(sends.single().getJSONObject("params").getBoolean("force"))
            assertTrue(rawInput(peer).isEmpty())
            session.detach()
        }
    }

    @Test fun cancellingAfterDialogTextDoesNotSendEnterOrRetryTheAnswer() = runBlocking {
        interactivePeer(blocked = true).use { peer ->
            peer.client.start()
            val session = peer.client.openTerminalSession("t7", AttachMode.TAKEOVER)
            val send = async(Dispatchers.IO) { session.sendLine("answer") }
            awaitUntil { rawInput(peer).isNotEmpty() }
            send.cancelAndJoin()
            assertEquals(1, rawInput(peer).size)
            assertArrayEquals("answer".toByteArray(), rawInput(peer).single().payload)
            session.detach()
        }
    }

    @Test fun resizeConfirmationMatchesRowsAsWellAsColumns() = runBlocking {
        interactivePeer().use { peer ->
            val ordinary = peer.handler!!
            val confirmations = AtomicInteger()
            peer.handler = { request ->
                if (request.optString("method") == "terminal.get" && resized(peer)) {
                    val count = confirmations.incrementAndGet()
                    peer.reply(request, ProtocolPeer.terminal().put("cols", 100).put("rows", if (count == 1) 44 else 30))
                } else ordinary(request)
            }
            peer.client.start()
            val session = peer.client.openTerminalSession("t7", AttachMode.TAKEOVER)
            assertTrue(session.requestResize(100, 30).isSuccess)
            assertTrue(confirmations.get() >= 2)
            assertEquals(100 to 30, session.dimensions.value)
            session.detach()
        }
    }

    @Test fun cancelledResizeSnapshotDoesNotSwallowCancellationOrKeepInputLocked() = runBlocking {
        interactivePeer().use { peer ->
            val ordinary = peer.handler!!
            val confirmed = AtomicBoolean()
            val snapshotWaiting = CompletableDeferred<Unit>()
            peer.handler = { request ->
                when {
                    request.optString("method") == "terminal.get" && resized(peer) -> {
                        confirmed.set(true)
                        peer.reply(request, ProtocolPeer.terminal().put("cols", 100).put("rows", 30))
                    }
                    request.optString("method") == "terminal.read" && confirmed.get() -> snapshotWaiting.complete(Unit)
                    else -> ordinary(request)
                }
            }
            peer.client.start()
            val session = peer.client.openTerminalSession("t7", AttachMode.TAKEOVER)
            val returnedNormally = AtomicBoolean()
            val resize = async(Dispatchers.IO) {
                val result = session.requestResize(100, 30)
                returnedNormally.set(true)
                result
            }
            withTimeout(1500) { snapshotWaiting.await() }
            resize.cancelAndJoin()
            assertFalse(returnedNormally.get())
            assertTrue(withTimeout(500) { session.sendSpecialKey("enter") }.isSuccess)
            session.detach()
        }
    }

    @Test fun slowResizeStatusCannotConsumeFiveFullRpcTimeouts() = runBlocking {
        interactivePeer().use { peer ->
            val ordinary = peer.handler!!
            peer.handler = { request ->
                // Leave size confirmation unanswered, but continue serving unrelated control calls.
                if (!(request.optString("method") == "terminal.get" && resized(peer))) ordinary(request)
            }
            peer.client.start()
            val session = peer.client.openTerminalSession("t7", AttachMode.TAKEOVER)
            assertTrue(withTimeout(1900) { session.requestResize(100, 30) }.isSuccess)
            assertTrue(withTimeout(500) { session.sendSpecialKey("enter") }.isSuccess)
            session.detach()
        }
    }

    private fun interactivePeer(blocked: Boolean = false) = ProtocolPeer().also { peer ->
        peer.handler = { request ->
            val terminal = ProtocolPeer.terminal().put("status", JSONObject().put("state", if (blocked) "blocked" else "idle"))
            when (request.getString("method")) {
                "terminal.get" -> peer.reply(request, terminal)
                "terminal.attach" -> peer.reply(request, JSONObject().put("channel", 41).put("terminal", terminal).put("resize_notifications", false))
                "terminal.read" -> peer.reply(request, JSONObject().put("text", "question"))
                else -> peer.reply(request)
            }
        }
    }

    private data class HistoryFrame(
        val status: String = "idle", val historyPrefix: String = "old history",
        val screenPrefix: String = "old screen", val cols: Int = 132, val rows: Int = 44,
        val screenOnly: Boolean = false
    ) {
        fun history() = List(260) { "$historyPrefix $it" }
        fun screen() = List(rows) { "$screenPrefix $it" }
    }
    private fun historyPeer(frame: AtomicReference<HistoryFrame>, onRead: (HistoryFrame, Int) -> Unit = { _, _ -> }) =
        ProtocolPeer().also { peer ->
            peer.handler = { request ->
                val current = frame.get()
                val terminal = ProtocolPeer.terminal().put("cols", current.cols).put("rows", current.rows)
                    .put("status", JSONObject().put("state", current.status))
                when (request.getString("method")) {
                    "terminal.get" -> peer.reply(request, terminal)
                    "terminal.attach" -> peer.reply(request, JSONObject().put("channel", 41).put("terminal", terminal)
                        .put("resize_notifications", request.getJSONObject("params").getBoolean("observe")))
                    "terminal.read" -> {
                        val lines = request.getJSONObject("params").getInt("lines")
                        onRead(current, lines)
                        val history = if (lines > 0 && !current.screenOnly) current.history().takeLast(lines) else emptyList()
                        val text = (history + current.screen()).joinToString("\n")
                        peer.reply(request, JSONObject().put("text", text).put("how", if (lines == 0 || current.screenOnly) "screen" else "history"))
                    }
                    else -> peer.reply(request)
                }
            }
        }

    private fun rawInput(peer: ProtocolPeer) = peer.frames.filter { it.channel != 0L && it.kind == HcFrameKind.DATA }
    private fun rpcRequests(peer: ProtocolPeer) = peer.frames.filter { it.channel == 0L }
        .map { JSONObject(it.payload.toString(Charsets.UTF_8)) }
    private fun resized(peer: ProtocolPeer) = peer.frames.any { it.kind == HcFrameKind.RESIZE }
    private suspend fun awaitUntil(condition: () -> Boolean) = withTimeout(1500) { while (!condition()) delay(2) }
}
