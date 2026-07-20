package app.dozecam.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `main screen shows the placeholder title`() {
        composeRule.setContent {
            DozecamTheme {
                MainScreen()
            }
        }

        composeRule.onNodeWithText("Dozecam").assertExists()
    }
}
