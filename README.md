# 肥鱼笔记

中文 | [English](README.en.md)

<img src="app/src/main/res/drawable-nodpi/whale_02_01.webp" width="128" alt="肥鱼笔记图标">

拍下板书或 PPT，围绕问题与 DeepSeek 对话，再把讲解整理成本地复习笔记。支持按课程和课次管理内容，也可以用刷题本记录错题与掌握状态。

项目处于早期阶段，目标是小而美。欢迎[报告问题](https://github.com/Yongzhaooo/FeiyuNote/issues/new?template=bug.yml)和[建议功能](https://github.com/Yongzhaooo/FeiyuNote/issues/new?template=feature.yml)。向主线贡献代码前，请先在 Issue 中确认范围；详见[贡献指南](CONTRIBUTING.md)。

## 下载与使用

前往 [Releases](https://github.com/Yongzhaooo/FeiyuNote/releases) 下载预览版 APK，支持 Android 8.0 及以上。

1. 安装后打开「设置」。
2. 在 [DeepSeek 开放平台](https://platform.deepseek.com/api_keys) 创建 API Key 并存入应用。API 按使用量计费，请自行检查平台余额与价格。
3. 新建课程和课次，输入问题、拍照或从相册选图后发送；需要复习时点击「整理本课」。

笔记、照片和对话保存在设备本地。发起模型请求时，所选问题、上下文和图片会发送给 DeepSeek。

API Key 使用 AES-256-GCM 加密存入私有目录，加密密钥由 Android Keystore 管理且不可导出。凭据不参与备份或设备迁移；设置页防截图、录屏，发布 APK 不可调试。密文损坏或 Keystore 密钥丢失后需重新输入 Key。

## 界面与特性

- 跟随系统首选语言：中文环境使用中文，其余语言使用英文。
- 适配手机单栏与大屏双栏，支持浅色与深色主题。
- 每个课次随机分配一张鲸鱼娘头像并保持稳定，支持在设置中更换自定义头像。
- 笔记支持离线阅读、编辑/预览、HTML 导出与系统分享。
- 0.2 原生离线 LaTeX 公式：行内支持 `$...$` 与 `\(...\)`，独立公式支持 `$$...$$` 与 `\[...\]`。支持常用分式、根号、积分和矩阵；宽公式横向滚动。复制保留公式源码，HTML 导出内嵌公式图片。不支持的语法显示原文，不支持完整 TeX 文档或自定义宏。

## 版本更新

- **0.3.0**：一条提问支持多张图片；相册多选、继续拍照追加、逐张预览和移除，导入失败保留其他图片。旧单图笔记自动迁移，多图支持重试与删除清理。使用系统照片选择器，无需整个相册的读取权限。
- **[0.2.0](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.2.0)**：对话、笔记与导出支持离线 LaTeX；新增编辑/预览及源码复制，完善 API Key 加密与凭据保护。
- **[0.1.0-alpha.1](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.1.0-alpha.1)**：课程/课次问答、刷题本、笔记整理与导出；中英界面、鲸鱼头像与自适应布局。

## 后续方向

近期候选：改善回答中 Markdown 格式的显示，清理未发送图片的残留文件，补齐真实折叠屏验证。较远期考虑讲解模板扩展和复习卡片；尚无固定排期，范围通过 Issue 讨论后确定。

有其他需求，欢迎[提出功能建议](https://github.com/Yongzhaooo/FeiyuNote/issues/new?template=feature.yml)，说明使用场景；参与开发请先阅读[贡献指南](CONTRIBUTING.md)。

## 构建与验证

需要 JDK 21、Android SDK（compileSdk 37）和网络连接。Android Studio 可直接打开项目。

```bash
# Linux / macOS
bash ./gradlew testDebugUnitTest assembleDebug

# Windows：快速构建与单测
pwsh -NoProfile -File scripts/ci.ps1

# 完整 CI：使用独立测试模拟器运行仪器与界面测试
pwsh -NoProfile -File scripts/ci.ps1 -Full
```

Windows 首次配置或 SDK 缺失时运行 `pwsh -File scripts/setup-sdk.ps1`；需要完整 CI 时加 `-WithEmulator`，创建或复用 `Feiyu_CI_API36`。SDK 默认位于 `%LOCALAPPDATA%\Android\Sdk`；CI 默认使用 `emulator-5556`，只接受模拟器目标。

本地完整 CI 使用隔离数据和模拟回答，不调用计费接口；截图位于 `build/ci/screenshots/`。可通过 `git config core.hooksPath .githooks` 启用 Git 钩子。

GitHub Actions 对指向 main 的 PR 及 main 推送运行单测和 debug 构建，使用只读权限。推送 `v*` 标签且构建通过后，独立任务使用仓库 Secrets 签名并发布预览版。详见[构建流程](.github/workflows/android.yml)。本地签名 release 构建需配置 `FEIYU_KEYSTORE` 与 `FEIYU_KEY_PASSWORD`（别名 `feiyu`），勿提交密钥。

## 许可与署名

代码采用 [GPL-3.0-or-later](LICENSE)。

鲸鱼娘人设：**上善无形、ZipZipPipe**。表情包：小红书 **Hidcote**（图标为 02-01）。Noto Sans SC 字体采用 SIL OFL 1.1；素材与代码许可分开，详见[第三方声明](THIRD_PARTY_NOTICES.md)。

## 项目文档

[产品约定](docs/spec.md) · [实施与验证记录](docs/plan.md) · [当前状态](docs/wayfinder.md) · [参与方式](CONTRIBUTING.md)
