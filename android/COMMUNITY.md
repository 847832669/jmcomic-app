# 描述与评论

入口：作品详情中的「描述」和「评论」；点击「查看全部评论」进入独立评论页。详情预览前两条评论，完整列表按页加载，支持刷新、重试、登录后发表评论。发送失败保留草稿；同一次发送只提交一次，避免超时自动重试造成重复评论。

描述来自现有 `album?id=…` 返回的 `description`，保留中文、换行、HTML 实体、粗斜体和 HTTP(S) 链接，支持选取复制及长文本展开。空内容显示「暂无描述」。评论使用相同的原生文本渲染，不执行远程 HTML 脚本；内嵌图片以 `[图片]` 占位。

评论读取：签名 `GET forum?mode=manhua&aid=…&page=…`，返回 `{list,total}`。条目使用 `CID`、`nickname/username`、`content`、`addtime`、`photo`；头像位于当前图片线路的 `/media/users/…`。发送：沿用已登录的 JWT / AVS 会话，表单 `POST comment`，字段 `aid`、`comment`、`status=undefined`。只在服务器明确确认后清空草稿并刷新评论。

2026-11-13 的匿名只读接口验证中，每页实际返回 10 条；适配层从首屏识别页大小，结合服务端总数结束分页，并兼容旧的每页 6 条及缺少总数的响应。没有在真实账号下发布测试评论。

## 参考研究

- [Venera](https://github.com/venera-app/venera)，版本 `a0eba914f4c2a84ac1bc925adec2baabe920b9be`，GPL-3.0：通用 UI 在 `lib/pages/comic_details_page/comments_page.dart`、`comments_preview.dart`、`comic_page.dart`；JS 桥在 `lib/foundation/comic_source/parser.dart`。
- [Venera 图源配置](https://github.com/venera-app/venera-configs)，版本 `a04e05591f7832f7166d6660546613c114d6738a`：`jm.js` 中 `comic.loadInfo`、`loadComments`、`sendComment` 定义站点接口。该禁漫画源忽略 `replyTo`，不提供楼中楼回复或评论点赞函数；本次也不显示无可用接口支持的按钮。

本项目使用 Kotlin / Compose 自行实现，未复制或嵌入 Venera 的 Dart / JavaScript 代码。接口参数按上述公开图源研究，并通过匿名只读请求验证；继续使用本项目现有签名、解密及登录处理。

## 验证

`testDebugUnitTest` 验证每页 10/6 条、整页与末页、空页、缺少总数、重复行、字段为空、错误响应及提交确认。

debug 构建包含 `CommunityPreviewActivity`，模式 `normal`、`empty`、`error`、`send-error`、`guest` 为离线内存测试；`live` 模式只读真实评论且禁用发送。正式构建不包含该 Activity。

2026-11-13：`2.19-glass.3` / 16 已构建并覆盖安装到 BSN-AN00 真机（API 37）。8 项 JVM 单元测试通过；真机验证了描述展开/收起、两页评论与末页停止、空状态、错误重试、离线发送成功刷新及失败保留草稿、未登录提示。真实接口读取与正式详情页的描述入口、评论跳转、第二页内容变化及返回导航通过。现有六项主题/重启保留检查通过。发送验证使用内存数据，尚未验证真实账号发表评论；没有向站点发布测试评论。
