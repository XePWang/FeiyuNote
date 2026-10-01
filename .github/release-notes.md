肥鱼笔记 0.2，支持 Android 8.0 及以上。项目仍处于早期预览阶段。

- 对话、笔记支持离线 LaTeX 公式渲染：分数、根号、积分、矩阵；宽公式横向滑动。
- 笔记新增编辑/预览，支持复制公式原文；HTML 导出内嵌公式图片，可离线阅读。不支持的语法保留原文。
- 检查并加固 API Key 保护：Android Keystore + AES-256-GCM，私有密文存储，凭据备份/迁移排除，设置页防截图，损坏凭据检测，配置日志脱敏；release 不可调试。
- 保持既有数据与凭据格式，可覆盖升级，无需迁移笔记。

下载下方 `.apk` 文件安装，在设置中填入自己的 DeepSeek API Key。界面跟随系统首选语言：中文使用中文，其他语言使用英语。数据存储在设备本地，提问时所选内容会发送给 DeepSeek。

欢迎 Issue 和功能建议，暂不接受 PR。

Early preview of Feiyu Notes for Android 8.0+. Download the APK below and add your own DeepSeek API key in Settings. Chinese is used for a Chinese primary system language; all other languages use English. Data is stored locally; content selected for a request is sent to DeepSeek.

Version 0.2 adds offline LaTeX rendering in conversations, notes, and HTML exports, with source-preserving copy and edit/preview modes. API-key protection uses Android Keystore and AES-256-GCM, excludes credentials from backups and device transfer, blocks ordinary Settings captures, detects damaged credentials, and redacts configuration strings. Release builds are not debuggable. Existing notes and credentials remain compatible with an in-place update.

Issues and feature suggestions are welcome. Pull requests are not accepted at this stage.
