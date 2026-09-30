# 肥鱼笔记 Wayfinder

**Project outcome:** 学生在安卓手机、平板或折叠屏上，按课程和课次拍照或从相册选图提问、获得 DeepSeek 讲解并主动整理成本地复习笔记；刷题本支持错题讲解和掌握状态。范围与验收以 [spec](spec.md) 为准，工作项与证据以 [plan](plan.md) 为准。

## Next actions

- **发布首次预览版** — UI 与中英界面已实现，代码许可选定 GPL-3.0-or-later；本地 CI、备用机检查与 GitHub 发布流程收尾中。目标仓库 `Yongzhaooo/FeiyuNote`。签名备份保存在仓库外 `../肥鱼笔记-private/signing/`，不要提交。
- **回答中的 Markdown 加粗残留** — 真实调用中模型偶尔输出 `**…**`，普通文本界面会原样显示；新请求已加强纯文本约束；若仍有问题，再考虑显示/导出前去除强调标记，入口 `app/src/main/java/com/feiyu/notes/study/StudyPrompts.kt`，改后补 ContextBuilder/NoteExporter 单测并跑 `pwsh scripts/ci.ps1 -Full`。
- **未发送照片的孤儿文件** — 拍照或选图后未发送即被强杀时，图片留在 `files/images/<notebookId>/` 无引用；决定是否在启动时清理（spec 未要求，需先定范围）。

## Waiting

**真实折叠态验收** — 模拟器 `device_state` 切到 CLOSED 后外屏黑屏，折叠与铰链遮挡目前由界面测试中的宽窄窗口切换代替；clearing: 可用的折叠真机或能正常折叠的模拟器镜像；supplier: 用户。

## Done (rolling)

- **本地 CI/CD（2026-09-30）** — `pwsh scripts/ci.ps1 [-Full]`：单元测试 14 项 + 模拟器仪器/界面测试 24 项全部通过，产物输出到 `dist/`；界面自动化替代了此前的人工截图验收，并修复了双栏返回键直接退出等 4 个缺陷。证据见 [plan 执行记录](plan.md#执行记录)。
- **相册导入（2026-09-30）** — 系统照片选择器选图，复制进私有目录并确认可解码后才作为附件；界面测试打桩验证。
- **真实 DeepSeek 冒烟（2026-09-30）** — `RealApiSmokeTest`（`-e realApi true`）用两张课堂截图完成 5 次调用，全部成功；结果保留在 App 内“真实API验证”课程。
