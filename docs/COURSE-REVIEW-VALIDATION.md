# 课程复习记录验收矩阵与真机准备报告 (COURSE-REVIEW-VALIDATION.md)

- **基准提交**：`8e9eb474de730da76e9266ae74974b5ea3641f1f`
- **上游版本**：v0.3.2 (`a15aa06c084f129eee494ff79e72faa216ec8b30`)
- **合并提交**：`f528d204a9d10a0ad052a11622ddfdf8bb3751f9`
- **目标分支**：`feat/course-review-records`
- **远端仓库**：`git@github.com:XePWang/FeiyuNote.git` (origin)

---

## 1. 验收矩阵状态一览 (V01–V19)

| 编号 | 场景要求 | 成功判据 | 最低证据要求 | 当前状态 | 证据与代码位置 |
| --- | --- | --- | --- | --- | --- |
| **V01** | 已初始化 GENERAL、PRACTICE、关联刷题本 | 数据层拒绝且 UI 无入口；真实 COURSE 可用 | Store 设备用例 + UI 用例 | 代码与单测已就绪，设备用例待跑 | `NotebookStore.isCourseNotebook` 显式排除 `GENERAL_ID`；`ChatScreen` / `LessonListScreen` / `NoteScreen` 均隐藏入口；`NotebookStoreTest.reviewRecordRejectsNonCourseNotebook` 覆盖。 |
| **V02** | A 携带 B 的 recordId 读/改/改状态/删 | 全拒绝，B 内容/状态/时间不变 | Store 设备用例 | 代码与单测已就绪，设备用例待跑 | `NotebookStore` 统一要求 `notebookId` 并在 SQL 中施加 `AND notebook_id = ?` 约束；`NotebookStoreTest.reviewRecordCrossCourseSecurityEnforced` 覆盖。 |
| **V03** | 跨课/失效/非完成来源、空主题 | 明确拒绝，不写入，不误报重复 | Store + UI 失败用例 | 代码与单测已通过，设备用例待跑 | `insertReviewRecord` 拒绝跨课来源、未完成 assistant 回复 (`PENDING`) 及空白主题；`NotebookStoreTest.reviewRecordRejectsBlankTopic` 与 `reviewRecordRejectsIncompleteAssistantSource` 覆盖。 |
| **V04** | 三个新增入口和手工记录 | 保存、重启读回正确，来源归属可查 | Store 重开 + UI | 代码与单测已就绪，设备用例待跑 | `NotebookStoreTest.reviewRecordCrudAndReopen` 验证增删改查与数据库重开持久化；UI 在 `CourseReviewScreen`、`ChatScreen`、`NoteScreen` 分别支持新增。 |
| **V05** | 快速重复/并发保存 | 同来源唯一；重复不覆盖已有正文；无来源单次动作不重复 | 事务用例 + 延迟 UI | 代码与单测已就绪，设备用例待跑 | `NotebookStore.insertReviewRecord` 在写入事务内查重，重复时返回 `ReviewInsertResult.AlreadyExists`；`NotebookStoreTest.reviewRecordDuplicateSourcePrevention` 覆盖。 |
| **V06** | 笔记/线程/课次/课程删除及回滚 | 文本/失效标记/级联符合规范，其他课程不变 | Store 设备用例 | 代码与单测已就绪，设备用例待跑 | `deleteNotebook` 外键级联删除；`deleteThread`、`deleteLesson` 与 `deleteNote` 在事务内执行 `UPDATE review_records SET source_deleted = 1`；`NotebookStoreTest.sourceDeletedMarkedOnLessonOrThreadDeletion` 覆盖。 |
| **V07** | 归档、取消归档 | 不当作删除，不自动解除归档，能查看对应来源 | Store + UI | 代码与单测已就绪，设备用例待跑 | 归档只作用于根 question；`isThreadArchived` 递归识别归档状态；`ReviewSourceDestination.Archived` 路由至归档界面；`NotebookStoreTest.isThreadArchivedDetectsThreadStatus` 覆盖。 |
| **V08** | 新建库、v1/v2/v3、已存在 v4 | 升至最终 schema，原数据/模板/照片关联保留 | 真实历史 fixture 设备用例 | 代码与单测已就绪，设备用例待跑 | `NotebookDatabase.onUpgrade` 支持旧版本升级，保留多图与模板来源；`NotebookStoreTest` 涵盖 `upgradesFromV1ToV4...`, `upgradesFromV2ToV4...`, `upgradesFromV3ToV4...`（确认自定义模板不被重新播种覆盖）。 |
| **V09** | 保存/编辑/状态/删除失败与重试 | 正确反馈，草稿不丢、不伪报成功，重试结果唯一 | 可控结果 UI + Store | 通过 (JVM 单元测试已验证) | `ReviewRecordEditDialog` 只有保存成功才关闭，失败保留草稿；`ReviewInteractionTest.dialogStatePreservesDraftOnFailure` 与 `dialogStatePreservesDraftOnException` 验证通过。 |
| **V10** | 重建页面/旋转时正在编辑 | 目标 ID、草稿、过滤保持且不重复写入 | UI 用例 / 状态保存 | 通过 (JVM 与代码架构验证) | `CourseReviewScreen` 中 `editingRecordId`、`reviewingEntryId`、`topic`、`notes`、`statusFilter` 均使用 `rememberSaveable`；`saving` 采用 `remember` 避免重建卡死；`ReviewInteractionTest.dialogStateRethrowsCancellationException` 验证通过。 |
| **V11** | NOTE/回答/归档来源导航与返回 | 到正确条目，回到原复习页，不跨课程 | UI 用例 | 代码已就绪，设备用例待跑 | `ReviewSourceDestination` 分发：Note -> `NoteKey`，Archived -> `ArchivedKey`，Chat -> `LessonKey(focusEntryId)`；使用 `backStack.add` 压栈，返回键直接回到复习页。 |
| **V12** | 课程被删除、加载、空课程、筛选无结果 | 每种状态明确，不能误建或留下可用假入口 | UI 用例 | 代码已就绪，设备用例待跑 | `CourseReviewScreen` 提供 `records?.isEmpty() == true` 空态文案、`statusFilter` 筛选、删除确认及通知弹窗。 |
| **V13** | 窄屏/横屏/长文本/英文中文/大字体 | 关键操作可达，内容不遮挡，说明可读 | 设备截图 / 实际真机步骤 | 未运行：容器无设备 | 待用户连接安卓开发机进行多分辨率/旋转测试；代码采用 `FlowRow`、`weight(1f)`、`minLines = 3` 与双语资源字符串。 |
| **V14** | 复习操作与既有请求行为 | fake 请求计数无新增，原请求构造回归通过 | JVM + Generator/UI fake | 通过 | 复习操作为纯 SQLite 数据操作，全程不创建 `AiInput` 或调用 `Generator`；既有 `ContextBuilderTest`、`GeneratorTest` 保持回归通过。 |
| **V15** | JVM、debug/test APK、差异检查 | 退出码、JUnit 汇总、APK 哈希真实一致 | 最终版本构建日志 | 通过 | JVM 单测 46/46 通过；Debug 与 AndroidTest APK 均已生成，SHA256 校验完毕。 |
| **V16** | 文档与推送 | schema/行为/测试证据一致；实时远端 SHA 对应交付 HEAD | diff 自审 + ls-remote | 进行中 | `COURSE-REVIEW-IMPLEMENTATION.md`、`memory-design.md` 已修正；阶段性合并提交已推送到远端并由 `ls-remote` 核实。 |
| **V17** | 上游发布与功能合并 | a15aa06 与 8e9eb47 均为最终 HEAD 祖先；无 MERGE_HEAD/冲突；功能文件未被覆盖 | Git 父关系 + 双边 diff + 新基线构建 | 通过 | 成功创建合并提交 `f528d20`，已验证 `a15aa06` 与 `8e9eb47` 均为 HEAD 祖先，无冲突残留，构建全部成功。 |
| **V18** | 0.3.2 原有行为 | 支持页面可达、反馈草稿/预览与诊断约束保持，普通聊天文案/历史预算正确 | 既有 JVM + fake UI 用例 | 通过 (JVM 单元测试已验证) | `DiagnosticsTest`、`FeedbackClientTest`、`UpdateClientTest`、`ModelLabelTest`、`ContextBuilderTest` 全部通过。 |
| **V19** | 开发机交付与升级 | 指定设备运行最终包；合成旧数据升级保留；复习与 0.3.2 冒烟通过 | APK 哈希、serial/API、runner 报告、手工检查表 | 交付包已就绪，待真机接入运行 | 产出 APK 及 SHA256，交付一键运行脚本 `scripts/verify-course-review.sh`。 |

---

## 2. 自动化构建与测试证据

### 2.1 基础构建与 JVM 回归
- **执行命令**：
  ```bash
  bash ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain
  ```
- **退出码**：`0` (BUILD SUCCESSFUL)
- **单元测试结果**：**74 / 74 测试全部通过，0 失败，0 跳过**
  - 复习数据模型与结果：`ReviewRecordModelTest` (6 测试通过)
  - 对话框与生产交互状态机：`ReviewInteractionTest` (8 测试通过)
  - 0.3.2 新增支持与诊断：`DiagnosticsTest` (9 测试通过), `FeedbackClientTest` (10 测试通过), `UpdateClientTest` (13 测试通过), `ModelLabelTest` (1 测试通过)
  - 学习上下文与既有测试：`ContextBuilderTest` (8 测试通过), `DeepSeekClientTest` (6 测试通过), `NoteExporterTest` (4 测试通过), `MathTextTest` (3 测试通过), `SkillImportTest` (3 测试通过), `ThreadNavigationTest` (3 测试通过)

### 2.2 产物 APK 与 SHA256 散列
- **Debug 应用程序 APK**：
  - 文件路径：`app/build/outputs/apk/debug/app-debug.apk`
  - 版本信息：versionName `0.3.2`, versionCode `7`
  - SHA256: `4956d79f326f5317568116f1c95a155556f7840d14e49416a3228a9978085134`
- **AndroidTest 测试套件 APK**：
  - 文件路径：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  - SHA256: `fb9ddbdfee7d2fea70403dec9c4587cbf41a05e63ee19306413d10ffca1db274`

---

## 3. 明日安卓开发机真机验收执行指南

### 3.1 环境准备
1. 准备一台开启了 **开发者选项** 与 **USB 调试** 的安卓手机（建议 Android 8.0 / API 26 及以上，无需 root）。
2. 使用 USB 线连接设备至主机，并在手机上弹出调试授权窗口时勾选“始终允许”。
3. 检查设备连接：
   ```bash
   adb devices -l
   ```
   记录输出中的设备序列号（例如 `RFCW10XYZAB`）。

### 3.2 一键执行自动化回归跑测
在 FeiyuNote 仓库根目录下执行：
```bash
./scripts/verify-course-review.sh -s <你的设备序列号>
```
**脚本自动执行流程**：
1. 校验设备序列号存在且已授权（非 unauthorized/offline）。
2. 检测设备 Android API 等级：
   - 若 API >= 33，运行全部测试类（含 `NotebookStoreTest`, `GeneratorTest`, `UiFlowTest`）。
   - 若 API < 33，运行数据层与模型生成测试类（`NotebookStoreTest`, `GeneratorTest`）。
3. 安装 `app-debug.apk` 与 `app-debug-androidTest.apk`。
4. 调用 `am instrument` 运行测试并将完整日志存入 `build/course-review-validation/device-test-<serial>.log`。
5. 若测试存在失败断言，脚本自动返回非零退出码。

### 3.3 手工交互冒烟核对清单
在真机上安装 `app-debug.apk` 后，执行以下关键学习流程验证：

- [ ] **1. 通用对话与练习本隔离**
  - 进入首页顶部的「通用对话」，发送一条提问并等待回答；确认回复卡片底部**不出现**「加入复习」按钮。
  - 打开或创建一个练习本（刷题本）；确认顶部栏与条目卡片**不出现**「课程复习」与「加入复习」按钮。
- [ ] **2. 课程复习记录添加与防重**
  - 进入一个课程笔记本，打开任意课次问答；在助手回复卡片上点击「加入复习」。
  - 弹窗确认主题预填正确，输入说明文本，点击「保存」。
  - 再次在同一条回复上点击「加入复习」，弹窗提示“该内容已在复习记录中”，草稿不丢失。
- [ ] **3. 课程复习列表查看与状态切换**
  - 返回课次列表，点击右上角「课程复习」进入列表。
  - 检查卡片是否展示主题、说明、最后更新时间（格式正确）及状态标签。
  - 点击状态下拉标签，切换为“已理解”或“仍有疑问”，确认颜色和文字即时更新。
  - 使用顶部的“全部 / 待复习 / 已理解 / 仍有疑问”过滤器，确认筛选结果准确。
- [ ] **4. 旋转屏幕与草稿保持**
  - 在复习列表中点击「新建」打开添加弹窗，输入较长的自定义主题与重点。
  - 旋转手机屏幕（竖屏变横屏或横屏变竖屏）；确认弹窗**依然打开**，输入的主题与内容**完整保留**。
- [ ] **5. 回到来源与返回栈**
  - 在带有来源编号的复习卡片上点击「回到来源」；确认精准跳转到对应课次并高亮/定位原条目。
  - 按手机物理返回键或顶部返回箭头；确认直接返回课程复习列表，且原筛选状态保留。
- [ ] **6. 来源删除与失效提示**
  - 在课次中删除一条已经加入复习的笔记或问答。
  - 回到课程复习列表；确认对应复习卡片显示红色「来源已删除」标识，点击跳转被安全拦截。
- [ ] **7. 0.3.2 基础功能冒烟**
  - 点击左上角设置 -> 关于与帮助，确认能够进入帮助界面并显示版本 0.3.2 (7)。
