package app.dozecam.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.dozecam.data.Camera
import app.dozecam.data.CameraStore
import app.dozecam.data.DetectorSettings
import app.dozecam.data.DetectorSettingsStore
import app.dozecam.monitoring.MonitorTransports
import app.dozecam.monitoring.MonitoringState
import app.dozecam.player.ConnectionState
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
import kotlin.math.roundToInt

class MonitorViewModel(
    private val cameraStore: CameraStore,
    private val credentials: CredentialsStore,
    monitoringState: MonitoringState,
    detectorSettings: DetectorSettingsStore,
    /** Where the credentials read happens; injectable so tests stay deterministic. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** The cameras the viewer shows — the same set the monitor listens to. */
    val cameras: StateFlow<List<Camera>> = cameraStore.enabledCameras
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Whether the monitor is listening this instant.
     *
     * The viewer needs it because live cameras on screen say nothing about it:
     * the grid looks identical whether or not anything is on watch, and that is
     * exactly the picture someone would take as proof that it is.
     */
    val monitoringRunning: StateFlow<Boolean> = monitoringState.serviceRunning

    /**
     * What the monitor is hearing from each camera, keyed by camera id — the
     * settings meter answers "is the detector hearing anything at all?", but
     * only per room does a level say *which* camera the noise is in. A camera
     * with no entry is one the monitor is not hearing right now, and its tile
     * shows no meter rather than a lying one. "Not hearing" covers more than
     * "not monitored": a monitor still connecting reports a level of zero it
     * has never measured, and one reconnecting or offline holds whatever came
     * last — so only cameras whose monitor is actually live get an entry.
     *
     * Levels are quantized to steps no meter could show anyway. The monitor
     * reports one per decoded buffer per camera, and a quiet room's noise
     * floor jitters on every one of them — raw, that is the whole viewer
     * recomposing tens of times a second all night to redraw nothing. Rounded,
     * a quiet night is one value, and the StateFlow's own equality drops it.
     */
    val audioLevels: StateFlow<Map<String, Float>> =
        combine(monitoringState.cameras, detectorSettings.settings) { states, settings ->
            buildMap {
                states.values.forEach { camera ->
                    // Both conditions, not either: a live connection can predate
                    // its first decoded buffer, and a lingering level can
                    // outlive its connection.
                    if (camera.connection != ConnectionState.Live) return@forEach
                    val level = camera.level ?: return@forEach
                    put(camera.cameraId, quantized(level, settings.threshold))
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Marked on every meter so a level near the line reads as "about to wake
     * me" here exactly as it does on the tuning meter in settings.
     */
    val audioThreshold: StateFlow<Float> = detectorSettings.settings
        .map { it.threshold }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DetectorSettings().threshold)

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
     * Whether starting the monitor would achieve anything — at least one camera
     * switched on that there is some way to hear. Falls out of the same
     * resolution [unmonitorable] does, so the viewer's offer to start cannot
     * disagree with the service about what is listenable.
     */
    private val _canMonitor = MutableStateFlow(false)
    val canMonitor: StateFlow<Boolean> = _canMonitor


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
                val unmonitorable = list.filter { camera ->
                    MonitorTransports.of(camera, sources.getValue(camera.id), host).isEmpty()
                }
                _unmonitorable.value = unmonitorable
                _canMonitor.value = list.size > unmonitorable.size
            }
        }
    }

    /** Call when the viewer comes back to the front, e.g. from onboarding. */
    fun refreshSources() {
        refreshes.value++
    }

    /**
     * Rounding must never move a level across the threshold: the meter swells
     * on `level >= threshold`, and a swell the detector would disown is a
     * false alarm two inches from a real one. Within half a step of the line
     * the raw value passes through instead — dedup lost exactly where a live
     * reading is what the eye is there for.
     */
    private fun quantized(level: Float, threshold: Float): Float {
        val stepped = (level * 100).roundToInt() / 100f
        return if ((stepped >= threshold) == (level >= threshold)) stepped else level
    }



    companion object {
        fun factory(
            cameraStore: CameraStore,
            credentials: CredentialsStore,
            monitoringState: MonitoringState,
            detectorSettings: DetectorSettingsStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MonitorViewModel(cameraStore, credentials, monitoringState, detectorSettings)
            }
        }
    }
}
