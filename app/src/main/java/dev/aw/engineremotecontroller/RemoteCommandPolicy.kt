package dev.aw.engineremotecontroller

data class RemoteCommand(
    val id: String,
    val claimId: String,
    val action: String,
    val durationMillis: Long,
    val expiresAt: Long
)

data class RemoteAction(val title: String, val channel: Int?)

object RemoteCommandPolicy {
    val actions = mapOf(
        "alarm" to RemoteAction("アラーム", 3),
        "ignition" to RemoteAction("イグニッション", 1),
        "engine_stop" to RemoteAction("エンジン切", 2),
        "choke_a" to RemoteAction("チョークA", 5),
        "choke_b" to RemoteAction("チョークB", 6),
        "all_off" to RemoteAction("全リレーOFF", null)
    )

    fun rejection(command: RemoteCommand, serverTime: Long, roundTripMillis: Long, state: RelayControlState): String? {
        if (!command.id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) || !command.claimId.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) return "指示IDが不正"
        if (command.expiresAt - serverTime <= roundTripMillis.coerceAtLeast(0)) return "指示の有効期限切れ"
        val action = actions[command.action] ?: return "未対応の操作"
        if (!state.connected) return "Bluetooth未接続"
        if (action.channel == null) return if (command.durationMillis == 0L) null else "全OFFの時間が不正"
        if (command.durationMillis !in 100L..5000L) return "操作時間が不正"
        if (!state.canRun || state.channels.any { it != RelayChannelState.OFF }) return "操作中またはリレー状態不明"
        return null
    }
}
