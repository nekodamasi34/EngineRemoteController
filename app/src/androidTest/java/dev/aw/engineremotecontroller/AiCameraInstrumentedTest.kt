package dev.aw.engineremotecontroller

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiCameraInstrumentedTest {
    @Test fun monitorsOnvifInBackgroundAndStopsWhenDisabled() {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("aiCameraHost")
        val port = arguments.getString("aiCameraPort")?.toIntOrNull()
        assumeTrue("Requires a test ONVIF server and an isolated emulator", host != null && port != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ai = AiSettings(context)
        val camera = CameraSettings(context)
        val oldAi = ai.load()
        val oldCamera = camera.load()
        val power = context.getSystemService(PowerManager::class.java)
        val intent = Intent(context, AiMonitorService::class.java)
        try {
            camera.save(CameraConfig(enabled = true, host = requireNotNull(host), port = requireNotNull(port), username = "ai-test"))
            ai.save(AiConfig(enabled = true, mode = AiMode.MANUAL, manualSeconds = 3, required = 3, notify = false))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await { it.running }
                scenario.moveToState(Lifecycle.State.CREATED)
                shell("input keyevent KEYCODE_SLEEP")
                await { !power.isInteractive }
                val screenOffAt = System.currentTimeMillis()
                val state = await { it.latest?.capturedAt?.let { time -> time >= screenOffAt } == true }
                assertFalse(power.isInteractive)
                assertEquals("監視中", state.message)
                assertEquals("接続OK", state.camera)
                assertEquals(DetectionClass.ANIMAL, state.latest?.detection?.target)
                assertNotNull(state.latest?.jpeg)
                assertTrue(requireNotNull(state.timings).totalMs >= requireNotNull(state.timings).inferenceMs)
                ai.save(ai.load().copy(enabled = false))
                context.stopService(intent)
                await { !it.running }
                val lastEventId = AiMonitorService.status.value.latest?.id
                SystemClock.sleep(2200)
                assertFalse(AiMonitorService.status.value.running)
                assertEquals(lastEventId, AiMonitorService.status.value.latest?.id)
            }
        } finally {
            context.stopService(intent)
            camera.save(oldCamera)
            ai.save(oldAi)
            shell("input keyevent KEYCODE_WAKEUP")
        }
    }

    private fun shell(command: String) {
        val device = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(device.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun await(done: (AiStatus) -> Boolean): AiStatus {
        val end = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < end) {
            val status = AiMonitorService.status.value
            if (done(status)) return status
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for AI state: ${AiMonitorService.status.value.message}")
    }
}
