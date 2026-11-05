package dev.aw.engineremotecontroller

import android.content.Context
import org.json.JSONObject

data class CameraConfig(val enabled: Boolean = false, val host: String = "", val port: Int = 80, val username: String = "", val password: String = "") {
    fun validate() {
        require(host.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"))) { "カメラのIPv4アドレスを入力してください" }
        val parts = host.split('.').map { it.toInt() }
        require(parts.all { it in 0..255 } && (parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31))) { "カメラはプライベートLAN内のIPv4を指定してください" }
        require(port in 1..65535 && username.length in 1..128 && password.length <= 256) { "ポート・認証情報を確認してください" }
    }
}

/** Reuses the existing Keystore envelope; no credentials leave this device. */
class CameraSettings(context: Context) {
    private val store = RemoteSettings(context, "camera_settings", "engine_camera_credentials")
    fun load(): CameraConfig = runCatching {
        val c = store.load()
        val j = JSONObject(c.token)
        CameraConfig(j.optBoolean("enabled"), j.optString("host"), j.optInt("port",80),j.optString("username"),j.optString("password"))
    }.getOrDefault(CameraConfig())
    fun save(config: CameraConfig) {
        if (config.enabled) config.validate()
        store.saveEncrypted(JSONObject().put("enabled",config.enabled).put("host",config.host).put("port",config.port).put("username",config.username).put("password",config.password).toString())
    }
}
