package dev.aw.engineremotecontroller

import org.junit.Assert.*
import org.junit.Test

class RemoteCommandPolicyTest {
    private val ready = RelayControlState(connected = true, channels = List(8) { RelayChannelState.OFF })
    private val command = RemoteCommand("command-1", "claim-1", "alarm", 1000, 16000)

    @Test fun acceptsValidAlarm() { assertNull(RemoteCommandPolicy.rejection(command, 1000, 100, ready)) }
    @Test fun rejectsExpiredOrSlowResponse() {
        assertNotNull(RemoteCommandPolicy.rejection(command, 16000, 0, ready))
        assertNotNull(RemoteCommandPolicy.rejection(command, 1000, 16000, ready))
    }
    @Test fun rejectsUnknownActionAndInvalidDuration() {
        assertNotNull(RemoteCommandPolicy.rejection(command.copy(action = "all_on"), 1000, 0, ready))
        assertNotNull(RemoteCommandPolicy.rejection(command.copy(durationMillis = 5001), 1000, 0, ready))
    }
    @Test fun rejectsOnWhenBusyOrUnknown() {
        assertNotNull(RemoteCommandPolicy.rejection(command, 1000, 0, ready.copy(busy = true)))
        assertNotNull(RemoteCommandPolicy.rejection(command, 1000, 0, ready.copy(channels = List(8) { RelayChannelState.UNKNOWN })))
    }
    @Test fun allowsAllOffWhileBusyButRequiresConnection() {
        val stop = command.copy(action = "all_off", durationMillis = 0)
        assertNull(RemoteCommandPolicy.rejection(stop, 1000, 0, ready.copy(busy = true)))
        assertNotNull(RemoteCommandPolicy.rejection(stop, 1000, 0, ready.copy(connected = false)))
    }
}
