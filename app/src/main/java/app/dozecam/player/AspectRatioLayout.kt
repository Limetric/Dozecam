package app.dozecam.player

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Sizes its child to the video's own aspect ratio, letterboxing inside the
 * space it is given. A bare SurfaceView at MATCH_PARENT stretches the picture
 * to the tile — a 16:9 nursery squeezed into a portrait phone — which is never
 * what anyone wants from a camera.
 *
 * Deliberately never crops: seeing the whole frame with bars matters more than
 * filling the tile when the thing you are looking for might be at the edge.
 */
class AspectRatioLayout(context: Context) : FrameLayout(context) {

    /** Width/height of the decoded video, or 0 while it is still unknown. */
    var aspectRatio: Float = 0f
        set(value) {
            if (abs(field - value) < TOLERANCE) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val ratio = aspectRatio
        val width = measuredWidth
        val height = measuredHeight
        if (ratio <= 0f || width == 0 || height == 0) return

        val boxRatio = width.toFloat() / height.toFloat()
        // Fit: the dimension that would overflow is the one that gets pulled in.
        val (childWidth, childHeight) = if (boxRatio > ratio) {
            (height * ratio).toInt() to height
        } else {
            width to (width / ratio).toInt()
        }
        measureChildren(
            MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (aspectRatio <= 0f) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        // Centre the letterboxed child; FrameLayout's gravity handling would do
        // this too, but only for children whose params we control.
        for (index in 0 until childCount) {
            val child: View = getChildAt(index)
            if (child.visibility == GONE) continue
            val x = (measuredWidth - child.measuredWidth) / 2
            val y = (measuredHeight - child.measuredHeight) / 2
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    private companion object {
        /** Below this, a ratio change is invisible and not worth a layout pass. */
        const val TOLERANCE = 0.001f
    }
}
