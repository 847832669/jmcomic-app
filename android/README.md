# JM Reader for Android

JM Reader 的 Android 版本（Kotlin + Jetpack Compose），与 iOS 版使用同一数据源，在同一仓库维护。本分支当前版本为 **2.19-glass.4**（versionCode **17**），已同步上游 v2.19。

## 安装

系统要求 Android 8.0（API 26）及以上。在 [v2.19-glass.4 Release](https://github.com/847832669/jmcomic-app/releases/tag/v2.19-glass.4) 下载 `JMReader-Android-v2.19-glass.4.apk` 安装，附带 `SHA256SUMS.txt` 校验文件。该安装包为已覆盖安装到 BSN-AN00 真机验证的调试签名版本；也可按下方步骤自行构建。

[上游 Releases](https://github.com/denia-dev/jmcomic-app/releases) 提供上游原版安装包；本分支的主题、QmDeve 材质和评论增强以本仓库源码为准。覆盖安装要求签名一致，本地构建的签名配置见 `app/build.gradle.kts`。

## 功能

- 首页推荐货架与频道、搜索历史与类型/排序筛选、编号直达、官方分类浏览。
- 详情页：作者作品页、标签展开、章节列表，以及可展开、收起和复制的作品描述。
- 信息胶囊：在标签下显示浏览量与更新时间，支持日间和夜间配色。
- 评论页：点击下载右侧的「评论」后加载；滑动接近底部自动续载，支持刷新及失败重试；登录后可发表评论，失败保留草稿。
- 评论关键词屏蔽：在设置中添加、删除关键词并保存在本机；命中评论正文时隐藏对应评论及其回复分支。
- 阅读器：上下滚动、横向翻页和方向切换，图片自动解打乱还原，下一章预加载。
- 个人页：账号登录、云端与本地收藏、收藏夹、观看历史、阅读统计、每日签到、屏蔽标签与作者、线路设置和存储空间。
- 粉色主题、全局液态玻璃卡片与浮动底栏；外观支持跟随系统、日间或夜间，立即生效并重启保留。

## 评论与详情信息

「个人 → 右上角齿轮 → 设置 → 评论关键词屏蔽」中，每次添加一个关键词，点击加号保存，点击词条右侧的关闭图标移除。任意一个词命中评论正文即可屏蔽，英文匹配忽略大小写；名单在本机持久化，无需登录。

作品详情中的评论按钮位于下载按钮右侧。打开评论页才请求首屏，滚动时自动加载后续页；连续页面均被屏蔽时继续请求，直至出现可见内容或接口结束。失败时保留已加载评论，点击「重试」恢复。

浏览量使用 `total_views`；更新时间与 Venera 的禁漫画源一致，使用 `addtime` 时间戳转换为本地 `yyyy-MM-dd` 日期，接口暂无单独的最后更新时间字段。缺失信息显示「—」。

当前支持移动 API 返回的全部评论分页及其实际父子关系。站点楼中楼的完整读取和回复发送尚未实现；接口研究、验证码限制与测试范围见 [COMMUNITY.md](COMMUNITY.md)。

## 构建

环境：JDK 17、Android SDK Platform 35、Gradle 8.7。在本目录创建 `local.properties` 指向本机 Android SDK，例如：

```properties
sdk.dir=C:/Android/Sdk
```

在 `android/` 目录执行：

```shell
gradle testDebugUnitTest assembleDebug
```

- APK：`app/build/outputs/apk/debug/app-debug.apk`
- 单元测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`
- debug 构建附带离线视觉与评论测试 Activity；release 构建不包含这些调试入口。

## 外观与液态玻璃

入口：底部「个人」→ 右上角齿轮 →「外观」。默认跟随系统，选择日间或夜间后立即更新页面文字、背景和系统栏，并保存到本机；无需登录账号。

底栏、工具栏、卡片、搜索与输入框、筛选标签、主要按钮、弹窗和阅读器控制区使用统一玻璃材质。底栏可点击或横向拖动切换页面，选中胶囊以弹簧动画跟随；文字与漫画原图保持清晰。

使用 [QmDeve/AndroidLiquidGlassView](https://github.com/QmDeve/AndroidLiquidGlassView) 的实际 `LiquidGlassView` 组件，由 `:liquidglass` 源码模块提供。原 Java 组件与 AGSL 着色器保留，构建适配 SDK 35 / Java 17。来源版本、MIT 许可证和兼容修正见 [组件库说明](liquidglass/NOTICE.md)。

- Android 13 / API 33 及以上：GPU 模糊、AGSL 边缘折射、色散与材质着色。
- Android 8–12 / API 26–32：清晰、可操作的半透明表面。

通过 Compose / View 适配层调用组件库的 `bind()`。卡片采样环境背景，浮动底栏采样页面内容；采样源与玻璃层分离，避免递归叠加。弹窗使用屏幕坐标对齐背景，每个组件仅录制自身范围。原 Kyant0 AGSL 与 CPU 渲染器已移除；保留已有图层采样和导航布局的历史授权声明。

## 验证记录

2026-11-13 的验证范围：

| 设备 / 环境 | 已验证内容 |
| --- | --- |
| 雷电模拟器 9，Android 9 / API 28 | 日间、夜间、跟随系统切换；手动主题优先级与重启保留 |
| BSN-AN00 真机，API 37，1280 × 2788 | QmDeve 动态背景采样、折射与模糊、弹窗对齐、四个底栏入口、拖动导航、主题切换与重启保留 |
| BSN-AN00 真机，`2.19-glass.4` / 17 | 屏蔽词增删及重启保留、详情按需评论入口、自动分页、连续/全部屏蔽页、末页停止、失败重试、浏览量与日期、日夜布局 |
| JDK 17 / Gradle 8.7 / SDK 35 | `testDebugUnitTest assembleDebug` 通过，14 项 JVM 测试无失败 |

评论发送验证使用离线内存数据；真实站点验证为只读请求，没有发布测试评论。完整记录见 [COMMUNITY.md](COMMUNITY.md#验证)。

调试构建可启动离线玻璃场景：

```shell
adb shell am start -n com.denia.jmreader/.GlassPreviewActivity
```

离线评论分页场景：

```shell
adb shell am start -n com.denia.jmreader/.CommunityPreviewActivity --es mode normal
```

其他评论测试模式及接口限制见 [评论实现说明](COMMUNITY.md)。版本历史见 [CHANGELOG.md](../CHANGELOG.md)。
