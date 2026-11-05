package dev.aw.engineremotecontroller

interface RelayTransport {
    fun connect(address: String): Result<Unit>
    fun send(bytes: ByteArray): Result<Unit>
    fun disconnect()
}

object RelayCommands {
    fun channelOn(ch: Int): ByteArray = byteArrayOf(
        0xFD.toByte(), 0x02, 0x20, ch.toByte(), 0x01, 0x5D
    )

    fun channelOff(ch: Int): ByteArray = byteArrayOf(
        0xFD.toByte(), 0x02, 0x20, ch.toByte(), 0x00, 0x5D
    )

    val allOn: ByteArray = byteArrayOf(
        0xFD.toByte(), 0x02, 0x20, 0xEF.toByte(), 0xFF.toByte(), 0x5D
    )

    val allOff: ByteArray = byteArrayOf(
        0xFD.toByte(), 0x02, 0x20, 0xEF.toByte(), 0x00, 0x5D
    )
}
