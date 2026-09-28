package app.dozecam.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Live RMS meter with the trigger threshold marked — invaluable for tuning.
 * Scaled so the useful 0..0.5 RMS range fills the bar; the wave swells once the
 * level crosses the threshold, which is the moment an alert would fire.
 *
 * Shows the loudest camera rather than an average: one loud room hidden behind
 * three quiet ones would make the threshold impossible to set.
 *
 * Rises at once and falls slowly: a level is reported per decoded buffer, so a
 * short sound is on screen for a frame or two and gone before anyone looks up.
 * The bar takes each higher level the moment it arrives, then fades toward a
 * lower one over [DECAY_MS], however loud it was — so a cry and a cough both
 * linger for the same readable moment. The colour and the wave follow the bar,
 * not the raw level, so what is shown never disagrees with itself.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AudioLevelMeter(
    level: Float,
    threshold: Float,
    modifier: Modifier = Modifier,
) {
    // Inherited from whatever surface the meter was placed on, so the tick is
    // legible on a settings card and on a tile's overlay over video without
    // either caller having to know which it is.
    val thresholdColor = LocalContentColor.current
    val scale = 0.5f
    val levelFraction = (level / scale).coerceIn(0f, 1f)
    val thresholdFraction = (threshold / scale).coerceIn(0f, 1f)
    val shown = remember { Animatable(levelFraction) }
    // The height the current fade set out from. Pacing the fall by it, rather
    // than by wherever the bar is now, means the whole way down takes DECAY_MS
    // however many lower targets arrive on the way: a floor that jitters
    // between two quiet steps cannot restart the clock and hold the bar up.
    var peak by remember { mutableFloatStateOf(levelFraction) }
    LaunchedEffect(levelFraction) {
        val from = shown.value
        if (levelFraction >= from) {
            peak = levelFraction
            shown.snapTo(levelFraction)
        } else {
            // The remaining drop takes its share of the full fall.
            val duration = (DECAY_MS * (from - levelFraction) / peak).toInt()
            shown.animateTo(levelFraction, tween(duration, easing = LinearEasing))
            // Settled, so the next fall sets out from here. Only reached when
            // the fade ran its course: a newer target cancels this effect
            // first, and its fall is still part of the same way down.
            peak = levelFraction
        }
    }
    val triggered = shown.value * scale >= threshold
    Box(
        modifier = modifier
            .height(24.dp)
            .testTag("audio-level-meter"),
        contentAlignment = Alignment.CenterStart,
    ) {
        LinearWavyProgressIndicator(
            progress = { shown.value },
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

/** How long the bar takes to fall from any level to silence. */
internal const val DECAY_MS = 1000
