# JM Reader for Android

JM Reader 的 Android 版本（Kotlin + Jetpack Compose），与 iOS 版同一套设计与数据源，在同一仓库维护。

## 安装

在仓库的 [Releases](https://github.com/denia-dev/jmcomic-app/releases) 最新版发布中下载 APK 附件（`JMReader-Android-v….apk`）直接安装（调试签名），系统要求 Android 8.0（API 26）及以上。

## 功能

- 首页推荐货架 + 频道、搜索（带历史）、官方分类浏览
- 详情页：作者作品页、标签展开、章节列表
- 阅读器：上下滚动，图片自动解打乱还原
- 个人页：账号登录、云端 + 本地收藏（可搜索）、个人资料、每日签到、屏蔽标签、存储空间
- 与 iOS 统一的设计：粉色主题、全局液态玻璃卡片与浮动底栏
- 外观切换：个人页右上角齿轮 → 设置 → 外观，可选跟随系统、日间或夜间；立即生效，重启后保留选择

## 构建

环境：JDK 17、Android SDK 35、Gradle 8.7。在本目录放置 `local.properties` 指向本机 SDK 后执行 `gradle assembleDebug`，产物位于 `app/build/outputs/apk/debug/`。

## 外观切换与验证

入口：底部「个人」→ 右上角齿轮 →「外观」。默认跟随系统，选择日间或夜间后立即更新页面文字、背景和系统栏，并保存到本机；无需登录账号。

2026-11-13：使用 JDK 17、Gradle 8.7、Android SDK 35 完成 `assembleDebug`，并将 APK 安装到雷电模拟器 9 的 Android 9（API 28）实例验证：

- 日间与夜间即时切换，背景分别为 `#F2F2F7` 与 `#0E0E12`
- 强制结束应用后重开，夜间选择仍然保留
- 系统设为夜间时，手动日间仍使用浅色界面
- 跟随系统时，分别使用系统夜间和日间配色；重开后保留跟随系统设置
- 测试过程崩溃日志为空

## 液态玻璃

Android 界面的底栏、工具栏、卡片、搜索与输入框、筛选标签、主要按钮、弹窗和阅读器控制区使用统一玻璃材质。底栏可点击或横向拖动切换页面，选中胶囊以弹簧动画跟随；文字与漫画原图保持清晰。

使用 [QmDeve/AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView) 的实际 `LiquidGlassView` 组件，由 `:liquidglass` 源码模块提供。原 Java 组件与 AGSL 着色器保留，构建适配 SDK 35 / Java 17。来源版本、MIT 许可证和兼容修正见 [组件库说明](liquidglass/NOTICE.md)。

- Android 13 / API 33 及以上：新库的 GPU 模糊、AGSL 边缘折射、色散与材质着色。
- Android 8–12 / API 26–32：保留清晰、可操作的半透明表面；新库不支持这些系统的折射效果。

通过 Compose / View 适配层调用组件库的 `bind()`。卡片采样环境背景，浮动底栏采样页面内容；采样源与玻璃层分离，避免递归叠加。弹窗使用屏幕坐标对齐背景，每个组件仅录制自身范围。原 Kyant0 AGSL 与 CPU 渲染器已移除；保留已有图层采样和导航布局的历史授权声明。

调试构建附带离线视觉测试场景，可通过 `adb shell am start -n com.denia.jmreader/.GlassPreviewActivity` 启动；正式构建不包含此入口。

测试设备：BSN-AN00 真机（Android API 37，1280 × 2788）。使用离线色块场景验证折射、模糊、动态采样与弹窗对齐，并检查底栏导航与日夜主题。账号登录、在线内容与完整阅读流程不属于此次材质替换验证范围。

2026-11-13：`2.19-glass.2` / 15 调试构建成功并已覆盖安装到上述真机。四个底栏入口、横向拖动、设置导航、背景更新、弹窗开关和 QmDeve 着色器初始化检查通过；六项主题与重启保留检查全部通过。移动色块后，玻璃内部固定区域的 RGB 平均变化为 17.01 / 255，确认采样随背景更新。
