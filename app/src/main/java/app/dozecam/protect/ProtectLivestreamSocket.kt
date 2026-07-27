package app.dozecam.protect

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * One livestream WebSocket. Decodes the controller's frame protocol and hands
 * the resulting fMP4 on as a plain byte stream, in the order a demuxer needs:
 * the initialization segment first, then fragments as they arrive.
 *
 * The socket is single-use. Its authorization token is minted per negotiation,
 * so recovering from a drop means negotiating a fresh URL, never reopening
 * this one.
 */
class ProtectLivestreamSocket(
    httpClient: OkHttpClient,
    /** Returns false when the consumer cannot keep up; the socket then fails. */
    private val onBytes: (ByteArray) -> Boolean,
    private val onCodec: (String) -> Unit = {},
    private val onFailure: (Throwable) -> Unit,
) {
    private val client = httpClient.newBuilder()
        // A live socket is idle between fragments; the default read timeout
        // would tear it down mid-stream. Pings keep the path open instead.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    private val decoder = LivestreamDecoder()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closedByUs = false

    fun open(url: String) {
        closedByUs = false
        socket = client.newWebSocket(
            // OkHttp rewrites the wss:// scheme to https:// for the upgrade.
            Request.Builder().url(url).build(),
            object : WebSocketListener() {

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val segments = try {
                        decoder.decode(bytes.toByteArray())
                    } catch (e: LivestreamProtocolException) {
                        abort(webSocket, e)
                        return
                    }
                    for (segment in segments) {
                        val payload = when (segment) {
                            is LivestreamSegment.Init -> {
                                onCodec(segment.codec)
                                Av1ConfigRepair.repair(segment.data)
                            }
                            is LivestreamSegment.Media -> segment.data
                        }
                        if (!onBytes(payload)) {
                            abort(webSocket, IOException("Livestream consumer fell behind"))
                            return
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!closedByUs) onFailure(t)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                    if (!closedByUs) {
                        onFailure(IOException("Console closed the livestream: $code $reason"))
                    }
                }
            },
        )
    }

    fun close() {
        closedByUs = true
        socket?.cancel()
        socket = null
    }

    private fun abort(webSocket: WebSocket, cause: Throwable) {
        webSocket.cancel()
        if (!closedByUs) onFailure(cause)
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val PING_INTERVAL_SECONDS = 10L
    }
}
