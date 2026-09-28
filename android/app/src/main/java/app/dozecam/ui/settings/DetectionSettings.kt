package app.dozecam.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.data.DetectorSettings
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The Detection category: how loud, for how long, and when to re-arm. */
@Composable
fun DetectionSettings(
    detector: DetectorSettings,
    onDetectorChange: ((DetectorSettings) -> DetectorSettings) -> Unit,
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
                text = stringResource(R.string.detector_applies_to_all),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            JumpTarget(
                id = SettingIds.THRESHOLD,
                jumpTarget = jumpTarget,
                onJumpDone = onJumpDone,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(
                            R.string.detector_threshold_label,
                            (detector.threshold * 100).roundToInt(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = detector.threshold,
                        onValueChange = { value ->
                            onDetectorChange { it.copy(threshold = value) }
                        },
                        valueRange = 0.01f..0.5f,
                        modifier = Modifier.testTag("threshold-slider"),
                    )
                }
            }
            JumpTarget(
                id = SettingIds.SUSTAIN,
                jumpTarget = jumpTarget,
                onJumpDone = onJumpDone,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                }
            }
            JumpTarget(
                id = SettingIds.QUIET,
                jumpTarget = jumpTarget,
                onJumpDone = onJumpDone,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
}
