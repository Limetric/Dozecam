package app.dozecam.ui.monitor

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * How opaque chrome over video has to be.
 *
 * A surface role is only legible while enough of it survives whatever is
 * playing underneath: a nursery lit by a lamp, or a daytime window blown out to
 * white. Enough of the container shows through to read as glass over the
 * picture, and not so little that the video decides the contrast.
 */
internal const val VIDEO_OVERLAY_ALPHA = 0.85f

/**
 * The container every piece of chrome over a camera picture sits in — status,
 * names, meters, talk-back, the return countdown.
 *
 * Overlays used to paint their own black scrim and write on it in white, which
 * looked the same under every theme and belonged to none of them. They are
 * surfaces now: the colour scheme decides how they read, so the night palette
 * needs no branch of its own, and a light theme puts light chrome over the
 * video exactly as it does everywhere else in the app.
 *
 * The outline is what a translucent surface loses over a busy picture. A pale
 * pill on a pale wall has no edge without it, and an overlay whose boundary is
 * ambiguous reads as part of the room rather than as something the app is
 * saying.
 */
@Composable
internal fun VideoOverlaySurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = color.copy(alpha = VIDEO_OVERLAY_ALPHA),
        contentColor = contentColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}
