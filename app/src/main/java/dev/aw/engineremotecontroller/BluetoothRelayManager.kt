package dev.aw.engineremotecontroller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.util.UUID

class BluetoothRelayManager(context: Context) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    private var socket: BluetoothSocket? = null

    private val sppUuid: UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<BluetoothDevice> {
        return adapter?.bondedDevices
            ?.sortedWith(compareBy({ it.name ?: "" }, { it.address }))
            ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String): Result<Unit> = runCatching {
        val device = adapter?.getRemoteDevice(address)
            ?: error("Bluetoothアダプタが使えない")

        adapter?.cancelDiscovery()
        disconnect()

        val secureResult = runCatching {
            val s = device.createRfcommSocketToServiceRecord(sppUuid)
            s.connect()
            s
        }

        if (secureResult.isSuccess) {
            socket = secureResult.getOrThrow()
            return@runCatching
        }

        val insecureResult = runCatching {
            val s = device.createInsecureRfcommSocketToServiceRecord(sppUuid)
            s.connect()
            s
        }

        if (insecureResult.isSuccess) {
            socket = insecureResult.getOrThrow()
            return@runCatching
        }

        throw RuntimeException(
            buildString {
                appendLine("secure失敗: ${secureResult.exceptionOrNull()?.message}")
                appendLine("insecure失敗: ${insecureResult.exceptionOrNull()?.message}")
            }
        )
    }

    fun send(bytes: ByteArray): Result<Unit> = runCatching {
        val outputStream = socket?.outputStream ?: error("Bluetooth未接続")
        outputStream.write(bytes)
        outputStream.flush()
    }

    fun disconnect() {
        runCatching { socket?.close() }
        socket = null
    }
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