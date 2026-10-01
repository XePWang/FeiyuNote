# 参与项目 / Contributing

肥鱼笔记处于早期阶段，目标是小而美。欢迎报告问题和提出功能建议；**计划向主线贡献代码时，请先通过 Issue 与维护者确认范围，再提交 PR。**

先搜索[已有 Issues](https://github.com/Yongzhaooo/FeiyuNote/issues)，再通过[新建 Issue](https://github.com/Yongzhaooo/FeiyuNote/issues/new/choose)选择「问题报告」或「功能建议」。报告问题时提供应用/Android 版本、复现步骤、预期与实际结果；建议功能时说明使用场景、遇到的问题和期望结果。只提建议同样欢迎。日志和截图请脱敏，不要提交 API Key、私人笔记或个人照片。

## 从提议到合并

1. **先讨论。** 维护者评估项目方向和维护成本，决定采纳、缩小范围、暂缓或拒绝。
2. **明确范围。** 维护者在 Issue 评论中确认接受的范围和验收方式，并添加 `approved` 标签。开始开发前，请留言认领，避免重复投入。
3. **开发。** Fork 仓库，从最新 `main` 创建功能分支；一个 PR 聚焦一件事，复用现有架构与构建入口。
4. **提交 PR。** 链接获批的 Issue，说明改动和相关验证结果。界面改动附必要截图，也可以先开 Draft PR 讨论实现。
5. **评审与合并。** CI 通过后，由维护者评审范围、实现和维护成本，提出修改或决定合并到 `main`。CI 不会自动合并，发布时机由维护者决定。

任何人都可按许可证自行 fork、开发和发布，无需本项目事先批准。这里的批准针对**是否接收至本项目主线**；需求获批不保证最终 PR 一定合并。未经讨论的大型改动可能被关闭，并请贡献者先补充 Issue。

构建方式见 [README](README.md#构建与验证)。PR CI 运行单元测试、编译 debug APK 并保留产物；界面或平台行为变更应提供本地完整 CI 结果。按改动范围验证，纯文档修改不要求重复跑设备测试。外部贡献者不需要签名密钥或真实 DeepSeek Key；PR 检查使用只读仓库权限，发布签名与上传仅在维护者推送版本标签后执行。

贡献代码沿用 [GPL-3.0-or-later](LICENSE)，引入第三方代码或素材时保留许可与署名。

## English

Feiyu Notes is an early-stage project intended to stay small and focused. Bug reports and feature suggestions are welcome. **Discuss changes intended for upstream in an Issue before submitting a PR.**

Search [existing Issues](https://github.com/Yongzhaooo/FeiyuNote/issues), then use the [bug or feature form](https://github.com/Yongzhaooo/FeiyuNote/issues/new/choose). Describe the problem, context, and desired outcome; include app/Android versions and reproduction steps for bugs. Remove credentials and private data from logs and screenshots. Suggestions without code are welcome.

1. Discuss the proposal. The maintainer may accept, narrow, defer, or decline it.
2. Wait for the maintainer to confirm scope and acceptance criteria in the Issue and add `approved`. Comment before starting work to avoid duplication.
3. Fork the repository and create a focused branch from the latest `main`. Reuse the existing architecture and build tools.
4. Open a PR linked to the approved Issue, with relevant verification evidence and screenshots for UI changes. Draft PRs are welcome for implementation discussions.
5. After CI passes, the maintainer reviews the implementation and decides whether to merge. Passing CI does not trigger automatic merging; release timing remains a maintainer decision.

You may fork, develop, and distribute within the license terms without our approval. Approval here concerns **acceptance into this upstream project**. Approval of a proposal does not guarantee a merge. Large changes submitted without discussion may be closed pending an Issue.

See the [English README](README.en.md#build-and-verify) for verification. PR CI runs unit tests and builds a debug APK with read-only repository permissions; signing and publishing run only for maintainer-pushed version tags. UI/platform changes should include local full-CI evidence; documentation-only changes do not require repeated device tests. Contributors do not need signing credentials or a real DeepSeek API key. Contributions use [GPL-3.0-or-later](LICENSE); preserve third-party licenses and attribution.
