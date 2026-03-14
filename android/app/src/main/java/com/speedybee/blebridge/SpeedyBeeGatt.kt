package com.speedybee.blebridge

import java.util.UUID

object SpeedyBeeGatt {
    private fun uuid16(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    val SVC_SPEEDYBEE: UUID    = uuid16(0xABF0)
    val CHR_SERIAL_TX: UUID    = uuid16(0xABF1)  // Write without response (host → FC)
    val CHR_SERIAL_RX: UUID    = uuid16(0xABF2)  // Notify (FC → host)
    val CHR_SB_TX: UUID        = uuid16(0xABF3)  // Write (host → FC, proprietary handshake)
    val CHR_SB_RX_NOTIFY: UUID = uuid16(0xABF4)  // Notify (FC → host, proprietary handshake)

    val HANDSHAKE_PAYLOAD: ByteArray = byteArrayOf(0x02, 0x00, 0x08, 0x03)

    val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
