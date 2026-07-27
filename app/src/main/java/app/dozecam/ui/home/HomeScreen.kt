package app.dozecam.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dozecam.R
import app.dozecam.data.Camera
import app.dozecam.data.DetectorSettings
import app.dozecam.ui.components.GroupRow
import app.dozecam.ui.components.Section
import app.dozecam.ui.components.groupShape
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onWatch: (String) -> Unit,
    onToggleMonitoring: (enabled: Boolean, streamUrl: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOnboarding: () -> Unit,
) {
    val cameras by viewModel.cameras.collectAsStateWithLifecycle()
    val selectedCamera by viewModel.selectedCamera.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val detector by viewModel.detector.collectAsStateWithLifecycle()
    val monitoringRunning by viewModel.monitoringRunning.collectAsStateWithLifecycle()
    val canMonitor by viewModel.canMonitor.collectAsStateWithLifecycle()
    val audioLevel by viewModel.audioLevel.collectAsStateWithLifecycle()
    HomeScreen(
        cameras = cameras,
        selectedCameraId = selectedCamera?.id,
        form = form,
        onFormName = viewModel::onFormName,
        onFormUrl = viewModel::onFormUrl,
        onFormSave = viewModel::saveCamera,
        onFormCancel = viewModel::cancelEdit,
        onSelect = viewModel::selectCamera,
        onWatch = { camera -> onWatch(camera.url) },
        onEdit = viewModel::startEdit,
        onDelete = viewModel::deleteCamera,
        monitoringRunning = monitoringRunning,
        canMonitor = canMonitor,
        onToggleMonitoring = { enabled ->
            onToggleMonitoring(enabled, selectedCamera?.url.orEmpty())
        },
        audioLevel = audioLevel,
        detector = detector,
        onDetectorChange = viewModel::onDetectorChange,
        onOpenSettings = onOpenSettings,
        onOpenOnboarding = onOpenOnboarding,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    cameras: List<Camera>,
    selectedCameraId: String?,
    form: CameraFormState,
    onFormName: (String) -> Unit,
    onFormUrl: (String) -> Unit,
    onFormSave: () -> Unit,
    onFormCancel: () -> Unit,
    onSelect: (String) -> Unit,
    onWatch: (Camera) -> Unit,
    onEdit: (Camera) -> Unit,
    onDelete: (String) -> Unit,
    monitoringRunning: Boolean,
    canMonitor: Boolean,
    onToggleMonitoring: (Boolean) -> Unit,
    audioLevel: Float,
    detector: DetectorSettings,
    onDetectorChange: ((DetectorSettings) -> DetectorSettings) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                subtitle = {
                    Text(
                        stringResource(
                            if (monitoringRunning) {
                                R.string.home_subtitle_listening
                            } else {
                                R.string.home_subtitle_idle
                            },
                        ),
                    )
                },
                actions = {
                    IconButton(
                        onClick = onOpenSettings,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.testTag("open-settings"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MonitoringCard(
                monitoringRunning = monitoringRunning,
                canMonitor = canMonitor,
                onToggleMonitoring = onToggleMonitoring,
                audioLevel = audioLevel,
                threshold = detector.threshold,
            )

            CamerasSection(
                cameras = cameras,
                selectedCameraId = selectedCameraId,
                onSelect = onSelect,
                onWatch = onWatch,
                onEdit = onEdit,
                onDelete = onDelete,
                onOpenOnboarding = onOpenOnboarding,
            )

            CameraForm(
                form = form,
                onName = onFormName,
                onUrl = onFormUrl,
                onSave = onFormSave,
                onCancel = onFormCancel,
            )

            DetectorTuning(
                detector = detector,
                onDetectorChange = onDetectorChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * The one control that matters at 3am, so it leads the screen: a hero card that
 * takes the theme's primary tones while monitoring runs and falls back to a
 * neutral container when it doesn't.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MonitoringCard(
    monitoringRunning: Boolean,
    canMonitor: Boolean,
    onToggleMonitoring: (Boolean) -> Unit,
    audioLevel: Float,
    threshold: Float,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (monitoringRunning) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(
                            if (monitoringRunning) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_graphic_eq),
                        contentDescription = null,
                        tint = if (monitoringRunning) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.monitoring_toggle),
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                    Text(
                        text = stringResource(
                            when {
                                monitoringRunning -> R.string.monitoring_supporting_running
                                canMonitor -> R.string.monitoring_supporting_ready
                                else -> R.string.monitoring_supporting_unavailable
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = monitoringRunning,
                    onCheckedChange = onToggleMonitoring,
                    enabled = canMonitor,
                    modifier = Modifier.testTag("monitoring-switch"),
                )
            }

            AnimatedVisibility(visible = monitoringRunning) {
                Column {
                    Text(
                        text = stringResource(R.string.audio_level_label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    AudioLevelMeter(
                        level = audioLevel,
                        threshold = threshold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CamerasSection(
    cameras: List<Camera>,
    selectedCameraId: String?,
    onSelect: (String) -> Unit,
    onWatch: (Camera) -> Unit,
    onEdit: (Camera) -> Unit,
    onDelete: (String) -> Unit,
    onOpenOnboarding: () -> Unit,
) {
    Section(title = stringResource(R.string.section_cameras)) {
        cameras.forEachIndexed { index, camera ->
            CameraRow(
                camera = camera,
                selected = camera.id == selectedCameraId,
                shape = groupShape(index, cameras.size),
                onSelect = { onSelect(camera.id) },
                onWatch = { onWatch(camera) },
                onEdit = { onEdit(camera) },
                onDelete = { onDelete(camera.id) },
            )
        }
        if (cameras.isEmpty()) {
            Text(
                text = stringResource(R.string.no_cameras_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        OutlinedButton(
            onClick = onOpenOnboarding,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag("open-onboarding"),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_videocam),
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(stringResource(R.string.connect_to_protect))
        }
    }
}

@Composable
private fun CameraRow(
    camera: Camera,
    selected: Boolean,
    shape: Shape,
    onSelect: () -> Unit,
    onWatch: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    GroupRow(
        headline = camera.name,
        supporting = camera.url,
        shape = shape,
        containerColor = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        leading = {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                modifier = Modifier.testTag("camera-select-${camera.name}"),
            )
        },
        trailing = {
            Row {
                IconButton(
                    onClick = onEdit,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.testTag("camera-edit-${camera.name}"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = stringResource(R.string.edit),
                    )
                }
                IconButton(
                    onClick = onDelete,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.testTag("camera-delete-${camera.name}"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete),
                    )
                }
            }
        },
        onClick = onWatch,
        modifier = Modifier.testTag("camera-row-${camera.name}"),
    )
}

@Composable
private fun CameraForm(
    form: CameraFormState,
    onName: (String) -> Unit,
    onUrl: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    if (form.editingId != null) R.string.edit_camera else R.string.add_camera,
                ),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            OutlinedTextField(
                value = form.name,
                onValueChange = onName,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("camera-name-field"),
                label = { Text(stringResource(R.string.camera_name_label)) },
                singleLine = true,
            )
            OutlinedTextField(
                value = form.url,
                onValueChange = onUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("camera-url-field"),
                label = { Text(stringResource(R.string.stream_url_label)) },
                placeholder = { Text(stringResource(R.string.stream_url_hint)) },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSave,
                    shapes = ButtonDefaults.shapes(),
                    enabled = form.canSave,
                    modifier = Modifier.testTag("camera-save"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(stringResource(R.string.save))
                }
                if (form.editingId != null || form.name.isNotEmpty() || form.url.isNotEmpty()) {
                    OutlinedButton(onClick = onCancel, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }
}

/**
 * Live RMS meter with the trigger threshold marked — the doc calls this
 * invaluable for tuning, and it is. Scaled so the useful 0..0.5 RMS range fills
 * the bar; the wave swells once the level crosses the threshold, which is the
 * moment an alert would fire.
 */
@Composable
fun AudioLevelMeter(level: Float, threshold: Float, modifier: Modifier = Modifier) {
    val scale = 0.5f
    val levelFraction = (level / scale).coerceIn(0f, 1f)
    val thresholdFraction = (threshold / scale).coerceIn(0f, 1f)
    val triggered = level >= threshold
    Box(
        modifier = modifier
            .height(24.dp)
            .testTag("audio-level-meter"),
        contentAlignment = Alignment.CenterStart,
    ) {
        LinearWavyProgressIndicator(
            progress = { levelFraction },
            color = if (triggered) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            amplitude = { if (triggered) 1f else 0.2f },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            if (thresholdFraction > 0f) {
                Box(modifier = Modifier.fillMaxWidth(thresholdFraction))
            }
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurface),
            )
        }
    }
}

@Composable
private fun DetectorTuning(
    detector: DetectorSettings,
    onDetectorChange: ((DetectorSettings) -> DetectorSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Section(title = stringResource(R.string.section_detection), modifier = modifier) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.detector_threshold_label,
                        (detector.threshold * 100).roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = detector.threshold,
                    onValueChange = { value -> onDetectorChange { it.copy(threshold = value) } },
                    valueRange = 0.01f..0.5f,
                    modifier = Modifier.testTag("threshold-slider"),
                )
                Text(
                    text = stringResource(
                        R.string.detector_sustain_label,
                        detector.sustainMs / 1000f,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = detector.sustainMs / 1000f,
                    onValueChange = { value ->
                        onDetectorChange { it.copy(sustainMs = (value * 1000).roundToLong()) }
                    },
                    valueRange = 0.5f..5f,
                    modifier = Modifier.testTag("sustain-slider"),
                )
                Text(
                    text = stringResource(
                        R.string.detector_quiet_label,
                        (detector.quietMs / 1000).toInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = detector.quietMs / 1000f,
                    onValueChange = { value ->
                        onDetectorChange { it.copy(quietMs = (value * 1000).roundToLong()) }
                    },
                    valueRange = 2f..30f,
                    modifier = Modifier.testTag("quiet-slider"),
                )
            }
        }
    }
}
