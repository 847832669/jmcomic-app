package com.denia.jmreader

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
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

/** All application glass surfaces use the QmDeve library, below sharp Compose content. */
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
    val alpha = (tintStrength ?: if (accent) 0.78f else if (prominent) 0.25f else 0.48f) * color.alpha
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
            val inset = 0.5.dp.toPx()
            drawRoundRect(Brush.linearGradient(listOf(
                Color.White.copy(alpha = if (dark) 0.3f else 0.78f), Color.White.copy(alpha = 0.06f),
                Color.White.copy(alpha = if (dark) 0.18f else 0.45f)),
                start = Offset.Zero, end = Offset(size.width, size.height)),
                topLeft = Offset(inset, inset), size = Size((size.width - inset * 2).coerceAtLeast(0f),
                    (size.height - inset * 2).coerceAtLeast(0f)),
                cornerRadius = CornerRadius((actualRadius - inset).coerceAtLeast(0f)), style = Stroke(1.dp.toPx()))
        }, propagateMinConstraints = true) {
        if (native) {
            AndroidView(factory = { QmGlassHost(it) }, modifier = Modifier.matchParentSize(),
                onRelease = { it.release() }, update = {
                    it.update(backdrop!!, density, direction, actualRadius,
                        color.red, color.green, color.blue, alpha, prominent)
                })
        }
        content()
    }
}

/** Floating navigation with the same native glass material as the rest of the app. */
@Composable
fun LiquidGlassBottomBar(selectedPage: Int, pagePosition: Float, backdrop: GlassBackdrop,
                         onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf("首页" to Icons.Default.Home, "分类" to Icons.Default.Apps,
        "个人" to Icons.Default.Person, "搜索" to Icons.Default.Search)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var dragging by remember { mutableStateOf<Float?>(null) }
    val target = pagePosition.coerceIn(0f, 3f)
    val position by animateFloatAsState(dragging ?: target, spring(0.72f, 420f), label = "liquidSelection")
    val latestSelect by rememberUpdatedState(onSelect)
    val latestPosition by rememberUpdatedState(target)
    GlassSurface(modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(32.dp),
        radius = 32.dp, backdrop = backdrop, prominent = true) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp).pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { dragging = latestPosition },
                onDragCancel = { dragging = null },
                onDragEnd = {
                    dragging?.let { latestSelect(it.roundToInt().coerceIn(0, 3)) }
                    dragging = null
                },
            ) { change, amount ->
                change.consume()
                dragging = ((dragging ?: latestPosition) + amount / (size.width / 4f)).coerceIn(0f, 3f)
            }
        }) {
            val tabWidth = maxWidth / 4
            GlassSurface(Modifier.offset(x = tabWidth * position).width(tabWidth).fillMaxHeight()
                .drawWithContent {
                    drawContent()
                    drawRoundRect(if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.18f),
                        cornerRadius = CornerRadius(size.height / 2))
                }, shape = RoundedCornerShape(28.dp), radius = 28.dp, backdrop = backdrop,
                prominent = true, tintStrength = if (dark) 0.2f else 0.12f) {}
            Row(Modifier.fillMaxSize().selectableGroup()) {
                items.forEachIndexed { index, (label, icon) ->
                    val interaction = remember { MutableInteractionSource() }
                    val down by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (down) 1.15f else 1f,
                        spring(0.65f, 500f), label = "tabPress")
                    val tint by animateColorAsState(if (selectedPage == index) Pink else MaterialTheme.colorScheme.onSurface,
                        label = "tabTint")
                    Column(Modifier.weight(1f).fillMaxHeight()
                        .selectable(selectedPage == index, role = Role.Tab, interactionSource = interaction,
                            indication = null, onClick = { onSelect(index) })
                        .graphicsLayer { scaleX = scale; scaleY = scale },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(23.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(label, color = tint, fontSize = 10.sp, lineHeight = 12.sp)
                    }
                }
            }
        }
    }
}
