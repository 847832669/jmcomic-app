# JM Reader for Android

JM Reader 的 Android 版本（Kotlin + Jetpack Compose），与 iOS 版同一套设计与数据源，在同一仓库维护。

## 安装

在仓库的 [Releases](https://github.com/denia-dev/jmcomic-app/releases) 最新版发布中下载 APK 附件（`JMReader-Android-v….apk`）直接安装（调试签名），系统要求 Android 8.0（API 26）及以上。

## 功能

- 首页推荐货架 + 频道、搜索（带历史）、官方分类浏览
- 详情页：作者作品页、标签展开、章节列表
- 阅读器：上下滚动，图片自动解打乱还原
- 个人页：账号登录、云端 + 本地收藏（可搜索）、个人资料、每日签到
- Material 3 + 粉色主题

## 构建

环境：JDK 17、Android SDK 34、Gradle 8.7。在本目录放置 `local.properties` 指向本机 SDK 后执行 `gradle assembleDebug`，产物位于 `app/build/outputs/apk/debug/`。
