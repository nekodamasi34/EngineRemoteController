package dev.aw.engineremotecontroller

import java.net.URL
import javax.net.ssl.HttpsURLConnection

class CameraUploadClient(private val config: RemoteConfig) {
    fun upload(jpeg: ByteArray, capturedAt: Long) {
        val c = URL(config.baseUrl.trimEnd('/')+"/api/device/camera/frame").openConnection() as HttpsURLConnection
        try {
            c.requestMethod="POST"; c.instanceFollowRedirects=false
            c.connectTimeout=3000; c.readTimeout=3000; c.doOutput=true
            c.setRequestProperty("Authorization","Bearer ${config.token}")
            c.setRequestProperty("Content-Type","image/jpeg")
            c.setRequestProperty("X-Captured-At",capturedAt.toString())
            c.setFixedLengthStreamingMode(jpeg.size)
            c.outputStream.use { it.write(jpeg) }
            if(c.responseCode != 200) throw RemoteApiException(c.responseCode)
        } finally { c.disconnect() }
    }
}
