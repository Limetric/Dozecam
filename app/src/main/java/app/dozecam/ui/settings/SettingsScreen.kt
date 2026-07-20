package app.dozecam.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.data.AppSettings
import app.dozecam.data.OrientationLock

@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
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
                    text = stringResource(R.string.settings),
                    style = MaterialTheme.typography.headlineMedium,
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
            }

            SettingSwitch(
                label = stringResource(R.string.setting_night_theme),
                description = stringResource(R.string.setting_night_theme_description),
                checked = settings.nightTheme,
                onCheckedChange = { checked -> onSettingsChange { it.copy(nightTheme = checked) } },
                tag = "night-theme-switch",
            )
            SettingSwitch(
                label = stringResource(R.string.setting_alert_chime),
                description = stringResource(R.string.setting_alert_chime_description),
                checked = settings.alertChime,
                onCheckedChange = { checked -> onSettingsChange { it.copy(alertChime = checked) } },
                tag = "chime-switch",
            )
            SettingSwitch(
                label = stringResource(R.string.setting_alert_vibrate),
                description = stringResource(R.string.setting_alert_vibrate_description),
                checked = settings.alertVibrate,
                onCheckedChange = { checked -> onSettingsChange { it.copy(alertVibrate = checked) } },
                tag = "vibrate-switch",
            )

            Text(
                text = stringResource(R.string.setting_orientation),
                style = MaterialTheme.typography.titleMedium,
            )
            OrientationLock.entries.forEach { lock ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = settings.orientationLock == lock,
                        onClick = { onSettingsChange { it.copy(orientationLock = lock) } },
                        modifier = Modifier.testTag("orientation-${lock.name}"),
                    )
                    Text(
                        text = stringResource(
                            when (lock) {
                                OrientationLock.AUTO -> R.string.orientation_auto
                                OrientationLock.PORTRAIT -> R.string.orientation_portrait
                                OrientationLock.LANDSCAPE -> R.string.orientation_landscape
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            Text(text = description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag),
        )
    }
}
