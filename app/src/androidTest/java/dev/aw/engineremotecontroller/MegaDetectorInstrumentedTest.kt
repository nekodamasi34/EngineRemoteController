package dev.aw.engineremotecontroller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileNotFoundException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MegaDetectorInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun missingModelReportsLoadFailure() {
        assertThrows(FileNotFoundException::class.java) { MegaDetector.open(context, "missing-model.onnx") }
    }

    @Test fun loadsVerifiedContractAndRunsCpu() {
        assumeTrue("$MODEL_NAME.onnx missing from APK", "$MODEL_NAME.onnx" in context.assets.list("").orEmpty())
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF727272.toInt())
        try {
            MegaDetector.open(context).use { detector ->
                val transform = detector.prepare(bitmap)
                val detections = detector.infer(transform, AiConfig()).detections
                assertTrue(detections.isEmpty())
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun detectsAnimalInOfficialSampleAndReportsProcessMemory() {
        val assets = instrumentation.context.assets
        assumeTrue("Install optional official cat fixture", "mdv6-cat.jpg" in assets.list("").orEmpty())
        val bitmap = assets.open("mdv6-cat.jpg").use { BitmapFactory.decodeStream(it) }
        val before = Debug.getPss()
        try {
            MegaDetector.open(context).use { detector ->
                val transform = detector.prepare(bitmap)
                val times = (1..5).map {
                    val result = detector.infer(transform, AiConfig())
                    val detection = result.detections.single()
                    assertEquals(DetectionClass.ANIMAL, detection.target)
                    assertEquals(.9221f, detection.confidence, .03f)
                    assertEquals(.064f, detection.box.top, .04f)
                    result.inferenceMs
                }
                Log.i("MegaDetectorTest", "CPU inference ms=$times; process PSS KiB before=$before after=${Debug.getPss()}")
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun inactiveCameraConsumerDoesNotFetchAndActiveConsumersShareSnapshot() {
        val config = CameraConfig(enabled = true, host = "192.168.1.76", username = "test")
        val frame = CameraSnapshot(byteArrayOf(1), 640, 480, SystemClock.elapsedRealtime(), System.currentTimeMillis())
        assertNull(CameraSnapshot.captureIfActive(config, { false }) { error("Inactive consumer fetched") })
        assertSame(frame, CameraSnapshot.captureIfActive(config) { frame })
        assertSame(frame, CameraSnapshot.captureIfActive(config) { error("Duplicate request") })
    }
}
