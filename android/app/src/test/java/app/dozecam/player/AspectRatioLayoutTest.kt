package app.dozecam.player

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AspectRatioLayoutTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun layoutWith(ratio: Float, boxWidth: Int, boxHeight: Int): View {
        val child = View(context)
        val parent = AspectRatioLayout(context).apply {
            addView(
                child,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            aspectRatio = ratio
        }
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(boxWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(boxHeight, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, boxWidth, boxHeight)
        return child
    }

    @Test
    fun `a wide video in a tall box gets bars above and below`() {
        val child = layoutWith(ratio = 16f / 9f, boxWidth = 900, boxHeight = 900)

        assertEquals(900, child.width)
        assertEquals(506, child.height) // 900 / (16/9), rounded down
    }

    @Test
    fun `a tall video in a wide box gets bars either side`() {
        val child = layoutWith(ratio = 3f / 4f, boxWidth = 800, boxHeight = 400)

        assertEquals(300, child.width) // 400 * 3/4
        assertEquals(400, child.height)
    }

    @Test
    fun `a matching ratio fills the box exactly`() {
        val child = layoutWith(ratio = 16f / 9f, boxWidth = 1600, boxHeight = 900)

        assertEquals(1600, child.width)
        assertEquals(900, child.height)
    }

    @Test
    fun `the letterboxed picture is centred`() {
        val child = layoutWith(ratio = 16f / 9f, boxWidth = 900, boxHeight = 900)

        assertEquals((900 - child.height) / 2, child.top)
        assertEquals((900 - child.height) / 2, 900 - child.bottom)
    }

    @Test
    fun `an unknown ratio fills the box rather than collapsing`() {
        // Before the first decoded frame there is nothing to fit to; a tile
        // that measured itself to zero would flash blank on every reconnect.
        val child = layoutWith(ratio = 0f, boxWidth = 800, boxHeight = 400)

        assertEquals(800, child.width)
        assertEquals(400, child.height)
    }

    @Test
    fun `a 4 by 3 camera is never stretched to a 16 by 9 tile`() {
        val child = layoutWith(ratio = 4f / 3f, boxWidth = 1600, boxHeight = 900)

        // The whole point: 1600x900 would be a 16:9 stretch of a 4:3 picture.
        assertEquals(1200, child.width)
        assertEquals(900, child.height)
    }
}
