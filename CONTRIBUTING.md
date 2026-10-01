# 参与项目 / Contributing

肥鱼笔记处于早期阶段，目标是小而美。欢迎报告问题和提出建议；**向本项目主线贡献代码时，请先在 Issue 中获得维护者对范围的明确认可，再提交 PR。**

先搜索[已有 Issues](https://github.com/Yongzhaooo/FeiyuNote/issues)，再选择[问题报告或功能建议](https://github.com/Yongzhaooo/FeiyuNote/issues/new/choose)。报告问题时提供系统/应用版本、复现步骤、预期与实际结果；建议功能时说明使用场景和期望结果。只提建议也欢迎。日志和截图请去除 API Key、个人照片和私人笔记。

## 从提议到合并

1. **讨论与评估**：在 Issue 中讨论提案，维护者评估方向与维护成本。
2. **确认范围**：维护者在 Issue 中明确接受的范围与验收方式，并添加 `approved` 标签。开始编码前请留言认领，避免重复劳动。
3. **分支开发**：Fork 仓库并基于最新 `main` 建立功能分支；遵循现有架构与构建入口。
4. **提交 PR**：一个 PR 聚焦一件事，关联获批 Issue，说明实现与验证结果，界面变更附必要截图；可先开 Draft PR 讨论。
5. **评审合并**：CI 通过后仍需维护者评审，由维护者决定是否合并及何时发布，不自动合并。

按许可证自行 fork、修改和发布无需批准。范围批准仅针对**接收进本项目主线**，不保证最终合并；未经讨论的大型 PR 可能被关闭，待补充 Issue 后再评估。

代码构建见 [README](README.md#构建与验证)。PR CI 仅以只读权限运行单元测试与 debug 构建；界面或平台变更请在 PR 中附带本地完整 CI 证据，文档变更不强制运行设备测试。外部贡献者无需准备签名密钥或真实 API Key。贡献代码沿用 [GPL-3.0-or-later](LICENSE)，第三方代码和素材需保留署名与许可。

## English

Feiyu Notes is an early-stage project. Bug reports and feature suggestions are welcome. **Discuss proposed changes in an Issue and obtain maintainer approval before opening a PR targeted at the upstream repository.**

Search [existing Issues](https://github.com/Yongzhaooo/FeiyuNote/issues), then submit a [bug report or feature request](https://github.com/Yongzhaooo/FeiyuNote/issues/new/choose). Include versions, reproduction steps, and expected/actual results for bugs; describe the use case and desired outcome for suggestions. Suggestions without code are welcome. Remove credentials and private data from logs and screenshots.

1. **Discuss**: Propose changes in an Issue for evaluation.
2. **Approve scope**: Wait for maintainer confirmation of scope and acceptance criteria, plus the `approved` label. Comment before starting work to claim the task.
3. **Branch**: Fork the repository, branch off the latest `main`, and reuse existing architecture and scripts.
4. **PR**: Keep each PR focused, link the approved Issue, and include verification evidence and relevant UI screenshots. Draft PRs are welcome.
5. **Review**: Passing CI still requires maintainer review. The maintainer decides whether to merge and when to release; merging is not automatic.

Forking, modifying, and distributing under GPL-3.0-or-later requires no prior authorization. Approval applies strictly to **upstream integration**. Approved proposals do not guarantee merge acceptance. Unsolicited large PRs may be closed pending an Issue.

Verification details are in the [README](README.en.md#build-and-verify). PR CI runs tests and debug builds with read-only permissions; signing runs only on maintainer release tags. UI/platform PRs should provide local full-CI evidence; documentation changes do not require device tests. Contributors do not need signing credentials or a real DeepSeek API key. Code contributions follow [GPL-3.0-or-later](LICENSE); preserve third-party licenses and attribution.
