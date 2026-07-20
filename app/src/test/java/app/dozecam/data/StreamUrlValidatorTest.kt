package app.dozecam.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUrlValidatorTest {

    @Test
    fun `accepts plain rtsp url with port and token path`() {
        assertTrue(StreamUrlValidator.isValid("rtsp://192.168.1.1:7447/abcDEF123"))
    }

    @Test
    fun `accepts hostname urls and surrounding whitespace`() {
        assertTrue(StreamUrlValidator.isValid("  rtsp://console.local:7447/token  "))
    }

    @Test
    fun `accepts uppercase scheme`() {
        assertTrue(StreamUrlValidator.isValid("RTSP://192.168.1.1:7447/token"))
    }

    @Test
    fun `rejects blank input`() {
        assertFalse(StreamUrlValidator.isValid(""))
        assertFalse(StreamUrlValidator.isValid("   "))
    }

    @Test
    fun `rejects non-rtsp schemes`() {
        assertFalse(StreamUrlValidator.isValid("http://192.168.1.1:7447/token"))
    }

    @Test
    fun `accepts rtsps urls, including secure-RTSP query params`() {
        assertTrue(StreamUrlValidator.isValid("rtsps://192.168.1.1:7441/token"))
        assertTrue(StreamUrlValidator.isValid("rtsps://192.168.1.1:7441/EwjjtVc000xWicJ?enableSrtp"))
    }

    @Test
    fun `only plain rtsp urls are monitorable`() {
        assertTrue(StreamUrlValidator.isMonitorable("rtsp://192.168.1.1:7447/token"))
        // Media3's RTSP stack has no TLS; rtsps is watch-only.
        assertFalse(StreamUrlValidator.isMonitorable("rtsps://192.168.1.1:7441/token"))
        assertFalse(StreamUrlValidator.isMonitorable(""))
        assertFalse(StreamUrlValidator.isMonitorable("http://192.168.1.1/x"))
    }

    @Test
    fun `rejects urls without a host`() {
        assertFalse(StreamUrlValidator.isValid("rtsp://"))
        assertFalse(StreamUrlValidator.isValid("rtsp:token"))
    }

    @Test
    fun `rejects unparseable input`() {
        assertFalse(StreamUrlValidator.isValid("rtsp://bad host/with spaces"))
        assertFalse(StreamUrlValidator.isValid("not a url"))
    }
}
