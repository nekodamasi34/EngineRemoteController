package dev.aw.engineremotecontroller

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

internal class MegaDetector private constructor(
    private val environment: OrtEnvironment, private val session: OrtSession
) : AutoCloseable {
    private val plane = MODEL_SIZE * MODEL_SIZE
    private val input = ByteBuffer.allocateDirect(3 * plane * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val pixels = IntArray(plane)
    private val square = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(square)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun prepare(source: Bitmap): Letterbox {
        val transform = Letterbox(source.width, source.height)
        canvas.drawColor(0xFF727272.toInt())
        canvas.drawBitmap(source, null, Rect(
            transform.left, transform.top, transform.left + transform.resizedWidth, transform.top + transform.resizedHeight
        ), paint)
        square.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        pixels.forEachIndexed { i, pixel ->
            input.put(i, (pixel shr 16 and 255) / 255f)
            input.put(i + plane, (pixel shr 8 and 255) / 255f)
            input.put(i + 2 * plane, (pixel and 255) / 255f)
        }
        return transform
    }

    data class Result(val detections: List<Detection>, val inferenceMs: Long)

    fun infer(transform: Letterbox, config: AiConfig): Result =
        OnnxTensor.createTensor(environment, input, inputShape).use { tensor ->
            val start = SystemClock.elapsedRealtime()
            session.run(mapOf("images" to tensor)).use { result ->
                val inferenceMs = SystemClock.elapsedRealtime() - start
                @Suppress("UNCHECKED_CAST")
                val rows = (result[0].value as Array<Array<FloatArray>>).single()
                Result(parseDetections(rows, transform, config), inferenceMs)
            }
        }

    override fun close() {
        square.recycle()
        session.close()
    }

    companion object {
        private val inputShape = longArrayOf(1, 3, MODEL_SIZE.toLong(), MODEL_SIZE.toLong())
        private val outputShape = longArrayOf(1, MODEL_ROWS.toLong(), 6)

        fun open(context: Context, asset: String = "$MODEL_NAME.onnx"): MegaDetector =
            context.assets.openFd(asset).use { descriptor ->
                val model = descriptor.createInputStream().use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.length)
                }
                val environment = OrtEnvironment.getEnvironment()
                val session = OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2)
                    options.setInterOpNumThreads(1)
                    options.addConfigEntry("session.intra_op.allow_spinning", "0")
                    environment.createSession(model, options)
                }
                try {
                    validate(session.inputInfo.mapValues { it.value.info as? TensorInfo }, "images", inputShape)
                    validate(session.outputInfo.mapValues { it.value.info as? TensorInfo }, "output0", outputShape)
                    val names = session.metadata.customMetadata["names"].orEmpty()
                    require(names.replace(" ", "").replace('"', '\'') == "{0:'animal',1:'person',2:'vehicle'}") {
                        "MegaDetector class metadata mismatch"
                    }
                    MegaDetector(environment, session)
                } catch (error: Throwable) {
                    session.close()
                    throw error
                }
            }

        private fun validate(nodes: Map<String, TensorInfo?>, name: String, shape: LongArray) {
            val info = nodes[name]
            require(nodes.size == 1 && info != null && matchesModelTensor(info.shape, info.type == OnnxJavaType.FLOAT, shape)) {
                "MegaDetector tensor mismatch: $name"
            }
        }
    }
}

internal fun matchesModelTensor(actual: LongArray, isFloat: Boolean, expected: LongArray) =
    isFloat && actual.contentEquals(expected)
