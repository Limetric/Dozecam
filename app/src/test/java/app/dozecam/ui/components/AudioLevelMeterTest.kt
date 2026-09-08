package app.dozecam.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioLevelMeterTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var level by mutableFloatStateOf(0f)

    private fun show() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            DozecamTheme { AudioLevelMeter(level = level, threshold = 0.1f) }
        }
        composeRule.mainClock.advanceTimeByFrame()
    }

    /** The bar's fraction as the progress indicator reports it. */
    private fun shown(): Float {
        val node = composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .fetchSemanticsNode()
        return node.config[SemanticsProperties.ProgressBarRangeInfo].current
    }

    private fun set(value: Float) {
        level = value
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun `a louder level shows at once`() {
        show()

        set(0.3f)

        assertEquals(0.6f, shown(), 0.001f)
    }

    @Test
    fun `a sound lingers after the room goes quiet`() {
        show()
        set(0.3f)

        set(0f)
        composeRule.mainClock.advanceTimeBy(DECAY_MS / 2L)

        val midway = shown()
        assertTrue("still visible halfway through the fade, was $midway", midway > 0.1f)
        assertTrue("but falling, was $midway", midway < 0.6f)
    }

    @Test
    fun `the fade is over about a second after the sound`() {
        show()
        set(0.3f)

        set(0f)
        composeRule.mainClock.advanceTimeBy(DECAY_MS + 100L)

        assertEquals(0f, shown(), 0.001f)
    }

    @Test
    fun `a quiet sound fades in the same time as a loud one`() {
        show()
        set(0.1f)

        set(0f)
        composeRule.mainClock.advanceTimeBy(DECAY_MS / 2L)

        val midway = shown()
        assertTrue("still visible halfway through the fade, was $midway", midway > 0.05f)
    }

    @Test
    fun `a rise during the fade takes the new level at once`() {
        show()
        set(0.3f)
        set(0f)
        composeRule.mainClock.advanceTimeBy(DECAY_MS / 2L)

        set(0.4f)

        assertEquals(0.8f, shown(), 0.001f)
    }

    @Test
    fun `a lower target mid-fall keeps the pace rather than restarting it`() {
        show()
        set(0.3f)
        set(0.05f)
        composeRule.mainClock.advanceTimeBy(DECAY_MS / 3L)

        // The floor settling lower part-way through must not stretch the fade.
        set(0f)
        composeRule.mainClock.advanceTimeBy(DECAY_MS * 2L / 3 + 100L)

        assertEquals(0f, shown(), 0.001f)
    }

    @Test
    fun `a room that settled quiet after a loud sound still fades slowly`() {
        show()
        set(0.3f)
        set(0.05f)
        composeRule.mainClock.advanceTimeBy(DECAY_MS + 100L)

        set(0f)
        composeRule.mainClock.advanceTimeBy(DECAY_MS / 2L)

        val midway = shown()
        assertTrue("still visible halfway through the fade, was $midway", midway > 0.03f)
    }
}
