package com.precorderone.ui

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import kotlin.math.roundToInt

/**
 * SurfaceView that keeps the aspect ratio of the camera stream instead of stretching it over the whole screen.
 * Placed with layout_gravity="center", the preview is letterboxed and shows exactly the recorded frame.
 */
class AspectFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr) {
    private var aspect = 0f

    /** Width / height of the content; 0 fills the parent. */
    fun setAspectRatio(widthOverHeight: Float) {
        if (widthOverHeight == aspect) return
        aspect = widthOverHeight
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (aspect <= 0f || width == 0 || height == 0) {
            setMeasuredDimension(width, height)
        } else if (width / aspect <= height) {
            setMeasuredDimension(width, (width / aspect).roundToInt())
        } else {
            setMeasuredDimension((height * aspect).roundToInt(), height)
        }
    }
}
