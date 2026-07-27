package app.dozecam.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Shapes for the segmented-group look: big outer corners, small inner ones. */
val GroupSingleShape = RoundedCornerShape(24.dp)
val GroupTopShape =
    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 4.dp, bottomEnd = 4.dp)
val GroupMiddleShape = RoundedCornerShape(4.dp)
val GroupBottomShape =
    RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp, bottomStart = 24.dp, bottomEnd = 24.dp)

/** Shape for row [index] of a [count]-row group: outer corners at the edges, small ones inside. */
fun groupShape(index: Int, count: Int): Shape = when {
    count <= 1 -> GroupSingleShape
    index == 0 -> GroupTopShape
    index == count - 1 -> GroupBottomShape
    else -> GroupMiddleShape
}

/** A section header followed by its rows, spaced 2dp apart for the segmented look. */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

/**
 * One row of a [Section]: a tonal list item shaped by its position in the group.
 * A clickable row keeps the expressive press morph, so [shape] only sets the
 * resting state.
 */
@Composable
fun GroupRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    shape: Shape = GroupSingleShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = ListItemDefaults.colors(containerColor = containerColor)
    val shapes = ListItemDefaults.shapes(shape = shape)
    val supportingContent: @Composable (() -> Unit)? = supporting?.let { { Text(it) } }
    if (onClick == null) {
        ListItem(
            modifier = modifier,
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supportingContent,
            shapes = shapes,
            colors = colors,
        ) {
            Text(headline)
        }
    } else {
        ListItem(
            onClick = onClick,
            modifier = modifier,
            leadingContent = leading,
            trailingContent = trailing,
            supportingContent = supportingContent,
            shapes = shapes,
            colors = colors,
        ) {
            Text(headline)
        }
    }
}
