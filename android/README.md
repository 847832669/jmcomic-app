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

基于 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 的图层采样、圆角矩形 SDF 折射与色散着色器进行源码适配，保留现有 Compose 1.8 环境。来源版本与变更说明见 [第三方声明](../third-party/Kyant0-AndroidLiquidGlass/NOTICE.md)，许可证为 Apache-2.0。

- Android 13 / API 33 及以上：GPU 模糊、AGSL 边缘折射与七采样色散。
- Android 12 / API 31–32：GPU 模糊与反射高光。
- Android 8–11 / API 26–30：限制分辨率的背景快照、CPU 模糊与 SDF 折射，色散采用三通道近似。

卡片采样环境背景，浮动底栏采样页面内容；采样源与玻璃层分离，避免递归叠加。旧系统静止时降低快照频率，应用进入后台后停止采样。不同 Android 版本的效果与流畅度会有差异。

调试构建附带离线视觉测试场景，可通过 `adb shell am start -n com.denia.jmreader/.GlassPreviewActivity` 启动；正式构建不包含此入口。

2026-11-13：调试 APK 已安装到 BSN-AN00 真机（Android API 37，1280 × 2788）验证。四个底栏入口、横向拖动、设置卡片跳转、动态背景采样与玻璃弹窗通过；GPU AGSL 已启用。日间、夜间、跟随系统、手动覆盖系统与重启保留选择共六项外观检查通过。测试场景使用离线色块验证模糊、边缘折射和清晰前景；未覆盖账号登录、在线内容与完整阅读流程。
