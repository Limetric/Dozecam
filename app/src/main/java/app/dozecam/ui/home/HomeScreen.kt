package app.dozecam.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dozecam.R
import app.dozecam.data.Camera
import app.dozecam.data.DetectorSettings
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onWatch: (String) -> Unit,
    onToggleMonitoring: (enabled: Boolean, streamUrl: String) -> Unit,
    onOpenSettings: () -> Unit,
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
    )
}

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
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                )
                TextButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("open-settings"),
                ) {
                    Text(stringResource(R.string.settings))
                }
            }

            cameras.forEach { camera ->
                CameraRow(
                    camera = camera,
                    selected = camera.id == selectedCameraId,
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
                )
            }

            CameraForm(
                form = form,
                onName = onFormName,
                onUrl = onFormUrl,
                onSave = onFormSave,
                onCancel = onFormCancel,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.monitoring_toggle),
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(
                    checked = monitoringRunning,
                    onCheckedChange = onToggleMonitoring,
                    enabled = canMonitor,
                    modifier = Modifier.testTag("monitoring-switch"),
                )
            }

            if (monitoringRunning) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.audio_level_label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    AudioLevelMeter(
                        level = audioLevel,
                        threshold = detector.threshold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
            }

            DetectorTuning(
                detector = detector,
                onDetectorChange = onDetectorChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CameraRow(
    camera: Camera,
    selected: Boolean,
    onSelect: () -> Unit,
    onWatch: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onWatch)
            .testTag("camera-row-${camera.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            modifier = Modifier.testTag("camera-select-${camera.name}"),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = camera.name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = camera.url,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        TextButton(onClick = onEdit, modifier = Modifier.testTag("camera-edit-${camera.name}")) {
            Text(stringResource(R.string.edit))
        }
        TextButton(
            onClick = onDelete,
            modifier = Modifier.testTag("camera-delete-${camera.name}"),
        ) {
            Text(stringResource(R.string.delete))
        }
    }
}

@Composable
private fun CameraForm(
    form: CameraFormState,
    onName: (String) -> Unit,
    onUrl: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(
                if (form.editingId != null) R.string.edit_camera else R.string.add_camera,
            ),
            style = MaterialTheme.typography.titleMedium,
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
                enabled = form.canSave,
                modifier = Modifier.testTag("camera-save"),
            ) {
                Text(stringResource(R.string.save))
            }
            if (form.editingId != null || form.name.isNotEmpty() || form.url.isNotEmpty()) {
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

/**
 * Live RMS meter with the trigger threshold marked — the doc calls this
 * invaluable for tuning, and it is. Scaled so the useful 0..0.5 RMS range
 * fills the bar.
 */
@Composable
fun AudioLevelMeter(level: Float, threshold: Float, modifier: Modifier = Modifier) {
    val scale = 0.5f
    Box(
        modifier = modifier
            .height(16.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .testTag("audio-level-meter"),
    ) {
        val levelFraction = (level / scale).coerceIn(0f, 1f)
        if (levelFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(levelFraction)
                    .background(
                        if (level >= threshold) Color(0xFFEF5350) else Color(0xFF66BB6A),
                        RoundedCornerShape(8.dp),
                    ),
            )
        }
        val thresholdFraction = (threshold / scale).coerceIn(0f, 1f)
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(
                R.string.detector_threshold_label,
                (detector.threshold * 100).roundToInt(),
            ),
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
