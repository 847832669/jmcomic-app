package com.denia.jmreader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Scroll-end clearance, supplied only to the four root pages, not detail/readers. */
val LocalNavigationClearance = staticCompositionLocalOf { 0.dp }

/** Independently drawn material: 56dp capsule and 48dp neutral selection, measured on device.
 * The protected reference's active shader parameters and animation constants remain unknown. */
@Composable
fun LiquidGlassBottomBar(selectedPage: Int, pagePosition: Float, backdrop: GlassBackdrop,
                         onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items = remember { listOf("首页" to Icons.Default.Home, "书架" to Icons.Default.MenuBook,
        "个人" to Icons.Default.Person, "搜索" to Icons.Default.Search) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    var releasedPage by remember { mutableStateOf<Int?>(null) }
    val target = pagePosition.coerceIn(0f, 3f)
    // Hold the released destination while the pager starts its own animation. Otherwise the
    // lens jumps back to the old tab for one frame when the finger leaves the bar.
    LaunchedEffect(target, releasedPage) {
        if (releasedPage != null && abs(target - releasedPage!!) < 0.01f) releasedPage = null
    }
    val settled by animateFloatAsState(dragging ?: releasedPage?.toFloat() ?: target,
        spring(0.85f, 500f), label = "navigationPosition")
    val position = dragging ?: settled
    val stretch by animateFloatAsState(if (dragging != null) 1.06f else 1f,
        spring(0.8f, 600f), label = "navigationLens")
    val latestSelect by rememberUpdatedState(onSelect)
    val latestPosition by rememberUpdatedState(target)
    val border = if (dark) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.96f)

    Box(modifier.fillMaxWidth().height(56.dp)
        .shadow(18.dp, CircleShape, clip = false, ambientColor = Color.Black.copy(alpha = 0.12f),
            spotColor = Color.Black.copy(alpha = if (dark) 0.22f else 0.10f))
        .clip(CircleShape)
        .drawWithContent {
            drawContent()
            val line = if (dark) 0.8.dp.toPx() else 2.dp.toPx()
            drawRoundRect(Brush.linearGradient(listOf(border,
                border.copy(alpha = if (dark) 0.08f else 0.55f), border.copy(alpha = if (dark) 0.20f else 0.90f)),
                start = Offset.Zero, end = Offset(size.width * 0.65f, size.height)),
                topLeft = Offset(line / 2, line / 2),
                size = Size(size.width - line, size.height - line),
                cornerRadius = CornerRadius((size.height - line) / 2), style = Stroke(line))
        }) {
        if (Build.VERSION.SDK_INT >= 31) {
            AndroidView(factory = { NavigationGlassHost(it) }, modifier = Modifier.matchParentSize(),
                onRelease = { it.release() }, update = { it.update(backdrop, density, direction, dark) })
        } else {
            Box(Modifier.matchParentSize().background(if (dark) Color(0xED171717) else Color(0xEDFCFCFC)))
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp).pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { releasedPage = null; dragging = latestPosition },
                onDragCancel = { dragging = null },
                onDragEnd = {
                    dragging?.let {
                        releasedPage = it.roundToInt().coerceIn(0, 3)
                        latestSelect(releasedPage!!)
                    }
                    dragging = null
                }
            ) { change, amount ->
                change.consume()
                dragging = ((dragging ?: latestPosition) + amount / (size.width / 4f)).coerceIn(0f, 3f)
            }
        }) {
            val tabWidth = maxWidth / 4
            Box(Modifier.offset(x = tabWidth * position).width(tabWidth).fillMaxHeight()
                .graphicsLayer { scaleX = stretch; scaleY = if (dragging != null) 1.025f else 1f }
                .clip(CircleShape)
                .background(if (dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.10f)))
            Row(Modifier.fillMaxSize().selectableGroup()) {
                items.forEachIndexed { index, (label, icon) ->
                    val interaction = remember { MutableInteractionSource() }
                    val down by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (down) 0.94f else 1f,
                        spring(0.8f, 600f), label = "navigationPress")
                    val active = if (dragging != null) abs(position - index) < 0.5f else selectedPage == index
                    val tint by animateColorAsState(if (active) MaterialTheme.colorScheme.primary
                        else if (dark) Color.White else Color.Black, label = "navigationTint")
                    Column(Modifier.weight(1f).fillMaxHeight()
                        .selectable(selectedPage == index, role = Role.Tab, interactionSource = interaction,
                            indication = null, onClick = { releasedPage = null; onSelect(index) })
                        .graphicsLayer { scaleX = scale; scaleY = scale },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(2.dp))
                        Text(label, color = tint, fontSize = 10.sp, lineHeight = 12.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Only the sampled scene enters this RenderNode. Sharp icons/text and the bar never feed back. */
@RequiresApi(31)
private class NavigationGlassHost(context: Context) : FrameLayout(context) {
    private val source = ComposeGlassSource(context)
    private val node = RenderNode("JM navigation backdrop")
    private val tint = Paint()
    private var dark = false
    private var density = 1f
    private var shader: NavigationRefraction? = null
    private val preDraw = android.view.ViewTreeObserver.OnPreDrawListener { invalidate(); true }

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setWillNotDraw(false)
        source.visibility = View.INVISIBLE
        addView(source, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (Build.VERSION.SDK_INT >= 33) shader = NavigationRefraction()
    }

    fun update(backdrop: GlassBackdrop, density: androidx.compose.ui.unit.Density,
               direction: androidx.compose.ui.unit.LayoutDirection, dark: Boolean) {
        source.backdrop = backdrop
        source.density = density
        source.direction = direction
        if (this.dark != dark || this.density != density.density) {
            this.dark = dark
            this.density = density.density
            updateEffect()
        }
        invalidate()
    }

    private fun updateEffect() {
        if (width == 0 || height == 0) return
        val blur = RenderEffect.createBlurEffect(5.5f * density, 5.5f * density, Shader.TileMode.CLAMP)
        val lens = shader?.effect(width, height, density, dark)
        node.setRenderEffect(if (lens != null) RenderEffect.createChainEffect(lens, blur) else blur)
        tint.color = if (dark) 0x94111111.toInt() else 0xD1FFFFFF.toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        node.setPosition(0, 0, w, h)
        updateEffect()
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        if (canvas.isHardwareAccelerated && source.backdrop?.ready == true) {
            val recording = node.beginRecording(width, height)
            source.draw(recording)
            node.endRecording()
            canvas.drawRenderNode(node)
        }
        // API 31/32 get the same blur and tint, without AGSL edge bending.
        if (shader == null) canvas.drawColor(tint.color)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDraw)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        node.discardDisplayList()
        super.onDetachedFromWindow()
    }

    fun release() { source.backdrop = null; node.discardDisplayList() }
    override fun dispatchTouchEvent(event: android.view.MotionEvent) = false
}

/** Original shader; no proprietary bytecode, shader or graphic assets are copied. */
@RequiresApi(33)
private class NavigationRefraction {
    private val shader = RuntimeShader("""
        uniform shader content;
        uniform float2 size;
        uniform float density;
        uniform float dark;
        half4 main(float2 p) {
            float radius = size.y * 0.5;
            float2 q = p - size * 0.5;
            float2 v = float2(q.x - clamp(q.x, -size.x * 0.5 + radius, size.x * 0.5 - radius), q.y);
            float distance = length(v) - radius;
            float2 normal = v / max(length(v), 0.001);
            float edge = 1.0 - smoothstep(0.0, 7.0 * density, -distance);
            float2 sample = p - normal * edge * edge * 2.5 * density;
            half4 c = content.eval(sample);
            half luma = dot(c.rgb, half3(0.2126, 0.7152, 0.0722));
            half3 rgb = mix(half3(luma), c.rgb, 0.92);
            half3 tint = mix(half3(0.99), half3(0.067), dark);
            rgb = mix(rgb, tint, mix(0.82, 0.58, dark));
            float rim = 1.0 - smoothstep(0.0, 1.4 * density, -distance);
            float light = 0.5 + 0.5 * dot(normal, float2(-0.55, -0.83));
            rgb += half3(rim * light * mix(0.055, 0.12, dark));
            return half4(clamp(rgb, 0.0, 1.0), 1.0);
        }
    """.trimIndent())

    fun effect(w: Int, h: Int, density: Float, dark: Boolean): RenderEffect {
        shader.setFloatUniform("size", w.toFloat(), h.toFloat())
        shader.setFloatUniform("density", density)
        shader.setFloatUniform("dark", if (dark) 1f else 0f)
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}
