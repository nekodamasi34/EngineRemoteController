package dev.aw.engineremotecontroller

import org.junit.Assert.*
import org.junit.Test

class CameraPolicyTest {
    @Test fun offDoesNotFetchOrUploadAndExpiredCaptureIsDiscarded() {
        var captures=0;var uploads=0;var active=false
        fun attempt()=CameraFrameGate.transfer({active},{captures++;active=false;byteArrayOf(1)},{uploads++})
        assertFalse(attempt());assertEquals(0,captures);assertEquals(0,uploads)
        active=true;assertFalse(attempt());assertEquals(1,captures);assertEquals(0,uploads)
    }
    @Test fun failedCaptureDoesNotUploadAndNextAttemptRecovers() {
        var uploads=0
        assertTrue(runCatching { CameraFrameGate.transfer({true},{throw java.io.IOException("offline")},{uploads++}) }.isFailure)
        assertEquals(0,uploads)
        assertTrue(CameraFrameGate.transfer({true},{byteArrayOf(1)},{uploads++}))
        assertEquals(1,uploads)
    }
    @Test fun offAndExpiredLeaseNeverCapture() {
        assertEquals("off",CameraLease().modeAt(0))
        assertEquals("off",CameraLease("normal",20000).modeAt(20000))
        assertEquals("off",CameraLease("invalid",20000).modeAt(0))
    }
    @Test fun normalAndDetailIntervalsAndTimeout() {
        assertEquals(5000,CameraLease("normal",200000).interval(1000))
        val detail=CameraLease("detail",200000,120000)
        assertEquals(1000,detail.interval(119999))
        assertEquals("normal",detail.modeAt(120000))
        assertEquals(5000,detail.interval(120000))
        assertEquals("off",detail.modeAt(200000))
    }
    @Test fun onlyLocalCameraAddressesAccepted() {
        val camera = CameraConfig(enabled = true, port = 8080, username = "camera")
        for (host in listOf("10.0.2.2", "192.168.1.100", "172.16.0.1", "172.31.255.254")) {
            camera.copy(host = host).validate()
        }
        for (host in listOf(
            "8.8.8.8", "127.0.0.1", "169.254.1.2", "224.0.0.1", "100.64.0.1",
            "172.15.0.1", "172.32.0.1", "192.168.1.999", "camera.example", "10.0.0.1/path",
            "010.0.0.1", "10.0.1", "10.0.0.1 ", "::ffff:10.0.0.1"
        )) {
            assertThrows(host, CameraInputException::class.java) { camera.copy(host = host).validate() }
        }
    }

    @Test fun onvifUrlsMustUseTheConfiguredLiteralWithoutUserInfo() {
        val camera = CameraConfig(host = "172.21.0.4", port = 8080, username = "camera")
        assertEquals(8080, camera.cameraUrl("http://172.21.0.4:8080/onvif/media").port)
        assertEquals("https://172.21.0.4:8443/snapshot?profile=2",
            camera.cameraUrl("https://172.21.0.4:8443/snapshot?profile=2").toString())
        for (url in listOf(
            "http://172.21.0.5/snapshot", "http://8.8.8.8/snapshot", "http://localhost/snapshot",
            "http://user:password@172.21.0.4/snapshot", "http://172.21.0.4@8.8.8.8/snapshot",
            "http://172.21.0.4:0/snapshot", "http://172.21.0.4:65536/snapshot", "http://172.21.0.4:/snapshot",
            "http://172.21.0.4/snapshot#fragment", "http://172.21.0.4\\@example.com/snapshot",
            "http://172.21.000.4/snapshot", "file://172.21.0.4/snapshot", "/snapshot"
        )) {
            assertThrows(url, CameraConnectionException::class.java) { camera.cameraUrl(url) }
        }
    }
}
