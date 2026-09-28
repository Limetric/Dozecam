package app.dozecam.ui.components

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.dozecam.R
import app.dozecam.permissions.LocalNetworkDenial
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalNetworkPermissionDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun text(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    @Test
    fun `a refusal Android will revisit offers the prompt again`() {
        var allowed = 0
        composeRule.setContent {
            DozecamTheme {
                LocalNetworkPermissionDialog(
                    denial = LocalNetworkDenial.RETRIABLE,
                    onAllow = { allowed++ },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(text(R.string.local_network_allow)).assertIsDisplayed()
        composeRule.onNodeWithTag("local-network-allow").performClick()

        assertEquals(1, allowed)
    }

    @Test
    fun `a permanent refusal points at the only place left to undo it`() {
        composeRule.setContent {
            DozecamTheme {
                LocalNetworkPermissionDialog(
                    denial = LocalNetworkDenial.PERMANENT,
                    onAllow = {},
                    onDismiss = {},
                )
            }
        }

        // Asking again would do nothing: Android answers a permanent denial
        // without drawing anything, so the offer has to be Android's settings.
        composeRule.onNodeWithText(text(R.string.local_network_open_settings))
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.local_network_body_blocked))
            .assertIsDisplayed()
    }

    @Test
    fun `the explanation can be waved away`() {
        var dismissed = false
        composeRule.setContent {
            DozecamTheme {
                LocalNetworkPermissionDialog(
                    denial = LocalNetworkDenial.RETRIABLE,
                    onAllow = {},
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithTag("local-network-dismiss").performClick()

        assertTrue(dismissed)
    }
}
