# 肥鱼笔记 Wayfinder

**Project outcome:** 在安卓手机、平板或折叠屏上按课程、课次拍照或选图提问，获得 DeepSeek 讲解并整理成本地笔记；刷题本记录错题与掌握状态。[spec](spec.md) 定义产品规则，[plan](plan.md) 保存实现边界与验证证据。

## Next actions

以下两项待选择范围：

- **Markdown 加粗残留** — 新请求已有纯文本指令，历史回答不自动改写；若仍有反馈，再决定显示/导出处理，入口为 `Generator.kt`、`NoteExporter.kt`。
- **未发送照片的孤儿文件** — 拍照或选图后未发送即被强杀，`files/images/<notebookId>/` 可能留下无引用图片；是否增加启动清理需先定范围。

## Waiting

**设备安装与界面检查** — PHP110 当前显示 Shizuku 安装确认，0.3.0 的 ADB 安装仍在等待；备用 APK 在 `Download/feiyu-notes-v0.3.0.apk`。TB321FU 已安装 versionCode 3，启动命令成功，界面仍受锁屏阻挡。clearing: 用户处理手机安装提示并解锁平板；supplier: 用户。

**原模拟器残留占用** — Feiyu_Fold_API36 进程仍占用 5554/文件锁，原数据盘保留；CI 使用独立 Feiyu_CI_API36（5556）；clearing: 重启 Windows 后检查原实例；supplier: 本机环境。

**真实折叠态验收** — 模拟器 CLOSED 后外屏黑屏，宽窄窗口测试仅为替代检查，真实折叠与铰链避让未验收；clearing: 可用的折叠真机或可正常折叠的镜像；supplier: 用户。

## Done (rolling)

- **0.3 多图附件（2026-10-01）** — 多选、追加拍照、移除、旧库迁移与双语版本记录已随 [v0.3.0](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.3.0) 发布；本地/远端 CI 和实际 APK 验证见 [plan](plan.md#执行记录)。

- **贡献流程与 PR CI（2026-10-01）** — Issue 表单、批准范围后提交 PR 的流程及只读构建已配置；入口见 [贡献指南](../CONTRIBUTING.md)，验证见 [执行记录](plan.md#执行记录)。
- **0.2 发布（2026-10-01）** — 离线公式与凭据保护已随 [v0.2.0](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.2.0) 发布；本地/远端 CI、APK 校验及 SDK 恢复入口见 [plan](plan.md)。
