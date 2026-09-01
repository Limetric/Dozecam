package app.dozecam.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.dozecam.R
import app.dozecam.permissions.LocalNetworkDenial

/**
 * Why the monitor did not start. Refusing local-network access leaves nothing
 * on screen to read as a cause — the switch simply returns to off — and a
 * permanent refusal is answered by Android instantly, with no prompt drawn at
 * all, so the tap looks like a control that does nothing.
 *
 * The action follows the refusal: one Android will ask about again is asked
 * again, and one it will not is handed to the settings screen that can still
 * change the answer.
 */
@Composable
fun LocalNetworkPermissionDialog(
    denial: LocalNetworkDenial,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permanent = denial == LocalNetworkDenial.PERMANENT
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.local_network_title)) },
        text = {
            Text(
                stringResource(
                    if (permanent) R.string.local_network_body_blocked
                    else R.string.local_network_body,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onAllow,
                modifier = Modifier.testTag("local-network-allow"),
            ) {
                Text(
                    stringResource(
                        if (permanent) R.string.local_network_open_settings
                        else R.string.local_network_allow,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("local-network-dismiss"),
            ) {
                Text(stringResource(R.string.local_network_not_now))
            }
        },
        modifier = modifier.testTag("local-network-dialog"),
    )
}
