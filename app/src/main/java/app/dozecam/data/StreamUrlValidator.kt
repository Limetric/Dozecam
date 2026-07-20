package app.dozecam.data

import java.net.URI

object StreamUrlValidator {
    fun isValid(raw: String): Boolean {
        val candidate = raw.trim()
        if (candidate.isEmpty()) return false
        val uri = try {
            URI(candidate)
        } catch (_: Exception) {
            return false
        }
        return uri.scheme.equals("rtsp", ignoreCase = true) && !uri.host.isNullOrBlank()
    }
}
