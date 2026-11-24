package dev.aw.engineremotecontroller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException

internal class CameraSnapshot(
    private val raw: ByteArray, val width: Int, val height: Int,
    val elapsedAt: Long, val capturedAt: Long
) {
    fun decode(sample: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
            ?: throw IOException("JPEGを取得できません")
    }

    fun webJpeg(): ByteArray {
        var sample = 1
        while (width / sample > 1280 || height / sample > 720) sample *= 2
        val source = decode(sample)
        return try { source.eventJpeg() } finally { source.recycle() }
    }

    companion object {
        private var previous: CameraConfig? = null
        private var client: OnvifSnapshotClient? = null
        private var latest: CameraSnapshot? = null

        // The lock covers ONVIF I/O only. Consumers decode, infer and upload outside the lock.
        @Synchronized
        fun captureIfActive(
            config: CameraConfig,
            active: () -> Boolean = { true },
            fetch: (() -> CameraSnapshot)? = null
        ): CameraSnapshot? {
            if (!active()) return null
            latest?.takeIf { previous == config && SystemClock.elapsedRealtime() - it.elapsedAt < 900 }
                ?.let { return it }
            if (previous != config || client == null) {
                previous = config
                client = OnvifSnapshotClient(config)
            }
            // Only one bounded compressed snapshot is retained. Stale snapshots cannot be reused.
            latest = null
            return (fetch?.invoke() ?: checkNotNull(client).rawSnapshot(active)).also { latest = it }
        }
    }
}

internal fun Bitmap.eventJpeg(): ByteArray {
    val ratio = minOf(1.0, 640.0 / width, 360.0 / height)
    val scaled = Bitmap.createScaledBitmap(this, maxOf(1, (width * ratio).toInt()), maxOf(1, (height * ratio).toInt()), true)
    try {
        var result = ByteArray(0)
        for (quality in listOf(70, 55, 40, 25)) {
            result = ByteArrayOutputStream().use {
                check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, it))
                it.toByteArray()
            }
            if (result.size <= 50 * 1024) break
        }
        require(result.size <= 128 * 1024) { "圧縮画像が大きすぎます" }
        return result
    } finally {
        if (scaled !== this) scaled.recycle()
    }
}
