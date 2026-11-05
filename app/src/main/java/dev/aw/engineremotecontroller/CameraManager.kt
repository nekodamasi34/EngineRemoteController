package dev.aw.engineremotecontroller

import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Single independent worker, one frame in flight, never a frame queue. */
class CameraManager(private val settings: CameraSettings, private val upload: CameraUploadClient) : AutoCloseable {
    @Volatile private var lease=CameraLease()
    @Volatile private var serverOffset=0L
    @Volatile private var closed=false
    @Volatile var status="監視OFF"; private set
    @Volatile var lastUpload=0L; private set
    private var client: OnvifSnapshotClient?=null
    private var previous: CameraConfig?=null
    private val worker=ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy=true; setExecuteExistingDelayedTasksAfterShutdownPolicy(false) }
    init { worker.execute { tick() } }
    fun update(raw: JSONObject?, serverTime: Long, roundTrip: Long) {
        val now=SystemClock.elapsedRealtime()
        fun deadline(key:String)=now+(raw?.optLong(key,0)?.minus(serverTime)?.minus(roundTrip) ?: 0).coerceIn(0,120000)
        serverOffset=serverTime-now
        lease=CameraLease(raw?.optString("mode","off") ?: "off",deadline("expiresAt"),deadline("detailUntil"))
    }
    private fun tick() {
        if(closed) return
        var delay=1000L
        try {
            val current=lease; val now=SystemClock.elapsedRealtime()
            val config=settings.load()
            if(current.modeAt(now)=="off" || !config.enabled) { status="監視OFF"; client=null }
            else {
                delay=current.interval(now)
                if(config!=previous || client==null) { client=OnvifSnapshotClient(config);previous=config }
                val capturedAt=SystemClock.elapsedRealtime()+serverOffset
                val sent=CameraFrameGate.transfer(
                    active={ !closed && lease.modeAt(SystemClock.elapsedRealtime())!="off" && settings.load()==config },
                    capture={client!!.capture()}, upload={upload.upload(it,capturedAt)})
                if(sent) { lastUpload=System.currentTimeMillis();status="カメラ接続OK / ${current.modeAt(now)}" }
            }
        } catch(e: Exception) { status="カメラ取得・送信失敗（IP・認証・ONVIF対応を確認）"; client=null; delay=10000 }
        if(!closed) runCatching { worker.schedule({tick()},delay,TimeUnit.MILLISECONDS) }
    }
    override fun close() { closed=true;lease=CameraLease();worker.shutdownNow();status="監視OFF" }
}
