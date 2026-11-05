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
        CameraConfig(true,"192.168.1.100",80,"camera","secret").validate()
        for(host in listOf("8.8.8.8","127.0.0.1","192.168.1.999","camera.example","10.0.0.1/path")) {
            assertTrue(runCatching {CameraConfig(true,host,80,"user","pass").validate()}.isFailure)
        }
    }
}
