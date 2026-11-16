package com.denia.jmreader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Scroll-end clearance, supplied only to the four root pages, not detail/readers. */
val LocalNavigationClearance = staticCompositionLocalOf { 0.dp }

/** Both the floating bar and moving selection use the upstream LiquidGlassView material. */
@Composable
fun LiquidGlassBottomBar(selectedPage: Int, pagePosition: Float, backdrop: GlassBackdrop,
                         onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items = remember { listOf("首页" to Icons.Default.Home, "书架" to Icons.Default.MenuBook,
        "个人" to Icons.Default.Person, "搜索" to Icons.Default.Search) }
    val tabInteractions = remember { List(4) { MutableInteractionSource() } }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = LocalDensity.current
    val barHeight = 64.dp
    val restingLensHeight = barHeight - 8.dp
    val insetPx = with(density) { 4.dp.toPx() }
    val maxShiftPx = with(density) { 6.dp.toPx() }
    val frostedBackdrop = rememberGlassBackdrop()
    var touching by remember { mutableStateOf(false) }
    var shiftTarget by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var releasePosition by remember { mutableStateOf<Float?>(null) }
    var releasedPage by remember { mutableStateOf<Int?>(null) }
    val target = pagePosition.coerceIn(0f, 3f)
    // Hold the released destination while the pager starts its own animation. Otherwise the
    // lens jumps back to the old tab for one frame when the finger leaves the bar.
    LaunchedEffect(target, releasedPage) {
        if (releasedPage != null && abs(target - releasedPage!!) < 0.01f) releasedPage = null
    }
    val settled = remember { Animatable(target) }
    LaunchedEffect(dragging, releasedPage, target) {
        if (dragging != null) {
            settled.snapTo(dragging!!)
        } else {
            releasePosition?.let { settled.snapTo(it); releasePosition = null }
            settled.animateTo(releasedPage?.toFloat() ?: target, spring(0.85f, 500f))
        }
    }
    val position = dragging ?: settled.value
    val pressProgress by animateFloatAsState(if (touching) 1f else 0f,
        spring(0.8f, 600f), label = "navigationLens")
    val barShift by animateFloatAsState(shiftTarget,
        spring(0.85f, 450f), label = "navigationShift")
    val tabAppearance = items.mapIndexed { index, _ ->
        val down by tabInteractions[index].collectIsPressedAsState()
        val scale by animateFloatAsState(if (down) 0.94f else 1f,
            spring(0.8f, 600f), label = "navigationPress")
        val active = if (dragging != null) abs(position - index) < 0.5f else selectedPage == index
        val tint by animateColorAsState(if (active) MaterialTheme.colorScheme.primary
            else if (dark) Color.White else Color.Black, label = "navigationTint")
        tint to scale
    }
    val latestSelect by rememberUpdatedState(onSelect)
    val latestPosition by rememberUpdatedState(target)
    val latestShift by rememberUpdatedState(barShift)
    // Keep hit coordinates fixed while the rendered bar moves slightly under the finger.
    Box(modifier.fillMaxWidth().height(barHeight).pointerInput(insetPx, maxShiftPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val tabWidth = ((size.width - insetPx * 2) / 4f).coerceAtLeast(1f)
            val lensCenter = insetPx + (latestPosition + 0.5f) * tabWidth + latestShift
            val grabOffset = (down.position.x - lensCenter).takeIf { abs(it) <= tabWidth / 2 } ?: 0f
            var isDragging = false
            touching = true
            releasedPage = null
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    if (!change.pressed) {
                        if (isDragging) {
                            change.consume()
                            releasePosition = dragging
                            releasedPage = dragging!!.roundToInt().coerceIn(0, 3)
                            latestSelect(releasedPage!!)
                        }
                        break
                    }
                    val travel = change.position - down.position
                    if (!isDragging) {
                        if (abs(travel.x) > viewConfiguration.touchSlop && abs(travel.x) > abs(travel.y)) {
                            isDragging = true
                        } else if (abs(travel.y) > viewConfiguration.touchSlop) {
                            break
                        }
                    }
                    shiftTarget = (travel.x * 0.06f).coerceIn(-maxShiftPx, maxShiftPx)
                    if (isDragging) {
                        change.consume()
                        dragging = ((change.position.x - insetPx - latestShift - grabOffset) / tabWidth - 0.5f)
                            .coerceIn(0f, 3f)
                    }
                }
            } finally {
                touching = false
                dragging = null
                shiftTarget = 0f
            }
        }
    }) {
        BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { translationX = barShift }) {
            val barWidth = maxWidth
            // Record the page, frosted bar and icons before the top lens. Labels are
            // painted separately so optical displacement cannot duplicate their glyphs.
            // Extra sampling space preserves page content around the enlarged capsule.
            Box(Modifier.requiredSize(barWidth + 56.dp, barHeight + 56.dp).align(Alignment.Center)
                .clipToBounds().glassBackdrop(frostedBackdrop, background = backdrop)) {
                GlassSurface(Modifier.width(barWidth).height(barHeight).align(Alignment.Center),
                    shape = CircleShape, radius = barHeight / 2, backdrop = backdrop,
                    prominent = true, tintStrength = 0.5f, blurRadius = 12.dp) {}
                Row(Modifier.width(barWidth).height(barHeight).align(Alignment.Center)
                    .padding(4.dp).clearAndSetSemantics {}) {
                    items.forEachIndexed { index, (_, icon) ->
                        val (tint, scale) = tabAppearance[index]
                        Column(Modifier.weight(1f).fillMaxHeight()
                            .graphicsLayer { scaleX = scale; scaleY = scale },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.height(2.dp))
                            // Match the actual label's scaled line height in the foreground.
                            Spacer(Modifier.height(with(density) { 12.sp.toDp() }))
                        }
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
                val tabWidth = maxWidth / 4
                val lensWidth = tabWidth * (1f + 0.18f * pressProgress)
                val lensHeight = restingLensHeight + (72.dp - restingLensHeight) * pressProgress
                // The resting rim samples only nearby background, keeping the icon in the
                // undistorted center. Pressing restores the stronger native optical effect.
                GlassSurface(Modifier.offset(x = tabWidth * position + (tabWidth - lensWidth) / 2,
                    y = (restingLensHeight - lensHeight) / 2)
                    .wrapContentSize(Alignment.TopStart, unbounded = true).size(lensWidth, lensHeight),
                    shape = CircleShape, radius = lensHeight / 2, backdrop = frostedBackdrop,
                    tintStrength = 0f, refractionHeight = 4.dp + 8.dp * pressProgress,
                    refractionOffset = 6.dp + 39.dp * pressProgress,
                    dispersion = 0.15f + 0.85f * pressProgress) {}
            }
            // Labels stay sharp above the optics in every gesture state. Their input
            // and semantics share the same fixed hit areas as the underlying icons.
            Row(Modifier.fillMaxSize().padding(4.dp).selectableGroup()) {
                items.forEachIndexed { index, (label, _) ->
                    val (tint, scale) = tabAppearance[index]
                    val active = if (dragging != null) abs(position - index) < 0.5f else selectedPage == index
                    Box(Modifier.weight(1f).fillMaxHeight()
                        .semantics { contentDescription = label }
                        .selectable(selectedPage == index, role = Role.Tab,
                            interactionSource = tabInteractions[index], indication = null,
                            onClick = { releasedPage = null; onSelect(index) }),
                        contentAlignment = Alignment.Center) {
                        Column(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
                            .clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(24.dp + 2.dp))
                            Text(label, color = tint, fontSize = 10.sp, lineHeight = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
