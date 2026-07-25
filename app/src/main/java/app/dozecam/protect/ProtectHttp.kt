package app.dozecam.protect

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
 * Plain RTSP on the console's 7447 port. Both APIs converge here: the private
 * one hands back a bare alias, and the public one's `rtsps://` URL (7441) is
 * not a stream any player used here can open.
 */
internal fun rtspUrl(host: String, alias: String): String {
    // IPv6 literals need brackets in a URI authority.
    val literal = if (host.contains(':')) "[$host]" else host
    return "rtsp://$literal:7447/$alias"
}
