package app.dozecam.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.dozecam.ui.components.GroupRow
import app.dozecam.ui.components.GroupSingleShape
import kotlinx.coroutines.delay

/** How long a jumped-to row stays marked before the emphasis lets go. */
private const val JUMP_FLASH_MS = 1600L

/**
 * Wraps the row a search result can jump to. When [jumpTarget] names this row,
 * the screen scrolls it into view and a border flashes around it — long enough
 * to answer "which one did I just land on", short enough not to nag.
 */
@Composable
fun JumpTarget(
    id: String,
    jumpTarget: String?,
    onJumpDone: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = GroupSingleShape,
    content: @Composable () -> Unit,
) {
    val active = jumpTarget == id
    val requester = remember { BringIntoViewRequester() }
    val borderAlpha by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "jump-flash",
    )
    LaunchedEffect(active) {
        if (active) {
            requester.bringIntoView()
            delay(JUMP_FLASH_MS)
            onJumpDone()
        }
    }
    Box(
        modifier = modifier
            .bringIntoViewRequester(requester)
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = borderAlpha),
                shape = shape,
            )
            .then(if (active) Modifier.testTag("jump-active-$id") else Modifier),
    ) {
        content()
    }
}

@Composable
internal fun SettingSwitchRow(
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
