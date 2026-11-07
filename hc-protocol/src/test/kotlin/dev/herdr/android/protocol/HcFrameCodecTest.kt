package dev.herdr.android.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.FilterInputStream

class HcFrameCodecTest {
    @Test fun goldenHeaderUsesLittleEndianAndUnsignedChannels() {
        val bytes = HcFrameCodec.encode(HcFrame(0xfedcba98L, HcFrameKind.DATA, byteArrayOf(0, -1)))
        assertArrayEquals(byteArrayOf(0x98.toByte(), 0xba.toByte(), 0xdc.toByte(), 0xfe.toByte(), 0, 2, 0, 0, 0, 0, -1), bytes)
        val decoded = HcFrameCodec.read(ByteArrayInputStream(bytes))!!
        assertEquals(0xfedcba98L, decoded.channel)
        assertArrayEquals(byteArrayOf(0, -1), decoded.payload)
    }

    @Test fun fragmentedAndCoalescedFramesPreserveOpaqueUtf8AndEscapeBytes() {
        val payload = "中文\u001b[2J\u0000".toByteArray()
        val bytes = HcFrameCodec.encode(HcFrame(7, 0, payload)) + HcFrameCodec.encode(HcFrame(8, 251))
        val input = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(1, len))
        }
        assertArrayEquals(payload, HcFrameCodec.read(input)!!.payload)
        assertEquals(251, HcFrameCodec.read(input)!!.kind) // unknown extension kinds survive
        assertNull(HcFrameCodec.read(input))
    }

    @Test fun cleanEofIsDifferentFromTruncatedHeaderOrBody() {
        assertNull(HcFrameCodec.read(ByteArrayInputStream(byteArrayOf())))
        assertThrows(EOFException::class.java) { HcFrameCodec.read(ByteArrayInputStream(byteArrayOf(1, 0))) }
        val bytes = HcFrameCodec.encode(HcFrame(1, 0, byteArrayOf(1, 2)))
        assertThrows(EOFException::class.java) { HcFrameCodec.read(ByteArrayInputStream(bytes.dropLast(1).toByteArray())) }
    }

    @Test fun oversizedUnsignedLengthIsRejectedBeforeBodyAllocation() {
        val header = byteArrayOf(1, 0, 0, 0, 0, -1, -1, -1, -1)
        assertThrows(HcProtocolException::class.java) { HcFrameCodec.read(ByteArrayInputStream(header)) }
    }

    @Test fun creditAndResizeMatchCoreWireFormat() {
        assertArrayEquals(byteArrayOf(0, 0, 4, 0), HcFrameCodec.credit(1, 262144).payload)
        val resized = HcFrameCodec.resize(1, 513, 1027)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), resized.payload)
        assertEquals(513 to 1027, HcFrameCodec.dimensions(resized))
        assertThrows(IllegalArgumentException::class.java) { HcFrameCodec.resize(1, 0, 20) }
        assertThrows(HcProtocolException::class.java) { HcFrameCodec.encode(HcFrame(1, HcFrameKind.CREDIT, byteArrayOf(1))) }
        assertThrows(HcProtocolException::class.java) { HcFrameCodec.encode(HcFrame(1, HcFrameKind.RESIZE)) }
        assertThrows(HcProtocolException::class.java) { HcFrameCodec.encode(HcFrame(1, HcFrameKind.FOCUS, byteArrayOf(2))) }
        assertThrows(HcProtocolException::class.java) { HcFrameCodec.encode(HcFrame(-1, 0)) }
    }

    @Test fun helloReaderDoesNotConsumeFirstBinaryFrame() {
        val frame = HcFrameCodec.encode(HcFrame(9, 0, byteArrayOf(-1, 0, 10)))
        val input = ByteArrayInputStream("{\"id\":1}\r\n".toByteArray() + frame)
        assertEquals("{\"id\":1}", HcFramedClient.readHelloLine(input))
        assertArrayEquals(byteArrayOf(-1, 0, 10), HcFrameCodec.read(input)!!.payload)
    }
}
