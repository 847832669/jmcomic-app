# JM Reader

一款简洁、无广告的 JMComic 第三方阅读客户端，提供 iOS 与 Android 两个原生版本。

- **iOS**：SwiftUI 编写，未签名 IPA 由 GitHub Actions 自动构建
- **Android**：Kotlin + Jetpack Compose，源码位于 `android/` 目录

> 本项目为第三方客户端，与 JMComic 官方无关；内容版权归原作者及平台所有，项目仅供学习交流使用。

## 当前分支

本仓库是 [denia-dev/jmcomic-app](https://github.com/denia-dev/jmcomic-app) 的个人分支，已同步上游 v2.19。当前 Android 版本为 **2.19-glass.4**（versionCode **17**），新增日夜主题、QmDeve 全局液态玻璃、作品描述、按需评论与评论关键词屏蔽；这些分支增强仅针对 Android。

- [Android 安装、功能与构建说明](android/README.md)
- [描述、评论接口与真机验证记录](android/COMMUNITY.md)
- [分支更新日志与上游版本历史](CHANGELOG.md)

## 功能

- 首页推荐货架与频道（最新、同人、单本、短篇、韩漫等）
- 搜索（带搜索历史）：可按类型（作品 / 作者 / 标签 / 人物）与排序筛选，输入 JM 编号可直达；官方分类浏览
- 屏蔽标签与作者：自定义屏蔽名单，首页、分类、搜索等列表自动过滤（收藏与历史不受影响）
- 漫画详情：作者作品页、标签展开、章节列表、相关推荐
- 阅读器：上下滚动与横向翻页两种模式（翻页方向可调），图片自动解打乱还原，原图无压缩；下一章预加载，切换章节不用等
- 账号登录、云端收藏同步（支持多收藏夹）、每日签到（当月日历 + 连签奖励，可自动签到）
- 个人页：收藏搜索与排序（最近 / 最早）、观看历史、阅读统计、个人资料（经验值 / 等级 / 金币）；设置（右上角齿轮）：线路设置（官方域名自动更新、测速与手动切换）、存储空间（缓存占用查看与清理）
- iOS 26 原生液态玻璃界面；Android 版采用 QmDeve 液态玻璃组件，覆盖底栏、工具栏、卡片、按钮、输入框与弹窗

## Android 分支增强

- **日间 / 夜间 / 跟随系统**：个人 → 右上角齿轮 → 外观，切换立即生效，重启保留。
- **全局液态玻璃**：使用 [QmDeve/AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView) 的实际组件；API 33 及以上支持 GPU 模糊、边缘折射和色散，API 26–32 使用半透明表面。
- **作品详情**：长描述可展开、收起和复制；标签下方用胶囊展示浏览量与更新时间。更新时间按 Venera 图源使用接口的 `addtime` 字段。
- **按需评论**：点击「下载」右侧的「评论」才加载，滑动接近底部自动续载；支持刷新、失败重试及登录后发表评论，发送失败保留草稿。
- **评论关键词屏蔽**：在设置中添加或删除关键词，忽略英文大小写，命中正文的评论及其回复分支自动隐藏；名单保存在本机，连续整页被屏蔽仍继续加载后续页。

当前可加载移动 API 返回的全部评论分页，并按接口实际返回的父子关系展示。站点楼中楼的完整读取与回复发送仍受接口、网页验证码限制，详见 [评论实现与限制](android/COMMUNITY.md#楼中楼限制)。

## 安装

### iOS（IPA，需自签）

1. 在 [Releases](https://github.com/denia-dev/jmcomic-app/releases) 下载最新版的 `JMReader-unsigned.ipa`（请确保下载完整，文件不完整会导致解压 / 安装失败）
2. 使用轻松签（ESign）等签名工具，导入自己的证书签名后安装
3. 系统要求 iOS 16 及以上；液态玻璃效果需要 iOS 26

### iOS（描述文件版，免签名）

下载最新 iOS 版发布中的 `JMReader.mobileconfig` 并按系统提示安装，桌面会出现「JM Reader」图标，点开即全屏打开网页版（无广告，界面与 App 一致）。

> 网页版已实测可用：登录、收藏、每日签到、搜索与阅读均可正常使用。

### Android

系统要求 Android 8.0（API 26）及以上。当前分支 APK 已完成本地构建和真机安装，尚未发布 GitHub Release；可按 [Android 构建说明](android/README.md#构建) 生成安装包，后续发布见 [本分支 Releases](https://github.com/847832669/jmcomic-app/releases)。

上游原版 APK 可在 [上游 Releases](https://github.com/denia-dev/jmcomic-app/releases) 下载。当前分支已在雷电模拟器与 BSN-AN00 真机验证，具体设备和测试范围见 [Android 说明](android/README.md#验证记录)。

## 从源码构建

- **iOS**：使用 Xcode 打开 `JMReader.xcodeproj`；现有 GitHub Actions 工作流用于构建未签名 IPA 并生成草稿发布
- **Android**：见 [android/README.md](android/README.md)

## 版本历史

见 [CHANGELOG.md](CHANGELOG.md)。Android `2.19-glass.*` 条目记录本分支增强，其余条目保留上游版本历史。
