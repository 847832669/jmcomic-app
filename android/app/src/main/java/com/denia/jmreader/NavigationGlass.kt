package com.denia.jmreader

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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
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
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
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
    GlassSurface(modifier.fillMaxWidth().height(56.dp), shape = CircleShape, radius = 28.dp,
        backdrop = backdrop, prominent = true, tintStrength = if (dark) 0.24f else 0.08f) {
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
            // Sample the page directly, excluding both glass layers and all tab labels.
            GlassSurface(Modifier.offset(x = tabWidth * position).width(tabWidth).fillMaxHeight()
                .graphicsLayer { scaleX = stretch; scaleY = if (dragging != null) 1.025f else 1f },
                shape = CircleShape, radius = 24.dp, backdrop = backdrop, prominent = true,
                color = MaterialTheme.colorScheme.primary,
                tintStrength = if (dark) 0.18f else 0.12f) {}
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
