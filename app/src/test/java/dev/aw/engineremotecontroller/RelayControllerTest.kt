package dev.aw.engineremotecontroller

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class RelayControllerTest {
    @Test
    fun timedActionSendsOnThenOff() = withHarness { h ->
        assertTrue(h.controller.runTimedAction("始動", 1, 100))
        h.awaitState { !it.busy && it.status == "始動 完了" }
        assertEquals(listOf("ON1", "OFF1"), h.transport.events.toList())
        assertTrue(h.controller.currentState.canRun)
    }

    @Test
    fun disconnectDuringActionSendsAllOffBeforeClosing() = withHarness { h ->
        h.controller.runTimedAction("始動", 1, 5000)
        h.awaitState { it.channels[0] == RelayChannelState.ON }
        assertTrue(h.controller.disconnect())
        h.awaitState { !it.connected && !it.busy }
        assertEquals(listOf("ON1", "ALL_OFF", "CLOSE"), h.transport.events.toList())
        assertTrue(h.controller.currentState.channels.all { it == RelayChannelState.OFF })
    }

    @Test
    fun activityDestructionSendsAllOffBeforeClosingAndRejectsNewActions() = withHarness { h ->
        h.controller.runTimedAction("チョーク", 5, 5000)
        h.awaitState { it.channels[4] == RelayChannelState.ON }
        h.controller.close()
        assertTrue(h.worker.awaitTermination(3, TimeUnit.SECONDS))
        assertEquals(listOf("ON5", "ALL_OFF", "CLOSE"), h.transport.events.toList())
        assertFalse(h.controller.runTimedAction("逆方向", 6, 100))
        assertFalse(h.controller.connect("device"))
    }

    @Test
    fun stopBeforeQueuedOnPreventsThatOnFromBeingSent() = withHarness { h ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.worker.execute {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        try {
            assertTrue(h.controller.runTimedAction("始動", 1, 5000))
            assertTrue(h.controller.emergencyAllOff())
            assertFalse(h.controller.runTimedAction("別操作", 2, 100))
        } finally {
            release.countDown()
        }
        h.awaitState { it.canRun }
        assertEquals(listOf("ALL_OFF"), h.transport.events.toList())
    }

    @Test
    fun stopDuringOnWriteIsSentAfterOnAndInvalidatesItsUpdates() = withHarness { h ->
        h.transport.blockedCommand = "ON5"
        assertTrue(h.controller.runTimedAction("チョーク", 5, 5000))
        assertTrue(h.transport.entered.await(3, TimeUnit.SECONDS))
        val oldUpdate = h.updates.last { it.state.actionTitle == "チョーク" }
        try {
            assertTrue(h.controller.emergencyAllOff())
            assertFalse(h.controller.isCurrent(oldUpdate))
            assertFalse(h.controller.runTimedAction("逆方向", 6, 100))
        } finally {
            h.transport.release.countDown()
        }
        h.awaitState { it.canRun }
        assertEquals(listOf("ON5", "ALL_OFF"), h.transport.events.toList())
        assertTrue(h.controller.currentState.channels.all { it == RelayChannelState.OFF })
    }

    @Test
    fun offFailureMarksUnknownAndBlocksActionsUntilAllOffSucceeds() = withHarness { h ->
        h.transport.failedCommands.add("OFF1")
        h.controller.runTimedAction("始動", 1, 100)
        h.awaitState { !it.busy && it.status.contains("OFF失敗") }
        assertEquals(RelayChannelState.UNKNOWN, h.controller.currentState.channels[0])
        assertFalse(h.controller.currentState.canRun)
        assertFalse(h.controller.runTimedAction("別操作", 2, 100))
        assertEquals(listOf("ON1", "OFF1"), h.transport.events.toList())

        assertTrue(h.controller.emergencyAllOff())
        h.awaitState { it.canRun }
        assertTrue(h.controller.runTimedAction("別操作", 2, 100))
        h.awaitState { !it.busy && it.status == "別操作 完了" }
        assertEquals(listOf("ON1", "OFF1", "ALL_OFF", "ON2", "OFF2"), h.transport.events.toList())
    }

    @Test
    fun emergencyOffFailureKeepsAllChannelsUnknownAndAllowsRetry() = withHarness { h ->
        h.controller.runTimedAction("チョーク", 5, 5000)
        h.awaitState { it.channels[4] == RelayChannelState.ON }
        h.transport.failedCommands.add("ALL_OFF")
        h.controller.emergencyAllOff()
        h.awaitState { !it.busy && it.status.contains("全OFF失敗") }
        assertTrue(h.controller.currentState.channels.all { it == RelayChannelState.UNKNOWN })
        assertFalse(h.controller.runTimedAction("逆方向", 6, 100))

        h.transport.failedCommands.clear()
        h.controller.emergencyAllOff()
        h.awaitState { it.canRun }
        assertEquals(listOf("ON5", "ALL_OFF", "ALL_OFF"), h.transport.events.toList())
    }

    @Test
    fun cancelledTimerCannotTurnOffANewerActionOnTheSameChannel() = withHarness { h ->
        h.controller.runTimedAction("古い操作", 5, 100)
        h.awaitState { it.channels[4] == RelayChannelState.ON }
        h.controller.emergencyAllOff()
        h.awaitState { it.canRun }
        assertTrue(h.controller.runTimedAction("新しい操作", 5, 500))
        h.awaitState { it.channels[4] == RelayChannelState.ON }

        val oldDeadlinePassed = CountDownLatch(1)
        h.worker.schedule({ oldDeadlinePassed.countDown() }, 200, TimeUnit.MILLISECONDS)
        assertTrue(oldDeadlinePassed.await(3, TimeUnit.SECONDS))
        assertTrue(h.controller.currentState.busy)
        assertEquals(RelayChannelState.ON, h.controller.currentState.channels[4])
        assertEquals(listOf("ON5", "ALL_OFF", "ON5"), h.transport.events.toList())

        h.awaitState { !it.busy && it.status == "新しい操作 完了" }
        assertEquals(listOf("ON5", "ALL_OFF", "ON5", "OFF5"), h.transport.events.toList())
    }

    @Test
    fun failedOnWriteAlsoMarksUnknownAndBlocksNewActions() = withHarness { h ->
        h.transport.failedCommands.add("ON1")
        h.controller.runTimedAction("始動", 1, 100)
        h.awaitState { !it.busy && it.status.contains("ON失敗") }
        assertEquals(RelayChannelState.UNKNOWN, h.controller.currentState.channels[0])
        assertFalse(h.controller.runTimedAction("別操作", 2, 100))
        assertEquals(listOf("ON1"), h.transport.events.toList())
    }

    @Test
    fun stopDuringDisconnectCannotOverwriteTheDisconnectedState() = withHarness { h ->
        h.transport.blockedCommand = "ALL_OFF"
        h.controller.disconnect()
        assertTrue(h.transport.entered.await(3, TimeUnit.SECONDS))
        try {
            assertFalse(h.controller.emergencyAllOff())
        } finally {
            h.transport.release.countDown()
        }
        h.awaitState { !it.connected && !it.busy }
        assertEquals(listOf("ALL_OFF", "CLOSE"), h.transport.events.toList())
    }

    @Test
    fun failedDisconnectOffDoesNotPretendRelaysAreOff() = withHarness { h ->
        h.controller.runTimedAction("始動", 1, 5000)
        h.awaitState { it.channels[0] == RelayChannelState.ON }
        h.transport.failedCommands.add("ALL_OFF")
        h.controller.disconnect()
        h.awaitState { !it.connected && !it.busy }
        assertTrue(h.controller.currentState.channels.all { it == RelayChannelState.UNKNOWN })
        assertTrue(h.controller.currentState.status.contains("状態不明"))
        assertEquals(listOf("ON1", "ALL_OFF", "CLOSE"), h.transport.events.toList())
    }

    @Test
    fun reconnectRequiresSuccessfulAllOffBeforeAllowingActions() = withHarness { h ->
        h.controller.disconnect()
        h.awaitState { !it.connected && !it.busy }
        h.transport.failedCommands.add("ALL_OFF")
        assertTrue(h.controller.connect("device"))
        h.awaitState { it.connected && !it.busy }
        assertFalse(h.controller.currentState.canRun)
        assertTrue(h.controller.currentState.channels.all { it == RelayChannelState.UNKNOWN })
        assertFalse(h.controller.runTimedAction("始動", 1, 100))
    }

    @Test
    fun destructionWhileConnectingStillStopsTheNewConnection() {
        val h = Harness()
        h.transport.blockConnect = true
        try {
            h.controller.connect("device")
            assertTrue(h.transport.entered.await(3, TimeUnit.SECONDS))
            h.controller.close()
            h.transport.release.countDown()
            assertTrue(h.worker.awaitTermination(3, TimeUnit.SECONDS))
            assertEquals(listOf("CONNECT", "ALL_OFF", "ALL_OFF", "CLOSE"), h.transport.events.toList())
            assertFalse(h.controller.currentState.connected)
        } finally {
            h.close()
        }
    }

    private fun withHarness(test: (Harness) -> Unit) {
        val h = Harness()
        try {
            assertTrue(h.controller.connect("device"))
            h.awaitState { it.canRun }
            h.transport.events.clear()
            test(h)
        } finally {
            h.close()
        }
    }

    private class Harness : AutoCloseable {
        val worker = ScheduledThreadPoolExecutor(1)
        val transport = FakeTransport()
        val updates = CopyOnWriteArrayList<RelayUpdate>()
        private val pendingUpdates = LinkedBlockingQueue<RelayUpdate>()
        val controller = RelayController(transport, worker) {
            updates.add(it)
            pendingUpdates.add(it)
        }

        fun awaitState(predicate: (RelayControlState) -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (true) {
                val remaining = deadline - System.nanoTime()
                val update = if (remaining > 0) pendingUpdates.poll(remaining, TimeUnit.NANOSECONDS) else null
                checkNotNull(update) { "Timed out waiting for state; current=${controller.currentState}" }
                if (controller.isCurrent(update) && predicate(update.state)) return
            }
        }

        override fun close() {
            transport.release.countDown()
            controller.close()
            assertTrue("Transport cleanup did not finish", worker.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private class FakeTransport : RelayTransport {
        val events = CopyOnWriteArrayList<String>()
        val failedCommands = ConcurrentHashMap.newKeySet<String>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blockedCommand: String? = null
        var blockConnect = false

        override fun connect(address: String): Result<Unit> = runCatching {
            events.add("CONNECT")
            if (blockConnect) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
        }

        override fun send(bytes: ByteArray): Result<Unit> = runCatching {
            val command = if (bytes.contentEquals(RelayCommands.allOff)) "ALL_OFF"
            else "${if (bytes[4] == 1.toByte()) "ON" else "OFF"}${bytes[3].toInt()}"
            events.add(command)
            if (command == blockedCommand) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
            }
            if (command in failedCommands) throw IOException("Simulated $command failure")
        }

        override fun disconnect() {
            events.add("CLOSE")
        }
    }
}
