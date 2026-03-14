package com.speedybee.blebridge

/**
 * SpeedyBee proprietary protocol helpers.
 *
 * Packet format: [cmd_id] [0x00] [protobuf_payload...]
 * Protobuf fields: field1=varint (tag 0x08), field2=string (tag 0x12)
 */
object SpeedyBeeProto {

    fun encodeVarint(value: Int): ByteArray {
        val result = mutableListOf<Byte>()
        var v = value
        while (v > 0x7F) {
            result.add(((v and 0x7F) or 0x80).toByte())
            v = v ushr 7
        }
        result.add((v and 0x7F).toByte())
        return result.toByteArray()
    }

    fun encodeProto(field1: Int? = null, field2: ByteArray? = null): ByteArray {
        val payload = mutableListOf<Byte>()
        if (field1 != null) {
            payload.add(0x08.toByte())
            payload.addAll(encodeVarint(field1).toList())
        }
        if (field2 != null) {
            payload.add(0x12.toByte())
            payload.addAll(encodeVarint(field2.size).toList())
            payload.addAll(field2.toList())
        }
        return payload.toByteArray()
    }

    fun sbPacket(cmd: Int, field1: Int? = null, field2: ByteArray? = null): ByteArray {
        val proto = encodeProto(field1, field2)
        return byteArrayOf(cmd.toByte(), 0x00) + proto
    }

    fun decodeVarint(data: ByteArray, startOffset: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var offset = startOffset
        while (offset < data.size) {
            val b = data[offset].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            offset++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to offset
    }

    fun decodeProto(data: ByteArray): Map<Int, Any> {
        val fields = mutableMapOf<Int, Any>()
        var offset = 0
        while (offset < data.size) {
            val tag = data[offset].toInt() and 0xFF
            offset++
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            when (wireType) {
                0 -> { // varint
                    val (v, newOffset) = decodeVarint(data, offset)
                    fields[fieldNum] = v
                    offset = newOffset
                }
                2 -> { // length-delimited
                    val (length, newOffset) = decodeVarint(data, offset)
                    fields[fieldNum] = data.copyOfRange(newOffset, minOf(newOffset + length, data.size))
                    offset = newOffset + length
                }
                else -> break
            }
        }
        return fields
    }

    /** MSP v1 API_VERSION request: $M< len=0 cmd=2 checksum=2 */
    val MSP_API_VERSION = byteArrayOf(0x24, 0x4D, 0x3C, 0x00, 0x02, 0x02)
}
