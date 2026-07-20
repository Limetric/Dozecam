package app.dozecam.ui.home

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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import app.dozecam.data.DetectorSettings
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onWatch: (String) -> Unit,
    onToggleMonitoring: (enabled: Boolean, streamUrl: String) -> Unit,
) {
    val url by viewModel.urlInput.collectAsStateWithLifecycle()
    val canWatch by viewModel.canWatch.collectAsStateWithLifecycle()
    val detector by viewModel.detector.collectAsStateWithLifecycle()
    val monitoringRunning by viewModel.monitoringRunning.collectAsStateWithLifecycle()
    val audioLevel by viewModel.audioLevel.collectAsStateWithLifecycle()
    HomeScreen(
        url = url,
        canWatch = canWatch,
        onUrlChange = viewModel::onUrlChange,
        onWatch = { onWatch(viewModel.commitUrl()) },
        monitoringRunning = monitoringRunning,
        // The committed URL rides along so the service never races the
        // asynchronous DataStore write.
        onToggleMonitoring = { enabled -> onToggleMonitoring(enabled, viewModel.commitUrl()) },
        audioLevel = audioLevel,
        detector = detector,
        onDetectorChange = viewModel::onDetectorChange,
    )
}

@Composable
fun HomeScreen(
    url: String,
    canWatch: Boolean,
    onUrlChange: (String) -> Unit,
    onWatch: () -> Unit,
    monitoringRunning: Boolean,
    onToggleMonitoring: (Boolean) -> Unit,
    audioLevel: Float,
    detector: DetectorSettings,
    onDetectorChange: (DetectorSettings) -> Unit,
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.stream_url_label)) },
                placeholder = { Text(stringResource(R.string.stream_url_hint)) },
                singleLine = true,
            )
            Button(onClick = onWatch, enabled = canWatch) {
                Text(stringResource(R.string.watch))
            }

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
                    enabled = canWatch || monitoringRunning,
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
    onDetectorChange: (DetectorSettings) -> Unit,
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
            onValueChange = { onDetectorChange(detector.copy(threshold = it)) },
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
            onValueChange = {
                onDetectorChange(detector.copy(sustainMs = (it * 1000).roundToLong()))
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
            onValueChange = {
                onDetectorChange(detector.copy(quietMs = (it * 1000).roundToLong()))
            },
            valueRange = 2f..30f,
            modifier = Modifier.testTag("quiet-slider"),
        )
    }
}
