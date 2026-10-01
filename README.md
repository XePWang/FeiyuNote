# 肥鱼笔记

中文 | [English](README.en.md)

<img src="app/src/main/res/drawable-nodpi/whale_02_01.webp" width="128" alt="肥鱼笔记图标">

拍下板书或 PPT，围绕问题与 DeepSeek 对话，再把讲解整理成本地复习笔记。支持按课程和课次管理内容，也可以用刷题本记录错题与掌握状态。

项目处于早期阶段，目标是小而美。欢迎 [Issue 和功能建议](https://github.com/Yongzhaooo/FeiyuNote/issues)，**暂不接受 PR**。

## 下载与使用

前往 [Releases](https://github.com/Yongzhaooo/FeiyuNote/releases) 下载 APK，支持 Android 8.0 及以上。预览版仍在迭代，请保留重要笔记的 HTML 导出副本。

1. 安装后打开「设置」。
2. 在 [DeepSeek 开放平台](https://platform.deepseek.com/api_keys) 登录、创建 API Key，复制到应用并保存。API 按使用量计费，请自行检查平台余额与价格。
3. 新建课程和课次，输入问题、拍照或从相册选图，点击发送；需要复习时点击「整理本课」。

笔记、照片和对话保存在设备本地，API Key 使用 Android Keystore 加密保存。发起模型请求时，所选问题、上下文和图片会发送给 DeepSeek。

API Key 以 AES-256-GCM 密文存入应用私有目录，加密密钥由 Android Keystore 管理且不可导出；凭据排除在备份与设备迁移之外，设置页禁止普通截图/录屏。发布 APK 不可调试，普通应用无法读取私有凭据。损坏密文或丢失 Keystore 密钥后需要重新输入 Key。

## 界面

- 跟随系统首选语言：中文使用中文，其他语言使用英语。
- 适配手机单栏和大屏双栏，支持浅色与深色主题。
- 每个课次随机选择一张鲸鱼娘头像并保持不变，也可以在设置中上传自己的头像。
- 使用 Noto Sans SC 开源字体；笔记支持离线阅读、编辑、HTML 导出与分享。
- 0.2 支持离线 LaTeX 公式：行内使用 `$...$` 或 `\(...\)`，独立公式使用 `$$...$$` 或 `\[...\]`。支持常用分数、根号、积分和矩阵；宽公式可横向滑动。笔记可切换编辑/预览，复制保留公式源码，HTML 导出内嵌公式图片。不支持的语法显示原文，不处理完整 TeX 文档或自定义宏。

## 构建与验证

需要 JDK 21、Android SDK 和网络连接。Android Studio 可直接打开本目录。

```bash
# Linux / macOS
bash ./gradlew testDebugUnitTest assembleDebug

# Windows：构建、单元测试、测试 APK 和 dist 产物
pwsh -NoProfile -File scripts/ci.ps1

# 复用独立的 emulator-5556，运行完整仪器与界面测试
pwsh -NoProfile -File scripts/ci.ps1 -Full
```

Windows 首次配置或 SDK 缺失时，运行 `pwsh -File scripts/setup-sdk.ps1`；加 `-WithEmulator` 可补齐模拟器工具/镜像并创建独立的 `Feiyu_CI_API36` 测试实例（已存在则复用）。脚本将 SDK 安装在持久目录（默认 `%LOCALAPPDATA%\Android\Sdk`），对齐用户级 `ANDROID_HOME` 和本地 `local.properties`，不删除既有 AVD 或应用数据。CI 默认使用 `emulator-5556`，只允许显式选择模拟器，不安装到真机；构建前检查 JDK 和平台包，ADB 无响应时及时报错。请将 SDK、`%USERPROFILE%\.android\avd` 和 JDK 目录排除在临时文件清理范围之外。

本地 full CI 使用隔离的数据和模拟回答，不调用计费接口；界面截图保存在 `build/ci/screenshots/`。Git 钩子位于 `.githooks/`，可通过 `git config core.hooksPath .githooks` 启用。

GitHub Actions 在推送 main 时运行单元测试并生成 debug APK；推送 `v*` 标签时使用仓库 Secrets 中的签名密钥构建 release APK，并发布到预览版 Release。详情见 [构建流程](.github/workflows/android.yml)。本地 release 构建如需签名，应设置 `FEIYU_KEYSTORE` 和 `FEIYU_KEY_PASSWORD`（密钥别名 `feiyu`）；密钥不进入仓库。

## 许可与署名

项目代码采用 [GPL-3.0-or-later](LICENSE)。

鲸鱼娘人设：**上善无形、ZipZipPipe**。表情包：小红书 **Hidcote**。图标使用第二组第 1 张（02-01）。字体采用 SIL OFL 1.1；素材与代码许可分开，来源见 [第三方署名](THIRD_PARTY_NOTICES.md)。

## 项目文档

[产品约定](docs/spec.md) · [实施与验证记录](docs/plan.md) · [当前进度](docs/wayfinder.md) · [参与方式](CONTRIBUTING.md)
