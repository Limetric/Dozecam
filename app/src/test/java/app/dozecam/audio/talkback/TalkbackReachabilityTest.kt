package app.dozecam.audio.talkback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkbackReachabilityTest {

    private val attempts = mutableListOf<Pair<String, Int>>()

    private fun reachability(answer: (String, Int) -> Boolean) = TalkbackReachability { host, port ->
        attempts += host to port
        answer(host, port)
    }

    @Test
    fun `a camera answering on 443 is reachable, and 80 is never tried`() = runTest {
        val probe = reachability { _, port -> port == 443 }

        assertTrue(probe.isReachable("192.168.1.12"))
        assertEquals(listOf("192.168.1.12" to 443), attempts)
    }

    @Test
    fun `a camera answering only on 80 is still reachable`() = runTest {
        val probe = reachability { _, port -> port == 80 }

        assertTrue(probe.isReachable("192.168.1.12"))
        assertEquals(listOf("192.168.1.12" to 443, "192.168.1.12" to 80), attempts)
    }

    @Test
    fun `a camera on another vlan answers nowhere`() = runTest {
        val probe = reachability { _, _ -> false }

        assertFalse(probe.isReachable("10.0.0.5"))
        assertEquals(2, attempts.size)
    }

    /** The answer changes only with the network, so asking twice is waste. */
    @Test
    fun `an answer is remembered rather than probed again`() = runTest {
        val probe = reachability { _, port -> port == 443 }

        repeat(3) { assertTrue(probe.isReachable("192.168.1.12")) }

        assertEquals(1, attempts.size)
    }

    @Test
    fun `an unreachable answer is remembered too`() = runTest {
        val probe = reachability { _, _ -> false }

        repeat(3) { assertFalse(probe.isReachable("10.0.0.5")) }

        assertEquals(2, attempts.size)
    }

    @Test
    fun `cameras are remembered apart`() = runTest {
        val probe = reachability { host, _ -> host == "192.168.1.12" }

        assertTrue(probe.isReachable("192.168.1.12"))
        assertFalse(probe.isReachable("10.0.0.5"))
        assertTrue(probe.isReachable("192.168.1.12"))

        assertEquals(3, attempts.size)
    }

    /**
     * Walking out of the house with the phone changes every answer, and a
     * remembered "reachable" would offer a control that cannot work.
     */
    @Test
    fun `a network change forgets what was known`() = runTest {
        var onHomeWifi = true
        val probe = reachability { _, _ -> onHomeWifi }

        assertTrue(probe.isReachable("192.168.1.12"))
        onHomeWifi = false
        probe.forget()

        assertFalse(probe.isReachable("192.168.1.12"))
    }
}
