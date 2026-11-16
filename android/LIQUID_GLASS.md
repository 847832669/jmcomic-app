# 液态玻璃效果

Android `2.30-glass.5` / versionCode `23` 的全局表面、底栏及移动选中透镜统一使用 [QmDeve/AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView)。

## 组件来源

- 固定上游版本：`d4238c557e46271f29ec0e3d3ee211bcf58dc2e0`，1.1.0 API；2026-11-16 重新核对。
- `:liquidglass` 构建原版 Java 组件及 `liquidglass_effect.agsl`。着色器正文与上游一致，仅文件换行格式不同。
- 保留原始 MIT 许可证与版权声明，构建、坐标和生命周期兼容修改见 [NOTICE](liquidglass/NOTICE.md)。

## 统一渲染路径

`NavigationGlass.kt` 只负责四个入口、胶囊布局与手势。整个底栏与移动选中透镜通过 `GlassSurface → QmGlassHost → LiquidGlassView.bind(source)` 渲染，光学效果均由库内 `LiquidGlassimpl` 和原版着色器提供。

全局组件保留库默认折射与色散；底栏背景通过库提供的模糊和着色 API 单独设置磨砂材质，透明透镜保持原参数。圆角按各组件尺寸设置，着色按日夜主题和文字对比度配置。

| 参数 | 值 | 说明 |
| --- | --- | --- |
| 折射高度 | 20dp | 使用密度换算后传入库的 px API |
| 折射偏移 | 70dp | 库自动转换为负方向偏移 |
| 色散 | 0.5 | 保留库内七次颜色采样 |
| 底栏背景模糊 | 12dp | 换算为 px 后调用库 API，库上限 50px |
| 透镜及其它表面模糊 | 0.01px | 保持库默认值与清晰折射 |
| 底栏着色 | 日间 / 夜间均为 0.5 | 使用当前主题 surface 色，50% 背景透视 |
| 选中透镜着色 | 日间 0.12 / 夜间 0.18 | 使用当前主题 primary 色 |

底栏高度 56dp，选中透镜内缩 4dp，高 48dp，水平边距 20dp。图标与标签在玻璃上方独立绘制，保持清晰及可访问性。点击、拖动与页面横滑共用选中状态；按下时透镜宽高均放大 6%，超过系统触摸阈值后直接跟随手指，保留在透镜边缘按下的抓取位置。整体底栏按位移的 6% 同向偏移，上限为左右各 6dp；松手后底栏归位、透镜缩回并吸附目标页。拖动取消、纵向移出或组件销毁时清理交互状态。Compose 只负责位置、缩放与弹簧动画，不替换库的折射算法。

普通表面采样环境背景；底栏和选中透镜直接采样页面，排除它们自身及前景文字。通过屏幕坐标对齐 Compose 与原生 View，每个采样源只暴露该组件范围，避免递归和整屏 CPU 截图。

API 33+ 使用库原生效果；API 26–32 使用半透明兼容表面。低版本系统不宣称支持真实折射与色散。

## 验证

构建命令：`gradle testDebugUnitTest assembleDebug`。离线视觉场景仅存在于 debug 构建：

```shell
adb shell am start -n com.denia.jmreader/.GlassPreviewActivity --es theme light
adb shell am start -n com.denia.jmreader/.GlassPreviewActivity --es theme dark
```

场景使用独立设置存储，支持切换纯色、移动棋盘背景与弹窗；测试后 force-stop 再打开主界面，恢复应用原设置。

2026-11-16 验证结果：

- `2.30-glass.5` / 23 已覆盖安装到 BSN-AN00 / API 37，29 项 JVM 测试和 APK 构建通过。
- 真机通过分开的 DOWN / MOVE / UP 触控验证按压放大、左右跟手、松手吸附，以及 CANCEL 和纵向移出的状态恢复；拖动时整体底栏左右各偏移约 18px（设备密度 2.975，对应 6dp），释放后归位。
- 日夜磨砂背景、透明透镜、背景移动、弹窗、四个入口、页面横滑和本地浏览历史均验证通过。取消手势后的底栏区域与初始截图像素一致，日志未发现应用崩溃。
- `testDebugUnitTest assembleDebug` 成功，29 项 JVM 测试通过，0 失败。
- BSN-AN00 / API 37 已覆盖安装 `2.30-glass.4` / 22，验证日间、夜间、纯色和棋盘背景、背景移动、弹窗打开与关闭。
- 真机验证四个底栏入口、双向拖动，以及书架到个人页的横滑联动；首页内的横向漫画货架会优先消费其自身手势。
- 验证个人页滚动、本地浏览历史入口，原有本地记录保留。日志确认 QmDeve AGSL 渲染器初始化及采样就绪，未发现应用崩溃。
- 棋盘背景移动后，底栏区域约 50.8% 像素变化，验证动态采样有效；该数值不代表帧率或视觉保真度。
- API 26–32 的兼容分支本次未重新实机验证。
