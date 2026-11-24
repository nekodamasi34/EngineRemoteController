package dev.aw.engineremotecontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class AiStatus(
    val running: Boolean = false,
    val message: String = "監視停止中",
    val camera: String = "未取得",
    val interval: Long? = null,
    val thermal: Int? = null,
    val powerSave: Boolean = false,
    val timings: InferenceTimings? = null,
    val average: Double? = null,
    val failures: Int = 0,
    val latest: AiEvent? = null,
    val delivery: String = ""
)

class AiMonitorService : Service() {
    private val settings by lazy { AiSettings(this) }
    private val cameraSettings by lazy { CameraSettings(this) }
    private val remoteSettings by lazy { RemoteSettings(this) }
    private val events by lazy { AiEventStore(this) }
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val rate = AdaptiveInferenceRate()
    private var tracker = DetectionTracker()
    private var previousConfig: AiConfig? = null
    private var previousCamera: CameraConfig? = null
    private var detector: MegaDetector? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tickTask: ScheduledFuture<*>? = null
    private var uploadTask: ScheduledFuture<*>? = null
    @Volatile private var running = false
    private var uploadFailures = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!settings.load().enabled || intent?.action == STOP) {
            if (intent?.action == STOP) settings.save(settings.load().copy(enabled = false))
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        notifications.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "AI監視サービス", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(EVENT_CHANNEL, "AI検知", NotificationManager.IMPORTANCE_DEFAULT))
        val stop = PendingIntent.getService(this, 42, Intent(this, AiMonitorService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = notification(SERVICE_CHANNEL, "AI監視サービス", "アプリで現在の監視状態を確認できます")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "監視OFF", stop).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else startForeground(SERVICE_ID, notification)
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EngineRemoteController:AiMonitor").apply { acquire() }
        } catch (error: RuntimeException) {
            synchronized(statusLock) {
                statusState.value = statusState.value.copy(running = false, message = "監視停止中：サービスを開始できません")
            }
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        publish { AiStatus(running = true, message = "準備中", latest = it.latest) }
        inferenceWorker.execute {
            if (running) {
                publish { it.copy(latest = events.latest()) }
                tick()
            }
        }
        deliveryWorker.execute { deliver() }
        return START_STICKY
    }

    private fun tick() {
        if (!active()) return
        val config = settings.load()
        val thermal = thermal()
        val powerSave = power.isPowerSaveMode
        if (config.copy(enabled = false) != previousConfig?.copy(enabled = false)) {
            tracker.invalidate()
            if (!config.saveImage && previousConfig?.saveImage != false) {
                runCatching { events.forgetLatestImage() }
                publish { it.copy(latest = it.latest?.copy(jpeg = null)) }
            }
            previousConfig = config
        }
        val interval = rate.interval(config.mode, config.manualSeconds, thermal, powerSave)
        val pause = rate.thermalPauseMs(thermal)
        publish { it.copy(thermal = thermal, powerSave = powerSave, interval = if (pause == null) interval else null) }
        if (pause != null) {
            tracker.invalidate()
            publish { it.copy(message = "監視停止中：端末高温", camera = "取得一時停止") }
            scheduleTick(pause)
            return
        }
        if (config.targets.isEmpty()) {
            publish { it.copy(message = "監視停止中：検知対象未選択", camera = "未取得", interval = null) }
            scheduleTick(3000)
            return
        }
        val camera = cameraSettings.load()
        if (!camera.enabled) {
            tracker.invalidate()
            publish { it.copy(message = "監視停止中：カメラ未設定", camera = "OFF", interval = null) }
            scheduleTick(3000)
            return
        }
        if (previousCamera != camera) {
            tracker = DetectionTracker()
            previousCamera = camera
        }

        var stage = "モデルを読み込めません"
        try {
            val model = detector ?: MegaDetector.open(this).also { detector = it }
            val started = now()
            stage = "カメラ画像を取得できません"
            val frame = CameraSnapshot.captureIfActive(camera, { active() && cameraSettings.load() == camera })
            if (frame == null || !active()) {
                scheduleTick(interval)
                return
            }
            val acquired = now()
            publish { it.copy(camera = "接続OK") }
            stage = "画像前処理・推論に失敗"
            var sample = 1
            while (maxOf(frame.width, frame.height) / sample > 1280) sample *= 2
            val source = frame.decode(sample)
            try {
                val transform = model.prepare(source)
                if (!active()) return
                if (camera != cameraSettings.load()) {
                    scheduleTick(interval)
                    return
                }
                val beforeInferenceThermal = thermal()
                val stopped = rate.thermalPauseMs(beforeInferenceThermal)
                if (stopped != null) {
                    tracker.invalidate()
                    publish { it.copy(thermal = beforeInferenceThermal, message = "監視停止中：端末高温",
                        camera = "取得一時停止", interval = null) }
                    scheduleTick(stopped)
                    return
                }
                val inferenceStart = now()
                val result = model.infer(transform, config)
                val finished = now()
                if (!active() || config != settings.load() || camera != cameraSettings.load()) {
                    scheduleTick(interval)
                    return
                }
                val timings = InferenceTimings(acquired - started, inferenceStart - acquired, result.inferenceMs, finished - started)
                val currentThermal = thermal()
                val currentPowerSave = power.isPowerSaveMode
                rate.record(timings, finished, config.mode, interval, thermal == 2 || powerSave)
                val next = rate.interval(config.mode, config.manualSeconds, currentThermal, currentPowerSave)
                val heatDelay = rate.thermalPauseMs(currentThermal)
                publish { it.copy(
                    message = if (heatDelay == null) "監視中" else "監視停止中：端末高温",
                    camera = if (heatDelay == null) "接続OK" else "取得一時停止",
                    interval = if (heatDelay == null) next else null, powerSave = currentPowerSave,
                    thermal = currentThermal, timings = timings, average = rate.averageMs, failures = 0
                ) }
                for (hit in tracker.sample(result.detections, frame.elapsedAt, config)) {
                    if (!active()) break
                    record(frame, source, hit, config, camera)
                }
                scheduleTick(heatDelay ?: rate.nextDelay(inferenceStart, now(), next))
            } finally {
                source.recycle()
            }
        } catch (error: Exception) {
            failed(stage, interval)
        } catch (error: LinkageError) {
            failed("ONNX Runtimeを読み込めません", interval)
        } catch (error: OutOfMemoryError) {
            failed("推論に必要なメモリを確保できません", interval)
        }
    }

    private fun record(
        frame: CameraSnapshot, source: android.graphics.Bitmap, hit: Detection, config: AiConfig, camera: CameraConfig
    ) {
        val jpeg = if (config.saveImage) runCatching { source.eventJpeg() }.getOrNull() else null
        if (!active() || config != settings.load() || camera != cameraSettings.load()) return
        val event = AiEvent(UUID.randomUUID().toString(), frame.capturedAt, hit, jpeg)
        val route = remoteSettings.load().eventRoute()
        val saved = runCatching { events.store(event, route) }.isSuccess
        publish { it.copy(
            latest = event,
            delivery = when {
                !saved -> "検知イベントの端末保存に失敗"
                config.saveImage && jpeg == null -> "検知画像の生成に失敗"
                route == null -> "クラウド接続未設定・端末のみ"
                else -> "検知イベント送信待ち"
            }
        ) }
        if (config.notify) runCatching {
            notifications.notify(EVENT_ID, notification(EVENT_CHANNEL, "AI検知", "${hit.target.name.lowercase()} ${(hit.confidence * 100).toInt()}%")
                .setAutoCancel(true).build())
        }
    }

    private fun failed(stage: String, interval: Long) {
        tracker.invalidate()
        val delay = rate.fail(interval)
        if (stage != "カメラ画像を取得できません") {
            runCatching { detector?.close() }
            detector = null
        }
        publish { it.copy(
            message = "監視停止中：$stage（${delay / 1000}秒後再試行）",
            camera = if (stage == "カメラ画像を取得できません") "取得失敗" else it.camera,
            interval = null, failures = rate.failures
        ) }
        scheduleTick(delay)
    }

    private fun deliver() {
        if (!active()) return
        var delay = 3000L
        try {
            val config = remoteSettings.load()
            val route = config.eventRoute()
            val event = events.next(route)
            if (route != null && event != null && active()) {
                val status = uploadAiEvent(event, route, config.token)
                when (status) {
                    in 200..299 -> {
                        events.acknowledge(event.id)
                        uploadFailures = 0
                        delay = 1000
                        publish { it.copy(delivery = "検知イベント送信済み") }
                    }
                    400, 409, 410, 413, 415 -> {
                        events.acknowledge(event.id)
                        publish { it.copy(delivery = "検知イベント受付不可（HTTP $status）") }
                    }
                    else -> error("Event upload failed")
                }
            } else uploadFailures = 0
        } catch (error: Exception) {
            uploadFailures = (uploadFailures + 1).coerceAtMost(9)
            delay = (1000L shl uploadFailures).coerceAtMost(300_000)
            publish { it.copy(delivery = "クラウド送信失敗・再送待ち") }
        }
        if (active()) uploadTask = deliveryWorker.schedule({ deliver() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun notification(channel: String, title: String, text: String) =
        Notification.Builder(this, channel).setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))

    private fun active() = running && settings.load().enabled
    private fun now() = SystemClock.elapsedRealtime()
    private fun thermal(): Int? = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else null

    private fun scheduleTick(delay: Long) {
        if (active()) tickTask = inferenceWorker.schedule({ tick() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun publish(change: (AiStatus) -> AiStatus) = synchronized(statusLock) {
        if (running) statusState.value = change(statusState.value)
    }

    override fun onDestroy() {
        synchronized(statusLock) {
            running = false
            val old = statusState.value
            statusState.value = AiStatus(
                message = if (old.running) "監視停止中" else old.message,
                latest = old.latest, delivery = old.delivery
            )
        }
        tickTask?.cancel(false)
        uploadTask?.cancel(false)
        inferenceWorker.execute {
            runCatching { detector?.close() }
            detector = null
        }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        private const val STOP = "dev.aw.engineremotecontroller.AI_STOP"
        private const val SERVICE_CHANNEL = "ai_monitor"
        private const val EVENT_CHANNEL = "ai_detection"
        private const val SERVICE_ID = 51
        private const val EVENT_ID = 52
        private val statusLock = Any()
        private val statusState = MutableStateFlow(AiStatus())
        internal val status = statusState.asStateFlow()

        // Shared per process so quick OFF/ON or a service restart cannot overlap inferences.
        private val inferenceWorker = aiWorker("AI inference", ::setWorkerPriority)
        private val deliveryWorker = aiWorker("AI event delivery", ::setWorkerPriority)

        private fun setWorkerPriority() = Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    }
}

internal fun aiWorker(name: String, initializeThread: () -> Unit = {}) =
    ScheduledThreadPoolExecutor(1) { work ->
        Thread({
            initializeThread()
            work.run()
        }, name)
    }.apply { removeOnCancelPolicy = true }
