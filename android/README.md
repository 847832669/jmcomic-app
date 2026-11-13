# JM Reader for Android

JM Reader 的 Android 版本（Kotlin + Jetpack Compose），与 iOS 版同一套设计与数据源，在同一仓库维护。

## 安装

在仓库的 [Releases](https://github.com/denia-dev/jmcomic-app/releases) 最新版发布中下载 APK 附件（`JMReader-Android-v….apk`）直接安装（调试签名），系统要求 Android 8.0（API 26）及以上。

## 功能

- 首页推荐货架 + 频道、搜索（带历史）、官方分类浏览
- 详情页：作者作品页、标签展开、章节列表
- 阅读器：上下滚动，图片自动解打乱还原
- 个人页：账号登录、云端 + 本地收藏（可搜索）、个人资料、每日签到、屏蔽标签、存储空间
- 与 iOS 统一的设计：粉色主题、玻璃卡片、毛玻璃悬浮底栏
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
