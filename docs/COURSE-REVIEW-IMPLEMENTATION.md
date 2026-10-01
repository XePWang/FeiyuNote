# 课程复习记录（Course Review Records）首版实施报告

## 1. 概述与设计边界

本变更实现了 FeiyuNote “课程复习记录”的首版本地操作闭环，严格落实用户讨论共识与规范约定：

- **业务目标**：帮助学生在课程学习过程中记录疑难点与知识状态（“待复习 / 已理解 / 仍有疑问”），支持随时修改、删除、标记状态并回溯原提问/回答/笔记。
- **作用域边界**：
  - 严格限定在单个 `COURSE` 笔记本内（`notebookId` 物理隔离），练习本（`PRACTICE`）与通用对话（`GENERAL_ID`）不开放复习记录。
  - 严禁引入全局跨课记忆、跨课自动检索、普通问答自动注入、后台摘要或能力画像。
  - 本地纯数据操作闭环，**零模型请求消耗**。
- **生命周期与不变量**：
  - 笔记本删除：级联删除该课程下全部复习记录。
  - 来源条目/线程/课次删除：保留复习记录及原文本，置 `source_deleted = 1`，界面展示“来源已删除”并禁用跳转，避免笔记内容随聊天清理丢失。
  - 防重插入：同一来源条目在同一课程下仅能创建一条有效复习记录，避免重复点击产生垃圾数据。

---

## 2. 数据层与 SQLite v4 架构

### 2.1 数据模型
- [`ReviewStatus`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/data/Models.kt): 枚举类型，对应数据库字符串字段：
  - `PENDING` ("pending"): 待复习
  - `UNDERSTOOD` ("understood"): 已理解
  - `CONFUSED` ("confused"): 仍有疑问
- [`ReviewRecord`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/data/Models.kt): 数据类，包含字段：
  - `id: Long`
  - `notebookId: Long`
  - `topic: String`
  - `notes: String`
  - `sourceEntryId: Long? = null`
  - `sourceDeleted: Boolean = false`
  - `status: ReviewStatus = ReviewStatus.PENDING`
  - `createdAt: Long`
  - `updatedAt: Long`

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
    status TEXT NOT NULL DEFAULT 'pending',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_review_records_notebook ON review_records(notebook_id);
```

- **非破坏性迁移**：在 `onUpgrade (oldVersion < 4)` 中执行上述建表与建索引操作，完整保留已有 v1、v2、v3 数据及用户已删除模板配置。

### 2.3 存储与数据访问 ([`NotebookStore.kt`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/data/NotebookStore.kt))
- `listReviewRecords(notebookId: Long): List<ReviewRecord>`
- `getReviewRecord(notebookId: Long, recordId: Long): ReviewRecord?` / `getReviewRecord(recordId: Long)`
- `findReviewRecordBySource(notebookId: Long, sourceEntryId: Long): ReviewRecord?`
- `insertReviewRecord(...)`: 校验笔记本类型为 `COURSE`，校验来源条目属于同课程，校验防重；返回插入/已有记录。
- `updateReviewRecord(...)`: 支持更新 topic、notes、status，自动刷新 `updated_at`。
- `setReviewStatus(...)`: 快捷更新状态并记录 `updated_at`。
- `deleteReviewRecord(...)`: 单条删除。
- `deleteThread` & `deleteLesson`: 关联查询受影响的 `review_records`，将其 `source_deleted` 标记为 1。

---

## 3. UI 界面与交互

- **课程复习主界面** ([`CourseReviewScreen.kt`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/ui/CourseReviewScreen.kt)):
  - 顶部导航栏展示当前课程名称及“新建复习记录”按钮。
  - 提供 `FilterChip` 进行状态筛选（全部 / 待复习 / 已理解 / 仍有疑问）。
  - 复习卡片展示主题、疑问备注、更新时间、来源条目编号（若已删除则醒目标记）。
  - 支持下拉菜单快速切换复习状态、弹窗编辑内容、确认删除。
  - 点击“回到来源”可直接跳转至对应课次的问答或笔记。
- **入口导航** ([`AppNavigation.kt`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/ui/AppNavigation.kt) & [`LessonListScreen.kt`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/ui/LessonListScreen.kt)):
  - 在 `COURSE` 笔记本的课次列表顶部栏中新增“课程复习”图标操作。
  - 新增 `ReviewListKey(notebookId: Long)` 导航键，支持平滑回退。
- **问答与笔记快速加入** ([`ChatScreen.kt`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/ui/ChatScreen.kt) & [`NoteScreen.kt`](file:///home/circleci/project/app/src/main/java/com/feiyu/notes/ui/NoteScreen.kt)):
  - 在已完成的 AI 回答卡片及笔记详情预览页中增加“加入复习”按钮。
  - 弹出 `ReviewRecordEditDialog`，预填标题与内容摘要，保存后给予 Toast/Notice 反馈；若已存在则提示已在复习记录中。
- **国际化**：在 `res/values/strings.xml` 与 `res/values-zh/strings.xml` 中完整配置中英双语文本。

---

## 4. 测试与验证结果

### 4.1 自动化测试
1. **JVM 单元测试** ([`ReviewRecordModelTest.kt`](file:///home/circleci/project/app/src/test/java/com/feiyu/notes/data/ReviewRecordModelTest.kt)):
   - 验证 `ReviewStatus` 数据库映射字符串 (`pending`, `understood`, `confused`)。
   - 验证 `ReviewStatus.fromDb` 正向解析与异常值回退到 `PENDING`。
   - 验证 `ReviewRecord` 默认值与不可变拷贝状态转换。
2. **SQLite 与业务集成测试** ([`NotebookStoreTest.kt`](file:///home/circleci/project/app/src/androidTest/java/com/feiyu/notes/data/NotebookStoreTest.kt)):
   - `reviewRecordsIsolatedByCourseNotebook`: 验证不同课程记录严格隔离。
   - `reviewRecordRejectsCrossCourseSourceEntry`: 验证拒绝跨课程条目关联。
   - `reviewRecordRejectsNonCourseNotebook`: 验证拒绝练习本与通用对话创建复习记录。
   - `reviewRecordCrudAndReopen`: 验证增删改查及数据库重启持久化。
   - `reviewRecordDuplicateSourcePrevention`: 验证同来源防重。
   - `reviewRecordsCascadeOnNotebookDeletion`: 验证删除课程级联清理。
   - `sourceDeletedMarkedOnLessonOrThreadDeletion`: 验证课次/线程删除时保留记录并将 `source_deleted` 标记为 1。
   - `v3ToV4MigrationPreservesDataAndAllowsReviewRecords`: 验证旧库平滑迁移至 v4，已有数据完好且可正常新增复习记录。

### 4.2 构建执行命令与产物
```bash
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --console=plain
```
- **测试结果**：31 / 31 项单元测试全部通过（原有 26 项 + 新增 5 项），0 失败。
- **Debug APK**：`app/build/outputs/apk/debug/app-debug.apk`
  - SHA256: `920c17a61d390c133340b1c9a6c42a06ffaf269cec77fd7d519db7d2505284bb`
- **AndroidTest APK**：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  - SHA256: `655b580f48460ab7292802cc05feac30966981201f598ce67708f438f497343b`
- **代码规范检查**：`git diff --check` 通过，无空白或格式告警。

### 4.3 未实测项声明
- 本次验证运行于 Linux 无头 CI 容器环境，未挂载真实 Android 设备且缺少模拟器（环境未安装 `adb`），因此 Instrumented 测试（`NotebookStoreTest` 等）已完成编译生成测试 APK，但**尚未在真实设备或模拟器上执行真机跑测**。

---

## 5. 待提 PR 标题与描述草稿

用户可直接复制以下内容向 `XePWang/FeiyuNote` 发起 Pull Request：

### PR Title:
```text
feat(review): implement course review records local closed loop and v4 schema
```

### PR Description:
```markdown
## Summary
This PR implements the first version of **Course Review Records** (课程复习记录) in FeiyuNote as an entirely local, explicit learning review workflow.

### Scope & Key Design Decisions
- **Course Isolation**: Scoped strictly to `COURSE` notebooks (`notebookId` boundary). Practice books and General Chat cannot create review records.
- **Zero AI Requests**: Operates strictly on local SQLite data; no background summarization, profiling, or auto-retrieval.
- **SQLite Schema v4**:
  - Incremented database version to 4.
  - Added `review_records` table and `idx_review_records_notebook` index with non-destructive migration preserving existing v1-v3 data.
  - Cascade deletion on notebook removal; marks `source_deleted = 1` when parent thread/lesson is deleted so student notes are not lost.
- **UI & Navigation**:
  - Added `CourseReviewScreen` with status filtering (`FilterChip`), inline status updates, editing, deletion, manual addition, and jump-to-source navigation.
  - Added "Course Review" entry point to `LessonListScreen` for course notebooks.
  - Added "Add to review" quick actions to assistant message cards in `ChatScreen` and note previews in `NoteScreen`.
  - Added bilingual strings in English and Simplified Chinese.

### Test & Validation
- **Unit Tests**: Added `ReviewRecordModelTest` (5 tests). Total JVM unit tests: 31 passed, 0 failures (`./gradlew testDebugUnitTest`).
- **Android Tests**: Added 8 test scenarios to `NotebookStoreTest` covering course isolation, cross-course rejection, non-course rejection, CRUD/reopen persistence, duplicate prevention, cascade deletion, source deletion flag, and v3->v4 migration.
- **Compilation**: Both `app-debug.apk` and `app-debug-androidTest.apk` built successfully.
  - `app-debug.apk` SHA256: `920c17a61d390c133340b1c9a6c42a06ffaf269cec77fd7d519db7d2505284bb`
- **Note**: Instrumented tests were compiled into APK but not executed on real hardware/emulators due to container environment limits.
```
