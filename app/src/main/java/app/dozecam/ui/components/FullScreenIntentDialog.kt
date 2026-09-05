package app.dozecam.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.dozecam.R

/**
 * Why Dozecam is about to send you to an Android settings screen.
 *
 * Full-screen-intent access is the grant with the widest gap between what it
 * costs to give and what it costs to withhold: one switch, versus an alert that
 * is posted perfectly and never seen by anyone asleep. It used to be asked for
 * by dropping the user into the system screen with no warning, in the middle of
 * arming the monitor — which is the way to have it refused.
 *
 * So the sentence comes first, and it says what the setting does rather than
 * what it is called. Whether it was actually granted is not assumed afterwards:
 * the bedtime check goes on asking, and goes on saying so if the answer is no.
 */
@Composable
fun FullScreenIntentDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.full_screen_intent_title)) },
        text = { Text(stringResource(R.string.full_screen_intent_body)) },
        confirmButton = {
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("full-screen-intent-open"),
            ) {
                Text(stringResource(R.string.full_screen_intent_open))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("full-screen-intent-dismiss"),
            ) {
                Text(stringResource(R.string.full_screen_intent_not_now))
            }
        },
        modifier = modifier,
    )
}
