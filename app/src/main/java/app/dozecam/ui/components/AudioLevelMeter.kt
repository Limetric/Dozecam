package app.dozecam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Live RMS meter with the trigger threshold marked — invaluable for tuning.
 * Scaled so the useful 0..0.5 RMS range fills the bar; the wave swells once the
 * level crosses the threshold, which is the moment an alert would fire.
 *
 * Shows the loudest camera rather than an average: one loud room hidden behind
 * three quiet ones would make the threshold impossible to set.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AudioLevelMeter(
    level: Float,
    threshold: Float,
    modifier: Modifier = Modifier,
    /**
     * The tick's color is the caller's problem: on a settings card the theme's
     * onSurface reads fine, but the same color vanishes against the dark scrim
     * a camera tile draws this on in day theme.
     */
    thresholdColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val scale = 0.5f
    val levelFraction = (level / scale).coerceIn(0f, 1f)
    val thresholdFraction = (threshold / scale).coerceIn(0f, 1f)
    val triggered = level >= threshold
    Box(
        modifier = modifier
            .height(24.dp)
            .testTag("audio-level-meter"),
        contentAlignment = Alignment.CenterStart,
    ) {
        LinearWavyProgressIndicator(
            progress = { levelFraction },
            color = if (triggered) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            amplitude = { if (triggered) 1f else 0.2f },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxSize()) {
            if (thresholdFraction > 0f) {
                Box(modifier = Modifier.fillMaxWidth(thresholdFraction))
            }
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(thresholdColor),
            )
        }
    }
}
