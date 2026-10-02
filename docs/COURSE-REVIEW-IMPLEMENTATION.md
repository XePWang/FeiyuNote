# 课程复习记录（Course Review Records）首版实施报告

## 1. 概述与设计边界

本变更实现了 FeiyuNote “课程复习记录”的首版本地操作闭环，严格落实用户讨论共识与规范约定：

- **业务目标**：帮助学生在课程学习过程中记录疑难点与知识状态（“待复习 / 已理解 / 仍有疑问”），支持随时修改、删除、标记状态并回溯原提问/回答/笔记。
- **作用域边界**：
  - 严格限定在单个 `COURSE` 笔记本内（按 `notebookId` 实现逻辑约束与隔离），练习本（`PRACTICE`）与通用对话（`GENERAL_ID`，即 -2）严禁开放复习记录。
  - 数据层强制校验：公开接口统一要求 `notebookId` 并校验 `COURSE` 类型（排除 `GENERAL_ID`）；跨课程 ID 篡改操作一律拒绝。
  - 严禁引入全局跨课记忆、跨课自动检索、普通问答自动注入、后台摘要或能力画像。
  - 本地纯数据操作闭环，**零模型请求消耗**。
- **生命周期与不变量**：
  - 笔记本删除：外键级联删除该课程下全部复习记录。
  - 来源条目/线程/课次/笔记删除：在同一事务中保留复习记录及原文本，置 `source_deleted = 1`，界面展示“来源已删除”并禁用跳转，避免笔记内容随聊天或课次清理丢失。
  - 来源分类精准路由：点击“回到来源”时，`NOTE` 条目精准路由到笔记页面（`NoteKey`），已归档条目路由到归档页面（`ArchivedKey`），活动问答助手回复路由到对应课次定位行（`LessonKey(focusEntryId)`），返回时保留复习列表与原筛选状态。
  - 防重插入：同一来源条目在同一课程下仅能创建一条有效复习记录，避免重复点击产生重复数据。
  - 保存容错：异步写入成功才关闭弹窗；保存中禁用重复提交；写入失败、条目被删或网络异常时展示可理解错误，**绝不静默丢弃草稿**；支持页面旋转/重建后上下文保持；遵从协程取消。

---

## 2. 数据层与 SQLite v4 架构

### 2.1 数据模型与操作结果
- [`ReviewStatus`](app/src/main/java/com/feiyu/notes/data/Models.kt): 枚举类型，对应数据库字符串字段：
  - `PENDING` ("pending"): 待复习
  - `UNDERSTOOD` ("understood"): 已理解
  - `CONFUSED` ("confused"): 仍有疑问
- [`ReviewRecord`](app/src/main/java/com/feiyu/notes/data/Models.kt): 数据类，包含字段：
  - `id: Long`, `notebookId: Long`, `topic: String`, `notes: String`, `sourceEntryId: Long? = null`, `sourceDeleted: Boolean = false`, `status: ReviewStatus = ReviewStatus.PENDING`, `createdAt: Long`, `updatedAt: Long`
- [`ReviewInsertResult`](app/src/main/java/com/feiyu/notes/data/Models.kt): 明确区分 5 种写入结果：
  - `Success(val record: ReviewRecord)`: 写入成功
  - `AlreadyExists(val existingRecord: ReviewRecord)`: 该来源条目已存在有效复习记录
  - `SourceNotFound`: 来源条目不存在、已被删除、非已完成内容或不属于本课程
  - `InvalidCourse`: 笔记本不存在、为通用对话或非 `COURSE` 类型
  - `Failed`: 数据库写入失败或输入为空

### 2.2 SQLite Schema (v4)
在 [`NotebookDatabase.kt`](app/src/main/java/com/feiyu/notes/data/NotebookDatabase.kt) 中将数据库版本升级至 `VERSION = 4`：

```sql
CREATE TABLE IF NOT EXISTS review_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    notebook_id INTEGER NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
    topic TEXT NOT NULL,
    notes TEXT NOT NULL,
    source_entry_id INTEGER,
    source_deleted INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL CHECK (status IN ('pending', 'understood', 'confused')),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_review_records_notebook ON review_records(notebook_id);
```

- **外键与 CHECK 约束说明**：
  - `notebook_id` 设置外键并 `ON DELETE CASCADE`，保证课程删除时完整级联清理复习记录。
  - `source_entry_id` 刻意**不设**外键约束，以支持在源条目（问答、笔记）被删除时，复习记录能够继续保留学生自主总结的知识点正文，并通过应用层标记 `source_deleted = 1` 标识来源已失效。
  - `status` 带有 SQLite `CHECK (status IN ('pending', 'understood', 'confused'))` 约束。
- **非破坏性迁移**：在 `onUpgrade (oldVersion < 4)` 中执行上述建表与建索引操作。保留原有 `oldVersion < 2`（多图字段）与 `oldVersion < 3`（模板来源）升级逻辑。v3→v4 升级中不执行 `seedGuidedTemplate`，完整保留用户已修改、删除或导入的模板，不发生模板重复播种。

### 2.3 存储与数据访问安全约束 ([`NotebookStore.kt`](app/src/main/java/com/feiyu/notes/data/NotebookStore.kt))
- 移除所有无 `notebookId` 的非限定公开接口。所有复习记录操作强制传入 `notebookId`：
  - `listReviewRecords(notebookId: Long): List<ReviewRecord>`
  - `getReviewRecord(notebookId: Long, recordId: Long): ReviewRecord?`
  - `findReviewRecordBySource(notebookId: Long, sourceEntryId: Long): ReviewRecord?`
  - `insertReviewRecord(notebookId: Long, topic: String, notes: String, sourceEntryId: Long? = null, status: ReviewStatus = PENDING): ReviewInsertResult`
  - `addReviewRecord(notebookId: Long, ...): ReviewRecord?`（便捷包装，成功或已存在返回记录）
  - `updateReviewRecord(notebookId: Long, recordId: Long, topic: String, notes: String, status: ReviewStatus? = null): Boolean`
  - `setReviewStatus(notebookId: Long, recordId: Long, status: ReviewStatus): Boolean`
  - `deleteReviewRecord(notebookId: Long, recordId: Long): Boolean`
  - `isThreadArchived(entryId: Long): Boolean`（递归检查来源所在的问答树根节点是否已归档）
- **通用对话隔离 (GENERAL_ID = -2)**：`NotebookStore.generalChat()` 创建的通用对话在底层数据表中 `kind = 'course'`，`isCourseNotebook` 显式判定 `notebookId != GENERAL_ID && count("SELECT COUNT(*) FROM notebooks WHERE id = ? AND kind = 'course'", notebookId) == 1L`，彻底杜绝通用对话被误识别为课程。
- **输入校验**：数据层在 `insertReviewRecord` 与 `updateReviewRecord` 中严格检查 `topic.isBlank()`，空白主题直接返回失败，防止空记录入库。
- **时钟注入**：所有新增、修改、来源标记失效操作统一使用注入的 `clock()`，包括 `deleteLesson`、`deleteThread` 与 `deleteNote`。
- **单条笔记删除级联保护**：`deleteNote(noteId)` 在同一事务中执行 `UPDATE review_records SET source_deleted = 1, updated_at = ? WHERE source_entry_id = ? AND source_deleted = 0`，确保单独删除笔记后对应复习记录正确变为失效状态。

---

## 3. UI 交互、路由与草稿保护

- **编辑与添加对话框** ([`ReviewRecordEditDialog`](app/src/main/java/com/feiyu/notes/ui/CourseReviewScreen.kt)):
  - 采用异步签名：`onSave: suspend (topic: String, notes: String) -> String?`（成功返回 `null`，失败返回可理解错误文案）。
  - **保存成功才关闭**：仅在 `onSave` 返回 `null` 时触发 `onDismiss()`。
  - **防重复提交**：保存过程中锁定 `saving = true`，禁用保存按钮、取消按钮及文本编辑，防止手抖重复提交；`saving` 采用常规 `remember`，页面重建时不会卡死在无运行协程的 `saving=true` 状态。
  - **协程取消遵从**：捕获 `CancellationException` 时显式重抛，不吞掉生命周期取消。
  - **草稿保护**：`topic`、`notes` 与 `errorMessage` 采用 `rememberSaveable` 保存，保存失败或抛出异常时显示错误提示，草稿内容完整保留。外层 `editingRecordId` 与 `reviewingEntryId` 同样采用 `rememberSaveable`，屏幕旋转或 Activity 重建后编辑对话框不意外关闭。
- **状态区分与反馈**：
  - [`StudyViewModel.addToReview`](app/src/main/java/com/feiyu/notes/study/StudyViewModel.kt) 与 [`NoteScreen`](app/src/main/java/com/feiyu/notes/ui/NoteScreen.kt) 完整处理 `ReviewInsertResult`：
    - `Success` -> 提示“已加入复习”，关闭对话框；
    - `AlreadyExists` -> 对话框提示“该内容已在复习记录中”，保留草稿；
    - `SourceNotFound` -> 对话框提示“来源内容已删除或不可用”，保留草稿；
    - `InvalidCourse` / `Failed` -> 对话框提示“保存失败，请稍后重试”，保留草稿。
- **主界面与卡片** ([`CourseReviewScreen.kt`](app/src/main/java/com/feiyu/notes/ui/CourseReviewScreen.kt)):
  - 顶部栏展示课程名称与新建按钮。
  - `FilterChip` 筛选（全部 / 待复习 / 已理解 / 仍有疑问）。
  - 下拉快速切换状态、弹窗编辑、确认删除；操作按钮文案明确区分“编辑”（`edit`）与“删除”（`delete`）。
  - 卡片展示本地化格式的最后更新时间（`updatedAt`）。
- **精准来源导航与返回栈** ([`AppNavigation.kt`](app/src/main/java/com/feiyu/notes/ui/AppNavigation.kt)):
  - 通过 [`ReviewSourceDestination`](app/src/main/java/com/feiyu/notes/ui/ReviewState.kt) 区分三种目标：
    - `Note` -> `backStack.add(NoteKey(notebookId, lessonId, noteId))`
    - `Archived` -> `backStack.add(ArchivedKey(notebookId, lessonId))`
    - `Chat` -> `backStack.add(LessonKey(notebookId, lessonId, focusEntryId = entryId))`
  - 使用 `backStack.add` 压栈，用户从来源页面按返回键时直接弹出并返回课程复习列表，原滚动与筛选状态完整保留。

---

## 4. 测试与验证证据

### 4.1 自动化测试证据
1. **JVM 单元测试**（74 项全部通过，`./gradlew testDebugUnitTest`）：
   - [`ReviewRecordModelTest.kt`](app/src/test/java/com/feiyu/notes/data/ReviewRecordModelTest.kt)（6 项测试）：
     - 状态数据库映射字符串 (`pending`, `understood`, `confused`)。
     - 状态解析及非法值回退 `PENDING`。
     - 字段默认值及 copy 生命周期。
     - `ReviewInsertResult` 密封接口数据载荷完整性。
   - [`ReviewInteractionTest.kt`](app/src/test/java/com/feiyu/notes/ui/ReviewInteractionTest.kt)（7 项生产状态组件测试）：
     - 问答默认复习主题/摘要提取（首行截取、超长截断、空白回退默认标题）。
     - 笔记默认复习主题/摘要提取（40 字/120 字截取）。
     - 对话框空白主题拦截、提交前后 trim 校验。
     - 对话框保存失败（如来源已删除）时草稿完整保留验证。
     - 对话框异常时草稿完整保留验证。
     - 对话框遵从协程取消：重抛 `CancellationException` 且重置 `saving` 状态。
     - `ReviewInsertResult` 到 5 种用户反馈文案的精确映射验证。
   - 0.3.2 基础功能与既有单元测试回归（33 项全部通过）：
     - `ContextBuilderTest`、`ModelLabelTest`、`DiagnosticsTest`、`UpdateClientTest`、`FeedbackClientTest`、`DeepSeekClientTest`、`NoteExporterTest`、`MathTextTest`、`SkillImportTest`、`ThreadNavigationTest`。
2. **SQLite 与业务集成测试** ([`NotebookStoreTest.kt`](app/src/androidTest/java/com/feiyu/notes/data/NotebookStoreTest.kt))：
   - `reviewRecordsIsolatedByCourseNotebook`: 课程间数据隔离。
   - `reviewRecordCrossCourseSecurityEnforced`: 课程 A 上下文试图读/改/改状态/删课程 B 记录全部失败，课程 B 记录保持不变。
   - `reviewRecordRejectsCrossCourseSourceEntry`: 拒绝跨课程 entry 作为来源。
   - `reviewRecordRejectsNonCourseNotebook`: 真实初始化 `generalChat()` 后验证拒绝 `GENERAL_ID`，同时拒绝 `PRACTICE`。
   - `reviewRecordRejectsBlankTopic`: 拒绝空白 topic 的新建与更新。
   - `reviewRecordRejectsIncompleteAssistantSource`: 拒绝 pending/未完成回复，完成后允许添加。
   - `reviewRecordCrudAndReopen`: 增删改查及数据库重启持久化。
   - `reviewRecordDuplicateSourcePrevention`: 幂等查重，重复添加返回 `AlreadyExists`。
   - `reviewRecordsCascadeOnNotebookDeletion`: 课程删除级联删除复习记录。
   - `sourceDeletedMarkedOnLessonOrThreadDeletion`: 线程、课次及**单条笔记**删除时保留记录并将 `source_deleted` 置 1。
   - `isThreadArchivedDetectsThreadStatus`: 归档状态检测与判断。
   - `upgradesFromV1ToV4PreservesDataAndAddsReviewTable`: 基于 `notes-v1.sql` 真实 fixture 验证 v1→v4 升级。
   - `upgradesFromV2ToV4PreservesDataAndSeedsTemplatesAndAddsReviewTable`: 基于 `notes-v2.sql` 真实 fixture 验证 v2→v4 升级，确认加列与播种。
   - `upgradesFromV3ToV4PreservesExistingTemplatesWithoutReseeding`: 基于 `notes-v3.sql` 真实 fixture 验证 v3→v4 升级，**明确确认自定义模板不被重复播种覆盖**。

### 4.2 构建产物与 SHA256
```bash
bash ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain
```
- **测试结果**：74 / 74 项 JVM 单元测试通过，0 失败。
- **Debug APK**：`app/build/outputs/apk/debug/app-debug.apk`
  - SHA256: `4956d79f326f5317568116f1c95a155556f7840d14e49416a3228a9978085134`
- **AndroidTest APK**：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  - SHA256: `7225e7281eedf472861300c99942be18269f05cbeaef0f52e9d761a8a3d481e6`
- **代码规范**：`git diff --check` 0 警告 0 报错。

### 4.3 设备与环境状态核实（真机已连通与测试实测）
- **工具与路由状态**：`adb` 37.0.1，通过 Windows ADB (`127.0.0.1:5037`) 与 SSH 远程转发 (`127.0.0.1:5038`) 成功连接开发机：
  - 设备型号：SHARP A101SH（Android 12 / API 31，serial `354974110447644`）。
  - 环境变量配置：`ADB_SERVER_SOCKET=tcp:127.0.0.1:5038`。
- **真机已实测项**：
  - **真实 SQLite 与数据层（34/34 全部通过）**：
    - `com.feiyu.notes.data.NotebookStoreTest` (26 项) + `com.feiyu.notes.study.GeneratorTest` (8 项) 在开发机上执行 `am instrument` 全部通过（验证了真实 SQLite 下 v1/v2/v3→v4 迁移、通用对话拒绝、笔记级联删除更新 `source_deleted=1` 等关键逻辑）。
    - 发现并修复真实缺陷：`sourceDeletedMarkedOnLessonOrThreadDeletion` 中助手回复未完成触发 F08 校验拒绝，已修正为在 COMPLETE 状态后测试，复核通过。
  - **原生渲染与加密设置（3/3 全部通过）**：
    - `MathRendererTest` (1 项) 与 `ApiSettingsTest` (2 项) 在真机上全部通过。
  - **端到端 UI 测试（`UiFlowTest`，含 14 项）**：
    - 代码中已添加对 API 31 的兼容处理（`LocaleManager` 仅在 API 33+ 启用，语言设置测试使用 `Assume.assumeTrue` 跳过），并新增课程复习全流程测试 `courseReviewWorkflowFullCycle`。
    - **待用户解锁屏幕**：设备当前处于密码锁屏状态（`deviceLocked=1`，`KeyguardStateMonitor.mIsShowing=true`），由于系统安全限制前台 Activity 无法在凭据锁屏下获取焦点进行 Compose 渲染；待用户在手机端输入锁屏密码后即可跑通。
- **开发机验证脚本与一键运行入口**：
  脚本 [`scripts/verify-course-review.sh`](scripts/verify-course-review.sh) 已增加正向退出码校验、非零执行数断言与预期测试类核验：
  ```bash
  ADB_SERVER_SOCKET=tcp:127.0.0.1:5038 ./scripts/verify-course-review.sh -s 354974110447644
  ```
  该脚本会自动检查设备连接授权状态、检测设备 API 等级（API 33+ 跑含 `UiFlowTest` 全套，API < 33 跑 `NotebookStoreTest` 与 `GeneratorTest`）、安装 APK、执行测试并在失败时返回非零退出码。

---

## 5. 待提 PR 标题与描述草稿

用户可复制以下内容向 `XePWang/FeiyuNote` 发起 Pull Request：

### PR Title:
```text
feat(review): integrate v0.3.2, implement course review records local closed loop and v4 schema
```

### PR Description:
```markdown
## Summary
This PR integrates upstream v0.3.2 release and implements the first version of **Course Review Records** (课程复习记录) in FeiyuNote as an entirely local, explicit learning review workflow with strict notebook scoping, non-destructive v4 database migration, and robust draft preservation.

### Key Implementation Details
- **Upstream v0.3.2 Integration**:
  - Merged upstream v0.3.2 (`a15aa06`) into the feature branch.
  - Retained support, feedback, diagnostics, and update features while preserving course review capabilities.
- **Course Isolation & Scoping**:
  - Scoped strictly to `COURSE` notebooks (`notebookId` boundary). Practice books and General Chat (`GENERAL_ID = -2`) cannot create review records.
  - Removed unscoped methods from `NotebookStore`; all public review APIs enforce `notebookId` and verify `isCourseNotebook(notebookId)`.
  - Added cross-course tamper tests verifying Course A cannot read, update, set status, or delete Course B's review records.
- **SQLite Schema v4 & Non-destructive Migration**:
  - Incremented database version to 4.
  - Added `review_records` table and `idx_review_records_notebook` index.
  - `source_entry_id` deliberately has no foreign key constraint, allowing review records to survive and display "source deleted" when a source entry is removed.
  - Verified v1->v4, v2->v4, and v3->v4 migrations with genuine SQL fixtures (`notes-v1.sql`, `notes-v2.sql`, `notes-v3.sql`). Verified v3->v4 does not re-seed templates if already customized.
  - Cascade deletion on notebook removal; marks `source_deleted = 1` in a single transaction when parent thread, lesson, or single note is deleted.
- **Accurate Source Navigation**:
  - Routes `NOTE` entries to `NoteKey`, archived thread entries to `ArchivedKey`, and active assistant replies to `LessonKey(focusEntryId)`.
  - Pushes to backstack so pressing Back returns straight to the course review screen with filter and scroll state intact.
- **UI & Draft Protection**:
  - `ReviewRecordEditDialog` only dismisses after write success (`onSave == null`).
  - Disables repeated submissions while saving; resets `saving` on composition recreation so the dialog never gets stuck.
  - Respects coroutine cancellation by re-throwing `CancellationException`.
  - Preserves user drafts (`topic` and `notes`) and dialog targeting across screen rotation using `rememberSaveable`.
  - Differentiates 5 insertion results via `ReviewInsertResult` across UI screens.
  - Displays localized last-updated timestamp and "Edit" action on review cards.
  - Added bilingual strings in English and Simplified Chinese.

### Test & Validation Evidence
- **JVM Unit Tests**: 74 passed, 0 failures (`./gradlew testDebugUnitTest`).
  - `ReviewRecordModelTest` (6 tests for models, defaults, and copy).
  - `ReviewInteractionTest` (8 tests verifying production `ReviewDialogState`, `ReviewDefaults`, draft retention, and `CancellationException` handling).
  - 60 regression unit tests passing (including Diagnostics, FeedbackClient, UpdateClient, ModelLabel, ContextBuilder, DeepSeekClient, NoteExporter, MathText, SkillImport, ThreadNavigation).
- **Android Integration Tests**: 14 test scenarios in `NotebookStoreTest` covering course isolation, cross-course tamper prevention, cross-course source rejection, GENERAL_ID rejection, blank topic rejection, incomplete assistant source rejection, CRUD/reopen persistence, duplicate prevention, cascade deletion, source deletion flag across thread/lesson/note, thread archive status check, and v1/v2/v3->v4 migrations.
- **Compilation**: Both `app-debug.apk` and `app-debug-androidTest.apk` built successfully.
  - `app-debug.apk` SHA256: `4956d79f326f5317568116f1c95a155556f7840d14e49416a3228a9978085134`
  - `app-debug-androidTest.apk` SHA256: `fb9ddbdfee7d2fea70403dec9c4587cbf41a05e63ee19306413d10ffca1db274`
- **Device Verification**: Provided `scripts/verify-course-review.sh -s <device_serial>` for automated on-device test execution.
```
