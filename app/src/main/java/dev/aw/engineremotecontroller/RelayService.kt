package dev.aw.engineremotecontroller

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One owner for Bluetooth, whether actions originate in the Activity or on the web. */
class RelayService : Service() {
    inner class LocalBinder : Binder() { val service: RelayService get() = this@RelayService }
    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArrayList<(RelayUpdate) -> Unit>()
    private val remoteLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    var cameraManager: CameraManager? = null
        private set
    private var networkWorker: ScheduledThreadPoolExecutor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var remoteSession = ""
    private var connectedAddress: String? = null
    private var activeCommand: RemoteCommand? = null
    private val reports = mutableListOf<JSONObject>()
    private val seenCommands = linkedSetOf<String>()
    @Volatile private var destroyed = false
    @Volatile var remoteRunning = false
        private set
    @Volatile var remoteStatus = "遠隔待受停止中"
        private set
    @Volatile var currentUpdate = RelayUpdate(0, RelayControlState())
        private set
    lateinit var controller: RelayController
        private set

    override fun onCreate() {
        super.onCreate()
        controller = RelayController(BluetoothRelayManager(applicationContext)) { update ->
            currentUpdate = update
            // RelayController calls back with its own lock held: never acquire remoteLock here.
            mainHandler.post {
                if (destroyed || !controller.isCurrent(update)) return@post
                synchronized(remoteLock) {
                    activeCommand?.let { command ->
                        if (!update.state.busy) {
                            val action = RemoteCommandPolicy.actions.getValue(command.action)
                            val succeeded = update.state.status == if (action.channel == null) "全OFF送信完了" else "${action.title} 完了"
                            val result = when {
                                succeeded -> "succeeded"
                                update.state.channels.any { it == RelayChannelState.UNKNOWN } -> "unknown"
                                update.state.status.contains("失敗") -> "failed"
                                else -> "cancelled"
                            }
                            addReport(command, result, update.state.status)
                            activeCommand = null
                        }
                    }
                }
                publish()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun addListener(listener: (RelayUpdate) -> Unit) { listeners.add(listener); listener(currentUpdate) }
    fun removeListener(listener: (RelayUpdate) -> Unit) { listeners.remove(listener) }
    fun connect(address: String, deviceName: String = address): Boolean {
        val accepted = controller.connect(address, deviceName)
        if (accepted) connectedAddress = address
        return accepted
    }
    private fun publish() { if (!destroyed) listeners.forEach { it(currentUpdate) } }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_REMOTE) {
            stopRemote()
            return START_NOT_STICKY
        }
        if (intent?.action != START_REMOTE) { stopSelf(); return START_NOT_STICKY }
        // Started from a visible Activity after Bluetooth permission and explicit user action.
        val config = RemoteSettings(this).load()
        try {
            config.validate()
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "遠隔操作の待受", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 2, Intent(this, RelayService::class.java).setAction(STOP_REMOTE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("エンジン遠隔待受中")
                .setContentText("現地端末としてWebからの指示を待っています")
                .setContentIntent(open).setOngoing(true).addAction(0, "待受停止・全OFF", stop).build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION_ID, notification)
            if (!remoteRunning) startRemote(config)
        } catch (e: Exception) {
            stopRemote()
            remoteStatus = e.message ?: "遠隔待受を開始できませんでした"
            publish()
        }
        // Never silently resume remote execution after process death or a device reboot.
        return START_NOT_STICKY
    }

    private fun startRemote(config: RemoteConfig) {
        synchronized(remoteLock) {
            require(!controller.currentState.connected || config.bluetoothAddress == connectedAddress) { "Bluetoothを切断して選択した機器で待受を開始してください" }
            remoteSession = UUID.randomUUID().toString()
            reports.clear()
            seenCommands.clear()
            remoteRunning = true
            remoteStatus = "サーバーへ接続中"
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EngineRemote:Receiver").apply {
                setReferenceCounted(false)
                acquire(60000L)
            }
            if (!controller.currentState.connected && !controller.currentState.busy) connect(config.bluetoothAddress)
            val worker = ScheduledThreadPoolExecutor(1).apply {
                removeOnCancelPolicy = true
                setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            }
            networkWorker = worker
            cameraManager = runCatching { CameraManager(CameraSettings(this), CameraUploadClient(config)) }.getOrNull()
            val client = RemoteApiClient(config)
            val session = remoteSession
            worker.execute { pollOnce(client, config, session, worker) }
        }
        publish()
    }

    private fun pollOnce(client: RemoteApiClient, config: RemoteConfig, session: String, worker: ScheduledThreadPoolExecutor) {
        if (!remoteRunning || destroyed || session != remoteSession) return
        synchronized(remoteLock) {
            if (!remoteRunning || session != remoteSession) return
            // Renew a bounded lock only while the receiver loop is alive.
            wakeLock?.acquire(60000L)
        }
        var nextDelay = 5000L
        try {
            val pending = synchronized(remoteLock) { reports.take(10) }
            val state = controller.currentState
            val payload = JSONObject().put("deviceId", config.deviceId).put("sessionId", session)
                .put("state", JSONObject().put("connected", state.connected).put("ready", state.canRun && state.channels.all { it == RelayChannelState.OFF })
                    .put("busy", state.busy).put("status", state.status).put("channels", JSONArray(state.channels.map { it.name })))
                .put("results", JSONArray(pending))
            val sentAt = SystemClock.elapsedRealtime()
            val reply = client.poll(payload)
            val elapsed = SystemClock.elapsedRealtime() - sentAt
            runCatching { cameraManager?.update(reply.optJSONObject("camera"), reply.getLong("serverTime"), elapsed) }
            synchronized(remoteLock) {
                if (!remoteRunning || session != remoteSession) return
                reports.removeAll(pending.toSet())
                remoteStatus = "遠隔待受中 / サーバー接続OK"
                val raw = reply.optJSONObject("command")
                if (raw != null) {
                    val command = RemoteCommand(raw.getString("id"), raw.getString("claimId"), raw.getString("action"), raw.getLong("durationMs"), raw.getLong("expiresAt"))
                    execute(command, reply.getLong("serverTime"), elapsed)
                }
            }
        } catch (e: Exception) {
            if (!remoteRunning || session != remoteSession) return
            if (e is RemoteApiException && e.statusCode in listOf(401, 403, 503)) {
                nextDelay = 30000L
                remoteStatus = "接続設定を確認してください / HTTP ${e.statusCode}"
            } else {
                nextDelay = 15000L
                remoteStatus = "サーバー応答なし / 再接続待ち"
            }
            // Timed relay OFF is independent of this network worker and always continues.
        }
        publish()
        synchronized(remoteLock) {
            if (remoteRunning && !destroyed && session == remoteSession && !worker.isShutdown) {
                worker.schedule({ pollOnce(client, config, session, worker) }, nextDelay, TimeUnit.MILLISECONDS)
            }
        }
    }

    // Called with remoteLock held. Physical operations occur after an atomic server claim.
    private fun execute(command: RemoteCommand, serverTime: Long, elapsed: Long) {
        if (!seenCommands.add(command.id)) return
        if (seenCommands.size > 128) seenCommands.remove(seenCommands.first())
        val rejected = RemoteCommandPolicy.rejection(command, serverTime, elapsed, controller.currentState)
        if (rejected != null) { addReport(command, "failed", rejected); return }
        if (command.action == "all_off") {
            activeCommand?.let { addReport(it, "cancelled", "全OFFにより中断") }
            activeCommand = null
        }
        val action = RemoteCommandPolicy.actions.getValue(command.action)
        activeCommand = command
        val accepted = if (action.channel == null) controller.emergencyAllOff()
        else controller.runTimedAction(action.title, action.channel, command.durationMillis)
        if (!accepted) { activeCommand = null; addReport(command, "failed", "現地端末が操作を受け付けませんでした") }
    }

    private fun addReport(command: RemoteCommand, state: String, message: String) {
        reports.add(JSONObject().put("id", command.id).put("claimId", command.claimId).put("state", state).put("message", message.take(160)))
    }

    fun stopRemote() {
        synchronized(remoteLock) {
            remoteRunning = false
            remoteSession = ""
            cameraManager?.close()
            cameraManager = null
            networkWorker?.shutdownNow()
            networkWorker = null
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            controller.emergencyAllOff()
            remoteStatus = "遠隔待受停止 / 全OFFを要求"
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        publish()
    }

    override fun onDestroy() {
        destroyed = true
        cameraManager?.close()
        cameraManager = null
        synchronized(remoteLock) {
            remoteRunning = false
            remoteSession = ""
            networkWorker?.shutdownNow()
            wakeLock?.let { if (it.isHeld) it.release() }
            controller.close()
        }
        listeners.clear()
        super.onDestroy()
    }

    companion object {
        const val START_REMOTE = "dev.aw.engineremotecontroller.START_REMOTE"
        const val STOP_REMOTE = "dev.aw.engineremotecontroller.STOP_REMOTE"
        private const val CHANNEL_ID = "remote_receiver"
        private const val NOTIFICATION_ID = 1
    }
}
