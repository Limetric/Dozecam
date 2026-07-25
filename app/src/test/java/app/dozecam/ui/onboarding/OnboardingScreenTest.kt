package app.dozecam.ui.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setScreen(
        state: OnboardingUiState,
        onConnect: () -> Unit = {},
        onConfirmFingerprint: (String) -> Unit = {},
        onToggleCamera: (String) -> Unit = {},
        onImport: () -> Unit = {},
    ) {
        composeRule.setContent {
            DozecamTheme {
                OnboardingScreen(
                    state = state,
                    onHost = {},
                    onUsername = {},
                    onPassword = {},
                    onConnect = onConnect,
                    onConfirmFingerprint = onConfirmFingerprint,
                    onRejectFingerprint = {},
                    onToggleCamera = onToggleCamera,
                    onImport = onImport,
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun `connect is gated on a complete form`() {
        setScreen(OnboardingUiState(host = "192.168.1.1", username = "user", password = ""))

        composeRule.onNodeWithTag("connect-button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `complete form can connect`() {
        var connected = false
        setScreen(
            OnboardingUiState(host = "192.168.1.1", username = "user", password = "pw"),
            onConnect = { connected = true },
        )

        composeRule.onNodeWithTag("connect-button").performScrollTo().performClick()

        assertEquals(true, connected)
    }

    @Test
    fun `fingerprint prompt confirms with the shown fingerprint`() {
        var confirmed: String? = null
        setScreen(
            OnboardingUiState(step = OnboardingStep.ConfirmFingerprint("AA:BB")),
            onConfirmFingerprint = { confirmed = it },
        )

        composeRule.onNodeWithTag("fingerprint-confirm").performScrollTo().performClick()

        assertEquals("AA:BB", confirmed)
    }

    @Test
    fun `camera picker toggles and imports`() {
        val camera = DiscoveredCamera(id = "cam1", name = "Nursery", detail = "Medium")
        var toggled: String? = null
        setScreen(
            OnboardingUiState(
                step = OnboardingStep.PickCameras(listOf(camera)),
                selectedCameraIds = setOf("cam1"),
            ),
            onToggleCamera = { toggled = it },
        )

        composeRule.onNodeWithTag("camera-pick-Nursery").performScrollTo().performClick()
        composeRule.onNodeWithTag("import-button").performScrollTo().assertIsEnabled()

        assertEquals("cam1", toggled)
    }

    @Test
    fun `import is disabled with nothing selected`() {
        val camera = DiscoveredCamera(id = "cam1", name = "Nursery", detail = "Medium")
        setScreen(
            OnboardingUiState(
                step = OnboardingStep.PickCameras(listOf(camera)),
                selectedCameraIds = emptySet(),
            ),
        )

        composeRule.onNodeWithTag("import-button").performScrollTo().assertIsNotEnabled()
    }
}
