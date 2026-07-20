package app.dozecam.data

import java.net.URI

object StreamUrlValidator {
    fun isValid(raw: String): Boolean {
        val scheme = schemeOf(raw) ?: return false
        return scheme.equals("rtsp", ignoreCase = true) || scheme.equals("rtsps", ignoreCase = true)
    }

    /**
     * Wake-on-sound uses Media3's RTSP module, which opens a plain socket for
     * every scheme (no TLS in 1.10.1) — rtsps cameras can be watched (libVLC)
     * but not monitored.
     */
    fun isMonitorable(raw: String): Boolean =
        schemeOf(raw)?.equals("rtsp", ignoreCase = true) == true

    private fun schemeOf(raw: String): String? {
        val candidate = raw.trim()
        if (candidate.isEmpty()) return null
        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            return null
        }
        if (uri.host.isNullOrBlank()) return null
        return uri.scheme
    }
}
