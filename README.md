# JM Reader

一款简洁、无广告的 JMComic 第三方阅读客户端，提供 iOS 与 Android 两个原生版本。

- **iOS**：SwiftUI 编写，未签名 IPA 由 GitHub Actions 自动构建
- **Android**：Kotlin + Jetpack Compose，源码位于 `android/` 目录

> 本项目为第三方客户端，与 JMComic 官方无关；内容版权归原作者及平台所有，项目仅供学习交流使用。

## 功能

- 首页推荐货架与频道（最新、同人、单本、短篇、韩漫等）
- 搜索（带搜索历史）与官方分类浏览
- 屏蔽标签：自定义屏蔽名单，首页、分类、搜索等列表按标签自动过滤
- 漫画详情：作者作品页、标签展开、章节列表、相关推荐
- 阅读器：上下滚动，图片自动解打乱还原，原图无压缩
- 账号登录、云端收藏同步、每日签到（当月日历 + 连签奖励）
- 个人页：收藏搜索与排序（最近 / 最早）、观看历史、个人资料（经验值 / 等级 / 金币）
- iOS 26 液态玻璃界面，四个主页面支持左右滑切换

## 安装

### iOS（IPA，需自签）

1. 在 [Releases](https://github.com/denia-dev/jmcomic-app/releases) 下载最新版的 `JMReader-unsigned.ipa`（请确保下载完整，文件不完整会导致解压 / 安装失败）
2. 使用轻松签（ESign）等签名工具，导入自己的证书签名后安装
3. 系统要求 iOS 16 及以上；液态玻璃效果需要 iOS 26

### iOS（描述文件版，免签名）

下载最新 iOS 版发布中的 `JMReader.mobileconfig` 并按系统提示安装，桌面会出现「JM Reader」图标，点开即全屏打开网页版（无广告，界面与 App 一致）。

> 网页版已实测可用：登录、收藏、每日签到、搜索与阅读均可正常使用。

### Android

在 [Releases](https://github.com/denia-dev/jmcomic-app/releases) 最新版发布中下载 APK 附件（`JMReader-Android-v….apk`）直接安装（调试签名），系统要求 Android 8.0 及以上。

> ⚠️ Android 版已在模拟器与真机初步试用（仍在完善中），遇到问题欢迎到 [Issues](https://github.com/denia-dev/jmcomic-app/issues) 反馈运行情况（机型 / 系统版本 / 遇到的问题）。

## 从源码构建

- **iOS**：使用 Xcode 打开 `JMReader.xcodeproj`；仓库的 GitHub Actions 会在提交后自动构建未签名 IPA 并生成草稿发布，三端附件齐备后转为正式发布
- **Android**：见 [android/README.md](android/README.md)

## 版本历史

见 [CHANGELOG.md](CHANGELOG.md)，每个版本的更新说明也会同步发布在对应的 Release 页面。
