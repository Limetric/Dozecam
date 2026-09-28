package app.dozecam.protect

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the real OkHttp WebSocket against a mock console, so the decoder and
 * the socket are exercised together the way they run in production.
 */
class ProtectLivestreamSocketTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun frame(type: Int, payload: String): ByteArray {
        val bytes = payload.encodeToByteArray()
        return byteArrayOf(
            type.toByte(),
            ((bytes.size shr 16) and 0xFF).toByte(),
            ((bytes.size shr 8) and 0xFF).toByte(),
            (bytes.size and 0xFF).toByte(),
        ) + bytes
    }

    /** Enqueues an upgrade whose server side sends [payloads] once connected. */
    private fun enqueueSocket(vararg payloads: ByteArray) {
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            payloads.forEach { webSocket.send(it.toByteString()) }
                        }
                    },
                )
                .build(),
        )
    }

    private fun url() = server.url("/ws/livestream").toString()

    @Test
    fun `forwards the init segment and then each fragment`() {
        val received = LinkedBlockingQueue<String>()
        enqueueSocket(
            frame(LivestreamFrame.CODEC_INFORMATION, "av01.0.05M.08") +
                frame(LivestreamFrame.INIT_SEGMENT, "INIT"),
            frame(LivestreamFrame.BEGIN_SEGMENT, "") +
                frame(LivestreamFrame.MOOF, "MOOF") +
                frame(LivestreamFrame.MDAT, "MDAT") +
                frame(LivestreamFrame.END_SEGMENT, ""),
        )
        val codecs = LinkedBlockingQueue<String>()

        val socket = ProtectLivestreamSocket(
            httpClient = OkHttpClient(),
            onBytes = { received.offer(it.decodeToString()); true },
            onCodec = { codecs.offer(it) },
            onFailure = { received.offer("FAILURE:${it.message}") },
        )
        socket.open(url())

        assertEquals("INIT", received.poll(5, TimeUnit.SECONDS))
        assertEquals("MOOFMDAT", received.poll(5, TimeUnit.SECONDS))
        assertEquals("av01.0.05M.08", codecs.poll(5, TimeUnit.SECONDS))
        socket.close()
    }

    @Test
    fun `fails the stream when the consumer cannot keep up`() {
        val failures = LinkedBlockingQueue<Throwable>()
        enqueueSocket(frame(LivestreamFrame.INIT_SEGMENT, "INIT"))

        val socket = ProtectLivestreamSocket(
            httpClient = OkHttpClient(),
            onBytes = { false }, // the pipe is full
            onFailure = { failures.offer(it) },
        )
        socket.open(url())

        val failure = failures.poll(5, TimeUnit.SECONDS)
        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("fell behind"))
        socket.close()
    }

    @Test
    fun `reports a frame type that is not part of the protocol`() {
        val failures = LinkedBlockingQueue<Throwable>()
        enqueueSocket(frame(42, "garbage"))

        val socket = ProtectLivestreamSocket(
            httpClient = OkHttpClient(),
            onBytes = { true },
            onFailure = { failures.offer(it) },
        )
        socket.open(url())

        val failure = failures.poll(5, TimeUnit.SECONDS)
        assertTrue(failure is LivestreamProtocolException)
        socket.close()
    }

    @Test
    fun `reassembles a fragment split across websocket messages`() {
        val received = LinkedBlockingQueue<String>()
        val whole = frame(LivestreamFrame.BEGIN_SEGMENT, "") +
            frame(LivestreamFrame.MOOF, "MOOF") +
            frame(LivestreamFrame.END_SEGMENT, "")
        // The console has no obligation to align frames to message boundaries.
        enqueueSocket(whole.copyOfRange(0, 6), whole.copyOfRange(6, whole.size))

        val socket = ProtectLivestreamSocket(
            httpClient = OkHttpClient(),
            onBytes = { received.offer(it.decodeToString()); true },
            onFailure = { received.offer("FAILURE") },
        )
        socket.open(url())

        assertEquals("MOOF", received.poll(5, TimeUnit.SECONDS))
        socket.close()
    }

    @Test
    fun `surfaces a console that closes the stream`() {
        val failures = LinkedBlockingQueue<Throwable>()
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.close(1000, "done")
                        }
                    },
                )
                .build(),
        )

        val socket = ProtectLivestreamSocket(
            httpClient = OkHttpClient(),
            onBytes = { true },
            onFailure = { failures.offer(it) },
        )
        socket.open(url())

        assertNotNull(failures.poll(5, TimeUnit.SECONDS))
        socket.close()
    }

    @Test
    fun `a socket we closed does not report a failure`() {
        val failures = LinkedBlockingQueue<Throwable>()
        val opened = LinkedBlockingQueue<Unit>()
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            opened.offer(Unit)
                        }
                    },
                )
                .build(),
        )
        val socket = ProtectLivestreamSocket(
            httpClient = OkHttpClient(),
            onBytes = { true },
            onFailure = { failures.offer(it) },
        )

        socket.open(url())
        assertNotNull(opened.poll(5, TimeUnit.SECONDS))
        socket.close()

        // Cancelling a live socket raises onFailure internally; a teardown we
        // initiated must not be reported as the stream breaking.
        assertEquals(null, failures.poll(1, TimeUnit.SECONDS))
    }
}
