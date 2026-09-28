package app.dozecam.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.data.Camera

/** The smallest a touch target may be, whatever the pill drawn inside it. */
private val MIN_TOUCH_TARGET = 48.dp

/**
 * Pauses one grid tile's camera, from the corner the tile's own chrome leaves
 * free. Drawn as the same small pill as the audible badge, inside a target big
 * enough for a thumb — the pill is sized for the picture, the target for a
 * hand.
 */
@Composable
internal fun PauseTileButton(
    cameraName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.viewer_pause_camera, cameraName)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            // The pill lands at the same margin as every other piece of tile
            // chrome; the target around it reaches past that into the edge.
            .padding(OverlayChrome.Margin - (MIN_TOUCH_TARGET - OverlayChrome.TileHeight) / 2)
            .size(MIN_TOUCH_TARGET)
            .clip(CircleShape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
            .testTag("pause-camera-$cameraName"),
    ) {
        OverlayPill(
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(OverlayChrome.TileHeight),
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(R.drawable.ic_pause),
                    contentDescription = label,
                    modifier = Modifier.size(OverlayChrome.IconSize),
                )
            }
        }
    }
}

/** Pauses the camera that has the screen to itself, beside its other controls. */
@Composable
internal fun PauseCameraButton(
    cameraName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.testTag("pause-fullscreen"),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_pause),
            contentDescription = stringResource(R.string.viewer_pause_camera, cameraName),
        )
    }
}

/**
 * A paused camera's slot on the grid: no picture, no session, and a plain
 * statement that nobody is watching this room — with the way back on it.
 *
 * Kept in the grid rather than dropped from it, because a room that simply
 * vanished would read as a camera lost, and a camera lost at 3am is the one
 * thing this app is not allowed to be quiet about. Dim, so the rooms that are
 * being watched still draw the eye first — but not so dim that the slot
 * disappears into the black around the pictures.
 */
@Composable
internal fun PausedTile(
    camera: Camera,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            // A surface of its own rather than the grid's black: a slot that
            // blends into the letterboxing reads as a gap, not as a room.
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(OverlayChrome.Margin)
            .testTag("paused-tile-${camera.name}"),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OverlayChrome.Gap),
        ) {
            Text(
                text = camera.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.viewer_camera_paused),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(
                onClick = onResume,
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.testTag("resume-camera-${camera.name}"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.viewer_resume_camera))
            }
        }
    }
}
