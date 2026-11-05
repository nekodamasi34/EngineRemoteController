package dev.aw.engineremotecontroller

/** All deadlines are monotonic and derived from the server response, never the phone clock. */
data class CameraLease(val mode: String = "off", val expiry: Long = 0, val detailUntil: Long = 0) {
    fun modeAt(now: Long): String = if (now >= expiry || mode !in listOf("normal","detail")) "off" else if(mode == "detail" && now < detailUntil) "detail" else "normal"
    fun interval(now: Long): Long = if(modeAt(now)=="detail") 1000 else 5000
}

/** Recheck after capture so expiration, OFF or a settings change drops the frame. */
object CameraFrameGate {
    fun transfer(active: () -> Boolean, capture: () -> ByteArray, upload: (ByteArray) -> Unit): Boolean {
        if (!active()) return false
        val frame=capture()
        if (!active()) return false
        upload(frame)
        return true
    }
}
