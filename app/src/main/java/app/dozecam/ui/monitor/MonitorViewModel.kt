package app.dozecam.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.dozecam.data.Camera
import app.dozecam.data.CameraStore
import app.dozecam.monitoring.MonitorTransports
import app.dozecam.player.StreamSource
import app.dozecam.protect.CredentialsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MonitorViewModel(
    private val cameraStore: CameraStore,
    private val credentials: CredentialsStore,
    /** Where the credentials read happens; injectable so tests stay deterministic. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** The cameras the viewer shows — the same set the monitor listens to. */
    val cameras: StateFlow<List<Camera>> = cameraStore.enabledCameras
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Non-null once at least one camera exists but none are switched on. */
    val hasDisabledOnly: StateFlow<Boolean> =
        combine(cameraStore.cameras, cameras) { all, enabled ->
            all.isNotEmpty() && enabled.isEmpty()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)






    /**
     * How each camera's video is fetched. Resolving needs the signed-in console
     * (a camera issued by a different one must fall back to its own RTSP URL),
     * which is a disk read, so it is resolved once here rather than per tile.
     */
    private val _sources = MutableStateFlow<Map<String, StreamSource>>(emptyMap())
    val sources: StateFlow<Map<String, StreamSource>> = _sources

    /**
     * Enabled cameras that the monitor cannot actually listen to — a viewer
     * that showed such a camera while silently not monitoring it would be lying
     * by omission.
     *
     * Asked of [MonitorTransports] rather than of the URL alone, because a
     * stale rtsps entry is no longer the end of the story: a Protect camera can
     * be listened to over the livestream whatever its RTSP URL says. Resolved
     * alongside [sources], from the one console read they share.
     */
    private val _unmonitorable = MutableStateFlow<List<Camera>>(emptyList())
    val unmonitorable: StateFlow<List<Camera>> = _unmonitorable


    /**
     * Bumped to re-resolve against credentials that may have changed while the
     * viewer was away. Signing in to a different console replaces them without
     * touching the camera list, so the list alone is not enough of a signal:
     * cameras from the old console must fall back to RTSP rather than keep
     * negotiating ids the new console has never heard of.
     */
    private val refreshes = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            combine(cameras, refreshes) { list, _ -> list }.collect { list ->
                val host = withContext(ioDispatcher) { credentials.load()?.host }
                val sources = list.associate { it.id to StreamSource.of(it, host) }
                _sources.value = sources
                _unmonitorable.value = list.filter { camera ->
                    MonitorTransports.of(camera, sources.getValue(camera.id), host).isEmpty()
                }
            }
        }
    }

    /** Call when the viewer comes back to the front, e.g. from onboarding. */
    fun refreshSources() {
        refreshes.value++
    }



    companion object {
        fun factory(
            cameraStore: CameraStore,
            credentials: CredentialsStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { MonitorViewModel(cameraStore, credentials) }
        }
    }
}
