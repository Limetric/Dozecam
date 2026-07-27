package app.dozecam.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.ui.components.GroupRow
import app.dozecam.ui.components.Section
import app.dozecam.ui.components.groupShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onHost: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConnect: () -> Unit,
    onConfirmFingerprint: (String) -> Unit,
    onRejectFingerprint: () -> Unit,
    onToggleCamera: (String) -> Unit,
    onImport: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.onboarding_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onClose,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.testTag("onboarding-close"),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .padding(16.dp)
                            .testTag("onboarding-error"),
                    )
                }
            }

            when (val step = state.step) {
                OnboardingStep.Form -> ConsoleForm(
                    state = state,
                    onHost = onHost,
                    onUsername = onUsername,
                    onPassword = onPassword,
                    onConnect = onConnect,
                )

                OnboardingStep.Connecting ->
                    ProgressStep(stringResource(R.string.onboarding_connecting))

                is OnboardingStep.ConfirmFingerprint -> FingerprintPrompt(
                    fingerprint = step.fingerprint,
                    onConfirm = { onConfirmFingerprint(step.fingerprint) },
                    onReject = onRejectFingerprint,
                )

                is OnboardingStep.PickCameras -> CameraPicker(
                    cameras = step.cameras,
                    selectedIds = state.selectedCameraIds,
                    onToggle = onToggleCamera,
                    onImport = onImport,
                )

                OnboardingStep.Importing ->
                    ProgressStep(stringResource(R.string.onboarding_importing))

                is OnboardingStep.Done -> Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_done, step.importedCount),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        modifier = Modifier.testTag("onboarding-done"),
                    )
                    Button(onClick = onClose, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.onboarding_finish))
                    }
                }
            }
        }
    }
}

/** Waiting on the console: the expressive loading indicator, plus what it waits for. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProgressStep(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ContainedLoadingIndicator()
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ConsoleForm(
    state: OnboardingUiState,
    onHost: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onConnect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.onboarding_intro),
            style = MaterialTheme.typography.bodyLarge,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = onHost,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("console-host"),
                    label = { Text(stringResource(R.string.console_host_label)) },
                    placeholder = { Text(stringResource(R.string.console_host_hint)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = onUsername,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("console-username"),
                    label = { Text(stringResource(R.string.console_username_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = onPassword,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("console-password"),
                    label = { Text(stringResource(R.string.console_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        }
        Text(
            text = stringResource(R.string.onboarding_account_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onConnect,
            shapes = ButtonDefaults.shapes(),
            enabled = state.canConnect,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("connect-button"),
        ) {
            Text(stringResource(R.string.connect))
        }
    }
}

/**
 * Trusting a certificate is the one irreversible step here, so it gets its own
 * tonal card rather than sitting in the run of body text.
 */
@Composable
private fun FingerprintPrompt(
    fingerprint: String,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(imageVector = Icons.Default.Lock, contentDescription = null)
                    Text(
                        text = stringResource(R.string.fingerprint_title),
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                }
                Text(
                    text = stringResource(R.string.fingerprint_explanation),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = fingerprint,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("fingerprint-value"),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onConfirm,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.testTag("fingerprint-confirm"),
            ) {
                Text(stringResource(R.string.fingerprint_trust))
            }
            OutlinedButton(onClick = onReject, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
}

@Composable
private fun CameraPicker(
    cameras: List<DiscoveredCamera>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onImport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Section(title = stringResource(R.string.pick_cameras_title)) {
            if (cameras.isEmpty()) {
                Text(
                    text = stringResource(R.string.pick_cameras_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            cameras.forEachIndexed { index, camera ->
                val checked = camera.id in selectedIds
                GroupRow(
                    headline = camera.name,
                    supporting = camera.detail,
                    shape = groupShape(index, cameras.size),
                    containerColor = if (checked) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainer
                    },
                    leading = {
                        Icon(
                            painter = painterResource(R.drawable.ic_videocam),
                            contentDescription = null,
                        )
                    },
                    trailing = {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onToggle(camera.id) },
                            modifier = Modifier.testTag("camera-pick-${camera.name}"),
                        )
                    },
                    onClick = { onToggle(camera.id) },
                )
            }
        }
        Button(
            onClick = onImport,
            shapes = ButtonDefaults.shapes(),
            enabled = selectedIds.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("import-button"),
        ) {
            Text(stringResource(R.string.import_cameras))
        }
    }
}
