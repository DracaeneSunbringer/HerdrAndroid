package dev.herdr.android.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class HcFramedClientTest {
    @Test fun matchesConcurrentRepliesByIdRatherThanArrivalOrder() = runBlocking {
        ProtocolPeer().use { peer ->
            assertEquals("Windows", peer.client.start().coreName)
            val one = async(Dispatchers.IO) { peer.client.request("one") }
            val two = async(Dispatchers.IO) { peer.client.request("two") }
            val requests = listOf(peer.nextRequest(), peer.nextRequest())
            val a = requests.first { it.getString("method") == "one" }
            val b = requests.first { it.getString("method") == "two" }
            peer.reply(b, JSONObject().put("value", 2))
            peer.reply(a, JSONObject().put("value", 1))
            assertEquals(1, one.await().getInt("value"))
            assertEquals(2, two.await().getInt("value"))
        }
    }

    @Test fun attachInstallsChannelBeforeImmediatelyFollowingDataAndCreditsOnlyConsumedBytes() = runBlocking {
        ProtocolPeer().use { peer ->
            peer.handler = { request ->
                peer.reply(request, JSONObject().put("channel", 0xf0000001L).put("terminal", ProtocolPeer.terminal()),
                    HcFrame(0xf0000001L, 0, byteArrayOf(-28, -72)), // UTF-8 split must remain opaque
                    HcFrameCodec.resize(0xf0000001L, 132, 44))
            }
            peer.client.start()
            val stream = peer.client.attach("t7", AttachMode.OBSERVE, 132, 44)
            val request = peer.nextRequest()
            assertTrue(request.getJSONObject("params").getBoolean("observe"))
            assertTrue(request.getJSONObject("params").getBoolean("resize_notifications"))
            // A control reply after the DATA is a barrier: the reader has queued both stream frames.
            peer.handler = { peer.reply(it) }
            peer.client.request("barrier")
            assertFalse(peer.frames.any { it.kind == HcFrameKind.CREDIT })
            assertArrayEquals(byteArrayOf(-28, -72), withTimeout(1500) { stream.receive() }!!.payload)
            awaitUntil { peer.frames.any { it.kind == HcFrameKind.CREDIT } }
            val credit = peer.frames.first { it.kind == HcFrameKind.CREDIT }
            assertEquals(0xf0000001L, credit.channel)
            assertArrayEquals(byteArrayOf(2, 0, 0, 0), credit.payload)
            assertEquals(132 to 44, HcFrameCodec.dimensions(stream.receive()!!))
            stream.detach()
            awaitUntil { peer.frames.any { it.kind == HcFrameKind.CLOSE } }
            assertFalse(peer.frames.filter { it.channel == 0L }.any { it.payload.toString(Charsets.UTF_8).contains("terminal.kill") })
        }
    }

    @Test fun rpcErrorsKeepConnectionOpenAndLateTimeoutRepliesAreNotReplayed() = runBlocking {
        ProtocolPeer().use { peer ->
            peer.handler = { peer.error(it, "blocked") }
            peer.client.start()
            try { peer.client.sendTerminal("t7", "message"); fail("expected refusal") }
            catch (error: HcRpcException) { assertEquals("blocked", error.kind); assertEquals("do not force", error.hint) }
            assertTrue(peer.client.isConnected.value)
            peer.handler = null
            try { peer.client.request("slow", timeoutMs = 60); fail("expected timeout") }
            catch (error: HcRpcException) { assertEquals("timeout", error.kind) }
            val all = listOf(peer.nextRequest(), peer.nextRequest())
            val slow = all.first { it.getString("method") == "slow" }
            peer.reply(slow)
            peer.handler = { peer.reply(it, JSONObject().put("version", "0.1.18").put("live", 1).put("finished", 0)) }
            assertEquals(1, peer.client.serverStatus().liveTerminals)
            assertEquals(1, peer.frames.count { it.channel == 0L && JSONObject(it.payload.toString(Charsets.UTF_8)).optString("method") == "slow" })
        }
    }

    @Test fun eofCompletesOutstandingCallsAndMarksConnectionLost() = runBlocking {
        ProtocolPeer().use { peer ->
            peer.client.start()
            val result = async(Dispatchers.IO) { runCatching { peer.client.serverStatus() } }
            peer.nextRequest()
            peer.eof()
            assertTrue(withTimeout(1500) { result.await() }.isFailure)
            assertFalse(peer.client.isConnected.value)
            assertNotNull(peer.client.failure.value)
        }
    }

    @Test fun parentCancellationIsNotMisreportedAsAnRpcTimeout() = runBlocking {
        ProtocolPeer().use { peer ->
            peer.client.start()
            val call = async(Dispatchers.IO) { peer.client.request("pending") }
            peer.nextRequest()
            call.cancel()
            try { call.await(); fail("expected cancellation") } catch (_: CancellationException) { }
            assertTrue(peer.client.isConnected.value)
        }
    }

    @Test fun observeBlocksEveryInputAndModeSwitchReattachesBeforeAllowingTakeover() = runBlocking {
        ProtocolPeer().use { peer ->
            val channels = AtomicLong(40)
            peer.handler = { request ->
                when (request.getString("method")) {
                    "terminal.get" -> peer.reply(request, ProtocolPeer.terminal())
                    "terminal.attach" -> {
                        val channel = channels.incrementAndGet()
                        peer.reply(request, JSONObject().put("channel", channel).put("terminal", ProtocolPeer.terminal())
                            .put("resize_notifications", request.getJSONObject("params").getBoolean("observe")),
                            HcFrame(channel, HcFrameKind.DATA, "\u001b[2Jraw".toByteArray()))
                    }
                    "terminal.read" -> peer.reply(request, JSONObject().put("text", "plain screen"))
                    else -> peer.reply(request)
                }
            }
            peer.client.start()
            val session = peer.client.openTerminalSession("t7")
            assertEquals("plain screen", withTimeout(1500) { session.screenSnapshot.first { it.isNotEmpty() } })
            assertArrayEquals("\u001b[2Jraw".toByteArray(), withTimeout(1500) { session.outputFrames.first() })
            assertTrue(session.sendRawInput(byteArrayOf(3)).isFailure)
            assertTrue(session.sendLine("x").isFailure)
            assertTrue(session.sendSpecialKey("Enter").isFailure)
            assertTrue(session.interrupt().isFailure)
            assertTrue(session.requestResize(80, 24).isFailure)
            assertFalse(peer.frames.any { it.channel != 0L && it.kind == HcFrameKind.DATA })
            session.setMode(AttachMode.TAKEOVER)
            assertEquals(AttachMode.TAKEOVER, session.mode.value)
            assertTrue(session.sendRawInput(byteArrayOf(0, -1, 3)).isSuccess)
            assertTrue(session.sendLine("中文").isSuccess)
            assertTrue(session.sendSpecialKey("Ctrl+c").isSuccess)
            assertTrue(session.interrupt().isSuccess)
            assertTrue(session.requestResize(100, 30).isSuccess)
            awaitUntil { peer.frames.any { it.channel == 42L && it.kind == HcFrameKind.RESIZE } }
            val methods = peer.frames.filter { it.channel == 0L }.map { JSONObject(it.payload.toString(Charsets.UTF_8)) }
            val attaches = methods.filter { it.getString("method") == "terminal.attach" }
            assertEquals(2, attaches.size)
            assertFalse(attaches[1].getJSONObject("params").getBoolean("observe"))
            assertEquals(132, attaches[1].getJSONObject("params").getInt("cols"))
            val send = methods.first { it.getString("method") == "terminal.send" }.getJSONObject("params")
            assertFalse(send.getBoolean("force")); assertFalse(send.getBoolean("wait"))
            session.detach()
            assertFalse(session.isAttached.value)
            awaitUntil { peer.frames.count { it.kind == HcFrameKind.CLOSE } == 2 }
            assertTrue(session.sendLine("after detach").isFailure)
            assertFalse(methods.any { it.optString("method") == "terminal.kill" })
        }
    }

    private suspend fun awaitUntil(predicate: () -> Boolean) = withTimeout(2000) {
        while (!predicate()) delay(5)
    }
}
