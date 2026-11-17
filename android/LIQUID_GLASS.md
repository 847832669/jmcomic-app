# 液态玻璃底栏与主题控件

Android `2.30-glass.8` / versionCode `26` 仅在底栏及移动胶囊中使用 [QmDeve/AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView)。其余界面采用清晰的日夜主题表面，保留粉色强调和统一圆角。

## 渲染与布局

- 底栏背景与胶囊通过 `GlassSurface(liquidGlass = true) → QmGlassHost → LiquidGlassView.bind(source)` 渲染。
- 四个图标与文字只在单个前景 Row 绘制。它们与磨砂底栏共用实际布局位移，不独立移动、缩放或切换绘制副本；选中胶囊从内容行后方滑过。
- 胶囊采样仅包含页面及已渲染的磨砂底栏，排除图标、文字和胶囊自身。底栏四周扩展 28dp 采样范围，以容纳按压突出部分。
- 底栏轻移使用实际布局偏移，使 Compose 背景记录源、原生 View 和前景行坐标同步；固定外部手势区域避免位移反馈干扰触控。
- 其他 `GlassSurface` 调用默认使用常规 Material Surface，不创建玻璃渲染器。主要按钮、描边按钮、输入框及弹窗直接调用对应 Material 控件，保留输入焦点、禁用状态、可访问性和触控反馈。

## 底栏参数

| 项目 | 配置 |
| --- | --- |
| 底栏 / 静止胶囊高度 | 64dp / 56dp |
| 按压胶囊 | 72dp 高，宽度增加 18% |
| 底栏轻移 | 手指水平位移的 6%，左右最多 6dp，松手或取消归位 |
| 底栏着色 / 模糊 | 日夜均使用 0.5 主题色 / 12dp GPU 模糊 |
| 胶囊着色 / 模糊 | 0 / 0.01px，完整保留库材质 |
| 胶囊折射高度 | 12dp |
| 胶囊偏移 / 色散 | 静止 20dp / 0.15，按压平滑增至 45dp / 1.0 |
| 根页面末尾避让 | 88dp |

所有光学参数经库公共 API 配置，遵守最小 12dp 折射高度和 20dp 偏移限制。Compose 负责布局和弹簧动画，未修改库 AGSL。

## 来源与兼容

固定库修订 `d4238c557e46271f29ec0e3d3ee211bcf58dc2e0`，1.1.0 API。`:liquidglass` 编译原版 Java 组件和着色器；保留 MIT 许可证与版权，构建、生命周期及尺寸动画适配见 [NOTICE](liquidglass/NOTICE.md)。

API 33+ 使用真实折射、色散和模糊；API 26–32 的底栏使用半透明兼容表面。常规控件在这两个版本范围均使用主题样式。

## 验证方法

构建命令：`gradle testDebugUnitTest assembleDebug`。调试构建可使用独立设置存储的离线场景，避免覆盖用户主题：

```shell
adb shell am start -n com.denia.jmreader/.GlassPreviewActivity --es theme light
adb shell am start -n com.denia.jmreader/.GlassPreviewActivity --es theme dark
```

该场景展示常规按钮、主题卡片、弹窗和玻璃底栏，支持移动棋盘背景。测试完成后 force-stop 并重新打开主界面，恢复应用原设置。

### 2.30-glass.8 验证记录（2026-11-17）

- `testDebugUnitTest assembleDebug` 构建成功，29 项 JVM 测试全部通过。
- BSN-AN00 真机（Android API 37，1280×2788，密度 2.975）离线场景通过日间、夜间、按压、双向拖动、取消归位、点击切页及弹窗开关验证。
- 四个导航项的布局边界在右拖时统一平移 +18px、左拖时统一平移 -18px，取消后恢复；图标和文字不随胶囊独立移动。
- 明暗主题下普通卡片及弹窗均使用实体主题表面，按钮保持粉色主题，移动背景只改变底栏采样。
- 测试后已退出独立预览场景并重新打开主界面。本轮不包含联网内容及账号功能的完整回归。
