package dev.aw.engineremotecontroller

import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class RemoteApiException(val statusCode: Int) : IOException("API応答: HTTP $statusCode")

class RemoteApiClient(private val config: RemoteConfig) {
    fun poll(payload: JSONObject): JSONObject {
        val connection = URL(config.baseUrl.trimEnd('/') + "/api/device/poll").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${config.token}")
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != 200) throw RemoteApiException(connection.responseCode)
            val text = connection.inputStream.bufferedReader().use { reader ->
                val chars = CharArray(16385)
                var count = 0
                while (count < chars.size) {
                    val n = reader.read(chars, count, chars.size - count)
                    if (n < 0) break
                    count += n
                }
                if (count > 16384) throw IOException("API応答が大きすぎます")
                String(chars, 0, count)
            }
            return JSONObject(text)
        } finally { connection.disconnect() }
    }
}
