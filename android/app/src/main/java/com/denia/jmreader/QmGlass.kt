package com.denia.jmreader

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

@Stable
class GlassBackdrop internal constructor(val layer: GraphicsLayer, internal val view: android.view.View) {
    internal var origin = Offset.Zero
    internal var ready = false
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    val view = LocalView.current
    return remember(layer, view) { GlassBackdrop(layer, view) }
}

private fun android.view.View.windowOrigin(): Offset {
    val screen = IntArray(2)
    val window = IntArray(2)
    getLocationOnScreen(screen)
    getLocationInWindow(window)
    return Offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
}

val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** Record the sampling branch independently of the glass components. */
fun Modifier.glassBackdrop(backdrop: GlassBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInWindow() + backdrop.view.windowOrigin() }
    .drawWithContent {
        val scope = this
        backdrop.layer.record(size = IntSize(size.width.roundToInt(), size.height.roundToInt())) {
            scope.drawContent()
        }
        backdrop.ready = true
        drawLayer(backdrop.layer)
    }

@Composable
fun GlassWallpaper(modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < 0.5f
    Canvas(modifier.fillMaxSize()) {
        drawRect(background)
        drawRect(Brush.radialGradient(
            listOf(Pink.copy(alpha = if (dark) 0.10f else 0.09f), Color.Transparent),
            center = Offset(size.width * 0.05f, size.height * 0.18f), radius = size.width * 1.15f))
        drawRect(Brush.radialGradient(
            listOf(Color(0xFF7C8CFF).copy(alpha = if (dark) 0.12f else 0.10f), Color.Transparent),
            center = Offset(size.width, size.height * 0.75f), radius = size.width * 1.2f))
    }
}

/** The same upstream LiquidGlassView material under every surface and navigation lens. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    color: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke? = null,
    backdrop: GlassBackdrop? = LocalGlassBackdrop.current,
    radius: Dp = 20.dp,
    prominent: Boolean = false,
    tintStrength: Float? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val accent = color != MaterialTheme.colorScheme.surface
    val alpha = (tintStrength ?: if (accent) 0.72f else if (dark) 0.22f else 0.12f) * color.alpha
    val native = Build.VERSION.SDK_INT >= 33 && backdrop != null
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.975f else 1f,
        spring(0.75f, 550f), label = "glassPress")
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    val outline = shape.createOutline(Size(panelSize.width.toFloat(), panelSize.height.toFloat()), direction, density)
    val actualRadius = if (outline is Outline.Rounded) outline.roundRect.topLeftCornerRadius.x
        else with(density) { radius.toPx() }
    val clickModifier = if (onClick != null) Modifier.clickable(
        interactionSource = interaction, indication = null, onClick = onClick) else Modifier

    Box(modifier
        .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
        .shadow(if (prominent) 12.dp else 3.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.16f))
        .clip(shape)
        .then(if (border != null) Modifier.border(border, shape) else Modifier)
        .then(clickModifier)
        .onSizeChanged { panelSize = it }
        .drawWithContent {
            // The selected library has no shader support below API 33. Keep controls legible.
            if (!native) drawRect(color.copy(alpha = if (accent) 0.9f else if (dark) 0.9f else 0.84f))
            drawContent()
        }, propagateMinConstraints = true) {
        if (native) {
            AndroidView(factory = { QmGlassHost(it) }, modifier = Modifier.matchParentSize(),
                onRelease = { it.release() }, update = {
                    it.update(backdrop!!, density, direction, actualRadius,
                        color.red, color.green, color.blue, alpha)
                })
        }
        content()
    }
}
