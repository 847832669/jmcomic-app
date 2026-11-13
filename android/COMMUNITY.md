# 描述与评论

入口：作品详情标签下方展示浏览量和更新时间；操作区的「评论」按钮紧邻「下载」，点击后才进入评论页并发起首屏请求。评论列表滚动到底部时自动请求下一页，覆盖当前接口返回的全部分页；命中设置中关键词的评论及其回复分支会被隐藏。评论树按 `parent_CID` 组织，父评论缺失时仍显示该条评论。登录后可发表评论；发送失败保留草稿，同一次发送只提交一次，避免超时自动重试造成重复评论。

描述来自现有 `album?id=…` 返回的 `description`，保留中文、换行、HTML 实体、粗斜体和 HTTP(S) 链接，支持选取复制及长文本展开。空内容显示「暂无描述」。评论使用相同的原生文本渲染，不执行远程 HTML 脚本；内嵌图片以 `[图片]` 占位。

浏览量使用 `total_views`，更新时间按 Venera 的 `jm.js` 使用 `addtime` 时间戳，转换为本地日期并显示为 `yyyy-MM-dd`；接口暂无独立的最后更新时间字段，缺失时显示「—」。两项以标签/值胶囊展示在标签下方，并适配日间和夜间配色。

设置中的「评论关键词屏蔽」支持添加、删除与本地持久化，忽略英文大小写并按评论正文匹配任意一个词；HTML 标签和常见实体先还原为可读文本。命中父评论时隐藏整个回复分支，命中回复时保留其它正常分支。分页继续按服务端原始条数计算，连续整页被屏蔽也会继续读取，直到有可见内容填充列表或接口结束；加载错误等待用户重试。

评论读取：签名 `GET forum?mode=manhua&aid=…&page=…`，返回 `{list,total}`。条目使用 `CID`、`nickname/username`、`content`、`addtime`、`photo`；头像位于当前图片线路的 `/media/users/…`。发送：沿用已登录的 JWT / AVS 会话，表单 `POST comment`，字段 `aid`、`comment`、`status=undefined`。只在服务器明确确认后清空草稿并刷新评论。

2026-11-13 的匿名只读接口验证中，每页实际返回 10 条；适配层从首屏识别页大小，结合服务端总数结束分页，并兼容旧的每页 6 条及缺少总数的响应。接口对象包含 `parent_CID`，模型及界面支持将其构造成回复树；但该作品读取到的所有移动 API 评论均为 `parent_CID=0`，用 `parent_CID` 请求过滤也返回空列表，因此无法从该接口读取楼中楼。

## 参考研究

- [Venera](https://github.com/venera-app/venera)，版本 `a0eba914f4c2a84ac1bc925adec2baabe920b9be`，GPL-3.0：通用 UI 在 `lib/pages/comic_details_page/comments_page.dart`、`comments_preview.dart`、`comic_page.dart`；JS 桥在 `lib/foundation/comic_source/parser.dart`。
- [Venera 图源配置](https://github.com/venera-app/venera-configs)，版本 `a04e05591f7832f7166d6660546613c114d6738a`：`jm.js` 中 `comic.loadInfo`、`loadComments`、`sendComment` 定义站点接口。该禁漫画源忽略 `replyTo`，不提供楼中楼读取或回复发送实现。
- [FfmpegZZZ/JMComic-Api](https://github.com/FfmpegZZZ/JMComic-Api)，检查版本 `94ed9bf4a83e23617880c5761ff163bc73677013`：README 说明它基于 `LingLambda/JMComic-Api` 修改，使用 `jmcomic` 移动 API 的 FastAPI 服务；已注册的路由是 PDF、目录/作品信息、认证及健康检查，没有评论读取或回复路由，不能补上楼中楼能力。作品描述也已由本应用现有的 `album?id=…` 接口提供。

本项目使用 Kotlin / Compose 自行实现，未复制或嵌入 Venera 的 Dart / JavaScript 代码。接口参数按公开图源研究，并通过匿名只读请求验证；继续使用本项目现有签名、解密及登录处理。

## 楼中楼限制

用户提供的网页加载了站点脚本。只读检查确认回复表单通过 `POST /ajax/album_comment` 发送，携带 `comment_id`、`aid`、`video_id`、`comment`、`invite_verification`、`originator` 及 `is_reply=1`；回复表单会先请求 `/captcha/{随机值}`。已加载的页面脚本只在本地展开服务器预先渲染的 `.other-timelines`，没有独立的回复读取请求。网页“查看更多”使用 `POST /ajax/album_pagination`，参数为 `video_id`、`page`、`series`、`with_ad_wcm`；本次页面会话对该请求收到 Cloudflare 403，未尝试绕过拦截。

因此当前交付支持读取全部移动 API 分页及渲染 API 实际返回的父子关系；真正加载站点回复和提交楼中楼回复仍未实现。移动 API 响应不含可读回复，网页提交还依赖验证码和页面 originator 值，不能将已验证的顶层 `POST comment` 冒充为回复接口。没有解验证码，也没有向站点发布评论。

## 验证

`testDebugUnitTest` 验证每页 10/6 条、整页与末页、空页、缺少总数、重复行、字段为空、错误响应、提交确认、`parent_CID` 解析、孤立/嵌套节点分组、关键词屏蔽和时间戳格式化。

debug 构建包含 `CommunityPreviewActivity`，模式 `normal`、`empty`、`error`、`send-error`、`guest`、`filtered`、`all-filtered`、`page-error` 为离线内存测试；后三项分别覆盖连续屏蔽页、全部屏蔽及分页失败。`live` 模式只读真实评论且禁用发送。正式构建不包含该 Activity。

2026-11-13：`2.19-glass.3` / 16 已构建并覆盖安装到 BSN-AN00 真机（API 37）。8 项 JVM 单元测试通过；真机验证了描述展开/收起、两页评论与末页停止、空状态、错误重试、离线发送成功刷新及失败保留草稿、未登录提示。真实接口读取与正式详情页的描述入口、评论跳转、第二页内容变化及返回导航通过。现有六项主题/重启保留检查通过。发送验证使用内存数据，尚未验证真实账号发表评论；没有向站点发布测试评论。

`2.19-glass.4` / 17：14 项 JVM 测试通过；BSN-AN00 真机验证了屏蔽词添加/删除及重启保留、详情未打开评论时不请求、滚动自动分页与末页停止、连续/全部屏蔽页面、分页失败停止及重试，以及正式详情的按钮位置、浏览量 `112862` 和日期 `2026-11-13`。日间/夜间布局均已检查；没有向站点发布评论。
