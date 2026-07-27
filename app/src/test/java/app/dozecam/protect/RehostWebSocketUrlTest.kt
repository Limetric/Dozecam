package app.dozecam.protect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RehostWebSocketUrlTest {

    @Test
    fun `swaps only the hostname, keeping the port and token-bearing path`() {
        val minted = "wss://unifi.internal:7443/ws/livestream?token=abc123"

        assertEquals(
            "wss://192.168.1.1:7443/ws/livestream?token=abc123",
            rehostWebSocketUrl(minted, "192.168.1.1"),
        )
    }

    @Test
    fun `keeps a URL that carries no explicit port portless`() {
        assertEquals(
            "wss://console.lan/ws/livestream?token=t",
            rehostWebSocketUrl("wss://elsewhere/ws/livestream?token=t", "console.lan"),
        )
    }

    @Test
    fun `preserves a query string it must not re-encode`() {
        val minted = "wss://internal:7443/ws/livestream?token=a%2Fb&camera=x"

        assertEquals(
            "wss://10.0.0.2:7443/ws/livestream?token=a%2Fb&camera=x",
            rehostWebSocketUrl(minted, "10.0.0.2"),
        )
    }

    @Test
    fun `brackets an IPv6 console address`() {
        assertEquals(
            "wss://[fd00::1]:7443/ws/livestream?token=t",
            rehostWebSocketUrl("wss://internal:7443/ws/livestream?token=t", "fd00::1"),
        )
    }

    @Test
    fun `leaves an already-bracketed IPv6 address alone`() {
        assertEquals(
            "wss://[fd00::1]:7443/ws?token=t",
            rehostWebSocketUrl("wss://internal:7443/ws?token=t", "[fd00::1]"),
        )
    }

    @Test
    fun `returns null for a URL the controller mangled`() {
        assertNull(rehostWebSocketUrl("not a url at all", "192.168.1.1"))
        assertNull(rehostWebSocketUrl("", "192.168.1.1"))
        assertNull(rehostWebSocketUrl("wss://internal:7443", "192.168.1.1"))
    }
}
