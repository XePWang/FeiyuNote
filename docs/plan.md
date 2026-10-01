# 肥鱼笔记实施与验证记录

产品行为以 [spec](spec.md) 为准；本文记录实现边界与验证证据，待处理事项见 [Wayfinder](wayfinder.md)。

## 实现边界

单个 `app` 模块，Kotlin/Compose、Material 3 Adaptive、Navigation 3；Android 原生 SQLite 保存数据，OkHttp 发送请求。包名 `com.feiyu.notes`，下文 `K` 表示 `app/src/main/java/com/feiyu/notes`。minSdk 26、compileSdk 37、targetSdk 36；构建使用 JDK 21、AGP 9.4.1 与 Gradle 9.8.0。构建命令见 [README](../README.md#构建与验证)。

| 边界 | 核心文件 | 职责与约束 |
| --- | --- | --- |
| 应用生命周期 | `K/FeiyuApp.kt` | 手工持有 `NotebookStore`、`PhotoFiles`、`Generator`；启动时调用 `markPendingInterrupted`，不自动重发请求。 |
| 导航 | `K/ui/AppNavigation.kt` | 导航键只传笔记本、课次、条目 ID；`ListDetailSceneStrategy` 支持单双栏，`BackNavigationBehavior.PopLatest` 保证双栏返回时逐级退栈。 |
| 数据 | `K/data/Models.kt`、`NotebookDatabase.kt`、`NotebookStore.kt` | 单库四表；按笔记本/课次限定查询，写入入口校验归档与掌握状态。事务成功后递增 `changes: StateFlow<Long>` 供页面重读；删除事务成功后再清理图片。数据库 v2 将旧单图迁移为有序图片列表；字段与失效引用规则见 spec §5。 |
| 附件 | `K/data/PhotoFiles.kt` | 只分配、解析和删除私有 `images/<notebookId>/` 内的文件，拒绝含路径分隔符的文件名。 |
| 凭据 | `K/settings/ApiSettings.kt` | 固定 endpoint；API Key 以 AES-256-GCM 加密，Keystore 中的加密密钥不可导出。完整配置只由 `Generator` 读取；设置页只读取模型名和 Key 是否存在，明文不进入笔记库、导出或日志。 |
| 界面状态 | `K/settings/AppPrefs.kt`、`K/study/StudyViewModel.kt` | `AppPrefs.lastLesson: Pair<Long, Long>?` 保存笔记本与课次 ID，失效时清空；另保存课次头像索引。ViewModel 持有选择、草稿、附件等界面状态，不持有请求任务；数据变化、发送和重试前重新校验引用。 |
| 模型客户端 | `K/ai/AiTypes.kt`、`DeepSeekClient.kt` | 注入配置与 OkHttpClient，不读取设置；只允许 user 消息带图，缩放压缩为 JPEG 后编码；返回最终正文，区分认证、限流、网络、空回答与超限错误。协程取消传递到 HTTP 请求，不自动重试。 |
| 上下文 | `K/study/ContextBuilder.kt`、`StudyPrompts.kt` | 纯函数按已保存 user 条目的 action、父链、附件与引用组装请求；不另建一份请求输入。普通问答与整理沿用 spec §6 的不同上下文范围。 |
| 生成 | `K/study/Generator.kt` | 应用级单例，同一时刻只接受一个请求，不随页面销毁。删除前调用 `cancelIfAffected`；`commitReply` 在事务内复核目标仍为 pending，`commitSummary` 复核课次仍存在，迟到结果不能重建已删除内容。 |
| 公式与导出 | `K/math/MathText.kt`、`MathRenderer.kt`、`K/ui/MathContent.kt`、`K/export/NoteExporter.kt` | 纯文本解析与原生离线渲染共用于对话、笔记和 HTML；保留可复制、可编辑源码，失败回退原文。导出转义正文、内嵌公式图片，不带脚本、外部资源或内部条目 ID。 |

## 验证入口

复用 `scripts/ci.ps1`：默认执行构建与 JVM 单测，`-Full` 增加仪器和界面测试。界面测试对相机、相册等外部应用打桩，真实设备交互仍需人工抽查；计费 API 冒烟默认跳过。

SDK 缺失时使用 `scripts/setup-sdk.ps1`，需要模拟器时加 `-WithEmulator`。SDK 默认位于 `%LOCALAPPDATA%\Android\Sdk`，CI 使用独立 `Feiyu_CI_API36`（`emulator-5556`），只接受模拟器目标。JDK、SDK 与 AVD 为持久环境，不应纳入临时目录清理。

## 执行记录

### 0.3 多图附件（2026-10-01）

- 系统多选、拍照追加、逐张移除和导入反馈复用现有附件目录与请求链路；未增加相册读取权限或依赖。数据库 v1 → v2 保留旧图片、文字与关系。
- `scripts/ci.ps1 -Full` 通过：JVM 20 项，仪器/界面 runner `OK (33 tests)`，真实 API 冒烟默认跳过。新增覆盖旧库迁移、多图保存/删除、请求顺序、完整重试与缺图拒绝；界面覆盖多选、坏图、移除、拍照追加、取消及 Activity 重建。截图 `build/ci/screenshots/multi-photo-phone.png` 已检查。
- 本地签名 `assembleRelease lintVitalRelease` 通过，`apksigner` 验签成功，release 的 `debuggable=false`。沿用原发布签名。
- 断线测试发现 OkHttp 默认连接重试可能重发请求，已禁用并断言失败请求只发送一次。


以下为截至 2026-10-01 的验证结果，不代表所有真实设备场景均已验收。

### 0.2 本地功能与安全

- 完整命令 `pwsh -NoProfile -File scripts/ci.ps1 -Full -Serial emulator-5556 -Avd Feiyu_CI_API36` 通过；JVM 单测 19 项，仪器/界面 runner 报告 `OK (30 tests)`，其中真实 API 冒烟默认跳过。
- 自动化覆盖问答、追问、展开、整理、笔记编辑/导出/分享、相机相册打桩、刷题本、模板、归档删除、冷启动恢复、单双栏切换与草稿保留，以及首选语言和随机/自定义头像。
- 公式覆盖行内/独立公式、分式/根号/积分/矩阵、原文复制、编辑预览、无效语法回退与离线导出。窄屏截图位于 `build/ci/screenshots/math-chat-phone.png`；长公式位图宽度有渲染测试，未单独验证横向滑动手势。
- 独立合成 Key 验证密文落盘、随机 IV、Keystore 密钥不可导出、私有文件 UID/权限、篡改拒绝、密钥丢失后重新输入、备份排除与 FileProvider 隔离；界面测试检查 `FLAG_SECURE`，JVM 测试检查配置字符串脱敏。未读取用户真实 Key。
- 2026-09-30 的真实 DeepSeek 冒烟以两张课堂截图完成 5 次调用，均为 COMPLETE，覆盖照片、照片加文字、附原图追问、展开与整理；这是历史接口验证，不代表真机安装验收。

### 发布与贡献 CI

| 版本 / 提交 | 验证证据 |
| --- | --- |
| [v0.3.0](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.3.0) · `0f440de` | [main CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36884434892)、[发布 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36884438952) 通过。实际 Release APK 已下载验签，包名 `com.feiyu.notes`、versionCode 3、versionName 0.3.0、debuggable=false。TB321FU 已安装；手机安装与真机界面检查待系统交互，见 Wayfinder。 |
| [v0.1.0-alpha.1](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.1.0-alpha.1) · `9fd23ea` | [main CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36784905890)、[发布 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36785654962) 通过，签名预览 APK 已发布。 |
| [v0.2.0](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.2.0) · `22b5331` | 本地签名 release 构建及 `lintVitalRelease`、[main CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36823765077)、[发布 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36823765078) 通过。下载实际 Release APK 后用 `apksigner` 验证签名，`apkanalyzer` 确认包名 `com.feiyu.notes`、versionCode 2、versionName 0.2.0、debuggable=false。 |
| 贡献流程 · `287191f` | [CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36835816719) 单测、debug 构建和 APK 上传通过，main 推送正确跳过 release；CODEOWNERS 检查无错误，`approved` 标签已建立。 |

贡献规则见 [CONTRIBUTING](../CONTRIBUTING.md)。PR 事件及只读权限已检查配置，尚无外部 PR 的实际运行记录。CODEOWNERS 只请求评审，未启用分支保护或强制审批门禁。签名备份位于仓库外 `../肥鱼笔记-private/signing/`，不得提交。

未完成的真机、折叠态与环境检查，以及已知显示/附件问题，统一保留在 [Wayfinder](wayfinder.md)。
