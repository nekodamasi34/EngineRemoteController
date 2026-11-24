package dev.aw.engineremotecontroller

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class AiEvent(val id: String, val capturedAt: Long, val detection: Detection, val jpeg: ByteArray?)
internal data class EventRoute(val baseUrl: String, val deviceId: String)

internal fun RemoteConfig.eventRoute(): EventRoute? {
    val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null || uri.path !in listOf("", "/") ||
        !deviceId.matches(Regex("[a-zA-Z0-9_-]{1,80}")) || token.length !in 32..256 ||
        token.any { it.code !in 33..126 }) return null
    return EventRoute(baseUrl.trimEnd('/'), deviceId)
}

private fun AiEvent.metadata(): JSONObject {
    val box = detection.box
    return JSONObject().put("eventId", id).put("capturedAt", capturedAt)
        .put("target", detection.target.name.lowercase()).put("confidence", detection.confidence.toDouble())
        .put("bbox", JSONArray(listOf(box.left.toDouble(), box.top.toDouble(), box.right.toDouble(), box.bottom.toDouble())))
}

internal class AiEventStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, "ai-events")
    private val last = AtomicFile(File(dir, "latest.json"))

    fun latest(): AiEvent? = synchronized(lock) {
        read(last)?.let { record ->
            record.event.takeIf { System.currentTimeMillis() - it.capturedAt < RETENTION_MS }
                ?: run { last.delete(); null }
        }
    }

    fun forgetLatestImage() = synchronized(lock) {
        read(last)?.let { write(last, it.event.copy(jpeg = null), null) }
    }

    fun store(event: AiEvent, route: EventRoute?) = synchronized(lock) {
        if (route != null) {
            val file = File(dir, "${event.id}.event")
            write(AtomicFile(file), event, route)
            trim(keep = file)
        }
        write(last, event, null)
    }

    fun next(route: EventRoute?): AiEvent? = synchronized(lock) {
        trim().firstNotNullOfOrNull { file ->
            val record = read(AtomicFile(file))
            if (record == null) AtomicFile(file).delete()
            record?.event?.takeIf { route != null && record.route == route }
        }
    }

    fun acknowledge(id: String) = synchronized(lock) { AtomicFile(File(dir, "$id.event")).delete() }

    private fun trim(keep: File? = null): List<File> {
        val now = System.currentTimeMillis()
        val files = dir.listFiles().orEmpty()
        files.filter { it.name.endsWith(".event.new") }.forEach { it.delete() }
        val pending = files.filter { it.extension == "event" }.sortedBy { it.lastModified() }.toMutableList()
        var bytes = pending.sumOf { it.length() }
        pending.toList().forEach { file ->
            if (file != keep && (now - file.lastModified() > RETENTION_MS || pending.size > 40 || bytes > 6 * 1024 * 1024)) {
                bytes -= file.length()
                AtomicFile(file).delete()
                pending.remove(file)
            }
        }
        return pending
    }

    private fun write(file: AtomicFile, event: AiEvent, route: EventRoute?) {
        val json = event.metadata()
        event.jpeg?.let { json.put("jpeg", Base64.encodeToString(it, Base64.NO_WRAP)) }
        route?.let { json.put("url", it.baseUrl).put("deviceId", it.deviceId) }
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private data class Record(val event: AiEvent, val route: EventRoute?)

    private fun read(file: AtomicFile): Record? = runCatching {
        val json = JSONObject(String(file.readFully(), Charsets.UTF_8))
        val box = json.getJSONArray("bbox")
        val detection = Detection(
            DetectionClass.valueOf(json.getString("target").uppercase()), json.getDouble("confidence").toFloat(),
            DetectionBox(box.getDouble(0).toFloat(), box.getDouble(1).toFloat(), box.getDouble(2).toFloat(), box.getDouble(3).toFloat())
        )
        val jpeg = json.optString("jpeg").takeIf { it.isNotEmpty() }?.let { Base64.decode(it, Base64.NO_WRAP) }
        val event = AiEvent(json.getString("eventId"), json.getLong("capturedAt"), detection, jpeg)
        val route = if (json.has("url")) EventRoute(json.getString("url"), json.getString("deviceId")) else null
        Record(event, route)
    }.getOrNull()

    companion object {
        private val lock = Any()
        private const val RETENTION_MS = 7 * 24 * 60 * 60 * 1000L
    }
}

internal fun uploadAiEvent(event: AiEvent, route: EventRoute, token: String): Int {
    val connection = URL(route.baseUrl + "/api/device/ai/events").openConnection() as HttpsURLConnection
    try {
        val body = event.jpeg ?: ByteArray(0)
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", if (body.isNotEmpty()) "image/jpeg" else "application/octet-stream")
        connection.setRequestProperty("X-AI-Event", event.metadata().put("deviceId", route.deviceId).toString())
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
        return connection.responseCode
    } finally {
        connection.disconnect()
    }
}
