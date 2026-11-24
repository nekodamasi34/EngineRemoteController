package dev.aw.engineremotecontroller

enum class AiMode { AUTO, ECONOMY, MANUAL }

data class InferenceTimings(val captureMs: Long, val preprocessMs: Long, val inferenceMs: Long, val totalMs: Long)

class AdaptiveInferenceRate {
    private val intervals = longArrayOf(1000, 2000, 3000, 5000, 10000)
    private var step = 2
    private var lowSamples = 0
    private var changeAfter = 0L
    var averageMs: Double? = null
        private set
    var failures = 0
        private set

    fun interval(mode: AiMode, manualSeconds: Int, thermal: Int?, powerSave: Boolean): Long {
        val base = when (mode) {
            AiMode.AUTO -> intervals[step]
            AiMode.ECONOMY -> maxOf(5000, intervals[step])
            AiMode.MANUAL -> manualSeconds.coerceIn(1, 10) * 1000L
        }
        return if (thermal == 2 || powerSave) maxOf(5000, base) else base
    }

    fun record(timings: InferenceTimings, now: Long, mode: AiMode, interval: Long, limited: Boolean) {
        val sample = timings.inferenceMs.toDouble()
        val ema = averageMs?.let { 0.8 * it + 0.2 * sample } ?: sample
        averageMs = ema
        failures = 0
        if (mode == AiMode.MANUAL || limited || now < changeAfter) {
            lowSamples = 0
            return
        }
        val ratio = ema / interval
        val minStep = if (mode == AiMode.ECONOMY) 3 else 0
        if (ratio < 0.10) lowSamples++ else lowSamples = 0
        val next = when {
            ratio > 0.25 -> (step + 1).coerceIn(minStep, intervals.lastIndex)
            lowSamples >= 30 -> (step - 1).coerceIn(minStep, intervals.lastIndex)
            else -> step
        }
        if (next != step) {
            step = next
            lowSamples = 0
            changeAfter = now + 30_000
        }
    }

    fun fail(interval: Long): Long {
        lowSamples = 0
        failures = (failures + 1).coerceAtMost(30)
        return maxOf(interval, (1000L shl failures.coerceAtMost(6)).coerceAtMost(60_000))
    }

    fun thermalPauseMs(thermal: Int?): Long? = if ((thermal ?: 0) >= 3) 30_000 else null

    fun nextDelay(started: Long, finished: Long, interval: Long): Long =
        (interval - (finished - started)).coerceAtLeast(0)
}
