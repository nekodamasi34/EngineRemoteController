package dev.aw.engineremotecontroller

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class RelayChannelState { OFF, ON, UNKNOWN }

data class RelayControlState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val channels: List<RelayChannelState> = List(8) { RelayChannelState.UNKNOWN },
    val actionTitle: String? = null,
    val remainingMillis: Long? = null,
    val status: String = "未接続"
) {
    val canRun: Boolean
        get() = connected && !busy && channels.none { it == RelayChannelState.UNKNOWN }
}

data class RelayUpdate(
    val generation: Long,
    val state: RelayControlState,
    val log: String? = null
)

/** All transport access runs on one worker; waiting for a timer never blocks STOP. */
class RelayController(
    private val transport: RelayTransport,
    private val executor: ScheduledThreadPoolExecutor = ScheduledThreadPoolExecutor(1),
    private val onUpdate: (RelayUpdate) -> Unit
) : AutoCloseable {
    private val lock = Any()
    @Volatile private var generation = 0L
    @Volatile private var state = RelayControlState()
    private var closed = false
    private var disconnectRequested = false
    // Accessed exclusively by the transport worker, including after Activity destruction.
    private var transportConnected = false

    init {
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        executor.removeOnCancelPolicy = true
    }

    val currentState: RelayControlState get() = state

    fun isCurrent(update: RelayUpdate): Boolean = update.generation == generation

    fun connect(address: String, deviceName: String = address): Boolean = synchronized(lock) {
        if (closed || state.connected || state.busy) return false
        val token = ++generation
        publish(state.copy(busy = true, status = "接続中..."))
        executor.execute {
            if (token != generation) return@execute
            val result = transport.connect(address)
            transportConnected = result.isSuccess
            if (result.isFailure) {
                update(token) {
                    publish(
                        RelayControlState(status = "接続失敗"),
                        "接続失敗: ${result.exceptionOrNull()?.message ?: "不明"}"
                    )
                }
                return@execute
            }

            // Reconnection must establish a safe baseline before permitting an ON command.
            val offResult = transport.send(RelayCommands.allOff)
            update(token) {
                publish(
                    RelayControlState(
                        connected = true,
                        channels = allChannels(offResult),
                        status = if (offResult.isSuccess) "接続中" else "接続済み / 全OFF失敗・状態不明"
                    ),
                    if (offResult.isSuccess) "接続成功: $deviceName / 全OFF送信"
                    else "接続成功: $deviceName / 全OFF失敗: ${offResult.exceptionOrNull()?.message ?: "不明"}"
                )
            }
        }
        true
    }

    fun runTimedAction(title: String, channel: Int, durationMillis: Long): Boolean = synchronized(lock) {
        if (closed || !state.canRun || channel !in 1..8 || durationMillis !in 100L..5000L) {
            return false
        }
        val token = ++generation
        publish(state.copy(busy = true, actionTitle = title, remainingMillis = durationMillis, status = "$title 実行中..."))
        executor.execute {
            if (token != generation) return@execute
            val result = transport.send(RelayCommands.channelOn(channel))
            // STOP may arrive during write(). Its queued all-OFF always runs after this write.
            update(token) {
                if (result.isFailure) {
                    publish(
                        state.copy(
                            busy = false, actionTitle = null, remainingMillis = null,
                            channels = channelState(channel, RelayChannelState.UNKNOWN),
                            status = "$title ON失敗 / 状態不明"
                        ),
                        "$title ON失敗: ${result.exceptionOrNull()?.message ?: "不明"}"
                    )
                } else {
                    publish(
                        state.copy(channels = channelState(channel, RelayChannelState.ON)),
                        "$title: CH$channel ON / ${durationMillis / 1000.0}秒"
                    )
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMillis)
                    scheduleTick(token, title, channel, deadline, durationMillis)
                }
            }
        }
        true
    }

    fun emergencyAllOff(): Boolean = synchronized(lock) {
        if (closed || disconnectRequested || !state.connected) return false
        val token = ++generation
        publish(state.copy(busy = true, actionTitle = null, remainingMillis = null, status = "全OFF送信中..."))
        executor.execute {
            val result = transport.send(RelayCommands.allOff)
            update(token) {
                publish(
                    state.copy(
                        busy = false, channels = allChannels(result),
                        status = if (result.isSuccess) "全OFF送信完了" else "全OFF失敗 / 状態不明"
                    ),
                    if (result.isSuccess) "全OFF送信"
                    else "全OFF失敗: ${result.exceptionOrNull()?.message ?: "不明"}"
                )
            }
        }
        true
    }

    fun disconnect(): Boolean = synchronized(lock) {
        if (closed || disconnectRequested) return false
        stopAndDisconnect()
        true
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        stopAndDisconnect()
        // Queued cleanup still runs; obsolete delayed timer tasks are discarded.
        executor.shutdown()
    }

    private fun stopAndDisconnect() {
        disconnectRequested = true
        val token = ++generation
        publish(state.copy(busy = true, actionTitle = null, remainingMillis = null, status = "全OFF送信・切断中..."))
        executor.execute {
            val result = if (transportConnected) transport.send(RelayCommands.allOff)
            else Result.failure(IllegalStateException("Bluetooth未接続"))
            try {
                transport.disconnect()
            } finally {
                transportConnected = false
                update(token) {
                    disconnectRequested = false
                    publish(
                        RelayControlState(
                            channels = allChannels(result),
                            status = if (result.isSuccess) "全OFF送信・切断済み" else "切断済み / リレー状態不明"
                        ),
                        if (result.isSuccess) "全OFFを送信して切断した"
                        else "切断した / 全OFF未送信または失敗: ${result.exceptionOrNull()?.message ?: "不明"}"
                    )
                }
            }
        }
    }

    private fun scheduleTick(token: Long, title: String, channel: Int, deadline: Long, remaining: Long) {
        executor.schedule({
            if (token != generation) return@schedule
            val nanos = deadline - System.nanoTime()
            if (nanos <= 0L) {
                val result = transport.send(RelayCommands.channelOff(channel))
                update(token) {
                    publish(
                        state.copy(
                            busy = false, actionTitle = null, remainingMillis = null,
                            channels = channelState(channel, if (result.isSuccess) RelayChannelState.OFF else RelayChannelState.UNKNOWN),
                            status = if (result.isSuccess) "$title 完了" else "$title OFF失敗 / 状態不明"
                        ),
                        if (result.isSuccess) "$title: CH$channel OFF"
                        else "$title OFF失敗: ${result.exceptionOrNull()?.message ?: "不明"}"
                    )
                }
            } else {
                val remainingMillis = TimeUnit.NANOSECONDS.toMillis(nanos).coerceAtLeast(1L)
                update(token) {
                    publish(state.copy(remainingMillis = remainingMillis))
                    scheduleTick(token, title, channel, deadline, remainingMillis)
                }
            }
        }, remaining.coerceAtMost(100L), TimeUnit.MILLISECONDS)
    }

    private fun update(token: Long, block: () -> Unit) = synchronized(lock) {
        if (token == generation) block()
    }

    // Called with lock held. Updates carry a generation so delayed UI callbacks can be ignored.
    private fun publish(nextState: RelayControlState, log: String? = null) {
        state = nextState
        onUpdate(RelayUpdate(generation, nextState, log))
    }

    private fun channelState(channel: Int, value: RelayChannelState): List<RelayChannelState> =
        state.channels.mapIndexed { index, old -> if (index == channel - 1) value else old }

    private fun allChannels(result: Result<Unit>): List<RelayChannelState> =
        List(8) { if (result.isSuccess) RelayChannelState.OFF else RelayChannelState.UNKNOWN }
}
