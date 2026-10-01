肥鱼笔记 0.3.2，支持 Android 8.0 及以上。项目仍处于早期预览阶段。

- 设置新增“关于与帮助”：显示当前版本，可手动检查更新，有新版时打开独立下载页 <https://feiyunote.cangming.fyi/feiyu/>，不需要访问 GitHub。
- 应用内反馈问题：填写描述后可选择附带诊断信息（默认不附带），提交前先预览，成功后显示反馈编号。草稿保存在本机，发送失败可重试，不会重复提交；网络不可用时也可以通过系统分享或复制发出。反馈页提供 QQ 讨论群 1079399140。
- 本机保存有大小和期限上限的诊断记录，只记录操作和错误类型，不含 API Key、聊天、笔记或图片，不会自动上传。应用意外退出后，下次启动会提示查看或忽略。
- 输入区的模型标签缩短为 `dsf.low` 这类形式，窄屏不再换行；公共聊天的“整理本课”改为“整理对话”；很长的对话只发送最近部分的历史，避免超出接口上限。

0.3.1 没有检查更新入口，请通过上面的下载页或本页下方的 APK 覆盖安装，笔记、照片和设置都会保留。数据保存在本地，提问时所选内容会发送给 DeepSeek。

欢迎[提出功能建议](https://github.com/Yongzhaooo/FeiyuNote/issues/new?template=feature.yml)；贡献代码前请通过 Issue 确认范围，详见[贡献指南](https://github.com/Yongzhaooo/FeiyuNote/blob/main/CONTRIBUTING.md)。

Feiyu Notes 0.3.2 is an early preview for Android 8.0+. Settings now has About and help: see your version, check for updates manually, and open the independent download page (<https://feiyunote.cangming.fyi/feiyu/>, no GitHub needed). Report a problem inside the app with optional diagnostics (off by default), a preview before sending and a report number on success; drafts survive failures, retries never duplicate a report, and you can share or copy the report when offline. Bounded local diagnostics record only operation and error types, never your API key, chats, notes or images, and nothing is uploaded automatically; after an unexpected exit the app offers to review or ignore it. The composer model label is shorter (e.g. `dsf.low`), general chat says "Summarize chat", and very long chats send only recent history.

Upgrade from 0.3.1 with the download page or the APK below; notes, photos and settings are kept. Data stays on your device; selected content is sent to DeepSeek when you ask a question. See the [README](https://github.com/Yongzhaooo/FeiyuNote/blob/main/README.en.md) and the [contribution guide](https://github.com/Yongzhaooo/FeiyuNote/blob/main/CONTRIBUTING.md#english).
