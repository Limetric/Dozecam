package app.dozecam.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.monitoring.ReadinessCheck
import app.dozecam.monitoring.ReadinessFinding
import app.dozecam.monitoring.ReadinessRemedy
import app.dozecam.monitoring.ReadinessState
import app.dozecam.monitoring.problems
import app.dozecam.monitoring.worstState
import app.dozecam.ui.components.GroupRow
import app.dozecam.ui.components.ReadinessIcon
import app.dozecam.ui.components.ReadinessRow
import app.dozecam.ui.components.Section
import app.dozecam.ui.components.groupShape
import app.dozecam.ui.components.readinessContainerColor
import app.dozecam.ui.components.readinessHeadline

/**
 * The bedtime check, in settings: the one place that answers *will this wake
 * me?* before it has to.
 *
 * What is wrong is shown; what is right is offered. A checklist of eleven green
 * rows is a wall of text that buries the one red one, so the failures stand
 * alone and the passes wait behind "What was checked" — which is still there,
 * because a checklist nobody can inspect is just a reassuring word, and this
 * feature exists because reassuring words are exactly what a baby monitor
 * should not be trusted for.
 */
@Composable
fun ReadinessSection(
    findings: List<ReadinessFinding>,
    onRemedy: (ReadinessRemedy) -> Unit,
    onTestAlert: () -> Unit,
    jumpTarget: String?,
    onJumpDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nothing has been checked yet — the probe's first read is a frame or two
    // behind the screen. Absent rather than green: "Ready for tonight" is the
    // one thing this section must never say before it knows.
    if (findings.isEmpty()) return

    var showingAll by rememberSaveable { mutableStateOf(false) }
    val problems = findings.problems()
    // Checked, and found to be fine. A masked check is neither: it stood aside
    // because something it depends on had already failed, and rendering it here
    // would put a green tick beside "the sound alert is switched on in Android"
    // over a channel nobody has looked at. A card that exists to stop a monitor
    // making claims it cannot support does not get to make one.
    val passes = findings.filter { it.state == ReadinessState.PASS && !it.masked }

    Section(title = stringResource(R.string.section_readiness), modifier = modifier) {
        JumpTarget(
            id = SettingIds.READINESS,
            jumpTarget = jumpTarget,
            onJumpDone = onJumpDone,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The verdict first, and always — including when everything
                // passes, which is the answer somebody opened this to get.
                GroupRow(
                    headline = readinessHeadline(findings),
                    supporting = stringResource(
                        if (problems.isEmpty()) {
                            R.string.readiness_ready_summary
                        } else {
                            R.string.readiness_problems_summary
                        },
                    ),
                    shape = groupShape(0, problems.size + 1),
                    containerColor = readinessContainerColor(findings.worstState()),
                    leading = { ReadinessIcon(findings.worstState()) },
                    modifier = Modifier.testTag("readiness-summary"),
                )
                problems.forEachIndexed { index, finding ->
                    ReadinessRow(
                        finding = finding,
                        onRemedy = onRemedy,
                        shape = groupShape(index + 1, problems.size + 1),
                    )
                }
            }
        }

        // Deliberately below the failures rather than folded in with them: the
        // rows that pass are evidence, not work, and nobody fixing something at
        // bedtime should have to scroll past them.
        TextButton(
            onClick = { showingAll = !showingAll },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.testTag("readiness-show-checks"),
        ) {
            Text(
                stringResource(
                    if (showingAll) R.string.readiness_hide_checks
                    else R.string.readiness_show_checks,
                ),
            )
        }
        AnimatedVisibility(visible = showingAll) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                passes.forEachIndexed { index, finding ->
                    ReadinessRow(
                        finding = finding,
                        onRemedy = onRemedy,
                        shape = groupShape(index, passes.size),
                    )
                }
            }
        }

        TestAlertRow(
            // Two things have to be true before a test can raise anything: the
            // monitor raises it, and the alerts switch is what lets it reach
            // anyone. Whichever is missing is named, rather than greyed out in
            // silence — both are rows directly above this one, and a disabled
            // button that does not say why is how a person concludes the
            // feature is broken.
            blockedBy = TEST_PRECONDITIONS.firstOrNull { check ->
                findings.any { it.check == check && it.state != ReadinessState.PASS }
            },
            onTestAlert = onTestAlert,
        )
    }
}

/**
 * The button that fires the real thing.
 *
 * Behind a question, because it is designed to be startling: at whatever hour
 * it is pressed it lights the screen, sounds the alarm and vibrates. The
 * question is the part that makes it safe to put next to a checklist people
 * poke at.
 */
@Composable
private fun TestAlertRow(
    blockedBy: ReadinessCheck?,
    onTestAlert: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    GroupRow(
        headline = stringResource(R.string.readiness_test),
        supporting = stringResource(
            when (blockedBy) {
                null -> R.string.readiness_test_summary
                ReadinessCheck.ALERTS_ON -> R.string.readiness_test_unavailable_alerts_off
                else -> R.string.readiness_test_unavailable
            },
        ),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        leading = {
            Icon(painter = painterResource(R.drawable.ic_bedtime), contentDescription = null)
        },
        onClick = if (blockedBy == null) ({ confirming = true }) else null,
        modifier = Modifier.testTag("readiness-test"),
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.readiness_test_title)) },
            text = { Text(stringResource(R.string.readiness_test_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onTestAlert()
                    },
                    modifier = Modifier.testTag("readiness-test-confirm"),
                ) {
                    Text(stringResource(R.string.readiness_test_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.readiness_test_cancel))
                }
            },
        )
    }
}

/**
 * What has to be true before a test alert can raise anything, in the order
 * worth naming: without the monitor there is nothing to raise it, and with
 * alerts off [app.dozecam.monitoring.MonitoringService] deliberately raises
 * nothing at all.
 */
private val TEST_PRECONDITIONS = listOf(ReadinessCheck.MONITORING, ReadinessCheck.ALERTS_ON)
