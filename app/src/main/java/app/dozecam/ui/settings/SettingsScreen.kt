package app.dozecam.ui.settings

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
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import app.dozecam.data.OrientationLock
import app.dozecam.ui.components.GroupRow
import app.dozecam.ui.components.Section
import app.dozecam.ui.components.groupShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
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
