# 肥鱼笔记 Wayfinder

**Project outcome:** 学生在安卓手机、平板或折叠屏上，按课程和课次拍照或从相册选图提问、获得 DeepSeek 讲解并主动整理成本地复习笔记；刷题本支持错题讲解和掌握状态。范围与验收以 [spec](spec.md) 为准，工作项与证据以 [plan](plan.md) 为准。

## In progress

**0.2 公式渲染与凭据保护** — running; resume: 19 项 JVM 测试、30 项设备/界面测试和签名 release 构建通过，见 [plan](plan.md#执行记录)；next: 推送 main 与 v0.2.0，确认 GitHub 发布 CI 和 APK。

## Next actions

- **回答中的 Markdown 加粗残留** — Generator 已为新请求加入纯文本指令，历史回答不自动改写。若仍有反馈，再考虑显示/导出处理；入口为 `Generator.kt` 与 `NoteExporter.kt`，验证走既有本地 CI。
- **未发送照片的孤儿文件** — 拍照或选图后未发送即被强杀时，图片留在 `files/images/<notebookId>/` 无引用；决定是否在启动时清理（spec 未要求，需先定范围）。

## Waiting

**备用机基础检查** — PHP110 已重新连接，但安装要求用户完成系统滑块拼图验证；未安装待测中间包，后续安装最终 Release APK；clearing: 用户可在手机上完成验证；supplier: 用户。

**原模拟器残留占用** — 原 Feiyu_Fold_API36 的进程无法正常结束且仍占用 5554/文件锁，数据盘保留；CI 已使用独立 Feiyu_CI_API36（5556）；clearing: 用户方便时重启 Windows 后检查原实例；supplier: 本机环境。

**真实折叠态验收** — 模拟器 `device_state` 切到 CLOSED 后外屏黑屏，折叠与铰链遮挡目前由界面测试中的宽窄窗口切换代替；clearing: 可用的折叠真机或能正常折叠的模拟器镜像；supplier: 用户。

## Done (rolling)

- **首次预览版（2026-10-01）** — [v0.1.0-alpha.1](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.1.0-alpha.1) 已发布，约 22 MB 签名 APK 可直接下载；[发布 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36785654962) 完成编译、签名与上传。标签指向 `9fd23ea`。

- **UI 与中英界面（2026-10-01）** — 02-01 图标、鲸鱼主题、Noto Sans SC、课次随机头像/自定义头像、API Key 指引已实现；完整本地 CI 通过，证据见 [plan](plan.md#执行记录)。
- **开源与构建（2026-10-01）** — 代码采用 GPL-3.0-or-later，素材独立署名。已推送 [Yongzhaooo/FeiyuNote](https://github.com/Yongzhaooo/FeiyuNote)，中文 README 链接英文版；接受 Issue 与功能建议，暂不接受 PR。[主分支 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36784905890) 已通过。签名备份位于仓库外 `../肥鱼笔记-private/signing/`，不要提交。

- **本地 CI/CD（2026-09-30）** — `pwsh scripts/ci.ps1 [-Full]`：单元测试 14 项 + 模拟器仪器/界面测试 24 项全部通过，产物输出到 `dist/`；界面自动化替代了此前的人工截图验收，并修复了双栏返回键直接退出等 4 个缺陷。证据见 [plan 执行记录](plan.md#执行记录)。
- **相册导入（2026-09-30）** — 系统照片选择器选图，复制进私有目录并确认可解码后才作为附件；界面测试打桩验证。
- **真实 DeepSeek 冒烟（2026-09-30）** — `RealApiSmokeTest`（`-e realApi true`）用两张课堂截图完成 5 次调用，全部成功；结果保留在 App 内“真实API验证”课程。
