package app.dozecam.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.data.Camera
import app.dozecam.data.StreamUrlValidator
import app.dozecam.ui.components.GroupRow
import app.dozecam.ui.components.groupShape

/**
 * The Cameras category: the list, the console route, and the manual form. A
 * camera's switch is the whole story: an enabled camera is the one the viewer
 * shows *and* the one the monitor listens to, so there is no second "monitor
 * this" choice to make.
 */
@Composable
fun CamerasSettings(
    cameras: List<Camera>,
    onCameraEnabled: (String, Boolean) -> Unit,
    onEdit: (Camera) -> Unit,
    onDelete: (String) -> Unit,
    onOpenOnboarding: () -> Unit,
    form: CameraFormState,
    onFormName: (String) -> Unit,
    onFormUrl: (String) -> Unit,
    onFormSave: () -> Unit,
    onFormCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
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
    CameraForm(
        form = form,
        onName = onFormName,
        onUrl = onFormUrl,
        onSave = onFormSave,
        onCancel = onFormCancel,
    )
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
