package dev.aw.engineremotecontroller

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AiPolicyTest {
    private val config = AiConfig()
    private fun timing(ms: Long) = InferenceTimings(20, 20, ms, 40 + ms)
    private fun AdaptiveInferenceRate.auto() = interval(AiMode.AUTO, 3, null, false)

    @Test fun startsAtThreeSecondsAndSlowsDownWithCooldown() {
        val rate = AdaptiveInferenceRate()
        assertEquals(3000, rate.auto())
        rate.record(timing(3000), 0, AiMode.AUTO, rate.auto(), false)
        assertEquals(5000, rate.auto())
        rate.record(timing(1000), 1000, AiMode.AUTO, rate.auto(), false)
        assertEquals(2600.0, rate.averageMs!!, 0.001)
        assertEquals(5000, rate.auto())
        rate.record(timing(3000), 30_000, AiMode.AUTO, rate.auto(), false)
        assertEquals(10_000, rate.auto())
    }

    @Test fun onlyShortensAfterThirtyConsecutiveLowSamples() {
        val rate = AdaptiveInferenceRate()
        repeat(29) { rate.record(timing(30), it * 3000L, AiMode.AUTO, rate.auto(), false) }
        assertEquals(3000, rate.auto())
        rate.fail(rate.auto())
        rate.record(timing(30), 90_000, AiMode.AUTO, rate.auto(), false)
        assertEquals(3000, rate.auto())
        repeat(29) { rate.record(timing(30), 93_000 + it * 3000L, AiMode.AUTO, rate.auto(), false) }
        assertEquals(2000, rate.auto())
    }

    @Test fun thermalAndPowerProtectionApplyInAllModesIncludingManual() {
        val rate = AdaptiveInferenceRate()
        assertEquals(5000, rate.interval(AiMode.MANUAL, 1, 2, false))
        assertEquals(5000, rate.interval(AiMode.MANUAL, 1, null, true))
        assertEquals(5000, rate.interval(AiMode.ECONOMY, 3, 0, false))
        assertEquals(30_000L, rate.thermalPauseMs(3))
        assertEquals(30_000L, rate.thermalPauseMs(6))
        assertNull(rate.thermalPauseMs(1))
        assertNull(rate.thermalPauseMs(null))
        assertEquals(1000, rate.interval(AiMode.MANUAL, 1, 1, false))
    }

    @Test fun overdueInferenceSchedulesImmediatelyAfterCompletionAndFailuresBackOff() {
        val rate = AdaptiveInferenceRate()
        assertEquals(0, rate.nextDelay(1000, 7000, 3000))
        assertEquals(2000, rate.nextDelay(1000, 2000, 3000))
        assertEquals(3000, rate.fail(3000))
        assertEquals(4000, rate.fail(3000))
        repeat(10) { rate.fail(3000) }
        assertEquals(60_000, rate.fail(3000))
        rate.record(timing(200), 600_000, AiMode.AUTO, 3000, false)
        assertEquals(0, rate.failures)
    }

    @Test fun inferenceWorkerSerializesOverdueWorkAcrossServiceRestarts() {
        val worker = aiWorker("Test inference")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val second = CountDownLatch(1)
        try {
            val first = worker.submit {
                started.countDown()
                release.await(2, TimeUnit.SECONDS)
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            worker.schedule({ second.countDown() }, 0, TimeUnit.MILLISECONDS)
            assertFalse(second.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            first.get(2, TimeUnit.SECONDS)
            assertTrue(second.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun verifiesTensorAndParsesOnlyValidSelectedRows() {
        val shape = longArrayOf(1, 300, 6)
        assertTrue(matchesModelTensor(shape, true, shape))
        assertFalse(matchesModelTensor(longArrayOf(1, 6, 300), true, shape))
        assertFalse(matchesModelTensor(shape, false, shape))
        val rows = arrayOf(
            floatArrayOf(160f, 240f, 480f, 400f, 0.30f, 0f),
            floatArrayOf(1f, 2f, 300f, 500f, 0.29f, 0f),
            floatArrayOf(1f, 2f, 300f, 500f, 0.9f, 1f),
            floatArrayOf(1f, 2f, 300f, 500f, 0.9f, 0.5f),
            floatArrayOf(1f, 2f, 300f, 500f, Float.NaN, 0f),
            floatArrayOf(1f, 2f, 300f, 10f, 0.9f, 0f)
        )
        val detected = parseDetections(rows, Letterbox(1280, 640), config).single()
        assertEquals(DetectionClass.ANIMAL, detected.target)
        assertEquals(DetectionBox(.25f, .25f, .75f, .75f), detected.box)
        val portrait = Letterbox(320, 640)
        assertEquals(DetectionBox(0f, 0f, 1f, 1f), portrait.box(floatArrayOf(0f, -40f, 640f, 700f)))
    }

    @Test fun confirmsAndSuppressesTheSameEventUntilItClears() {
        val tracker = DetectionTracker()
        val animal = Detection(DetectionClass.ANIMAL, 0.8f, DetectionBox(0f, 0f, 1f, 1f))
        val hits = listOf(animal)
        assertTrue(tracker.sample(hits, 0, config).isEmpty())
        assertTrue(tracker.sample(emptyList(), 3000, config).isEmpty())
        assertEquals(hits, tracker.sample(hits, 6000, config))
        for (t in 9000L..18_000L step 3000) assertTrue(tracker.sample(hits, t, config).isEmpty())
        for (t in 21_000L..48_000L step 3000) tracker.sample(emptyList(), t, config)
        assertTrue(tracker.sample(hits, 51_000, config).isEmpty())
        assertTrue(tracker.sample(hits, 54_000, config).isEmpty())
        for (t in 57_000L..90_000L step 3000) tracker.sample(emptyList(), t, config)
        assertTrue(tracker.sample(hits, 93_000, config).isEmpty())
        assertEquals(hits, tracker.sample(hits, 96_000, config))
    }

    @Test fun cameraFailureInvalidatesConfirmationEvidence() {
        val tracker = DetectionTracker()
        val hits = listOf(Detection(DetectionClass.ANIMAL, .5f, DetectionBox(0f, 0f, 1f, 1f)))
        assertTrue(tracker.sample(hits, 0, config).isEmpty())
        tracker.invalidate()
        assertTrue(tracker.sample(hits, 3000, config).isEmpty())
        assertEquals(hits, tracker.sample(hits, 6000, config))
    }
}
