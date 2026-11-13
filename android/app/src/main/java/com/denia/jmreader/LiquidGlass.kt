package com.denia.jmreader

import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.*

/** Compose 1.8 adapter for Kyant0's layer backdrop + SDF lens (see third-party/). */
@Stable
class GlassBackdrop internal constructor(val layer: GraphicsLayer, internal val view: android.view.View) {
    internal var origin by mutableStateOf(Offset.Zero)
    internal var ready by mutableStateOf(false)
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

/** Record only the background branch, never the glass that samples that branch. */
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

private data class GlassSample(val width: Int, val height: Int, val padding: Int,
                               val scale: Float, val radius: Float, val density: Float)
private class SampleBuffer { var spec: GlassSample? = null }

/** Glass is applied to the backdrop only; foreground text and artwork stay sharp. */
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
    val density = LocalDensity.current.density
    val view = LocalView.current
    val baseSurface = MaterialTheme.colorScheme.surface
    val accent = color != baseSurface
    val tint = color.copy(alpha = tintStrength ?: if (accent) 0.88f else if (prominent) 0.46f else 0.62f)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (pressed) 0.975f else 1f, spring(0.75f, 550f), label = "glassPress")
    val sampleLayer = rememberGraphicsLayer()
    val buffer = remember { SampleBuffer() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var softwareImage by remember { mutableStateOf<ImageBitmap?>(null) }
    val gpu = remember { if (Build.VERSION.SDK_INT >= 33) GlassGpuLens() else null }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // API 26–30: bounded snapshots + separable blur + the same SDF refraction on CPU.
    // Polling slows when pixels stop changing and stops entirely when off screen/backgrounded.
    if (Build.VERSION.SDK_INT < 31 && backdrop != null) {
        LaunchedEffect(sampleLayer, backdrop, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                var renderer: SoftwareGlassLens? = null
                var lastSpec: GlassSample? = null
                var previousHash: Int? = null
                var idleFrames = 0
                var reported = false
                while (isActive) {
                    delay(if (idleFrames > 6) 600L else if (prominent) 80L else 200L)
                    val spec = buffer.spec ?: continue
                    try {
                        val captured = sampleLayer.toImageBitmap().asAndroidBitmap()
                        val result = withContext(Dispatchers.Default) {
                            if (lastSpec != spec) {
                                renderer = SoftwareGlassLens(spec)
                                lastSpec = spec
                                previousHash = null
                            }
                            val software = captured.copy(Bitmap.Config.ARGB_8888, false)
                            try { renderer!!.render(software, previousHash) } finally { software.recycle() }
                        }
                        if (result.second != null) {
                            previousHash = result.first
                            softwareImage = result.second!!.asImageBitmap()
                            idleFrames = 0
                        } else {
                            idleFrames++
                        }
                        if (!reported) {
                            android.util.Log.d("JmGlass", "Software SDF lens ready: ${spec.width}x${spec.height}")
                            reported = true
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (e: Exception) {
                        if (!reported) android.util.Log.w("JmGlass", "Backdrop capture unavailable", e)
                        reported = true
                        delay(500)
                    }
                }
            }
        }
    }

    val clickModifier = if (onClick != null) Modifier.clickable(
        interactionSource = interaction, indication = null, onClick = onClick) else Modifier
    Box(modifier
        .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
        .shadow(if (prominent) 14.dp else 3.dp, shape, clip = false,
            ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.16f))
        .clip(shape)
        .then(if (border != null) Modifier.border(border, shape) else Modifier)
        .then(clickModifier)
        .onGloballyPositioned { origin = it.positionInWindow() + view.windowOrigin() }
        .drawWithContent {
            val outline = shape.createOutline(size, layoutDirection, this)
            val actualRadius = if (outline is Outline.Rounded) outline.roundRect.topLeftCornerRadius.x
                else min(radius.toPx(), size.minDimension / 2f)
            if (backdrop != null && backdrop.ready && size.width > 0f && size.height > 0f) {
                val downsample = if (Build.VERSION.SDK_INT < 31) min(1f, 360f / size.width) else 1f
                val pad = (12.dp.toPx() * downsample).roundToInt().coerceAtLeast(1)
                val sampleSize = IntSize((size.width * downsample).roundToInt() + pad * 2,
                    (size.height * downsample).roundToInt() + pad * 2)
                val offset = origin - backdrop.origin
                sampleLayer.record(size = sampleSize) {
                    translate(pad.toFloat(), pad.toFloat()) {
                        scale(downsample, downsample, Offset.Zero) {
                            translate(-offset.x, -offset.y) { drawLayer(backdrop.layer) }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    sampleLayer.renderEffect = if (Build.VERSION.SDK_INT >= 33) {
                        gpu!!.effect(size, actualRadius, pad.toFloat(), 8.dp.toPx())
                    } else {
                        android.graphics.RenderEffect.createBlurEffect(8.dp.toPx(), 8.dp.toPx(),
                            android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()
                    }
                    translate(-pad.toFloat(), -pad.toFloat()) { drawLayer(sampleLayer) }
                } else {
                    buffer.spec = GlassSample(sampleSize.width, sampleSize.height, pad,
                        downsample, actualRadius * downsample, density)
                    val image = softwareImage
                    if (image != null) {
                        drawImage(image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.Medium)
                    } else {
                        scale(1f / downsample, 1f / downsample, Offset.Zero) {
                            translate(-pad.toFloat(), -pad.toFloat()) { drawLayer(sampleLayer) }
                        }
                    }
                }
            }
            drawRect(tint)
            drawRect(Brush.linearGradient(
                listOf(Color.White.copy(alpha = if (dark) 0.09f else 0.30f), Color.Transparent,
                    Color.White.copy(alpha = if (dark) 0.03f else 0.09f)),
                start = Offset.Zero, end = Offset(size.width * 0.7f, size.height)))
            drawContent()
            val inset = 0.6.dp.toPx()
            drawRoundRect(
                brush = Brush.linearGradient(listOf(Color.White.copy(alpha = if (dark) 0.38f else 0.90f),
                    Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = if (dark) 0.19f else 0.60f)),
                    start = Offset.Zero, end = Offset(size.width, size.height)),
                topLeft = Offset(inset, inset), size = Size(size.width - inset * 2, size.height - inset * 2),
                cornerRadius = CornerRadius((actualRadius - inset).coerceAtLeast(0f)), style = Stroke(1.dp.toPx()))
            drawRoundRect(Color.Black.copy(alpha = if (dark) 0.22f else 0.05f),
                topLeft = Offset(inset * 3, inset * 3),
                size = Size((size.width - inset * 6).coerceAtLeast(0f), (size.height - inset * 6).coerceAtLeast(0f)),
                cornerRadius = CornerRadius((actualRadius - inset * 3).coerceAtLeast(0f)), style = Stroke(0.5.dp.toPx()))
        }, propagateMinConstraints = true) { content() }
}

@RequiresApi(33)
private class GlassGpuLens {
    private val shader = android.graphics.RuntimeShader(RoundedRectRefractionWithDispersionShaderString)
    init { android.util.Log.d("JmGlass", "GPU AGSL lens ready (Kyant0 SDF + dispersion)") }
    fun effect(size: Size, radius: Float, padding: Float, blur: Float): RenderEffect {
        shader.setFloatUniform("size", size.width, size.height)
        shader.setFloatUniform("offset", -padding, -padding)
        shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
        shader.setFloatUniform("refractionHeight", min(18f * blur / 8f, size.height / 2f))
        shader.setFloatUniform("refractionAmount", -12f * blur / 8f)
        shader.setFloatUniform("depthEffect", 0f)
        shader.setFloatUniform("chromaticAberration", 0.08f)
        val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
        val blurred = android.graphics.RenderEffect.createBlurEffect(blur, blur, android.graphics.Shader.TileMode.CLAMP)
        return android.graphics.RenderEffect.createChainEffect(lens, blurred).asComposeRenderEffect()
    }
}

/** CPU translation of Kyant0's rounded-rect distance/normal/circleMap equations. */
private class SoftwareGlassLens(private val spec: GlassSample) {
    private val width = spec.width - spec.padding * 2
    private val height = spec.height - spec.padding * 2
    private val redMap = IntArray(width * height)
    private val greenMap = IntArray(width * height)
    private val blueMap = IntArray(width * height)

    init {
        val halfW = width / 2f; val halfH = height / 2f
        val radius = min(spec.radius, min(halfW, halfH))
        val gradRadius = min(radius * 1.5f, min(halfW, halfH))
        val bevel = min(18f * spec.density * spec.scale, halfH).coerceAtLeast(1f)
        val amount = -12f * spec.density * spec.scale
        fun sample(x: Float, y: Float): Int = y.roundToInt().coerceIn(0, spec.height - 1) * spec.width +
            x.roundToInt().coerceIn(0, spec.width - 1)
        for (y in 0 until height) for (x in 0 until width) {
            val cx = x - halfW; val cy = y - halfH
            val qx = abs(cx) - (halfW - radius); val qy = abs(cy) - (halfH - radius)
            val sd = hypot(max(qx, 0f), max(qy, 0f)) - radius + min(max(qx, qy), 0f)
            val t = (1f - -min(sd, 0f) / bevel).coerceIn(0f, 1f)
            val displacement = if (-sd >= bevel) 0f else (1f - sqrt(1f - t * t)) * amount
            val gx = abs(cx) - (halfW - gradRadius); val gy = abs(cy) - (halfH - gradRadius)
            val nx: Float; val ny: Float
            if (gx >= 0f || gy >= 0f) {
                val length = hypot(max(gx, 0f), max(gy, 0f)).coerceAtLeast(0.001f)
                nx = sign(cx) * max(gx, 0f) / length; ny = sign(cy) * max(gy, 0f) / length
            } else {
                nx = if (gx >= gy) sign(cx) else 0f; ny = if (gx < gy) sign(cy) else 0f
            }
            val dispersion = 0.08f * (cx * cy / (halfW * halfH))
            val px = x + spec.padding + displacement * nx
            val py = y + spec.padding + displacement * ny
            val i = y * width + x
            redMap[i] = sample(px + displacement * nx * dispersion, py + displacement * ny * dispersion)
            greenMap[i] = sample(px, py)
            blueMap[i] = sample(px - displacement * nx * dispersion, py - displacement * ny * dispersion)
        }
    }

    fun render(bitmap: Bitmap, previousHash: Int?): Pair<Int, Bitmap?> {
        val pixels = IntArray(spec.width * spec.height)
        bitmap.getPixels(pixels, 0, spec.width, 0, 0, spec.width, spec.height)
        var hash = 1
        for (i in pixels.indices step 17) hash = 31 * hash + pixels[i]
        if (hash == previousHash) return hash to null
        val scratch = IntArray(pixels.size)
        val blurRadius = (4f * spec.density * spec.scale).roundToInt().coerceAtLeast(1)
        repeat(2) {
            blur(pixels, scratch, spec.width, spec.height, blurRadius, true)
            blur(scratch, pixels, spec.width, spec.height, blurRadius, false)
        }
        val output = IntArray(width * height) { i ->
            val r = (pixels[redMap[i]] ushr 16) and 255
            val g = (pixels[greenMap[i]] ushr 8) and 255
            val b = pixels[blueMap[i]] and 255
            val luma = r * 0.213f + g * 0.715f + b * 0.072f
            val vr = (luma + (r - luma) * 1.2f).roundToInt().coerceIn(0, 255)
            val vg = (luma + (g - luma) * 1.2f).roundToInt().coerceIn(0, 255)
            val vb = (luma + (b - luma) * 1.2f).roundToInt().coerceIn(0, 255)
            (255 shl 24) or (vr shl 16) or (vg shl 8) or vb
        }
        return hash to Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun blur(input: IntArray, out: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val count = radius * 2 + 1
        for (line in 0 until lines) {
            fun index(p: Int): Int = if (horizontal) line * w + p.coerceIn(0, length - 1)
                else p.coerceIn(0, length - 1) * w + line
            var r = 0; var g = 0; var b = 0
            for (p in -radius..radius) { val c = input[index(p)]; r += (c ushr 16) and 255; g += (c ushr 8) and 255; b += c and 255 }
            for (p in 0 until length) {
                out[index(p)] = (255 shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                val add = input[index(p + radius + 1)]; val remove = input[index(p - radius)]
                r += ((add ushr 16) and 255) - ((remove ushr 16) and 255)
                g += ((add ushr 8) and 255) - ((remove ushr 8) and 255)
                b += (add and 255) - (remove and 255)
            }
        }
    }
}

/** Kyant0 LiquidBottomTabs layout: 64dp capsule, 56dp spring-driven selection lens. */
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
                .shadow(2.dp, RoundedCornerShape(28.dp), clip = false)
                .clip(RoundedCornerShape(28.dp))
                .drawWithContent {
                    drawContent()
                    drawRoundRect(if (dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.06f),
                        cornerRadius = CornerRadius(size.height / 2))
                    drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (dark) 0.12f else 0.46f),
                        Color.Transparent)), cornerRadius = CornerRadius(size.height / 2))
                    drawRoundRect(Color.White.copy(alpha = if (dark) 0.25f else 0.65f),
                        topLeft = Offset(1f, 1f), size = Size(size.width - 2, size.height - 2),
                        cornerRadius = CornerRadius(size.height / 2), style = Stroke(0.6.dp.toPx()))
                }, shape = RoundedCornerShape(28.dp), radius = 28.dp, backdrop = backdrop, tintStrength = 0.16f) {}
            Row(Modifier.fillMaxSize().selectableGroup()) {
                items.forEachIndexed { index, (label, icon) ->
                    val interaction = remember { MutableInteractionSource() }
                    val down by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (down) 1.15f else 1f, spring(0.65f, 500f), label = "tabPress")
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
