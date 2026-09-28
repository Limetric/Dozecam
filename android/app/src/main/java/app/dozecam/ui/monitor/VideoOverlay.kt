package app.dozecam.ui.monitor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
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
 * The measurements every piece of chrome on the viewer shares, so that
 * whatever sits in a row sits in a line.
 *
 * Two heights, on purpose. Controls — the buttons, and any pill drawn in a row
 * with them — are Material's 40dp, which is what an icon button measures and
 * what a thumb expects. Chrome inside a grid tile is a size down: a thumbnail
 * is a picture first, and the status, name and meter it wears are captions on
 * it rather than things to press.
 *
 * Both are floors, not ceilings. Text scales with the system font size and a
 * pill that could not grow with it would cut "Reconnecting" in half for
 * exactly the reader who most needs it whole; at the default scale every
 * pill sits at its floor, which is what keeps a row in a line.
 */
internal object OverlayChrome {
    /** From the edge of the picture, or the screen, to the nearest chrome. */
    val Margin = 12.dp

    /** Between two pieces of chrome in the same row or column. */
    val Gap = 8.dp

    /** Buttons, and every pill that shares a row with one. */
    val ControlHeight = 40.dp

    /** Pills inside a grid tile. */
    val TileHeight = 32.dp

    /** The glyph inside a pill: readable at [TileHeight], not lost at [ControlHeight]. */
    val IconSize = 18.dp

    /** The pill's own inner margin on either side of its content. */
    val PillPadding = 12.dp
}

/**
 * The container every piece of chrome over a camera picture sits in — status,
 * names, meters, talk-back, the return countdown.
 *
 * The viewer uses a dark colour scheme even in system light mode. Surface
 * roles keep overlays dark while retaining wallpaper-derived accents and
 * letting the night palette dim them without a branch of its own.
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

/**
 * One line of chrome over the picture: a fully rounded [VideoOverlaySurface]
 * of at least [height] whose content sits centred in a row. Everything that
 * says one thing about a camera — its state, its name, how loud it is, whether
 * it can be heard — is one of these, so they line up with each other and with
 * the buttons beside them without each caller measuring its own.
 */
@Composable
internal fun OverlayPill(
    modifier: Modifier = Modifier,
    height: Dp = OverlayChrome.TileHeight,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    contentPadding: PaddingValues = PaddingValues(horizontal = OverlayChrome.PillPadding),
    content: @Composable RowScope.() -> Unit,
) {
    VideoOverlaySurface(
        shape = CircleShape,
        color = color,
        contentColor = contentColor,
        modifier = modifier.heightIn(min = height),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OverlayChrome.Gap),
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

/**
 * A sentence over the picture — a notice rather than a caption. Wider corners
 * than a pill because it may wrap to a second line, and the same surface so it
 * still reads as the app speaking rather than the room.
 */
@Composable
internal fun OverlayNotice(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    VideoOverlaySurface(
        modifier = modifier,
        color = color,
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
