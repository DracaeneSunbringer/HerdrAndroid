package dev.herdr.android.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class HcProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A channel is an unsigned 32-bit value, not a signed JVM Int. */
data class HcFrame(val channel: Long, val kind: Int, val payload: ByteArray = byteArrayOf())

/** HC generation 1: channel:u32le, kind:u8, length:u32le, then opaque bytes. */
object HcFrameCodec {
    const val HEADER_SIZE = 9
    const val MAX_PAYLOAD = 16 * 1024 * 1024
    const val INITIAL_STREAM_WINDOW = 256 * 1024
    const val INPUT_CHUNK_SIZE = 16 * 1024
    const val MAX_CHANNEL = 0xffff_ffffL

    fun encode(frame: HcFrame): ByteArray {
        validate(frame)
        return ByteArray(HEADER_SIZE + frame.payload.size).also {
            putU32(it, 0, frame.channel)
            it[4] = frame.kind.toByte()
            putU32(it, 5, frame.payload.size.toLong())
            frame.payload.copyInto(it, HEADER_SIZE)
        }
    }

    fun write(output: OutputStream, frame: HcFrame) {
        output.write(encode(frame))
    }

    /** Null means clean EOF BETWEEN frames. A partial header or body is an error. */
    fun read(input: InputStream): HcFrame? {
        val first = input.read()
        if (first == -1) return null
        val header = ByteArray(HEADER_SIZE)
        header[0] = first.toByte()
        readExactly(input, header, 1, HEADER_SIZE - 1)
        val length = u32(header, 5)
        if (length > MAX_PAYLOAD) throw HcProtocolException("HC frame payload $length exceeds $MAX_PAYLOAD")
        val bytes = ByteArray(length.toInt())
        readExactly(input, bytes, 0, bytes.size)
        return HcFrame(u32(header, 0), header[4].toInt() and 255, bytes).also(::validate)
    }

    fun credit(channel: Long, consumedBytes: Int): HcFrame {
        require(consumedBytes >= 0)
        return HcFrame(channel, HcFrameKind.CREDIT, ByteArray(4).also { putU32(it, 0, consumedBytes.toLong()) })
    }

    fun resize(channel: Long, cols: Int, rows: Int): HcFrame {
        require(cols in 1..65535 && rows in 1..65535) { "Terminal dimensions must be in 1..65535" }
        return HcFrame(channel, HcFrameKind.RESIZE, byteArrayOf(
            cols.toByte(), (cols ushr 8).toByte(), rows.toByte(), (rows ushr 8).toByte()
        ))
    }

    fun dimensions(frame: HcFrame): Pair<Int, Int> {
        require(frame.kind == HcFrameKind.RESIZE)
        validate(frame)
        val cols = (frame.payload[0].toInt() and 255) or ((frame.payload[1].toInt() and 255) shl 8)
        val rows = (frame.payload[2].toInt() and 255) or ((frame.payload[3].toInt() and 255) shl 8)
        if (cols == 0 || rows == 0) throw HcProtocolException("HC sent zero terminal dimensions")
        return cols to rows
    }

    private fun validate(frame: HcFrame) {
        if (frame.channel !in 0..MAX_CHANNEL) throw HcProtocolException("Invalid HC channel ${frame.channel}")
        if (frame.kind !in 0..255) throw HcProtocolException("Invalid HC frame kind ${frame.kind}")
        if (frame.payload.size > MAX_PAYLOAD) throw HcProtocolException("HC frame exceeds $MAX_PAYLOAD bytes")
        if ((frame.kind == HcFrameKind.CREDIT || frame.kind == HcFrameKind.RESIZE) && frame.payload.size != 4) {
            throw HcProtocolException("HC CREDIT/RESIZE requires exactly four bytes")
        }
        if (frame.kind == HcFrameKind.FOCUS && (frame.payload.size != 1 || frame.payload[0].toInt() !in 0..1)) {
            throw HcProtocolException("HC FOCUS requires a single 0 or 1 byte")
        }
    }

    private fun readExactly(input: InputStream, target: ByteArray, offset: Int, count: Int) {
        var at = offset
        val end = offset + count
        while (at < end) {
            val n = input.read(target, at, end - at)
            if (n < 0) throw EOFException("Truncated HC frame: missing ${end - at} bytes")
            if (n == 0) {
                val byte = input.read()
                if (byte < 0) throw EOFException("Truncated HC frame")
                target[at++] = byte.toByte()
            } else at += n
        }
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0..3) bytes[offset + i] = (value ushr (i * 8)).toByte()
    }

    private fun u32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { acc, i ->
        acc or ((bytes[offset + i].toLong() and 255) shl (i * 8))
    }
}
