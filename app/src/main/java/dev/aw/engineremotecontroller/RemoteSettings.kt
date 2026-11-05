package dev.aw.engineremotecontroller

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class RemoteConfig(val baseUrl: String, val deviceId: String, val token: String, val bluetoothAddress: String) {
    fun validate() {
        val url = URI(baseUrl)
        require(url.scheme == "https" && !url.host.isNullOrBlank() && url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null && url.path in listOf("", "/")) { "接続先はHTTPSのサイトURLを入力してください" }
        require(deviceId.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "端末IDが不正です" }
        require(token.length in 32..256 && token.all { it.code in 33..126 }) { "端末キーは32〜256文字で入力してください" }
        require(bluetoothAddress.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) { "接続するBluetooth機器を選択してください" }
    }
}

/** Device credential is encrypted with an Android Keystore key and excluded from backups. */
class RemoteSettings(context: Context) {
    private val preferences = context.getSharedPreferences("remote_settings", Context.MODE_PRIVATE)
    private val alias = "engine_remote_device_token"

    fun load(): RemoteConfig {
        val token = runCatching {
            val encoded = preferences.getString("token", null) ?: return@runCatching ""
            val parts = encoded.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
        return RemoteConfig(preferences.getString("url", "") ?: "", preferences.getString("device", "engine-1") ?: "engine-1", token, preferences.getString("bluetooth", "") ?: "")
    }

    fun save(config: RemoteConfig) {
        config.validate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(config.token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(preferences.edit().putString("url", config.baseUrl.trimEnd('/')).putString("device", config.deviceId)
            .putString("bluetooth", config.bluetoothAddress).putString("token", encrypted).commit()) { "設定を保存できませんでした" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
