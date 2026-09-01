package app.dozecam.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.data.AppSettings
import app.dozecam.data.OrientationLock
import app.dozecam.ui.components.GroupRow
import app.dozecam.ui.components.groupShape
import kotlin.math.roundToInt

/** The Display category: theme, screen wakefulness, and monitor orientation. */
@Composable
fun DisplaySettings(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    jumpTarget: String?,
    onJumpDone: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        JumpTarget(
            id = SettingIds.NIGHT_THEME,
            jumpTarget = jumpTarget,
            onJumpDone = onJumpDone,
            shape = groupShape(0, 3),
        ) {
            SettingSwitchRow(
                label = stringResource(R.string.setting_night_theme),
                description = stringResource(R.string.setting_night_theme_description),
                iconRes = R.drawable.ic_bedtime,
                checked = settings.nightTheme,
                onCheckedChange = { checked ->
                    onSettingsChange { it.copy(nightTheme = checked) }
                },
                shape = groupShape(0, 3),
                tag = "night-theme-switch",
            )
        }
        // The same switch that floats over the cameras; this copy is where it
        // gets a name and an explanation.
        JumpTarget(
            id = SettingIds.KEEP_SCREEN,
            jumpTarget = jumpTarget,
            onJumpDone = onJumpDone,
            shape = groupShape(1, 3),
        ) {
            SettingSwitchRow(
                label = stringResource(R.string.setting_keep_screen),
                description = stringResource(R.string.setting_keep_screen_description),
                iconRes = R.drawable.ic_aod,
                checked = settings.keepScreenOn,
                onCheckedChange = { checked ->
                    onSettingsChange { it.copy(keepScreenOn = checked) }
                },
                shape = groupShape(1, 3),
                tag = "keep-screen-switch",
            )
        }
        JumpTarget(
            id = SettingIds.ORIENTATION,
            jumpTarget = jumpTarget,
            onJumpDone = onJumpDone,
            shape = groupShape(2, 3),
        ) {
            Column {
                GroupRow(
                    headline = stringResource(R.string.setting_orientation),
                    supporting = stringResource(settings.orientationLock.descriptionRes()),
                    shape = groupShape(2, 3),
                    leading = {
                        Icon(
                            painter = painterResource(R.drawable.ic_smartphone),
                            contentDescription = null,
                        )
                    },
                )
                OrientationSelector(
                    selected = settings.orientationLock,
                    onSelect = { lock ->
                        onSettingsChange { it.copy(orientationLock = lock) }
                    },
                )
            }
        }
        JumpTarget(
            id = SettingIds.TALKBACK_VOLUME,
            jumpTarget = jumpTarget,
            onJumpDone = onJumpDone,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            TalkbackTuning(settings = settings, onSettingsChange = onSettingsChange)
        }
    }
}

/**
 * How loud a press of the talk button arrives in the room. App-side on
 * purpose: the camera's own speaker volume is a console setting shared with
 * every other viewer, and a baby monitor has no business rewriting it.
 */
@Composable
private fun TalkbackTuning(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    Card(
        modifier = Modifier.padding(top = 8.dp),
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
                    R.string.talkback_volume_label,
                    (settings.talkbackVolume * 100).roundToInt(),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = settings.talkbackVolume,
                onValueChange = { value -> onSettingsChange { it.copy(talkbackVolume = value) } },
                valueRange = 0f..1f,
                modifier = Modifier.testTag("talkback-volume-slider"),
            )
            Text(
                text = stringResource(R.string.talkback_volume_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
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

private fun OrientationLock.shortLabelRes(): Int = when (this) {
    OrientationLock.AUTO -> R.string.orientation_auto_short
    OrientationLock.PORTRAIT -> R.string.orientation_portrait_short
    OrientationLock.LANDSCAPE -> R.string.orientation_landscape_short
}

internal fun OrientationLock.descriptionRes(): Int = when (this) {
    OrientationLock.AUTO -> R.string.orientation_auto
    OrientationLock.PORTRAIT -> R.string.orientation_portrait
    OrientationLock.LANDSCAPE -> R.string.orientation_landscape
}
