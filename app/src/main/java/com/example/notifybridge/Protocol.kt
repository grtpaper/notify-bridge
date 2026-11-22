package com.example.notifybridge

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

data class Message(val app: String, val title: String, val text: String, val time: Long)

/**
 * Wire format shared by both roles.
 *
 * One notification = one UTF-8 JSON object. It is split into frames that each fit in a single
 * BLE notification. Every frame starts with one flag byte: 0 = more frames follow, 1 = last frame.
 */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("7e1c0001-5b2a-4d2e-9c3f-2f6f1a9b0c01")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("7e1c0002-5b2a-4d2e-9c3f-2f6f1a9b0c01")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val REQUESTED_MTU = 247
    const val DEFAULT_MTU = 23
    const val FLAG_MORE: Byte = 0
    const val FLAG_LAST: Byte = 1
    const val MAX_MESSAGE_BYTES = 8 * 1024
    private const val ATT_HEADER = 3

    fun encode(m: Message): ByteArray =
        JSONObject()
            .put("app", m.app.take(60))
            .put("title", m.title.take(120))
            .put("text", m.text.take(600))
            .put("t", m.time)
            .toString()
            .toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): Message? = try {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        Message(
            o.optString("app"),
            o.optString("title"),
            o.optString("text"),
            o.optLong("t", System.currentTimeMillis())
        )
    } catch (e: Exception) {
        null
    }

    /** Splits [payload] into frames that fit one notification at the given ATT [mtu]. */
    fun frame(payload: ByteArray, mtu: Int): List<ByteArray> {
        val dataPerFrame = (mtu - ATT_HEADER - 1).coerceAtLeast(1)
        if (payload.isEmpty()) return listOf(byteArrayOf(FLAG_LAST))
        val frames = ArrayList<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + dataPerFrame, payload.size)
            val frame = ByteArray(1 + end - offset)
            frame[0] = if (end == payload.size) FLAG_LAST else FLAG_MORE
            System.arraycopy(payload, offset, frame, 1, end - offset)
            frames.add(frame)
            offset = end
        }
        return frames
    }
}

/** Puts frames back together on the receiving side. Call from one thread only. */
class Reassembler {
    private var buf = ByteArrayOutputStream()

    fun reset() {
        buf = ByteArrayOutputStream()
    }

    /** Returns the full payload once the last frame arrives, otherwise null. */
    fun feed(frame: ByteArray): ByteArray? {
        if (frame.isEmpty()) return null
        if (buf.size() + frame.size - 1 > Protocol.MAX_MESSAGE_BYTES) {
            reset()
            return null
        }
        buf.write(frame, 1, frame.size - 1)
        if (frame[0] != Protocol.FLAG_LAST) return null
        val out = buf.toByteArray()
        reset()
        return out
    }
}
