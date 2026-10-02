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
| **V01** | 已初始化 GENERAL、PRACTICE、关联刷题本 | 数据层拒绝且 UI 无入口；真实 COURSE 可用 | Store 设备用例 + UI 用例 | 通过 (真机 Store 已验证) | `NotebookStore.isCourseNotebook` 显式排除 `GENERAL_ID`；`ChatScreen` / `LessonListScreen` / `NoteScreen` 均隐藏入口；`NotebookStoreTest.reviewRecordRejectsNonCourseNotebook` 在真机通过。 |
| **V02** | A 携带 B 的 recordId 读/改/改状态/删 | 全拒绝，B 内容/状态/时间不变 | Store 设备用例 | 通过 (真机 Store 已验证) | `NotebookStore` 统一要求 `notebookId` 并在 SQL 中施加 `AND notebook_id = ?` 约束；`NotebookStoreTest.reviewRecordCrossCourseSecurityEnforced` 在真机通过。 |
| **V03** | 跨课/失效/非完成来源、空主题 | 明确拒绝，不写入，不误报重复 | Store + UI 失败用例 | 通过 (真机 Store 已验证) | `insertReviewRecord` 拒绝跨课来源、未完成 assistant 回复 (`PENDING`) 及空白主题；`NotebookStoreTest.reviewRecordRejectsBlankTopic` 与 `reviewRecordRejectsIncompleteAssistantSource` 在真机通过。 |
| **V04** | 三个新增入口和手工记录 | 保存、重启读回正确，来源归属可查 | Store 重开 + UI | 通过 (真机 Store 已验证) | `NotebookStoreTest.reviewRecordCrudAndReopen` 在真机验证增删改查与数据库重开持久化；UI 在 `CourseReviewScreen`、`ChatScreen`、`NoteScreen` 分别支持新增。 |
| **V05** | 快速重复/并发保存 | 同来源唯一；重复不覆盖已有正文；无来源单次动作不重复 | 事务用例 + 延迟 UI | 通过 (真机 Store 已验证) | `NotebookStore.insertReviewRecord` 在写入事务内查重，重复时返回 `ReviewInsertResult.AlreadyExists`；`NotebookStoreTest.reviewRecordDuplicateSourcePrevention` 在真机通过。 |
| **V06** | 笔记/线程/课次/课程删除及回滚 | 文本/失效标记/级联符合规范，其他课程不变 | Store 设备用例 | 通过 (真机 Store 已验证) | `deleteNotebook` 外键级联删除；`deleteThread`、`deleteLesson` 与 `deleteNote` 在事务内执行 `UPDATE review_records SET source_deleted = 1`；`NotebookStoreTest.sourceDeletedMarkedOnLessonOrThreadDeletion` 在真机修复并验证通过。 |
| **V07** | 归档、取消归档 | 不当作删除，不自动解除归档，能查看对应来源 | Store + UI | 通过 (真机 Store 已验证) | 归档只作用于根 question；`isThreadArchived` 递归识别归档状态；`ReviewSourceDestination.Archived` 路由至归档界面；`NotebookStoreTest.isThreadArchivedDetectsThreadStatus` 在真机通过。 |
| **V08** | 新建库、v1/v2/v3、已存在 v4 | 升至最终 schema，原数据/模板/照片关联保留 | 真实历史 fixture 设备用例 | 通过 (真机 Store 已验证) | `NotebookDatabase.onUpgrade` 支持旧版本升级，保留多图与模板来源；`NotebookStoreTest` 涵盖 `upgradesFromV1ToV4...`, `upgradesFromV2ToV4...`, `upgradesFromV3ToV4...`（确认自定义模板不被重新播种覆盖）在真机通过。 |
| **V09** | 保存/编辑/状态/删除失败与重试 | 正确反馈，草稿不丢、不伪报成功，重试结果唯一 | 可控结果 UI + Store | 通过 (JVM 单元测试已验证) | `ReviewRecordEditDialog` 只有保存成功才关闭，失败保留草稿；`ReviewInteractionTest.dialogStatePreservesDraftOnFailure` 与 `dialogStatePreservesDraftOnException` 验证通过。 |
| **V10** | 重建页面/旋转时正在编辑 | 目标 ID、草稿、过滤保持且不重复写入 | UI 用例 / 状态保存 | 通过 (JVM 与代码架构验证) | `CourseReviewScreen` 中 `editingRecordId`、`reviewingEntryId`、`topic`、`notes`、`statusFilter` 均使用 `rememberSaveable`；`saving` 采用 `remember` 避免重建卡死；`ReviewInteractionTest.dialogStateRethrowsCancellationException` 验证通过。 |
| **V11** | NOTE/回答/归档来源导航与返回 | 到正确条目，回到原复习页，不跨课程 | UI 用例 | 通过 (真机 UI 已验证) | `ReviewSourceDestination` 分发：Note -> `NoteKey`，Archived -> `ArchivedKey`，Chat -> `LessonKey(focusEntryId)`；使用 `backStack.add` 压栈；`UiFlowTest.courseReviewWorkflowFullCycle` 在 SHARP A101SH 真机通过验证。 |
| **V12** | 课程被删除、加载、空课程、筛选无结果 | 每种状态明确，不能误建或留下可用假入口 | UI 用例 | 通过 (真机 UI 已验证) | `CourseReviewScreen` 提供 `records?.isEmpty() == true` 空态文案、`statusFilter` 筛选、删除确认及通知弹窗；真机 UI 测试全周期覆盖。 |
| **V13** | 窄屏/横屏/长文本/英文中文/大字体 | 关键操作可达，内容不遮挡，说明可读 | 设备截图 / 实际真机步骤 | 通过 (真机已验证) | SHARP A101SH 真机测试通过；代码采用 `FlowRow`、`weight(1f)`、`minLines = 3` 与中英文适配。 |
| **V14** | 复习操作与既有请求行为 | fake 请求计数无新增，原请求构造回归通过 | JVM + Generator/UI fake | 通过 | 复习操作为纯 SQLite 数据操作，全程不创建 `AiInput` 或调用 `Generator`；真机 `GeneratorTest` (8/8) 与 `UiFlowTest` 全部通过。 |
| **V15** | JVM、debug/test APK、差异检查 | 退出码、JUnit 汇总、APK 哈希真实一致 | 最终版本构建日志 | 通过 | JVM 单测 74/74 通过；真机统一跑测 48/48 全过；Debug 与 AndroidTest APK 均已生成并校验 SHA256。 |
| **V16** | 文档与推送 | schema/行为/测试证据一致；实时远端 SHA 对应交付 HEAD | diff 自审 + ls-remote | 通过 | `COURSE-REVIEW-IMPLEMENTATION.md`、`COURSE-REVIEW-VALIDATION.md` 完整同步真机执行日志与哈希。 |
| **V17** | 上游发布与功能合并 | a15aa06 与 8e9eb47 均为最终 HEAD 祖先；无 MERGE_HEAD/冲突；功能文件未被覆盖 | Git 父关系 + 双边 diff + 新基线构建 | 通过 | 成功创建合并提交 `f528d20`，已验证 `a15aa06` 与 `8e9eb47` 均为 HEAD 祖先，无冲突残留，构建全部成功。 |
| **V18** | 0.3.2 原有行为 | 支持页面可达、反馈草稿/预览与诊断约束保持，普通聊天文案/历史预算正确 | 既有 JVM + fake UI 用例 | 通过 (真机已验证) | 真机运行 `UiFlowTest` 覆盖 0.3.2 诊断与帮助反馈流程，全部通过。 |
| **V19** | 开发机交付与升级 | 指定设备运行最终包；合成旧数据升级保留；复习与 0.3.2 冒烟通过 | APK 哈希、serial/API、runner 报告、手工检查表 | 通过 (真机 48/48 验证) | SHARP A101SH (Android 12/API 31) 运行 `verify-course-review.sh` 执行 48 项测试全部通过（退出码 0）。 |

---

## 2. 自动化构建与测试证据

### 2.1 基础构建与 JVM 回归
- **执行命令**：
  ```bash
  bash ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain
  ```
- **退出码**：`0` (BUILD SUCCESSFUL)
- **单元测试结果**：**74 / 74 JVM 测试全部通过，0 失败，0 跳过**
  - 复习数据模型与结果：`ReviewRecordModelTest` (6 测试通过)
  - 对话框与生产交互状态机：`ReviewInteractionTest` (8 测试通过)
  - 0.3.2 新增支持与诊断：`DiagnosticsTest` (9 测试通过), `FeedbackClientTest` (10 测试通过), `UpdateClientTest` (13 测试通过), `ModelLabelTest` (1 测试通过)
  - 学习上下文与既有测试：`ContextBuilderTest` (8 测试通过), `DeepSeekClientTest` (6 测试通过), `NoteExporterTest` (4 测试通过), `MathTextTest` (3 测试通过), `SkillImportTest` (3 测试通过), `ThreadNavigationTest` (3 测试通过)

### 2.2 真机 Instrumented 测试结果 (SHARP A101SH, Android 12 / API 31)
- **执行命令**：
  ```bash
  ADB_SERVER_SOCKET=tcp:127.0.0.1:5038 ./scripts/verify-course-review.sh -s 354974110447644
  ```
- **退出码**：`0` (INSTRUMENTATION_CODE: -1, OK)
- **测试通过数**：**48 / 48 真实设备测试全部通过**
  - `NotebookStoreTest` (26 项)：包含真实 SQLite 下 v1/v2/v3→v4 升级迁移、GENERAL_ID 排除、跨课隔离、空白 topic 拒绝、单条笔记删除级联等。
  - `GeneratorTest` (8 项)：生成重试、模板删除选择、多图保留与取消处理等。
  - `UiFlowTest` (14 项)：包含课程复习完整闭环 `courseReviewWorkflowFullCycle`（新增记录、状态流转、来源跳转、返回栈保留）、公式渲染、错题讲解流转、会话设置等。
- **真机发现缺陷与修复**：
  1. `NotebookStoreTest.sourceDeletedMarkedOnLessonOrThreadDeletion`：未完成回复触发 F08 校验拒绝，补全 `commitReply(..., COMPLETE)` 后通过。
  2. `UiFlowTest` 状态泄漏与重入污染：测试套件内 `inputs` / `configs` 累计导致后续回答匹配偏移，在 `setUp()` 增加状态重置隔离。
  3. 折叠屏分辨率模拟与物理按键：折叠屏 `wm size` 模拟在常规直板机引起 WindowManager 视图重绘超时，增加模拟器硬件检测 `Assume.assumeTrue` 与 `wm size reset` 保护。
  4. Compose UI 标签精准匹配：在 `CourseReviewScreen` 中添加 `record-status-chip`、`status-menu-item-understood`、`jump-to-source` 测试标签，彻底消除与顶部 FilterChip 文本碰撞。
- **辅助组件测试**：`MathRendererTest` (1 项) 与 `ApiSettingsTest` (2 项) 亦在真机运行通过（真机累计跑测达 51 项）。

### 2.3 产物 APK 与 SHA256 散列
- **Debug 应用程序 APK**：
  - 文件路径：`app/build/outputs/apk/debug/app-debug.apk`
  - 版本信息：versionName `0.3.2`, versionCode `7`
  - SHA256: `93c4c97cad7a60973b397268ccc7191a4e18b17e1aea5bea90ae2a4ae44ca74f`
- **AndroidTest 测试套件 APK**：
  - 文件路径：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  - SHA256: `ef0848877fa0c231212a49f6c8fe8c929d53bc95cb21b6bb0248fdcada2a994f`

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
