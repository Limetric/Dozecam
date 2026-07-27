package app.dozecam.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.data.AppSettings
import app.dozecam.data.Camera
import app.dozecam.data.DetectorSettings
import app.dozecam.data.OrientationLock
import app.dozecam.data.StreamUrlValidator
import app.dozecam.ui.components.AudioLevelMeter
import app.dozecam.ui.components.GroupRow
import app.dozecam.ui.components.Section
import app.dozecam.ui.components.groupShape
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    monitoringRunning: Boolean,
    canMonitor: Boolean,
    onToggleMonitoring: (Boolean) -> Unit,
    audioLevel: Float,
    cameras: List<Camera>,
    onCameraEnabled: (String, Boolean) -> Unit,
    onEditCamera: (Camera) -> Unit,
    onDeleteCamera: (String) -> Unit,
    form: CameraFormState,
    onFormName: (String) -> Unit,
    onFormUrl: (String) -> Unit,
    onFormSave: () -> Unit,
    onFormCancel: () -> Unit,
    detector: DetectorSettings,
    onDetectorChange: ((DetectorSettings) -> DetectorSettings) -> Unit,
    onOpenOnboarding: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.testTag("settings-back"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
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
        ) {
            MonitoringSection(
                running = monitoringRunning,
                canMonitor = canMonitor,
                onToggle = onToggleMonitoring,
                audioLevel = audioLevel,
                threshold = detector.threshold,
            )

            CamerasSection(
                cameras = cameras,
                onCameraEnabled = onCameraEnabled,
                onEdit = onEditCamera,
                onDelete = onDeleteCamera,
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

            Section(title = stringResource(R.string.section_appearance)) {
                SettingSwitchRow(
                    label = stringResource(R.string.setting_night_theme),
                    description = stringResource(R.string.setting_night_theme_description),
                    iconRes = R.drawable.ic_bedtime,
                    checked = settings.nightTheme,
                    onCheckedChange = { checked ->
                        onSettingsChange { it.copy(nightTheme = checked) }
                    },
                    shape = groupShape(0, 1),
                    tag = "night-theme-switch",
                )
            }

            Section(title = stringResource(R.string.section_alerts)) {
                SettingSwitchRow(
                    label = stringResource(R.string.setting_alert_chime),
                    description = stringResource(R.string.setting_alert_chime_description),
                    iconRes = R.drawable.ic_volume_up,
                    checked = settings.alertChime,
                    onCheckedChange = { checked ->
                        onSettingsChange { it.copy(alertChime = checked) }
                    },
                    shape = groupShape(0, 2),
                    tag = "chime-switch",
                )
                SettingSwitchRow(
                    label = stringResource(R.string.setting_alert_vibrate),
                    description = stringResource(R.string.setting_alert_vibrate_description),
                    iconRes = R.drawable.ic_vibration,
                    checked = settings.alertVibrate,
                    onCheckedChange = { checked ->
                        onSettingsChange { it.copy(alertVibrate = checked) }
                    },
                    shape = groupShape(1, 2),
                    tag = "vibrate-switch",
                )
            }

            Section(title = stringResource(R.string.section_monitor)) {
                GroupRow(
                    headline = stringResource(R.string.setting_orientation),
                    supporting = stringResource(settings.orientationLock.descriptionRes()),
                    leading = {
                        Icon(
                            painter = painterResource(R.drawable.ic_smartphone),
                            contentDescription = null,
                        )
                    },
                )
                OrientationSelector(
                    selected = settings.orientationLock,
                    onSelect = { lock -> onSettingsChange { it.copy(orientationLock = lock) } },
                )
            }
        }
    }
}

/**
 * Arming the monitor, and the level meter that makes the threshold below
 * settable. It lives here rather than over the video: the viewer is for
 * watching, and a control that is only touched at bedtime does not need to
 * occupy the screen all night.
 */
@Composable
private fun MonitoringSection(
    running: Boolean,
    canMonitor: Boolean,
    onToggle: (Boolean) -> Unit,
    audioLevel: Float,
    threshold: Float,
) {
    Section(title = stringResource(R.string.section_monitoring)) {
        GroupRow(
            headline = stringResource(R.string.monitoring_toggle),
            supporting = stringResource(
                when {
                    running -> R.string.monitoring_listening
                    canMonitor -> R.string.monitoring_idle
                    else -> R.string.monitoring_unavailable
                },
            ),
            containerColor = if (running) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
            leading = {
                Icon(
                    painter = painterResource(R.drawable.ic_graphic_eq),
                    contentDescription = null,
                )
            },
            trailing = {
                Switch(
                    checked = running,
                    onCheckedChange = onToggle,
                    enabled = canMonitor,
                    modifier = Modifier.testTag("monitoring-switch"),
                )
            },
        )
        AnimatedVisibility(visible = running) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
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

/**
 * A camera's switch is the whole story: an enabled camera is the one the
 * viewer shows *and* the one the monitor listens to, so there is no second
 * "monitor this" choice to make.
 */
@Composable
private fun CamerasSection(
    cameras: List<Camera>,
    onCameraEnabled: (String, Boolean) -> Unit,
    onEdit: (Camera) -> Unit,
    onDelete: (String) -> Unit,
    onOpenOnboarding: () -> Unit,
) {
    Section(title = stringResource(R.string.section_cameras)) {
        cameras.forEachIndexed { index, camera ->
            CameraRow(
                camera = camera,
                shape = groupShape(index, cameras.size),
                onEnabled = { enabled -> onCameraEnabled(camera.id, enabled) },
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
    shape: Shape,
    onEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    // A stale rtsps entry can be watched but never listened to; saying so here
    // is the only place the user can act on it.
    val supporting = if (camera.enabled && !StreamUrlValidator.isMonitorable(camera.url)) {
        stringResource(R.string.camera_not_monitorable)
    } else {
        camera.url
    }
    GroupRow(
        headline = camera.name,
        supporting = supporting,
        shape = shape,
        containerColor = if (camera.enabled) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        leading = {
            Switch(
                checked = camera.enabled,
                onCheckedChange = onEnabled,
                modifier = Modifier.testTag("camera-enabled-${camera.name}"),
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
                    text = stringResource(R.string.detector_applies_to_all),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
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

/**
 * The orientation lock is a single choice among three, so it reads as a
 * connected button group: outer corners on the ends, the selection carrying the
 * theme's primary tone.
 */
@Composable
private fun OrientationSelector(
    selected: OrientationLock,
    onSelect: (OrientationLock) -> Unit,
) {
    val locks = OrientationLock.entries
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        locks.forEachIndexed { index, lock ->
            ToggleButton(
                checked = lock == selected,
                onCheckedChange = { onSelect(lock) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    locks.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("orientation-${lock.name}"),
                contentPadding = ToggleButtonDefaults.ContentPadding,
            ) {
                Text(stringResource(lock.shortLabelRes()))
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    description: String,
    iconRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
    tag: String,
) {
    GroupRow(
        headline = label,
        supporting = description,
        shape = shape,
        containerColor = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        leading = {
            Icon(painter = painterResource(iconRes), contentDescription = null)
        },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(tag),
            )
        },
        onClick = { onCheckedChange(!checked) },
    )
}

private fun OrientationLock.shortLabelRes(): Int = when (this) {
    OrientationLock.AUTO -> R.string.orientation_auto_short
    OrientationLock.PORTRAIT -> R.string.orientation_portrait_short
    OrientationLock.LANDSCAPE -> R.string.orientation_landscape_short
}

private fun OrientationLock.descriptionRes(): Int = when (this) {
    OrientationLock.AUTO -> R.string.orientation_auto
    OrientationLock.PORTRAIT -> R.string.orientation_portrait
    OrientationLock.LANDSCAPE -> R.string.orientation_landscape
}
