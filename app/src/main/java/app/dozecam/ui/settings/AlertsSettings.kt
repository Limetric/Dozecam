package app.dozecam.ui.settings

import android.media.RingtoneManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.dozecam.R
import app.dozecam.data.AppSettings
import app.dozecam.monitoring.AlarmSchedule
import app.dozecam.ui.components.GroupRow
import app.dozecam.ui.components.groupShape
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The Alerts category: how an alert reaches someone asleep. Every knob here is
 * app-side on purpose: the alert notification channel is deliberately silent,
 * and channel settings are immutable once created, so anything that lived
 * there could never be changed again.
 */
@Composable
fun AlertsSettings(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onPickAlertSound: () -> Unit,
    onPreviewAlertSound: () -> Unit,
    jumpTarget: String?,
    onJumpDone: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        JumpTarget(
            id = SettingIds.CHIME,
            jumpTarget = jumpTarget,
            onJumpDone = onJumpDone,
            shape = groupShape(0, 4),
        ) {
            SettingSwitchRow(
                label = stringResource(R.string.setting_alert_chime),
                description = stringResource(R.string.setting_alert_chime_description),
                iconRes = R.drawable.ic_volume_up,
                checked = settings.alertChime,
                onCheckedChange = { checked ->
                    onSettingsChange { it.copy(alertChime = checked) }
                },
                shape = groupShape(0, 4),
                tag = "chime-switch",
            )
        }
        JumpTarget(
            id = SettingIds.VIBRATE,
            jumpTarget = jumpTarget,
            onJumpDone = onJumpDone,
            shape = groupShape(1, 4),
        ) {
            SettingSwitchRow(
                label = stringResource(R.string.setting_alert_vibrate),
                description = stringResource(R.string.setting_alert_vibrate_description),
                iconRes = R.drawable.ic_vibration,
                checked = settings.alertVibrate,
                onCheckedChange = { checked ->
                    onSettingsChange { it.copy(alertVibrate = checked) }
                },
                shape = groupShape(1, 4),
                tag = "vibrate-switch",
            )
        }
        // Previewable on the spot: nobody should first hear their alert sound
        // at 3am, and least of all discover then that it is a message tone.
        JumpTarget(
            id = SettingIds.ALERT_SOUND,
            jumpTarget = jumpTarget,
            onJumpDone = onJumpDone,
            shape = groupShape(2, 4),
        ) {
            GroupRow(
                headline = stringResource(R.string.setting_alert_sound),
                supporting = alertSoundTitle(settings),
                shape = groupShape(2, 4),
                leading = {
                    Icon(painter = painterResource(R.drawable.ic_alarm), contentDescription = null)
                },
                trailing = {
                    IconButton(
                        onClick = onPreviewAlertSound,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.testTag("alert-sound-preview"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.setting_alert_preview),
                        )
                    }
                },
                onClick = onPickAlertSound,
                modifier = Modifier.testTag("alert-sound-row"),
            )
        }
        JumpTarget(
            id = SettingIds.ALERT_RAMP,
            jumpTarget = jumpTarget,
            onJumpDone = onJumpDone,
            shape = groupShape(3, 4),
        ) {
            SettingSwitchRow(
                label = stringResource(R.string.setting_alert_ramp),
                description = stringResource(R.string.setting_alert_ramp_description),
                iconRes = R.drawable.ic_escalate,
                checked = settings.alertRamp,
                onCheckedChange = { checked ->
                    onSettingsChange { it.copy(alertRamp = checked) }
                },
                shape = groupShape(3, 4),
                tag = "alert-ramp-switch",
            )
        }
    }
    AlertTuning(
        settings = settings,
        onSettingsChange = onSettingsChange,
        jumpTarget = jumpTarget,
        onJumpDone = onJumpDone,
    )
}

/**
 * The two numbers worth having: how loud, and how often. Both are a ceiling on
 * the phone's own alarm volume rather than an override of it — Dozecam plays on
 * the alarm stream and never rewrites what the user set there.
 */
@Composable
private fun AlertTuning(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    jumpTarget: String?,
    onJumpDone: () -> Unit,
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
                text = stringResource(R.string.alert_tuning_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            JumpTarget(
                id = SettingIds.ALERT_VOLUME,
                jumpTarget = jumpTarget,
                onJumpDone = onJumpDone,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(
                            R.string.alert_volume_label,
                            (settings.alertVolume * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = settings.alertVolume,
                        onValueChange = { value ->
                            onSettingsChange { it.copy(alertVolume = value) }
                        },
                        // Never zero: an alert nobody can hear is the one bug this whole
                        // section exists to prevent. Silence is what the chime switch is for.
                        valueRange = 0.1f..1f,
                        modifier = Modifier.testTag("alert-volume-slider"),
                    )
                }
            }
            JumpTarget(
                id = SettingIds.ALERT_REPEAT,
                jumpTarget = jumpTarget,
                onJumpDone = onJumpDone,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(
                            R.string.alert_repeat_label,
                            (settings.alertRepeatIntervalMs / 1000).toInt(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = settings.alertRepeatIntervalMs / 1000f,
                        onValueChange = { value ->
                            onSettingsChange {
                                it.copy(alertRepeatIntervalMs = (value * 1000).roundToLong())
                            }
                        },
                        valueRange = AlarmSchedule.MIN_REPEAT_INTERVAL_MS / 1000f..
                            AlarmSchedule.MAX_REPEAT_INTERVAL_MS / 1000f,
                        modifier = Modifier.testTag("alert-repeat-slider"),
                    )
                }
            }
            Text(
                text = stringResource(R.string.alert_repeat_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** The chosen tone's own name, or an honest description of the fallback. */
@Composable
internal fun alertSoundTitle(settings: AppSettings): String {
    val context = LocalContext.current
    val fallback = stringResource(R.string.setting_alert_sound_unknown)
    val uri = settings.alertSoundUri ?: return stringResource(R.string.setting_alert_sound_default)
    return remember(uri, fallback) {
        // A tone can be on storage that is no longer mounted, or behind a grant
        // that has since been revoked; the row still has to render.
        runCatching { RingtoneManager.getRingtone(context, uri.toUri())?.getTitle(context) }
            .getOrNull()
            ?: fallback
    }
}
