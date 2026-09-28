package app.dozecam.ui.monitor

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.max

/**
 * Close enough to fill the screen with a cot, far enough that the picture is
 * still a picture — an IP camera's frame turns to mush well before this.
 */
private const val MAX_ZOOM_SCALE = 5f

/**
 * Where a pinch has put the one camera filling the screen: how far in, and
 * which part of the room is under the middle of the view.
 *
 * The identity transform is the floor, never crossed: the picture can be
 * looked into but not shrunk below the screen. Panning is bounded by the
 * picture itself, not the screen — a letterboxed frame stops at its own edge,
 * and along an axis it does not yet overflow it stays centred — because a
 * zoomed view of bare letterbox black would read as a camera with something
 * wrong with it.
 */
@Stable
class PinchZoomState {

    var scale by mutableFloatStateOf(1f)
        private set

    var offset by mutableStateOf(Offset.Zero)
        private set

    /** The tile the picture is fitted into; kept for clamping between gestures. */
    private var bounds = Size.Zero

    /** Width over height of the picture itself, or null until the stream says. */
    private var pictureAspect: Float? = null

    /**
     * The tile was laid out — first composition, or a rotation the activity
     * absorbs without recreating. The transform is re-clamped at once: an
     * offset that was fine in portrait can hold the picture entirely off a
     * landscape screen, and no gesture may come to fix it.
     */
    fun viewportChanged(bounds: Size) {
        if (this.bounds == bounds) return
        this.bounds = bounds
        offset = offset.clamped(scale)
    }

    /** The stream declared its shape; the letterbox bars just moved. */
    fun pictureChanged(aspect: Float?) {
        if (pictureAspect == aspect) return
        pictureAspect = aspect
        offset = offset.clamped(scale)
    }

    /**
     * Folds one gesture update into the transform.
     *
     * The picture point under the fingers stays under the fingers: where it was
     * is recovered from the old transform, where it must end up is the centroid
     * after the pan, and the new offset is whatever closes that gap — then the
     * edges get the last word.
     */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM_SCALE)
        val fromCenter = centroid - bounds.center
        val moved = fromCenter + pan - (fromCenter - offset) * (newScale / scale)
        scale = newScale
        offset = moved.clamped(newScale)
    }

    /** Whole picture, dead centre — where every camera starts. */
    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /**
     * The rectangle the picture actually paints, before zooming: its own shape
     * fitted inside the tile, which is how both players place it. Until the
     * shape is known the whole tile stands in for it — pessimistic about bars,
     * but never able to pan the picture off the screen.
     */
    private fun picture(): Size {
        val aspect = pictureAspect ?: return bounds
        if (aspect <= 0f || bounds.width <= 0f || bounds.height <= 0f) return bounds
        return if (bounds.width / bounds.height > aspect) {
            Size(bounds.height * aspect, bounds.height)
        } else {
            Size(bounds.width, bounds.width / aspect)
        }
    }

    /**
     * Keeps the scaled picture's edges at or beyond the screen's per axis — or
     * dead centre along an axis the picture does not yet overflow. At scale 1
     * every limit is zero, so zooming all the way out is also what puts the
     * picture back exactly where it started.
     */
    private fun Offset.clamped(scale: Float): Offset {
        val picture = picture()
        val maxX = max(0f, (scale * picture.width - bounds.width) / 2f)
        val maxY = max(0f, (scale * picture.height - bounds.height) / 2f)
        return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
    }
}

/**
 * Feeds pinch and pan gestures on the receiver into [state]. Lives beside
 * `clickable` without stealing its taps: a touch that never moves is still a
 * click. [onGesture] fires on every update, because fingers on the picture are
 * someone being there, exactly as a tap is.
 */
internal fun Modifier.pinchZoom(state: PinchZoomState, onGesture: () -> Unit): Modifier =
    pointerInput(state) {
        detectTransformGestures { centroid, pan, zoom, _ ->
            state.transform(centroid, pan, zoom)
            onGesture()
        }
    }

/**
 * Puts [state]'s transform, or the identity for null, onto the view holding
 * the picture. View properties rather than a Compose layer, deliberately: the
 * picture lives in a `SurfaceView`, whose content the platform promises to
 * move and scale in step with an ancestor view's transform — the exact
 * mechanism every view-world video zoom rides on. Both default to a centre
 * pivot, so the arithmetic in [PinchZoomState] serves either unchanged.
 */
internal fun android.view.View.applyPinchZoom(state: PinchZoomState?) {
    scaleX = state?.scale ?: 1f
    scaleY = state?.scale ?: 1f
    translationX = state?.offset?.x ?: 0f
    translationY = state?.offset?.y ?: 0f
}
