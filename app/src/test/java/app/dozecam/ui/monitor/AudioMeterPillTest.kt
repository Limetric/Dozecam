package app.dozecam.ui.monitor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.dozecam.ui.components.DECAY_MS
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioMeterPillTest {

    @get:Rule
    val composeRule = createComposeRule()

    private data class Reading(val id: String, val level: Float)

    private var reading by mutableStateOf(Reading("a", 0.3f))

    private fun shown(): Float = composeRule
        .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
        .fetchSemanticsNode()
        .config[SemanticsProperties.ProgressBarRangeInfo]
        .current

    @Test
    fun `switching rooms does not carry the old room's fading sound over`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            DozecamTheme {
                AudioMeterPill(
                    cameraId = reading.id,
                    // The same name on purpose: two rooms may share one, and
                    // the meter must still tell them apart.
                    cameraName = "Nursery",
                    level = reading.level,
                    threshold = 0.1f,
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        reading = Reading("b", 0f)
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(DECAY_MS / 4L)

        assertEquals(0f, shown(), 0.001f)
    }
}
