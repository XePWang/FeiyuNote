# 课程复习记录（Course Review Records）首版实施报告

## 1. 概述与设计边界

本变更实现了 FeiyuNote “课程复习记录”的首版本地操作闭环，严格落实用户讨论共识与规范约定：

- **业务目标**：帮助学生在课程学习过程中记录疑难点与知识状态（“待复习 / 已理解 / 仍有疑问”），支持随时修改、删除、标记状态并回溯原提问/回答/笔记。
- **作用域边界**：
  - 严格限定在单个 `COURSE` 笔记本内（`notebookId` 物理隔离），练习本（`PRACTICE`）与通用对话（`GENERAL_ID`）不开放复习记录。
  - 数据层强制校验：公开接口统一要求 `notebookId` 并校验 `COURSE` 类型；跨课程 ID 篡改操作一律拒绝。
  - 严禁引入全局跨课记忆、跨课自动检索、普通问答自动注入、后台摘要或能力画像。
  - 本地纯数据操作闭环，**零模型请求消耗**。
- **生命周期与不变量**：
  - 笔记本删除：级联删除该课程下全部复习记录。
  - 来源条目/线程/课次删除：保留复习记录及原文本，置 `source_deleted = 1`，界面展示“来源已删除”并禁用跳转，避免笔记内容随聊天清理丢失。
  - 防重插入：同一来源条目在同一课程下仅能创建一条有效复习记录，避免重复点击产生垃圾数据。
  - 保存容错：异步写入成功才关闭弹窗；保存中禁用重复提交；写入失败、条目被删或网络异常时展示可理解错误，**绝不静默丢弃草稿**。

---

## 2. 数据层与 SQLite v4 架构

### 2.1 数据模型与操作结果
- [`ReviewStatus`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/data/Models.kt): 枚举类型，对应数据库字符串字段：
  - `PENDING` ("pending"): 待复习
  - `UNDERSTOOD` ("understood"): 已理解
  - `CONFUSED` ("confused"): 仍有疑问
- [`ReviewRecord`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/data/Models.kt): 数据类，包含字段：
  - `id: Long`, `notebookId: Long`, `topic: String`, `notes: String`, `sourceEntryId: Long? = null`, `sourceDeleted: Boolean = false`, `status: ReviewStatus = ReviewStatus.PENDING`, `createdAt: Long`, `updatedAt: Long`
- [`ReviewInsertResult`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/data/Models.kt): 明确区分 5 种写入结果：
  - `Success(val record: ReviewRecord)`: 写入成功
  - `AlreadyExists(val existingRecord: ReviewRecord)`: 该来源条目已存在有效复习记录
  - `SourceNotFound`: 来源条目不存在、已被删除或不属于本课程
  - `InvalidCourse`: 笔记本不存在或非 `COURSE` 类型
  - `Failed`: 数据库写入失败

### 2.2 SQLite Schema (v4)
在 [`NotebookDatabase.kt`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/data/NotebookDatabase.kt) 中将数据库版本升级至 `VERSION = 4`：

```sql
CREATE TABLE IF NOT EXISTS review_records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    notebook_id INTEGER NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
    topic TEXT NOT NULL,
    notes TEXT NOT NULL,
    source_entry_id INTEGER REFERENCES entries(id) ON DELETE SET NULL,
    source_deleted INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL CHECK (status IN ('pending', 'understood', 'confused')),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_review_records_notebook ON review_records(notebook_id);
```

- **非破坏性迁移**：在 `onUpgrade (oldVersion < 4)` 中执行上述建表与建索引操作。v3→v4 升级中不执行 `seedGuidedTemplate`，完整保留用户已修改、删除或导入的模板，不发生模板重复播种。

### 2.3 存储与数据访问安全约束 ([`NotebookStore.kt`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/data/NotebookStore.kt))
- 移除所有无 `notebookId` 的非限定公开接口。所有复习记录操作强制传入 `notebookId`：
  - `listReviewRecords(notebookId: Long): List<ReviewRecord>`
  - `getReviewRecord(notebookId: Long, recordId: Long): ReviewRecord?`
  - `findReviewRecordBySource(notebookId: Long, sourceEntryId: Long): ReviewRecord?`
  - `insertReviewRecord(notebookId: Long, topic: String, notes: String, sourceEntryId: Long? = null, status: ReviewStatus = PENDING): ReviewInsertResult`
  - `addReviewRecord(notebookId: Long, ...): ReviewRecord?`（便捷包装，成功或已存在返回记录）
  - `updateReviewRecord(notebookId: Long, recordId: Long, topic: String, notes: String, status: ReviewStatus? = null): Boolean`
  - `setReviewStatus(notebookId: Long, recordId: Long, status: ReviewStatus): Boolean`
  - `deleteReviewRecord(notebookId: Long, recordId: Long): Boolean`
- 每个方法内部均执行 `isCourseNotebook(notebookId)` 校验，并且 SQL 均附加 `AND notebook_id = ?` 条件。跨课程篡改（以课程 A 上下文操作课程 B 记录）将直接返回 `null`/`false`，且目标记录绝对不变。
- 线程/课次删除保护：`deleteThread` 与 `deleteLesson` 执行时级联查询受影响的 `review_records`，将其 `source_deleted` 标记为 1，保留学生复习笔记文本。

---

## 3. UI 交互与草稿保护

- **编辑与添加对话框** ([`ReviewRecordEditDialog`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/ui/CourseReviewScreen.kt)):
  - 采用异步签名：`onSave: suspend (topic: String, notes: String) -> String?`（成功返回 `null`，失败返回可理解错误文案）。
  - **保存成功才关闭**：仅在 `onSave` 返回 `null` 时触发 `onDismiss()`。
  - **防重复提交**：保存过程中锁定 `saving = true`，禁用保存按钮、取消按钮及文本编辑，防止手抖重复提交。
  - **草稿保护**：保存失败或抛出异常时，显示红色错误提示（`errorMessage`），对话框不关闭，**草稿内容（`topic` 与 `notes`）完整保留**。
- **状态区分与反馈**：
  - [`StudyViewModel.addToReview`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/study/StudyViewModel.kt) 与 [`NoteScreen`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/ui/NoteScreen.kt) 完整处理 `ReviewInsertResult`：
    - `Success` -> 提示“已加入复习”，关闭对话框；
    - `AlreadyExists` -> 对话框提示“该内容已在复习记录中”，保留草稿；
    - `SourceNotFound` -> 对话框提示“来源内容已删除或不可用”，保留草稿；
    - `InvalidCourse` / `Failed` -> 对话框提示“保存失败，请稍后重试”，保留草稿。
  - 严禁将底层写入失败误报为“已加入”或“已存在”。
- **主界面与导航** ([`CourseReviewScreen.kt`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/ui/CourseReviewScreen.kt)):
  - 顶部栏课程名与新建按钮。
  - `FilterChip` 筛选（全部 / 待复习 / 已理解 / 仍有疑问）。
  - 下拉快速切换状态、弹窗编辑、确认删除；操作失败弹出 Notice 提示。
  - “回到来源”跳转原课次问答或笔记，来源已删除时给出明确提示。

---

## 4. 测试与验证证据

### 4.1 自动化测试证据
1. **JVM 单元测试**（39 项全部通过，`./gradlew testDebugUnitTest`）：
   - [`ReviewRecordModelTest.kt`](file:///home/circleci/project/app/src/test/java/com/feiyu/notes/data/ReviewRecordModelTest.kt)（6 项测试）：
     - 状态数据库映射字符串 (`pending`, `understood`, `confused`)。
     - 状态解析及非法值回退 `PENDING`。
     - 字段默认值及 copy 生命周期。
     - `ReviewInsertResult` 密封接口数据载荷完整性。
   - [`ReviewInteractionTest.kt`](file:///home/circleci/project/app/src/test/java/com/feiyu/notes/ui/ReviewInteractionTest.kt)（7 项新增交互测试）：
     - 问答默认复习主题/摘要提取（首行截取、超长截断、空白回退默认标题）。
     - 笔记默认复习主题/摘要提取（40 字/120 字截取）。
     - 对话框空白主题拦截、提交前后 trim 校验。
     - 对话框保存失败（如来源已删除）时草稿完整保留验证。
     - 对话框异常时草稿完整保留验证。
     - `ReviewInsertResult` 到 5 种用户反馈文案的精确映射验证。
   - 既有单元测试回归（26 项）：`DeepSeekClientTest`, `NoteExporterTest`, `MathTextTest`, `SkillImportTest`, `ContextBuilderTest`, `ThreadNavigationTest` 全部通过。
2. **SQLite 与业务集成测试** ([`NotebookStoreTest.kt`](file:///home/circleci/project/app/src/androidTest/java/com/feiyu/notes/data/NotebookStoreTest.kt))：
   - `reviewRecordsIsolatedByCourseNotebook`: 课程间数据隔离。
   - `reviewRecordCrossCourseSecurityEnforced`: 课程 A 上下文试图读/改/改状态/删课程 B 记录全部失败，课程 B 记录保持不变。
   - `reviewRecordRejectsCrossCourseSourceEntry`: 拒绝跨课程 entry 作为来源。
   - `reviewRecordRejectsNonCourseNotebook`: 拒绝 `PRACTICE` 与 `GENERAL_ID`。
   - `reviewRecordCrudAndReopen`: 增删改查及数据库重启持久化。
   - `reviewRecordDuplicateSourcePrevention`: 幂等查重，重复添加返回 `AlreadyExists`。
   - `reviewRecordsCascadeOnNotebookDeletion`: 课程删除级联删除复习记录。
   - `sourceDeletedMarkedOnLessonOrThreadDeletion`: 线程/课次删除时保留记录并将 `source_deleted` 置 1。
   - `upgradesFromV1ToV4PreservesDataAndAddsReviewTable`: 基于 `notes-v1.sql` 真实 fixture 验证 v1→v4 升级。
   - `upgradesFromV2ToV4PreservesDataAndSeedsTemplatesAndAddsReviewTable`: 基于 `notes-v2.sql` 真实 fixture 验证 v2→v4 升级，确认加列与播种。
   - `upgradesFromV3ToV4PreservesExistingTemplatesWithoutReseeding`: 基于 `notes-v3.sql` 真实 fixture 验证 v3→v4 升级，**明确确认自定义模板不被重复播种覆盖**。

### 4.2 构建产物与 SHA256
```bash
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain
```
- **测试结果**：39 / 39 项单元测试通过，0 失败。
- **Debug APK**：`app/build/outputs/apk/debug/app-debug.apk`
  - SHA256: `35831a19274c532fc6d23357756cab0d7349ced6a6e7e3e89813d01690987baa`
- **AndroidTest APK**：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  - SHA256: `71e1a37630e150055f987f562e8bbdea67773f89e45fa3ae5f6ff87965646e92`
- **代码格式规范**：`git diff --check` 0 警告 0 报错。

### 4.3 设备与环境状态核实（明确声明）
- **工具状态**：`adb` 命令行工具**已安装且存在**（路径 `/home/circleci/android-sdk/platform-tools/adb`，版本 Android Debug Bridge 1.0.41 / 37.0.1）。
- **设备连接状态**：`adb devices` 显示**当前无连接设备**，系统亦未预配置 Android 虚拟设备（AVD）。
- **未实测项**：
  - 39 项 JVM 单元测试覆盖了数据模型、状态机与交互提取逻辑，但**不能等同于真机 SQLite 迁移或 UI 交互实测**。
  - Android 集成测试已完整编译进 `app-debug-androidTest.apk`，但由于无可用设备/模拟器，**尚未在真机或模拟器上执行自动化跑测**。
- **待运行命令**（连接真机或启动模拟器后执行）：
  ```bash
  ./gradlew connectedDebugAndroidTest --console=plain
  ```

---

## 5. 待提 PR 标题与描述草稿

用户可复制以下内容向 `XePWang/FeiyuNote` 发起 Pull Request：

### PR Title:
```text
feat(review): implement course review records local closed loop and v4 schema
```

### PR Description:
```markdown
## Summary
This PR implements the first version of **Course Review Records** (课程复习记录) in FeiyuNote as an entirely local, explicit learning review workflow with strict notebook scoping and draft preservation.

### Key Implementation Details
- **Course Isolation & Scoping**:
  - Scoped strictly to `COURSE` notebooks (`notebookId` boundary). Practice books and General Chat cannot create review records.
  - Removed unscoped methods from `NotebookStore`; all public review APIs enforce `notebookId` and verify `COURSE` notebook type.
  - Added cross-course tamper tests verifying Course A cannot read, update, set status, or delete Course B's review records.
- **SQLite Schema v4 & Multi-stage Migration**:
  - Incremented database version to 4.
  - Added `review_records` table and `idx_review_records_notebook` index.
  - Non-destructive migration preserves all existing v1-v3 data.
  - Verified v1->v4, v2->v4, and v3->v4 migrations with genuine SQL fixtures (`notes-v1.sql`, `notes-v2.sql`, `notes-v3.sql`). Verified v3->v4 does not re-seed templates if already customized.
  - Cascade deletion on notebook removal; marks `source_deleted = 1` when parent thread/lesson is deleted so student notes are not lost.
- **UI & Draft Protection**:
  - `ReviewRecordEditDialog` now only dismisses after write success (`onSave == null`).
  - Disables repeated submissions while saving.
  - Shows clear inline error messages and preserves user drafts (`topic` and `notes`) on save failure or source deletion.
  - Explicitly differentiates 5 results via `ReviewInsertResult` (`Success`, `AlreadyExists`, `SourceNotFound`, `InvalidCourse`, `Failed`) across `StudyViewModel`, `NoteScreen`, and `CourseReviewScreen`.
  - Added "Course Review" entry point to `LessonListScreen` for course notebooks.
  - Added "Add to review" quick actions to assistant message cards in `ChatScreen` and note previews in `NoteScreen`.
  - Added bilingual strings in English and Simplified Chinese.

### Test & Validation Evidence
- **JVM Unit Tests**: 39 passed, 0 failures (`./gradlew testDebugUnitTest`).
  - Added `ReviewRecordModelTest` (6 tests for models, defaults, and copy).
  - Added `ReviewInteractionTest` (7 tests for dialog submission state machine, draft preservation, topic/notes extraction, and result mapping).
  - 26 existing regression unit tests passing.
- **Android Integration Tests**: Added 11 test scenarios in `NotebookStoreTest` covering course isolation, cross-course tamper prevention, cross-course source rejection, non-course rejection, CRUD/reopen persistence, duplicate prevention, cascade deletion, source deletion flag, and v1/v2/v3->v4 migrations.
- **Compilation**: Both `app-debug.apk` and `app-debug-androidTest.apk` built successfully.
  - `app-debug.apk` SHA256: `35831a19274c532fc6d23357756cab0d7349ced6a6e7e3e89813d01690987baa`
  - `app-debug-androidTest.apk` SHA256: `71e1a37630e150055f987f562e8bbdea67773f89e45fa3ae5f6ff87965646e92`
- **Environment Status**: `adb` tool is available (v1.0.41), but no device or emulator was attached in the CI container; instrumented tests were compiled into APK but not executed on real hardware.
```
