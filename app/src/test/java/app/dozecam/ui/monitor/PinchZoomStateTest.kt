package app.dozecam.ui.monitor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pinch arithmetic on its own: what a gesture does to the transform, and
 * where the picture's edges stop it. Screen 1000×800 throughout, so the two
 * axes cannot silently swap without a test noticing.
 */
class PinchZoomStateTest {

    private val bounds = Size(1000f, 800f)
    private val state = PinchZoomState().apply { viewportChanged(bounds) }

    private fun assertTransform(scale: Float, offset: Offset) {
        assertEquals(scale, state.scale, 0.001f)
        assertEquals(offset.x, state.offset.x, 0.001f)
        assertEquals(offset.y, state.offset.y, 0.001f)
    }

    @Test
    fun `starts on the whole picture`() {
        assertTransform(1f, Offset.Zero)
    }

    @Test
    fun `a centred pinch zooms in place`() {
        state.transform(centroid = bounds.center, pan = Offset.Zero, zoom = 2f)

        assertTransform(2f, Offset.Zero)
    }

    @Test
    fun `the point under the fingers stays under the fingers`() {
        val fingers = Offset(250f, 200f)
        state.transform(centroid = fingers, pan = Offset.Zero, zoom = 2f)

        // Doubling around (250, 200): the picture point that was there started
        // there too, so after scaling it must be pushed back by its own new
        // distance from the centre — centre + (fingers − centre) × 2 + offset
        // lands on the fingers again only with this offset.
        assertTransform(2f, Offset(250f, 200f))
    }

    @Test
    fun `zooming out stops at the whole picture`() {
        state.transform(centroid = Offset(250f, 200f), pan = Offset.Zero, zoom = 2f)
        state.transform(centroid = bounds.center, pan = Offset.Zero, zoom = 0.25f)

        // Not just scale 1: the pan the zoom-in produced has to be gone too,
        // or the "whole" picture would come back sitting off screen.
        assertTransform(1f, Offset.Zero)
    }

    @Test
    fun `zooming in stops at the maximum`() {
        state.transform(centroid = bounds.center, pan = Offset.Zero, zoom = 100f)

        assertTransform(5f, Offset.Zero)
    }

    @Test
    fun `panning stops where the picture's edge meets the screen's`() {
        state.transform(centroid = bounds.center, pan = Offset.Zero, zoom = 2f)
        state.transform(centroid = bounds.center, pan = Offset(10_000f, -10_000f), zoom = 1f)

        // With the picture's shape unknown it is assumed to fill the tile, so
        // at double size there is exactly half a screen of picture beyond each
        // edge to pull in, per axis.
        assertTransform(2f, Offset(500f, -400f))
    }

    @Test
    fun `panning the whole picture goes nowhere`() {
        state.transform(centroid = bounds.center, pan = Offset(300f, 300f), zoom = 1f)

        assertTransform(1f, Offset.Zero)
    }

    @Test
    fun `a letterboxed picture pans only as far as its own edges`() {
        // Twice as wide as tall on a 1000×800 screen: fitted 1000×500, with
        // 150px of letterbox bar above and below.
        state.pictureChanged(2f)
        state.transform(centroid = bounds.center, pan = Offset.Zero, zoom = 2f)
        state.transform(centroid = bounds.center, pan = Offset(10_000f, 10_000f), zoom = 1f)

        // Sideways the doubled picture overflows by 1000, so 500 each way; up
        // and down it spans 1000 against an 800 screen, leaving 100 each way —
        // not the 800 a tile-sized clamp would have allowed into the black.
        assertTransform(2f, Offset(500f, 100f))
    }

    @Test
    fun `a letterboxed picture too small to overflow stays centred`() {
        state.pictureChanged(2f)
        // At 1.2× the fitted 1000×500 picture is still only 600 tall on an
        // 800-tall screen: nothing to pan to vertically.
        state.transform(centroid = bounds.center, pan = Offset.Zero, zoom = 1.2f)
        state.transform(centroid = bounds.center, pan = Offset(10_000f, 10_000f), zoom = 1f)

        assertTransform(1.2f, Offset(100f, 0f))
    }

    @Test
    fun `learning the picture's shape reins a pan back in`() {
        state.transform(centroid = bounds.center, pan = Offset.Zero, zoom = 2f)
        state.transform(centroid = bounds.center, pan = Offset(0f, 10_000f), zoom = 1f)

        // The stream declares itself 2:1 only now — the pan to 400 was into
        // what turn out to be letterbox bars, and 100 is as far as the picture
        // itself goes.
        state.pictureChanged(2f)

        assertTransform(2f, Offset(0f, 100f))
    }

    @Test
    fun `rotating the screen reins a pan back in`() {
        state.transform(centroid = bounds.center, pan = Offset.Zero, zoom = 2f)
        state.transform(centroid = bounds.center, pan = Offset(10_000f, 10_000f), zoom = 1f)

        // The activity absorbs rotation without recreating, so the state lives
        // on into a viewport where yesterday's offset could hold the picture
        // entirely off screen — and no gesture may come to fix it.
        state.viewportChanged(Size(800f, 1000f))

        // Sideways the limit shrank from 500 to 400 and pulls the pan back in;
        // the vertical pan of 400 still fits the taller screen and is kept.
        assertTransform(2f, Offset(400f, 400f))
    }

    @Test
    fun `reset returns to the whole picture`() {
        state.transform(centroid = Offset(250f, 200f), pan = Offset(40f, 40f), zoom = 3f)

        state.reset()

        assertTransform(1f, Offset.Zero)
    }
}
