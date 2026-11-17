package com.denia.jmreader

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.qmdeve.liquidglass.widget.LiquidGlassView

/** Native library component behind Compose content; input remains with Compose. */
internal class QmGlassHost(context: Context) : FrameLayout(context) {
    private val source = ComposeGlassSource(context)
    private val glass = LiquidGlassView(context)
    private var parameters: List<Float>? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isFocusable = false
        clipChildren = false
        // The library calls source.draw() explicitly. It must not appear in the host.
        source.visibility = INVISIBLE
        addView(source, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(glass, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        glass.bind(source)
    }

    fun update(backdrop: GlassBackdrop, density: Density, direction: LayoutDirection,
               radius: Float, red: Float, green: Float, blue: Float, alpha: Float,
               blurRadius: Float, refractionHeight: Float, refractionOffset: Float,
               dispersion: Float) {
        source.backdrop = backdrop
        source.density = density
        source.direction = direction
        val values = listOf(radius, red, green, blue, alpha, density.density, blurRadius,
            refractionHeight, refractionOffset, dispersion)
        if (parameters == values) return
        parameters = values
        glass.setCornerRadius(radius)
        // Configure optical parameters exclusively through the library's public API.
        // Height and offset use pixels; blur is the library's raw pixel value, not dp.
        glass.setRefractionHeight(refractionHeight)
        glass.setRefractionOffset(refractionOffset)
        glass.setDispersion(dispersion)
        glass.setBlurRadius(blurRadius)
        glass.setTintColorRed(red)
        glass.setTintColorGreen(green)
        glass.setTintColorBlue(blue)
        glass.setTintAlpha(alpha)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    fun release() {
        glass.bind(null)
        source.backdrop = null
    }
}

/** Adapts a recorded Compose background to the ViewGroup expected by bind().
 * Each source has the glass size, so the library records only that panel's crop.
 * Screen coordinates work for both the activity and separate dialog windows. */
internal class ComposeGlassSource(context: Context) : ViewGroup(context) {
    var backdrop: GlassBackdrop? = null
    var density: Density = Density(context.resources.displayMetrics.density)
    var direction = LayoutDirection.Ltr
    private val drawScope = CanvasDrawScope()
    private val location = IntArray(2)
    private var reported = false

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit

    override fun dispatchDraw(canvas: Canvas) {
        val sampled = backdrop ?: return
        if (!sampled.ready || width == 0 || height == 0 || !canvas.isHardwareAccelerated) return
        getLocationOnScreen(location)
        val offset = Offset(location[0].toFloat(), location[1].toFloat()) - sampled.origin
        drawScope.draw(density, direction, androidx.compose.ui.graphics.Canvas(canvas),
            Size(width.toFloat(), height.toFloat())) {
            translate(-offset.x, -offset.y) { drawLayer(sampled.layer) }
        }
        if (!reported && Build.VERSION.SDK_INT >= 33) {
            android.util.Log.d("JmGlass", "QmDeve LiquidGlassView sampling ready: ${width}x${height}")
            reported = true
        }
    }
}
