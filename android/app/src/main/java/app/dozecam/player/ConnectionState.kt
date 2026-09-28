package app.dozecam.player

sealed interface ConnectionState {
    /** Initial stream startup; nothing received yet. */
    data object Connecting : ConnectionState

    /** Frames are arriving. */
    data object Live : ConnectionState

    /** Stream failed or stalled; attempt N is pending or in flight. */
    data class Reconnecting(val attempt: Int) : ConnectionState

    /** No network; reconnecting is pointless until it returns. */
    data object Offline : ConnectionState
}
