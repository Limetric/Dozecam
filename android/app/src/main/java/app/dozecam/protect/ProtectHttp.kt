package app.dozecam.protect

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Runs one console exchange. The whole exchange — including the body read,
 * which can still touch the socket after headers arrive — stays on the IO
 * dispatcher.
 */
internal suspend fun <T> OkHttpClient.exchange(
    request: Request,
    handler: (Response, String) -> T,
): T = runInterruptible(Dispatchers.IO) {
    newCall(request).execute().use { response ->
        handler(response, response.body.string())
    }
}

/**
 * Re-points a controller-minted WebSocket URL at [host], the console address
 * that actually answered. Only the hostname moves: the port and path carry the
 * WebSocket port and the single-use authorization token, and the controller
 * routinely mints an internal hostname that resolves nowhere else on the
 * network — the same trap the RTSP path documents in
 * [ProtectPublicApiClient.streamUrlFor].
 *
 * Returns null when the controller's URL will not parse.
 */
internal fun rehostWebSocketUrl(mintedUrl: String, host: String): String? {
    val uri = runCatching { URI(mintedUrl.trim()) }.getOrNull() ?: return null
    val scheme = uri.scheme ?: return null
    if (uri.rawPath.isNullOrEmpty()) return null
    // IPv6 literals need brackets in a URI authority.
    val literal = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    val port = if (uri.port == -1) "" else ":${uri.port}"
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return "$scheme://$literal$port${uri.rawPath}$query"
}

/**
 * Plain RTSP on the console's 7447 port. Both APIs converge here: the private
 * one hands back a bare alias, and the public one's `rtsps://` URL (7441) is
 * not a stream any player used here can open.
 */
internal fun rtspUrl(host: String, alias: String): String {
    // IPv6 literals need brackets in a URI authority.
    val literal = if (host.contains(':')) "[$host]" else host
    return "rtsp://$literal:7447/$alias"
}
