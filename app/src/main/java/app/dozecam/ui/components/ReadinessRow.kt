package app.dozecam.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import app.dozecam.R
import app.dozecam.monitoring.ReadinessFinding
import app.dozecam.monitoring.ReadinessRemedy
import app.dozecam.monitoring.ReadinessState

/**
 * One line of the bedtime check: what is true, why it matters, and the button
 * that fixes it.
 *
 * The state is carried by an icon and by the row's own colour rather than by a
 * word, because the two things a reader does with this list are opposite: they
 * skim it for anything not green, then read the one row that is. So a failing
 * row is loud enough to find without reading, and carries its whole explanation
 * for when it is.
 */
@Composable
fun ReadinessRow(
    finding: ReadinessFinding,
    onRemedy: (ReadinessRemedy) -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val remedyLabel = readinessRemedyLabel(finding.remedy)
    GroupRow(
        headline = readinessSentence(finding),
        supporting = readinessReason(finding),
        shape = shape,
        containerColor = readinessContainerColor(finding.state),
        leading = { ReadinessIcon(finding.state) },
        trailing = if (remedyLabel == null) {
            null
        } else {
            {
                TextButton(
                    onClick = { onRemedy(finding.remedy) },
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.testTag("readiness-remedy-${finding.check.name}"),
                ) {
                    Text(remedyLabel)
                }
            }
        },
        modifier = modifier.testTag("readiness-${finding.check.name}"),
    )
}

/**
 * A tick, a warning triangle, or the same "blocked" mark the viewer's "not
 * monitoring" badge wears — deliberately the same, because it means the same
 * thing in both places: this will not happen tonight.
 */
@Composable
fun ReadinessIcon(state: ReadinessState, modifier: Modifier = Modifier) {
    when (state) {
        ReadinessState.PASS -> Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
        ReadinessState.WARN -> Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = modifier,
        )
        ReadinessState.FAIL -> Icon(
            painter = painterResource(R.drawable.ic_do_not_disturb),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = modifier,
        )
    }
}

@Composable
fun readinessContainerColor(state: ReadinessState): Color = when (state) {
    ReadinessState.PASS -> MaterialTheme.colorScheme.surfaceContainer
    ReadinessState.WARN -> MaterialTheme.colorScheme.tertiaryContainer
    ReadinessState.FAIL -> MaterialTheme.colorScheme.errorContainer
}
