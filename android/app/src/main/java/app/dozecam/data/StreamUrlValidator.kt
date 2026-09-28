package app.dozecam.data

import java.net.URI

object StreamUrlValidator {
    fun isValid(raw: String): Boolean {
        val scheme = schemeOf(raw) ?: return false
        return scheme.equals("rtsp", ignoreCase = true) || scheme.equals("rtsps", ignoreCase = true)
    }

    /**
     * Only a legacy safety net: [normalize] rewrites every rtsps:// entry to
     * a plain rtsp:// one before it's saved, so this should only ever see a
     * stale pre-normalization camera. Such an entry can't be monitored
     * (Media3 has no TLS) — and, per Ubiquiti's own community reports,
     * Protect's rtsps:// link isn't a real RTSP stream at all, so it can't
     * be watched via libVLC either.
     */
    fun isMonitorable(raw: String): Boolean =
        schemeOf(raw)?.equals("rtsp", ignoreCase = true) == true

    /**
     * Protect's console only ever shows a camera's rtsps:// link (port 7441),
     * but that link isn't a real RTSP stream — no player, including libVLC,
     * can open it (community-confirmed: community.ui.com and AlexxIT/go2rtc#2071).
     * The same alias is playable on the plain rtsp:// port (7447); rewrite to
     * that before a URL is ever persisted.
     */
    fun normalize(raw: String): String {
        val candidate = raw.trim()
        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            return candidate
        }
        if (!uri.scheme.equals("rtsps", ignoreCase = true)) return candidate
        val port = if (uri.port == PROTECT_SECURE_PORT) PROTECT_PLAIN_PORT else uri.port
        return try {
            URI("rtsp", uri.userInfo, uri.host, port, uri.path, null, null).toString()
        } catch (_: Exception) {
            candidate
        }
    }

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

    private const val PROTECT_SECURE_PORT = 7441
    private const val PROTECT_PLAIN_PORT = 7447
}
