package app.dozecam.player

import android.view.ViewGroup
import app.dozecam.data.Camera

sealed interface PlayerEvent {
    data object Playing : PlayerEvent
    data object Buffering : PlayerEvent
    data object Stopped : PlayerEvent
    data object Error : PlayerEvent
    data class TimeChanged(val timeMs: Long) : PlayerEvent
}

/**
 * Where a camera's live video comes from. The two transports are not
 * interchangeable: RTSP hands out raw RTP, which no Android player can
 * depayload for AV1, while Protect's livestream wraps whatever the camera
 * encodes in fMP4 and so carries any codec. A camera the user typed a URL for
 * has no console behind it, so it can only ever be RTSP.
 */
sealed interface StreamSource {

    data class Rtsp(val url: String) : StreamSource

    data class Livestream(val cameraId: String, val channel: Int) : StreamSource

    companion object {

        /** Onboarding's id shape for a Protect camera: `protect-<cameraId>-<channel>`. */
        private val LEGACY_PROTECT_ID = Regex("""^protect-([^-]+)-(\d+)$""")

        /**
         * [consoleHost] is the console currently signed in. A camera issued by
         * a different one cannot be negotiated here, so it falls back to its
         * own RTSP URL — which is self-contained and keeps working — rather
         * than failing against a console that has never heard of it.
         */
        fun of(camera: Camera, consoleHost: String? = null): StreamSource {
            val protect = camera.protect
            if (protect != null) {
                // A mismatch must not fall through to the id-derived identity
                // below, which would negotiate the same wrong camera anyway.
                val ours = protect.consoleHost == null || protect.consoleHost == consoleHost
                return if (ours) Livestream(protect.cameraId, protect.channel) else Rtsp(camera.url)
            }
            return legacyProtectIdentity(camera) ?: Rtsp(camera.url)
        }

        /**
         * Cameras onboarded before the livestream existed carry no [Camera.protect],
         * but onboarding has always encoded the console identity into their id.
         * Recovering it there means an upgrade fixes an AV1 camera on its own
         * instead of leaving every existing install on a black screen until the
         * user happens to re-run onboarding.
         */
        private fun legacyProtectIdentity(camera: Camera): Livestream? =
            LEGACY_PROTECT_ID.matchEntire(camera.id)?.let { match ->
                val (cameraId, channel) = match.destructured
                channel.toIntOrNull()?.let { Livestream(cameraId, it) }
            }
    }
}

interface VideoPlayerController {
    var listener: ((PlayerEvent) -> Unit)?

    /** Adds this player's video view to [container]; [detach] removes it again. */
    fun attach(container: ViewGroup)
    fun detach()
    fun play(source: StreamSource)

    /**
     * Silences this player's audio track. A grid plays several cameras at once,
     * and several rooms talking over each other is unusable, so only a camera
     * the user has singled out is ever audible.
     */
    fun setMuted(muted: Boolean)
    fun stop()
    fun release()
}
