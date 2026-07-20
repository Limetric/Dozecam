package app.dozecam.player

import org.videolan.libvlc.util.VLCVideoLayout

sealed interface PlayerEvent {
    data object Playing : PlayerEvent
    data object Buffering : PlayerEvent
    data object Stopped : PlayerEvent
    data object Error : PlayerEvent
    data class TimeChanged(val timeMs: Long) : PlayerEvent
}

interface VideoPlayerController {
    var listener: ((PlayerEvent) -> Unit)?

    fun attach(layout: VLCVideoLayout)
    fun detach()
    fun play(url: String)
    fun stop()
    fun release()
}
