package dev.aw.engineremotecontroller

import kotlin.math.round
import kotlin.math.roundToInt

internal const val MODEL_NAME = "MDV6-yolov10-c"
internal const val MODEL_SIZE = 640
internal const val MODEL_ROWS = 300

enum class DetectionClass { ANIMAL, PERSON, VEHICLE }
data class DetectionBox(val left: Float, val top: Float, val right: Float, val bottom: Float)
data class Detection(val target: DetectionClass, val confidence: Float, val box: DetectionBox)

/** Normalized xyxy in the source image, independent of JPEG display size. */
internal class Letterbox(width: Int, height: Int) {
    private val gain = minOf(MODEL_SIZE.toDouble() / width, MODEL_SIZE.toDouble() / height)
    val resizedWidth = round(width * gain).toInt().coerceAtLeast(1)
    val resizedHeight = round(height * gain).toInt().coerceAtLeast(1)
    val left = ((MODEL_SIZE - resizedWidth) / 2.0 - 0.1).roundToInt()
    val top = ((MODEL_SIZE - resizedHeight) / 2.0 - 0.1).roundToInt()
    private val widthScale = gain * width
    private val heightScale = gain * height

    fun box(row: FloatArray): DetectionBox = DetectionBox(
        ((row[0] - left) / widthScale).coerceIn(0.0, 1.0).toFloat(),
        ((row[1] - top) / heightScale).coerceIn(0.0, 1.0).toFloat(),
        ((row[2] - left) / widthScale).coerceIn(0.0, 1.0).toFloat(),
        ((row[3] - top) / heightScale).coerceIn(0.0, 1.0).toFloat()
    )
}

internal fun parseDetections(rows: Array<FloatArray>, transform: Letterbox, config: AiConfig): List<Detection> =
    rows.mapNotNull { row ->
        if (row.size != 6 || row.any { !it.isFinite() } || row[4] !in config.threshold..1f) return@mapNotNull null
        val target = DetectionClass.values().getOrNull(row[5].toInt()) ?: return@mapNotNull null
        if (row[5] != target.ordinal.toFloat() || target !in config.targets) return@mapNotNull null
        val box = transform.box(row)
        if (box.left >= box.right || box.top >= box.bottom) return@mapNotNull null
        Detection(target, row[4], box)
    }

internal class DetectionTracker {
    private class Track {
        val hits = ArrayDeque<Boolean>()
        var lastSeen: Long? = null
        var lastEvent: Long? = null
        var active = false
    }
    private val tracks = DetectionClass.values().associateWith { Track() }
    private var lastSample: Long? = null

    fun sample(detections: List<Detection>, now: Long, config: AiConfig): List<Detection> {
        if (lastSample?.let { now - it > 30_000 } == true) invalidate()
        lastSample = now
        return tracks.mapNotNull { (target, track) ->
            val hit = detections.filter { it.target == target }.maxByOrNull { it.confidence }
            if (track.lastSeen?.let { now - it >= 30_000 } == true) track.active = false
            if (hit != null) track.lastSeen = now
            track.hits.addLast(hit != null)
            if (track.hits.size > config.window) track.hits.removeFirst()
            if (hit == null || track.active || track.hits.count { it } < config.required) return@mapNotNull null
            track.active = true
            if (track.lastEvent?.let { now - it < config.suppressionSeconds * 1000L } == true) return@mapNotNull null
            track.lastEvent = now
            hit
        }
    }

    fun invalidate() = tracks.values.forEach { it.hits.clear() }
}
