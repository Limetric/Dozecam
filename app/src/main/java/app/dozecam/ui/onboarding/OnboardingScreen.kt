package app.dozecam.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.dozecam.R

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
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.back))
                }
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("onboarding-error"),
                )
            }

            when (val step = state.step) {
                OnboardingStep.Form -> ConsoleForm(
                    state = state,
                    onHost = onHost,
                    onUsername = onUsername,
                    onPassword = onPassword,
                    onConnect = onConnect,
                )

                OnboardingStep.Connecting -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.onboarding_connecting))
                }

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

                OnboardingStep.Importing -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.onboarding_importing))
                }

                is OnboardingStep.Done -> Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_done, step.importedCount),
                        modifier = Modifier.testTag("onboarding-done"),
                    )
                    Button(onClick = onClose) {
                        Text(stringResource(R.string.onboarding_finish))
                    }
                }
            }
        }
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
    Text(stringResource(R.string.onboarding_intro))
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
    Text(
        text = stringResource(R.string.onboarding_account_hint),
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        onClick = onConnect,
        enabled = state.canConnect,
        modifier = Modifier.testTag("connect-button"),
    ) {
        Text(stringResource(R.string.connect))
    }
}

@Composable
private fun FingerprintPrompt(
    fingerprint: String,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.fingerprint_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(R.string.fingerprint_explanation))
        Text(
            text = fingerprint,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("fingerprint-value"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("fingerprint-confirm"),
            ) {
                Text(stringResource(R.string.fingerprint_trust))
            }
            OutlinedButton(onClick = onReject) {
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pick_cameras_title),
            style = MaterialTheme.typography.titleMedium,
        )
        if (cameras.isEmpty()) {
            Text(stringResource(R.string.pick_cameras_empty))
        }
        cameras.forEach { camera ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = camera.id in selectedIds,
                    onCheckedChange = { onToggle(camera.id) },
                    modifier = Modifier.testTag("camera-pick-${camera.name}"),
                )
                Column {
                    Text(camera.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = camera.detail,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Button(
            onClick = onImport,
            enabled = selectedIds.isNotEmpty(),
            modifier = Modifier.testTag("import-button"),
        ) {
            Text(stringResource(R.string.import_cameras))
        }
    }
}
