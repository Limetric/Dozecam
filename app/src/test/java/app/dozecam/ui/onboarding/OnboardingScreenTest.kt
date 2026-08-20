package app.dozecam.ui.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
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
        onClose: () -> Unit = {},
        onFinish: () -> Unit = {},
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
                    onClose = onClose,
                    onFinish = onFinish,
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
    fun `a changed certificate shows both fingerprints and confirms with the new one`() {
        var confirmed: String? = null
        setScreen(
            OnboardingUiState(
                step = OnboardingStep.ConfirmFingerprint("CC:DD", replacing = "AA:BB"),
            ),
            onConfirmFingerprint = { confirmed = it },
        )

        composeRule.onNodeWithTag("fingerprint-previous").performScrollTo()
            .assertTextEquals("AA:BB")
        composeRule.onNodeWithTag("fingerprint-value").performScrollTo()
            .assertTextEquals("CC:DD")
        composeRule.onNodeWithTag("fingerprint-confirm").performScrollTo().performClick()

        assertEquals("CC:DD", confirmed)
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

    @Test
    fun `finishing is distinct from backing out`() {
        var finished = false
        var closed = false
        setScreen(
            OnboardingUiState(step = OnboardingStep.Done(importedCount = 2)),
            onClose = { closed = true },
            onFinish = { finished = true },
        )

        composeRule.onNodeWithTag("onboarding-finish").performScrollTo().performClick()

        // Finishing arms monitoring; abandoning the flow must not, so the two
        // cannot share a callback.
        assertEquals(true, finished)
        assertEquals(false, closed)
    }

    @Test
    fun `backing out of a completed import does not count as finishing`() {
        var finished = false
        var closed = false
        setScreen(
            OnboardingUiState(step = OnboardingStep.Done(importedCount = 2)),
            onClose = { closed = true },
            onFinish = { finished = true },
        )

        composeRule.onNodeWithTag("onboarding-close").performClick()

        assertEquals(true, closed)
        assertEquals(false, finished)
    }
}
