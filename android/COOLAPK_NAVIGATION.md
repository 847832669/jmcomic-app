# 酷安风格底栏：分析与独立实现

本分支 Android `2.30-glass.3` / versionCode `21` 参考酷安底栏，重做悬浮胶囊、背景材质和选中反馈。项目保留首页、书架、个人、搜索四个入口及粉色主题；酷安的五个位置、中央发布按钮和品牌图标没有移植。

## 输入与证据边界

- 用户提供：`CoolApk-16.7.3-2611121-coolapk-arm64-sign.apk`，113,909,329 字节。
- SHA-256：`b94f4ef31ad323f2f39855ab99623f0dbb299a17f31353f81dda0dd976ec6306`。
- REA + JADX headless 0.7.1：包名 `com.coolapk.market`，版本 `16.7.3` / `2611121`，minSdk 24、targetSdk 34。设备原装 16.7.2，已用提供的 APK 覆盖升级并核实版本，保留原数据；最终对照使用 16.7.3。
- 真机：BSN-AN00，API 37，1280 × 2788，显示 density 476，font_scale 0.85。系统夜间模式测试后恢复原来的 auto，JM Reader 的外观恢复测试前的日间设置。

REA 静态分析只能得到 31 个类，主要属于 `com.netease.nis.wrapper`，表明存在网易保护壳。没有恢复其底栏内部方法、完整依赖版本和实际运行参数，不能把效果复刻描述为源码还原。

包内存在 `META-INF/Glass:backdrop.kotlin_module`、`liquidglass.kotlin_module` 和 `liquidglass-coolapk.kotlin_module`。这些是打包模块标记，不能仅凭名字证明具体开源版本。资源中有 `liquid_glass_capability_name/value` 的 full / blur_only / fallback 三档，`liquid_home_structure_demo` 使用 ComposeView，`liquid_bottom_navigation_height` 为 56dp。这些属于静态声明，不证明每个分支在该设备上执行。

可复核的 REA Evidence：

| 内容 | Evidence ID |
| --- | --- |
| APK 包声明 | `ev_33b02c76c2fe025fefbce34caccf7e73f935dee843df2b1a842b02a90f2e4aab` |
| 完整的可解析类名集合 | `ev_da7ad6b82aa279259fe69ede7c87fe1cb915c85b987bb2e8cd0b30bc1231e6cd` |
| Apktool 资源解码 | `ev_1f3b7f963b41fb6ef7cd64c5b127cd60b670e94fa57de0b86741bed54b90c578` |
| 已安装 16.7.3 版本核实 | `ev_d40880501e95604dea90db339bc3da594be8d5e5e73ff88059e3999d39a747da` |
| 16.7.3 日间截图 | `ev_435ce887d735df4e470df2d284bd339637d2ec0ff19c2cad8cdefff0a132c4dd` |
| 16.7.3 夜间截图 | `ev_8a33238c0e9cffe8e122bf20927cf9ee2fe64f293912cf03d5e29ac92075859f` |

补充的普通字节检索在受保护 DEX 的尾部发现了 AGSL 明文字符串：一个渐隐 shader，以及普通折射、带色散折射两个变体。折射字符串使用圆弧映射控制边缘采样，色散变体组合七次颜色采样；还保留了 `LiquidBottomNavigationBar.kt`、`CoolapkGlassNavigationBar.kt` 等源码标记。字符串存在不证明底栏实际调用了哪个变体，也未给出其运行参数。本项目没有直接复制这些闭源字符串。

资源尺寸和 XML 节点另用 Android SDK aapt2 交叉检查；像素与触控区域测量来自截图和 uiautomator，属于普通工具观察，未冒充 REA 反编译结果。完整原始证据仅保存在本地研究目录，未把用户设备标识或账号页面上传仓库。

## 可观察外观与实现

| 观察 | 本项目实现 |
| --- | --- |
| 56dp 高的悬浮胶囊，左右约 20dp | `NavigationGlass.kt` 固定 56dp 高，主界面两侧 20dp，适配导航栏 Insets |
| 选中块内缩约 4dp，高约 48dp | 4dp 内边距、中性灰胶囊，保留项目强调色图标和标签 |
| 日间乳白、夜间深色；内容在其后柔化 | 独立 RenderNode 采样 Compose 场景，模糊后着色；全局普通组件继续使用 QmDeve |
| 边缘折射柔和、明暗轮廓清楚 | 原创 AGSL 胶囊距离场，靠近边缘的小幅折射和定向亮边，无彩虹描边 |
| 文字和图标不随背景模糊 | Compose 前景单独绘制，24dp 图标、10sp 标签，选中加粗 |
| 点击和横向拖动改变选中位置 | 点击、拖动和页面 Pager 联动；拖动直接跟手并轻微伸展，松手弹簧归位 |

模糊 5.5dp、折射宽度 7dp / 位移 2.5dp、着色比例及弹簧常量属于本项目为逼近观察效果选择的参数，**并非从酷安内部方法中提取**。本实现未复制酷安闭源代码、图标或图片。主题色、入口数量、不同背景和设备渲染会带来差别，未宣称像素级 1:1。

API 33+ 使用 RuntimeShader / AGSL；API 31–32 使用相同背景模糊及材质色；API 26–30 为半透明兼容表面。低版本分支保留导航操作能力，本次真机观察范围为 API 37。

## 页面与采样

原先主界面固定保留 88dp 空白，会让底栏采不到列表。现在根页面铺到底部，底栏作为独立层覆盖在页面上；首页推荐、分页列表、书架、个人页和搜索结果通过 `LocalNavigationClearance` 增加滚动内容末端留白。最后一项能够滚到胶囊上方，同时中间内容从玻璃下方经过。

场景图层不包含底栏、选中块和图标，避免递归采样。底栏记录自身区域，不对全屏截图做 CPU 读回。释放 View 时清理 RenderNode 和采样引用。

## 验证

- JDK 17 / Gradle 8.7 / SDK 35：`testDebugUnitTest assembleDebug` 通过，29 项现有测试无失败。
- BSN-AN00：日夜材质、纯色和彩色背景、动态背景变化、四个入口、双向底栏拖动、页面横滑与选中联动、个人页末端留白、设置和本地浏览历史入口。
- 视觉场景：`adb shell am start -n com.denia.jmreader/.GlassPreviewActivity --es theme light`，夜间使用 `--es theme dark`；支持切换纯色、移动背景和弹窗。场景使用独立设置文件，切回正式主界面测试前先 force-stop，避免单进程 Settings 缓存。
- 不通过静态源码推断实际帧率；本次未做 GPU 驱动差异或所有 Android 版本的性能保证。

当前发布页仍为旧版 Release；本次源码更新与提供的本地 APK 不代表新建了 GitHub Release。
