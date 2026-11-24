package dev.aw.engineremotecontroller

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiCameraInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savedCameraChangesDiscardTheSharedSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = CameraSettings(context)
        val original = settings.load()
        val first = CameraConfig(enabled = true, host = "192.168.1.24", port = 8080, username = "camera", password = "test")
        val second = first.copy(host = "172.20.1.4", port = 8081)
        fun capture(config: CameraConfig) = CameraSnapshot.captureIfActive(config) {
            CameraSnapshot(byteArrayOf(), 1, 1, SystemClock.elapsedRealtime(), 0)
        }
        try {
            settings.save(first)
            assertEquals(first, CameraSettings(context).load())
            val oldFrame = capture(first)
            assertNotNull(oldFrame)
            assertSame(oldFrame, capture(first))
            settings.save(second)
            assertEquals(second, CameraSettings(context).load())
            assertNotSame(oldFrame, capture(second))
            settings.save(first)
            assertNotSame(oldFrame, capture(first))
        } finally {
            settings.save(original)
        }
    }

    @Test fun aiTabKeepsDraftAndSavedOptionsAcrossNavigationAndRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ai = AiSettings(context)
        val original = ai.load()
        val camera = CameraSettings(context)
        val originalCamera = camera.load()
        val service = Intent(context, AiMonitorService::class.java)
        try {
            ai.save(AiConfig(notify = false))
            camera.save(CameraConfig())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                tab("AI監視").performClick().assertIsSelected()
                compose.onNodeWithText("AI監視設定").performScrollTo().performClick()
                compose.onNodeWithText("手動").performScrollTo().performClick()
                compose.onNodeWithText("認識間隔（1〜10秒）").performScrollTo().performTextReplacement("7")
                tab("設定").performClick().assertIsSelected()
                compose.onNodeWithText("AI監視を有効にする").assertDoesNotExist()
                tab("AI監視").performClick().assertIsSelected()
                compose.onNodeWithText("7").performScrollTo()
                scenario.recreate()
                tab("AI監視").assertIsSelected()
                compose.onNodeWithText("7").performScrollTo()
                compose.onNodeWithText("AI監視設定を保存").performScrollTo().performClick()
                assertEquals(AiMode.MANUAL, ai.load().mode)
                assertEquals(7, ai.load().manualSeconds)
                assertFalse(AiMonitorService.status.value.running)
                compose.onNodeWithText("AI監視を有効にする").performScrollTo().performClick()
                await { it.running }
                tab("設定").performClick().assertIsSelected()
                tab("ログ").performClick().assertIsSelected()
                assertTrue(AiMonitorService.status.value.running)
                tab("AI監視").performClick().assertIsSelected()
                compose.onNodeWithText("AI監視を有効にする").performScrollTo().performClick()
                await { !it.running }
                assertFalse(ai.load().enabled)
            }
        } finally {
            context.stopService(service)
            ai.save(original)
            camera.save(originalCamera)
        }
    }

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
            val draft = CameraConfig(enabled = true, host = requireNotNull(host), port = requireNotNull(port), username = "ai-test")
            assertTrue(OnvifSnapshotClient(draft).capture().isNotEmpty())
            assertEquals(oldCamera, camera.load())
            camera.save(draft)
            ai.save(AiConfig(enabled = true, mode = AiMode.MANUAL, manualSeconds = 3, required = 3, notify = false))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                await { it.running }
                tab("AI監視").performClick().assertIsSelected()
                tab("設定").performClick().assertIsSelected()
                tab("ログ").performClick().assertIsSelected()
                assertTrue(AiMonitorService.status.value.running)
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

    private fun tab(title: String) = compose.onNode(hasText(title) and hasClickAction())

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
