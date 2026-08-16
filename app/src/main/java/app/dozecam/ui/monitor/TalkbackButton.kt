package app.dozecam.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.audio.talkback.TalkbackAvailability
import app.dozecam.ui.theme.LocalNightTheme

/**
 * Hold to talk into the room on screen.
 *
 * Half duplex, and deliberately so. The viewer is playing the nursery's own
 * sound through the same phone, so talking while it plays closes a loop through
 * the room — phone, camera speaker, camera microphone, phone. Holding a button
 * settles that without fighting physics, and it is how the official app behaves
 * too. Echo cancellation sits on top rather than in its place.
 *
 * The control is never inert. Talk-back can fail in ways that look exactly like
 * a bug — a camera whose picture arrives perfectly while its speaker cannot be
 * reached at all — so a state that cannot talk still renders, and pressing it
 * says why rather than doing nothing.
 */
@Composable
internal fun TalkbackButton(
    availability: TalkbackAvailability,
    talking: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onExplain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nothing to show and nothing to explain: a camera still being resolved has
    // no answer yet, and a control that flickered into existence a moment later
    // would be worse than one that arrives once.
    if (availability is TalkbackAvailability.Resolving) return

    val night = LocalNightTheme.current
    val colorScheme = MaterialTheme.colorScheme
    val ready = availability is TalkbackAvailability.Ready

    val content = when {
        talking -> colorScheme.onPrimary
        ready && night -> colorScheme.primary
        ready -> Color.White
        // Dimmed rather than hidden: the feature exists on this screen even
        // when this camera cannot use it, and hiding it invites the question
        // again on every visit.
        else -> Color.White.copy(alpha = 0.5f)
    }
    val background =
        if (talking) colorScheme.primary else colorScheme.scrim.copy(alpha = 0.55f)

    val description = stringResource(
        when {
            talking -> R.string.talkback_talking
            ready -> R.string.talkback_hold
            else -> R.string.talkback_unavailable
        },
    )

    val gestures = if (ready) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    onPress()
                    // Returns whether the finger lifted inside the control or
                    // was cancelled; either way the press is over and the tail
                    // has to go out, so both end it the same way.
                    tryAwaitRelease()
                    onRelease()
                },
            )
        }
    } else {
        Modifier.clickable(onClick = onExplain)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(background, CircleShape)
            .then(gestures)
            .padding(horizontal = if (talking) 14.dp else 8.dp, vertical = 8.dp)
            .semantics { contentDescription = description }
            .testTag("talkback-button"),
    ) {
        Icon(
            painter = painterResource(if (ready || talking) R.drawable.ic_mic else R.drawable.ic_mic_off),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(20.dp),
        )
        if (talking) {
            Text(
                text = stringResource(R.string.talkback_talking),
                style = MaterialTheme.typography.labelLarge,
                color = content,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .testTag("talkback-talking"),
            )
        }
    }
}

/**
 * Why the button cannot be held, shown where the inactivity notice shows.
 *
 * Every reason is a sentence rather than a shrug, because the two failures that
 * actually happen — a camera on its own VLAN, and a microphone never granted —
 * are both invisible from the picture, and a user with no explanation would
 * reasonably conclude the app is broken.
 */
@Composable
internal fun TalkbackNotice(
    availability: TalkbackAvailability,
    modifier: Modifier = Modifier,
) {
    val reason = availability.explanation() ?: return
    val night = LocalNightTheme.current

    Text(
        text = stringResource(reason),
        style = MaterialTheme.typography.labelLarge,
        color = if (night) MaterialTheme.colorScheme.primary else Color.White,
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                MaterialTheme.shapes.large,
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("talkback-notice"),
    )
}

private fun TalkbackAvailability.explanation(): Int? = when (this) {
    TalkbackAvailability.Ready, TalkbackAvailability.Resolving -> null
    TalkbackAvailability.NeedsPermission -> R.string.talkback_why_permission
    TalkbackAvailability.NeedsUnlock -> R.string.talkback_why_unlock
    TalkbackAvailability.Unreachable -> R.string.talkback_why_unreachable
    is TalkbackAvailability.Unsupported -> when (reason) {
        TalkbackAvailability.Reason.NO_SPEAKER -> R.string.talkback_why_no_speaker
        TalkbackAvailability.Reason.NO_API_KEY -> R.string.talkback_why_no_api_key
        TalkbackAvailability.Reason.CODEC -> R.string.talkback_why_codec
        TalkbackAvailability.Reason.RATE -> R.string.talkback_why_rate
        TalkbackAvailability.Reason.ADDRESS -> R.string.talkback_why_address
    }
}
