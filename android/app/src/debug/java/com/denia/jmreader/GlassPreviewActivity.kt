package com.denia.jmreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Offline visual fixture, included only in debug builds. No account or network data. */
class GlassPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        setContent {
            JmTheme {
                val backdrop = rememberGlassBackdrop()
                var moved by remember { mutableStateOf(false) }
                var selected by remember { mutableIntStateOf(0) }
                var dialog by remember { mutableStateOf(false) }
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
                        Canvas(Modifier.fillMaxSize().glassBackdrop(backdrop)) {
                            drawRect(MaterialFixtureBackground)
                            val cell = 32.dp.toPx()
                            val colors = listOf(Color(0xFFEB82B5), Color(0xFF719AE9), Color(0xFF77CDBE), Color(0xFFEBC383))
                            for (y in 0..(size.height / cell).toInt()) {
                                for (x in -1..(size.width / cell).toInt()) {
                                    drawRect(colors[(x + y + 8) % colors.size],
                                        Offset(x * cell + if (moved) cell / 2 else 0f, y * cell), Size(cell, cell))
                                }
                            }
                        }
                        Column(Modifier.fillMaxWidth().padding(24.dp).align(Alignment.Center),
                            verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            GlassSurface(Modifier.fillMaxWidth(), prominent = true, tintStrength = 0.15f) {
                                Column(Modifier.padding(28.dp)) {
                                    Text("Liquid Glass", style = MaterialTheme.typography.headlineMedium)
                                    Text("清晰文字 · 背景折射 · 色散高光")
                                }
                            }
                            GlassButton(onClick = { moved = !moved }, modifier = Modifier.fillMaxWidth()) { Text("移动背景") }
                            GlassOutlinedButton(onClick = { dialog = true }, modifier = Modifier.fillMaxWidth()) { Text("测试弹窗") }
                        }
                        LiquidGlassBottomBar(selected, selected.toFloat(), backdrop,
                            onSelect = { selected = it }, modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp))
                        if (dialog) {
                            GlassAlertDialog(onDismissRequest = { dialog = false },
                                title = { Text("液态玻璃") }, text = { Text("背景可透视，文字保持清晰。") },
                                confirmButton = { GlassButton(onClick = { dialog = false }) { Text("完成") } })
                        }
                    }
                }
            }
        }
    }
}

private val MaterialFixtureBackground = Color(0xFFCAD7EF)
