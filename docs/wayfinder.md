# 肥鱼笔记 Wayfinder

**Project outcome:** 学生在安卓手机、平板或折叠屏上，按课程和课次拍照或从相册选图提问、获得 DeepSeek 讲解并主动整理成本地复习笔记；刷题本支持错题讲解和掌握状态。范围与验收以 [spec](spec.md) 为准，工作项与证据以 [plan](plan.md) 为准。

## Next actions

- **回答中的 Markdown 加粗残留** — Generator 已为新请求加入纯文本指令，历史回答不自动改写。若仍有反馈，再考虑显示/导出处理；入口为 `Generator.kt` 与 `NoteExporter.kt`，验证走既有本地 CI。
- **未发送照片的孤儿文件** — 拍照或选图后未发送即被强杀时，图片留在 `files/images/<notebookId>/` 无引用；决定是否在启动时清理（spec 未要求，需先定范围）。

## Waiting

**备用机基础检查** — PHP110 的安装要求用户完成系统滑块拼图验证；最终 Release APK 已放到手机 `Download/feiyu-notes-v0.2.0.apk`，尚未安装/运行验收；clearing: 用户可在手机上完成验证；supplier: 用户。

**原模拟器残留占用** — 原 Feiyu_Fold_API36 的进程无法正常结束且仍占用 5554/文件锁，数据盘保留；CI 已使用独立 Feiyu_CI_API36（5556）；clearing: 用户方便时重启 Windows 后检查原实例；supplier: 本机环境。

**真实折叠态验收** — 模拟器 `device_state` 切到 CLOSED 后外屏黑屏，折叠与铰链遮挡目前由界面测试中的宽窄窗口切换代替；clearing: 可用的折叠真机或能正常折叠的模拟器镜像；supplier: 用户。

## Done (rolling)

- **贡献流程与 PR CI（2026-10-01）** — 用户更新了早期贡献政策：Issue 讨论、维护者明确范围并标 approved、fork 开发、PR 检查与人工评审。入口见 [贡献指南](../CONTRIBUTING.md)；Issue 表单、PR 模板和 CODEOWNERS 已配置。PR 构建权限为只读，标签签名发布独立执行；远端 CI 结果见 [plan](plan.md#执行记录)。

- **0.2 公式与凭据保护（2026-10-01）** — [v0.2.0](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.2.0) 已发布，标签 `22b5331`；[main CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36823765077) 和 [发布 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36823765078) 均通过。Release APK 下载后签名、版本及不可调试属性检查通过；本地完整 CI 为 JVM 19 项与 runner OK (30 tests)，证据见 [plan](plan.md#执行记录)。SDK 恢复脚本与独立测试模拟器已可用。

- **界面与开源基线（2026-10-01）** — 鲸鱼主题、中英界面、随机/自定义头像、API Key 指引与本地笔记流程已实现；代码 GPL-3.0-or-later，素材独立署名，中文 README 链接英文版。0.1 发布、既往 CI、相册与真实 API 验收记录保留在 [plan](plan.md#执行记录)。签名备份位于仓库外 `../肥鱼笔记-private/signing/`，不要提交。
