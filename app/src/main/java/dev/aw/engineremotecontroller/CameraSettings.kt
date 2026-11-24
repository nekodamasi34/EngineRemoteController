package dev.aw.engineremotecontroller

import android.content.Context
import org.json.JSONObject
import java.net.URI
import java.net.URL

internal class CameraInputException(message: String) : IllegalArgumentException(message)

data class CameraConfig(val enabled: Boolean = false, val host: String = "", val port: Int = 80, val username: String = "", val password: String = "") {
    private fun validateHost() {
        if (!host.matches(IPV4)) throw CameraInputException("カメラのIPv4を先頭ゼロなしの10進数で入力してください")
        val parts = host.split('.').map { it.toInt() }
        val privateRange = parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
        if (parts.any { it > 255 }) throw CameraInputException("カメラのIPv4は各欄を0〜255で入力してください")
        if (!privateRange) throw CameraInputException("カメラはプライベートLAN内のIPv4を指定してください")
    }

    fun validate() {
        validateHost()
        if (port !in 1..65535) throw CameraInputException("ONVIFポートは1〜65535で入力してください")
        if (username.length !in 1..128 || password.length > 256) {
            throw CameraInputException("カメラの認証情報を確認してください")
        }
    }

    internal fun cameraUrl(value: String): URL {
        validateHost()
        val uri = runCatching { URI(value.trim()) }.getOrNull()
        if (uri == null || (uri.scheme != "http" && uri.scheme != "https") ||
            uri.host != host || uri.rawUserInfo != null || uri.rawFragment != null ||
            (uri.rawAuthority != host && (uri.port !in 1..65535 || uri.rawAuthority != "$host:${uri.port}"))) {
            throw CameraConnectionException("ONVIFのURLが不正です。設定したIP・認証情報を含まないURLを確認してください")
        }
        return uri.toURL()
    }

    private companion object {
        val IPV4 = Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")
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
        if (config.enabled || config.host.isNotEmpty()) config.validate()
        store.saveEncrypted(JSONObject().put("enabled",config.enabled).put("host",config.host).put("port",config.port).put("username",config.username).put("password",config.password).toString())
        CameraSnapshot.invalidate()
    }
}
