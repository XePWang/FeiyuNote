# 肥鱼笔记实施计划

日期：2026-09-30。状态：P1–P8、P10、P11 已实现，本地 CI（含界面自动化）全部通过；P9 仅剩真实折叠态等少量人工抽查。

唯一产品依据为 [spec](spec.md)。当前目录只有参考文档，没有 Android 工程、Gradle wrapper 或既有代码。以下源码及构建路径均为拟新建；本次请求只授权落 spec 和计划。

## 实现约定

一个 app 模块，Kotlin/Compose，自适应列表详情布局；Android 内置 SQLite 保存笔记本内容，OkHttp 负责请求。首版不引入 DI 框架、通用 repository/provider 接口、多模块业务框架或后台任务系统。

暂用 com.feiyu.notes 作为实施包名；下文 K 表示 app/src/main/java/com/feiyu/notes。发布身份不在本次计划范围。资源位于 app/src/main/res，测试位于 app/src/test/java/com/feiyu/notes 和 app/src/androidTest/java/com/feiyu/notes。

先使用本机已有 Android Studio/JDK/SDK 与兼容的稳定依赖。minSdk 默认取 26，若选定组件需要更高下限，先尝试兼容版本；确实会改变设备覆盖范围时才返回范围决策。compileSdk/targetSdk 在建立工程时按现有工具链和官方兼容要求确定并记录，不为照搬示例升级整套环境。

## 共享边界

这些名称用于工作项之间交接，可以用少量具体类或函数实现，不要求为每项增加抽象接口。数据字段及状态以 spec 第 5 节为准。“所有者”指首次建立该文件和契约的工作项；后续工作项按自己的范围扩展，修改责任随工作项交接。

| 所有者 | 边界 | 消费方和约束 |
| --- | --- | --- |
| P1 | K/FeiyuApp.kt：Application 子类，是应用级单例（NotebookStore、PhotoFiles、Generator）的唯一持有处，手工构造，不用 DI 框架 | P2/P4 在集成时各自加入自己的单例。 |
| P1 | K/ui/AppNavigation.kt：导航键只传 notebookId、lessonId、entryId | 骨架不定义业务数据类型；后续页面挂接到此处，串行集成。 |
| P2 | K/data/Models.kt：Notebook、NotebookKind、Lesson、Entry、EntryKind、EntryAction、EntryState、Mastery、Template | 字段以 spec 第 5 节为准。后续项使用这组类型，不依赖供应商 DTO。 |
| P2 | K/data/NotebookStore.kt：笔记本（list/create/update/delete，含 kind、linkedCourseId、defaultTemplateId）、课次（list/create/rename/delete）、条目（readEntries(lessonId, includeArchived)、saveEntries、updateEntry、updateNoteText、deleteNote）、线程（setArchived、setMastery、deleteThread）、readReferenceNotes(notebookId)、模板（list/save/delete）、markPendingInterrupted、commitReply/commitSummary（带目标复核）；changes: `StateFlow<Long>` | P4–P8 调用。单库，所有查询带笔记本或课次条件；readReferenceNotes 返回本笔记本笔记，刷题本另加所关联课程的笔记。写入和删除在事务中提交，返回成功或明确失败，不能部分更新后声称成功；删除事务成功后再通过 PhotoFiles 删除图片。setArchived/setMastery 在写入入口校验根 user 条目与刷题本约束，违反时拒绝且不改数据。commitReply 只在目标 assistant 条目仍存在且为 pending 时写入，commitSummary 只在课次仍存在时新建 note，否则返回“目标已删除”。每次成功写入后递增 changes，页面据此重新读取，不引入 Room。markPendingInterrupted 由 FeiyuApp 启动时调用一次。 |
| P2 | K/data/PhotoFiles.kt：allocatePhoto(notebookId): File、resolvePhoto(notebookId, fileName): File、deletePhotos(notebookId, fileNames)、deleteNotebookDir(notebookId) | 只生成/解析/删除 `images/<notebookId>/` 内的文件，拒绝含路径分隔符的文件名。 |
| P3 | K/ai/AiTypes.kt：AiInput(systemText, messages)、AiMessage(role, text, `images: List<File>`)、AiReply(text)、AiError | P3 在实现前发布具体类型。images 是调用方已解析好的文件，客户端不知道目录结构；只允许 user 消息带图。认证、限流、网络、无有效回答、超限是可区分错误。 |
| P3 | K/ai/DeepSeekClient.kt：构造参数 AiConfig(endpoint, model, apiKey) 和 OkHttpClient；suspend generate(input: AiInput): AiReply | 配置由调用方注入，客户端不读取设置，便于本地模拟服务测试。客户端负责图片缩放压缩和 base64 编码；AiReply 只含最终正文；协程取消向下取消 HTTP 请求。 |
| P3 | K/settings/ApiSettings.kt：load(): AiConfig?、model()、hasKey()、save(apiKey, model) | endpoint 固定为默认值；Key 经 Keystore 保护后存于私有配置。完整 AiConfig 只由 Generator 读取；设置页只读 model() 和 hasKey()，Key 明文不返回页面，也不进入笔记本存储。 |
| P4 | K/study/ContextBuilder.kt：buildTurn(userEntry, lessonEntries, referenceNote, template, resolvePhoto): AiInput；buildSummary(lessonEntries, template): AiInput | 纯函数，不依赖 ViewModel、存储或 Android 框架，可直接做单元测试。已保存的 user 条目是一次问答请求的唯一权威输入：action、父条目、自带照片、attachedImageEntryIds、templateId、参考笔记 ID 都从它读取，调用方只负责把这些 ID 解析为已校验的内容传入，不另传一份。P4 实现 buildTurn 的 ask，P5–P7 各自增加 expand、附图、参考笔记、buildSummary、mistake 和模板。 |
| P4 | K/study/StudyPrompts.kt：各动作的内置指令 | P4 建立 ask 与“识别并讲解”；后续项只增加自己的动作指令。模板指令由 ContextBuilder 追加在内置指令之后。 |
| P4 | K/study/Generator.kt：start(request)、cancel()、cancelIfAffected(notebookId?, lessonId?, entryIds)、status: `StateFlow<GenerationStatus>` | 应用级单例，同一时刻只接受一个请求，忙时拒绝新请求。负责读取配置、调用客户端，通过 commitReply/commitSummary 写回发起时的目标；目标已删除时丢弃结果。问答与整理是两条具体路径（spec 第 4 节），不建通用任务框架。删除操作先调用 cancelIfAffected。生命周期不随页面或 ViewModel。 |
| P4 | K/settings/AppPrefs.kt：lastLessonId 读写 | 普通应用设置，不放进 NotebookStore。启动时校验课次仍存在，否则清空并回到笔记本列表。 |
| P4 | K/study/StudyViewModel.kt：课次选择、草稿、待发送附件、所选附图/参考笔记/模板 | 只持有界面状态并把用户事件交给 Generator；不持有请求任务。所选引用在 changes 后重新校验，失效即清除；发送和重试前按 spec 第 5 节失效引用表再次校验。后续项扩展事件，不建立第二套任务状态。 |
| P5 | K/export/NoteExporter.kt：renderNote(notebookName, lessonTitle, noteText, exportedOn): String | 生成转义过的静态 HTML，UI 通过系统保存接口或系统分享写出；不读取 Key 或其他笔记本。 |

类型由产生它的一方定义：数据类型归 P2，AI 请求类型归 P3，学习动作和内置指令归 P4。实现者如需改变跨项字段或失败含义，应先同步本表和受影响的工作项，避免各自另造类型。无需为尚不存在的第二供应商编写适配器。

## 工作项

### P1：可自适应的应用骨架

- Outcome：工程可构建；启动后能用占位列表和详情展示单栏或双栏，覆盖 S01 的布局基础。不定义业务数据类型。
- Scope：新建 settings.gradle.kts、build.gradle.kts、gradle.properties、gradle/wrapper/、gradlew、gradlew.bat、.gitignore、app/build.gradle.kts、app/src/main/AndroidManifest.xml；拥有 K/FeiyuApp.kt、K/MainActivity.kt、K/ui/AppNavigation.kt、K/ui/theme/。占位内容只用于预览或开发检查，不能作为最终业务数据。
- Steps：确认本地工具链；选择相互兼容的稳定 Compose/Material 3 Adaptive/Navigation 3/Lifecycle 和 OkHttp 版本，先让空工程通过 assembleDebug；再依据官方 ListDetailSceneStrategy 示例构建单栏/双栏，建立保存选择和返回状态的方式。为后续页预留普通导航入口，不做路由框架。统一依赖、manifest 和 FeiyuApp 由本项建立，后续共享改动串行集成。
- Validation：运行 gradlew.bat :app:assembleDebug；在窄窗口和宽窗口查看同一组列表/详情，返回和选择行为正确。这里只证明骨架，完整折叠与业务恢复由 P9 集中验收。
- Stop condition：缺少可用 JDK/SDK、依赖组合无法成立，或运行样例要求引入不符合 spec 的平台组件时，报告具体缺口并调整兼容选择；不得因此加入自建布局框架或启动安装整套环境的旁支任务。

### P2：本地笔记本数据

- Outcome：笔记本、课次、条目和模板能保存、读取、归档和删除；重启仍可读，覆盖 S02、S11 和 S08 的存储部分。
- Scope：拥有 K/data/Models.kt、K/data/NotebookStore.kt、K/data/PhotoFiles.kt、K/data/NotebookDatabase.kt、K/ui/NotebookListScreen.kt，以及对应 data 测试；集成修改 FeiyuApp.kt、AppNavigation.kt。
- Steps：定义数据类型；按 spec 用原生 SQLite 建单库四表（notebooks、lessons、entries、templates），sourceEntryIds 和 attachedImageEntryIds 存 JSON 文本；实现第 5 节的全部读写、归档、掌握状态和级联删除规则，删除事务成功后删除图片；commitReply/commitSummary 的目标复核；archived/mastery 写入约束；changes 信号；FeiyuApp 启动时调用 markPendingInterrupted。接入笔记本列表（课程与刷题本两组）和课次列表，新课次默认标题为当天日期；不增加外部目录权限、Room、同步或备份功能。
- Validation：用两个笔记本验证查询隔离、改名后附件引用不变、父关系和来源 ID 往返；readReferenceNotes 对关联与未关联的刷题本返回正确范围；事务失败时原记录可读；重新打开数据库后 pending 变为 interrupted，complete 不变；readEntries 默认不含已归档线程；删除线程连带后代与照片，删除课次连带笔记，删除笔记本连带目录并解除刷题本关联，删除模板后默认模板置空；引用被删条目的笔记仍可读；写入后 changes 递增；resolvePhoto 拒绝越界文件名。负例：对课程条目或非根条目 setMastery、对非根条目 setArchived 均被拒绝且数据不变；目标 assistant 条目已删除时 commitReply 不写入、不重建条目，课次已删除时 commitSummary 不建 note。运行对应设备 SQLite 检查，并记录实际设备和结果。
- Stop condition：普通 SQL 或读写失败在本项修复，不扩大为存储框架；需要改变 spec 第 5 节字段时先更新 spec。

### P3：DeepSeek 客户端和最小配置

- Outcome：一个客户端能发送文本/图片请求、返回完整回答并响应取消，覆盖 S03。
- Scope：拥有 K/ai/DeepSeekClient.kt、K/ai/AiTypes.kt、K/settings/ApiSettings.kt、K/ui/SettingsScreen.kt（Key 和模型名）和对应 ai/settings 测试。导航挂接和 manifest 共享修改由后续集成统一完成。
- Steps：实现官方 chat/completions 的非流式请求；图片按长边缩放压缩为 JPEG 后以 base64 data URL 放入 user 消息，缩放参数与 endpoint/model 默认值集中在 AiConfig 所在文件；使用 Keystore 保护 Key，模型名可改，未填时用默认值；将 HTTP、协议错误转成 AiError。成功只返回最终正文，不把额外供应商字段当笔记。保持无自动重试。
- Validation：本地模拟服务分别返回正常回答、空正文、401、429和中断连接；检查请求的角色、图片内容、模型名与认证头，一条 user 消息可带多张图，大图被压缩到设定尺寸内，assistant 消息不带图；检查取消确实终止当前调用。使用内存合成数据，不记录 Key 和真实课堂材料。
- Stop condition：官方协议与默认配置发生实际不兼容时，修正客户端及引用文档；若需要改变供应商或接入方式，返回产品决策。验收不依赖真实计费 API，不安排模型效果实验。

### P4：系统相机与可恢复的课次问答

- Outcome：用户可以在笔记本里用文字或系统相机照片发问，得到并保存回答；打开应用回到上次课次；布局变化不重复调用，覆盖 S04、S05 的提问部分和 S08。
- Scope：拥有 K/study/ContextBuilder.kt、K/study/StudyPrompts.kt、K/study/Generator.kt、K/study/StudyViewModel.kt、K/ui/ChatScreen.kt、K/camera/PhotoCapture.kt、app/src/main/res/xml/file_paths.xml；集成修改 FeiyuApp.kt、MainActivity.kt、AppNavigation.kt、AndroidManifest.xml。对应测试仅覆盖本项行为。
- Steps：实现 ContextBuilder 的 ask 路径：沿父链取已完成问答的文字，本次提问自带照片；只有照片时使用 StudyPrompts 的“识别并讲解”指令。实现 Generator：先在一个事务中持久化提问（含 action）和 pending 回答，再用 ApiSettings 的配置调用客户端，结果写回发起时的课次。通过 ActivityResultContracts.TakePicture 和 FileProvider 唤起系统相机；在 SavedStateHandle 保存待拍照文件、原课次 ID 和输入草稿，成功回调后预览，点击发送才上传。ChatScreen 观察 NotebookStore.changes 与 Generator.status。打开课次时经 AppPrefs 记录 lastLessonId，启动时校验后恢复，失效则回到笔记本列表。Generator 通过 commitReply 写回，删除入口预留 cancelIfAffected。发送和重试前校验所需照片仍存在。实现取消、失败、重启中断状态和按原 action 手动重试。
- Validation：ContextBuilder 单元测试：两个分支中只取选中父链的文字，兄弟分支不混入，历史照片不随行；只有照片时带内置指令。用模拟 API 计数确认主动发送一次只收到一次请求，旋转和窗口调整不增加计数；请求中切换课次或返回上层，回答仍写入原课次；忙时第二次发送被拒绝；相机成功返回能预览，取消相机无请求；同一课次连续三次拍照提问后，每张照片各自对应自己的提问与回答，重启后不错位并回到该课次；网络失败和取消后原提问可读；模拟进程重启后 pending 显示中断且不自动重发。模拟 API 延迟响应期间删除目标线程或课次：请求被取消，已到达的迟到结果被丢弃，已删除内容不重新出现。lastLessonId 指向已删除课次时启动回到列表。重试所需照片缺失时提示用户，不改成纯文字请求。
- Stop condition：测试环境没有可用系统相机或折叠设备时，完成其他检查并明确待验收场景，不用自建相机或假截图替代。若上下文选择需要新产品行为，暂停该部分并更新 spec。

### P5：追问、整理与笔记

- Outcome：追问附图、展开、参考笔记、整理本课、笔记编辑与导出分享可用，覆盖 S05 的展开/整理、S06、S07。
- Scope：扩展 ContextBuilder.kt（buildSummary）、StudyPrompts.kt、Generator.kt（整理分支）、StudyViewModel.kt、ChatScreen.kt；拥有 K/ui/NoteScreen.kt、K/export/NoteExporter.kt 及对应测试。
- Steps：追问默认只发文字，“附上原图”选择器只列本条问答链上的照片，所选条目 ID 存入 attachedImageEntryIds；增加 expand 动作；参考笔记选择器使用 readReferenceNotes；“整理本课”读取本课未归档、已完成问答的文字并保留来源；Generator 的整理分支不预写条目，只保持运行状态，成功后经 commitSummary 新建 note；参考笔记在发送前按当前范围重新校验，失效则清除并提示；提供文本编辑，静态 HTML 可通过系统保存或系统分享写出。
- Validation：ContextBuilder 单元测试：追问未勾选时无图，勾选后只含所选本链照片，链外照片无法选入；展开和参考笔记正确加入；整理请求不含图片和已归档线程。整理来源能够回到原记录，重复整理不覆盖；整理失败、取消和进程重启后都不留下 note 或 pending 记录；已选参考笔记被删除后发送不带入该笔记；导出的中文和特殊字符可读、无脚本、不含条目 ID；分享发出的内容与保存的文件一致。
- Stop condition：同 P4。

### P6：刷题本与错题讲解

- Outcome：可以创建刷题本并可选关联课程，题目掌握状态可切换和筛选，错题讲解可用，覆盖 S09。
- Scope：扩展 NotebookListScreen.kt、ChatScreen.kt、StudyViewModel.kt、ContextBuilder.kt、StudyPrompts.kt 及对应测试。
- Steps：创建/编辑刷题本时可选一门课程关联；刷题本课次中根提问显示掌握状态，默认未掌握，可切换，可按状态筛选；增加 mistake 动作：在题目下以文字或照片提交解答，请求沿用追问的路径和附图规则，使用错题指令。课程中不显示掌握状态和错题讲解。
- Validation：ContextBuilder 单元测试：mistake 请求包含用户解答、所选附图和错题指令；刷题本参考笔记范围随关联变化；解除关联后原先选中的课程笔记被清除，不进入下一次请求。界面检查：新题为未掌握，切换后筛选正确；课程中不出现错题入口和掌握状态。
- Stop condition：同 P4；需要题目专用字段（正解、错因、标签等）时先返回范围决策。

### P7：讲解模板

- Outcome：模板可管理，笔记本默认模板和临时换用生效，覆盖 S10。
- Scope：拥有 K/ui/TemplateScreen.kt；扩展 SettingsScreen.kt 入口、笔记本编辑、ChatScreen.kt、StudyViewModel.kt、ContextBuilder.kt 及对应测试。
- Steps：模板列表与编辑（名称、指令文字）；笔记本可设默认模板；提问时可临时换一条或不用，实际使用的模板 ID 写入 user 条目；历史条目的模板已删除时显示“模板已删除”，重试时提示重新选择或不用，不静默换用其他指令；整理默认不带模板，可手动选一条。
- Validation：ContextBuilder 单元测试：默认模板追加到 ask、expand、mistake 的内置指令之后，不加入默认 summarize；手动为整理选择的模板生效；临时换用只影响本次请求。删除模板后默认模板置空，已选模板被清除，按钮和列表状态正确；重试引用已删模板的条目时出现选择提示。
- Stop condition：模板需要执行代码、加载资源或引入 skill 运行框架时，返回范围决策。

### P8：归档与删除界面

- Outcome：线程归档、恢复、删除，以及笔记、课次、笔记本删除可在界面完成，覆盖 S11 的交互部分。
- Scope：扩展 ChatScreen.kt、NoteScreen.kt、NotebookListScreen.kt、StudyViewModel.kt 及对应测试。
- Steps：所有删除入口先调用 Generator.cancelIfAffected，再执行存储删除；线程菜单提供归档和删除；课次内提供“已归档”入口，可阅读和恢复；笔记、课次、笔记本提供删除；所有删除先确认；笔记来源失效时显示“已删除”。
- Validation：归档线程从默认列表消失，在“已归档”中可读、可恢复，不进入整理；删除线程后后代和照片消失；来源被删的笔记正文仍在、显示“已删除”；删除课次、笔记本后相应内容和图片不再存在；请求进行中删除其目标，请求被取消且内容不回来；删除后界面上失效的选择（参考笔记、模板、附图、上次课次）被清除。
- Stop condition：同 P4。

### P9：组合验收

- Outcome：完成 spec 第 8 节 S01–S11 的组合验收。
- Scope：只修复验收暴露的缺陷，按所属项修改；更新 README.md 和本计划的执行记录。
- Steps：统一检查导航、离线阅读和错误状态；在手机窄屏、平板宽屏、可折叠设备/模拟器的折叠与展开状态及分屏窗口检查验收表。
- Validation：集中运行相关测试和 gradlew.bat :app:assembleDebug，确认 app/build/outputs/apk/debug/app-debug.apk 由此次成功构建产生；逐条记录 S01–S11 的实际设备和结果。
- Stop condition：任何需要重新加入已排除的运行时、渲染系统或存储框架的改动，先返回范围决策。缺少某种设备证据就记录该项未验收，不宣称全设备通过。

### P10：相册导入

- Outcome：提问时可以从相册选一张图片作为附件，覆盖 S04 的相册部分。
- Scope：扩展 StudyViewModel.kt、ChatScreen.kt；复用 PhotoFiles 分配目标文件。
- Steps：使用 ActivityResultContracts.PickVisualMedia（仅图片）；在 IO 线程把所选内容复制到 allocatePhoto 分配的文件，确认可解码后才设为待发送附件，失败则删除副本并提示；取消选择不改变状态。与拍照共用同一附件槽，替换未发送的旧附件。
- Validation：界面测试中打桩照片选择器返回一张测试图片，断言出现预览并发送后请求带图；打桩取消时无附件、无请求；不可解码的文件被拒绝。
- Stop condition：需要多选或读取整个相册时返回范围决策。

### P11：本地 CI/CD 与界面自动化

- Outcome：一条命令完成构建、单元测试、模拟器上的仪器测试和界面测试，并产出带版本与提交号的 APK；手工截图验收只保留少量抽查。
- Scope：拥有 scripts/ci.ps1、.githooks/、app/src/androidTest 下的界面测试、FeiyuApp 的测试替身入口；新增测试依赖（compose ui-test、espresso-intents）。
- Steps：ci.ps1 分 fast（单元测试 + 构建）和 full（再启动或复用无窗口模拟器，只针对 emulator-5554 安装并运行全部仪器测试），产物复制到 dist/；FeiyuApp 提供仅测试使用的覆盖入口（独立数据库名、独立文件根目录、假模型），界面测试不触碰用户数据和真实 API；用 Compose UI 测试覆盖此前的人工流程，相机和相册用 Espresso-Intents 打桩；git init 后通过 core.hooksPath 挂 pre-commit（fast）和 pre-push（full）。
- Validation：在干净的模拟器状态下运行 `scripts/ci.ps1 -Full` 通过，并确认 dist/ 中 APK 来自本次构建；故意让一项断言失败时脚本以非零退出码结束。
- Stop condition：模拟器无法无窗口启动或测试不稳定时，报告具体失败并保留 fast 模式可用，不以跳过测试换取通过。

## 顺序、并行和验证归属

| 工作项 | 实现何时可开始 | 验收依赖 |
| --- | --- | --- |
| P1 | 下一项；正式实施获授权后可开始 | 工具链和至少一个可查看布局的环境 |
| P2 | P1 的空工程通过 assembleDebug 后，可与 P1 布局工作并行 | SQLite 设备检查；与真实问答连接由 P4 验证 |
| P3 | 同 P2，可与 P1、P2 并行 | 自己的本地模拟 API 检查；页面整合由 P4 验证 |
| P4 | P1、P2、P3 完成，数据和客户端边界已固定后 | 实际系统相机环境及模拟 API |
| P5 | P4 的基础问答稳定后 | 模拟 API；系统保存与分享界面 |
| P6 | P5 完成后 | 模拟 API |
| P7 | P6 完成后 | 模拟 API |
| P8 | P7 完成后 | P2 的删除与归档存储检查 |
| P9 | P1–P8 完成后 | 所有生产者结果和目标设备；单一集成负责人汇总证据 |
| P10 | P4 完成后 | 界面测试中打桩的照片选择器 |
| P11 | P1 完成后可开始；界面测试随各功能补齐 | 本机模拟器；P9 的大部分验收改由 P11 自动执行 |

P2/P3 各自定义自己的类型，不再等待 P1 固定数据模型；它们与 P1 布局的实现文件不重叠，允许并行。build.gradle.kts、manifest、FeiyuApp、导航入口和同一 Gradle 工作目录的构建/设备检查必须串行，统一由集成负责人调度。P4–P8 都会修改同一问答状态、上下文和聊天界面代码，按顺序执行。

计划只描述可并行关系，不指定模型、agent 数量或强制委派。默认一个执行者顺序完成也成立。每项拥有自己的窄检查，完整设备矩阵只在 P9 集中执行；修复后只重跑受影响检查和必要的最终构建。

## 执行记录

- 环境（2026-09-30 安装，长期使用）：Temurin JDK 21（~/.jdks/jdk-21.0.12.1+1，另有 MSI 安装版）；Android SDK 位于 %LOCALAPPDATA%\Android\Sdk（cmdline-tools latest、platform-tools、emulator、platforms;android-37.0、build-tools 36/37、system-images;android-36;google_apis;x86_64）；Android Studio（winget）；用户级 JAVA_HOME/ANDROID_HOME/PATH 已设置。AVD：Feiyu_Fold_API36（pixel_9_pro_fold，WHPX 加速）。
- 工程：AGP 9.4.1、Gradle 9.8.0（wrapper 已校验 SHA-256）、Kotlin 2.4.20、Compose BOM 2026.09.00、Material3 Adaptive 1.3.0、Navigation3 1.2.0、Lifecycle 2.11.0、OkHttp 5.5.0；compileSdk 37、targetSdk 36、minSdk 26。项目路径含中文，gradle.properties 设 android.overridePathCheck=true，构建正常。
- P1–P8：代码已实现。实现偏差：lastLessonId 放在 AppPrefs；NoteExporter 接收字符串参数；整理提示不再要求模型输出 #编号，导出时额外剥离编号标记（spec §7 不导出条目 ID）。
- 自动化检查（最近一次全部通过）：
  - `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` → EXIT=0；JVM 单元测试 14 项（DeepSeekClient 5、ContextBuilder 6、NoteExporter 3）。
  - 仪器测试在 emulator-5554 上以 `adb -s emulator-5554 shell am instrument -w com.feiyu.notes.test/androidx.test.runner.AndroidJUnitRunner` 运行，17 项通过（NotebookStore 10、Generator 7），连续 10 次无失败。
  - 期间修复的真实缺陷：生成任务在启动前被取消时 finally 不执行导致永久 busy（改为 UNDISPATCHED 启动）；结束时先发布空闲状态再释放 busy 导致紧接着的请求被拒（改为先释放、按身份条件更新状态）；拍照后预览不重组（状态读取范围）。
- 设备检查（emulator-5554，展开态 2076x2152 与 wm size 1080x2300 窄窗口）：双栏/单栏切换并停留同一课次；草稿跨窗口尺寸保留；冷启动回到上次课次；系统相机拍照、预览、仅照片发送；无 Key 时回答标记失败、提问保留、可重试。
- 未验收 / 已知限制：
  - 真实 DeepSeek 调用：用户在设置页填入 Key 后，界面文字提问成功；另用可选冒烟测试 `RealApiSmokeTest`（需 `-e realApi true`，在 App 进程和真实数据库上运行，结果留在“真实API验证”课程中）以两张课堂截图完成 5 次调用，全部 COMPLETE（2026-09-30，约 96 秒）：仅照片识别控制系统例题并算出 ξ≈0.608、ωn≈5.28、K1=2、K2≈27.85、a≈6.42；照片+文字识别转置/对称矩阵；追问附原图、展开讲解、整理本课均成功，笔记无 #编号。观察到模型偶尔输出 Markdown 加粗 `**`，界面会原样显示。
  - 模拟器折叠（device_state CLOSED）后外屏黑屏，折叠与铰链遮挡用窄窗口替代，未在真实折叠态验收；旋转、分屏未逐项截图。
  - 整理、笔记编辑/导出/分享、模板、刷题本、归档/删除界面已实现并通过存储/生成层测试，但未逐项做界面截图验收。
  - 进程在“已拍照未发送”时被强杀，照片文件会残留为无引用文件（不影响数据一致性，尚无清理）。
- P10 相册导入、P11 本地 CI：已完成。`pwsh scripts/ci.ps1 -Full` 通过（2026-09-30）：JVM 单元测试 14 项；仪器与界面测试 24 项，其中 UiFlowTest 6 项覆盖课程问答/追问/展开/整理/笔记编辑导出分享/冷启动恢复、相机与相册（打桩）、刷题本掌握状态与错题讲解、默认模板、归档恢复删除、宽窄窗口切换与草稿保留；真实 API 冒烟测试默认跳过。git 已初始化，hooks 通过 core.hooksPath=.githooks 挂载，尚未提交。
- UI 自动化发现并修复的缺陷：双栏时系统返回键直接退出 App（ListDetailSceneStrategy 默认返回策略在列表层级间不变更布局，改为 PopLatest）；回答刚显示时点“整理本课”会被判忙（生成中禁用该按钮）；整理生成的笔记在列表顶部不可见（生成后滚动到顶部）；列表栏“返回”改为回到上一级列表。
- 私有资料（群聊原文、需求整理、预调研、接口探测记录）已移到仓库外的同级目录 `肥鱼笔记-private/`，不进入版本控制。
- 界面与发布补充（2026-10-01）：已实现中英界面、按课次持久随机头像和自定义头像、02-01 图标、Noto Sans SC 字体、配套主题和 API Key 指引；保留应用级 Generator、SQLite、原生导航与既有测试入口。
- 开源约定：代码 GPL-3.0-or-later；素材独立署名；中文 README 为默认并链接英文版，接受 Issue 和功能建议，暂不接受 PR。GitHub main 构建 debug APK，v* 标签使用仓库 Secrets 签名 release APK 并发布预览版。
- 提示词已加强纯文本约束；历史回答中的 Markdown 标记不自动改写。

本次计划不包含模型效果评测、真实计费请求、上架、部署服务器或联系内测人员。开源仓库与预览版发布由用户于 2026-10-01 追加授权，见界面与发布补充记录。

- UI 补充验收（2026-10-01）：`pwsh -NoProfile -File scripts/ci.ps1 -Full` 通过；JVM 单测 14 项，仪器/界面 runner 报告 OK (26 tests)，真实 API 冒烟默认跳过。新增覆盖每课次头像稳定、自定义头像导入/恢复、主语言为德语且次语言为中文时使用英语、主语言为中文时使用中文。截图随 CI 输出到 `build/ci/screenshots/`。修复了列表懒加载后的测试定位、重建后的键盘关闭等待，以及可变字体默认字重过细。

- 发布构建（2026-10-01）：本地 `assembleRelease --no-configuration-cache` 和 `lintVitalRelease` 通过，签名 APK 约 22 MB；[GitHub main CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36784905890) 的单元测试、debug 编译与 APK 上传通过。首次远端失败来自 SDK 安装 action 请求已移除的 tools 包，已改为 platform-tools。备用机 PHP110 在安装前断开，未进行真机验收。

- [v0.1.0-alpha.1 发布 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36785654962) 成功：标签 `9fd23ea` 的代码在 GitHub 构建并签名，APK 已上传 [Release](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.1.0-alpha.1)，为非草稿预览版。签名密钥保留于仓库外并以 GitHub Secrets 提供，未提交到仓库。后续本次提交只更新验收文档，沿用已通过的检查。

- 0.2 本地验收（2026-10-01）：`pwsh -NoProfile -File scripts/ci.ps1 -Full -Serial emulator-5556 -Avd Feiyu_CI_API36` 通过，JVM 19 项，仪器/界面 runner 为 OK (30 tests)，真实 API 冒烟默认跳过。原生 JLaTeXMath 共用于聊天、笔记与 HTML 导出，窄屏截图 `build/ci/screenshots/math-chat-phone.png` 已检查；覆盖行内/独立公式、分数/根号/积分/矩阵、原文复制、编辑预览、无效语法回退与离线导出。修复 Android ICU 对正则闭合花括号的严格转义要求；界面测试主动收起软键盘，不依赖 AVD 的输入法偏好。
- 0.2 凭据验收：独立合成 Key 验证 AES-256-GCM 密文落盘、随机 IV、Android Keystore 密钥不可导出、私有文件 UID/权限、篡改拒绝、丢失密钥后重新输入、禁止备份与 FileProvider 隔离。设置页 FLAG_SECURE 由界面测试检查；配置字符串脱敏由 JVM 测试检查。生产 Key 的别名和密文格式保持不变，未读取用户真实 Key。签名 release 构建和 lintVitalRelease 通过，最终打包完成；使用原发布签名。
- SDK 恢复（2026-10-01）：原 SDK 目录缺失，原因未确认。已恢复持久目录中的平台 37.0、build-tools 36.0.0、platform-tools、模拟器及 API 36 镜像；新增 `scripts/setup-sdk.ps1 [-WithEmulator]`，固定可用的 Java SDK 管理器 19.0，校验官方下载归档并统一 ANDROID_HOME/local.properties。首次镜像下载在 Java HTTPS 读取中停滞，改用官方归档恢复后再次运行恢复脚本成功。原 AVD 遗留无响应进程/文件锁，保留其用户数据，独立建立 Feiyu_CI_API36；CI 默认 5556，只接受模拟器目标，并检查环境和限制启动探测等待时间。原 5554 占用可能需要 Windows 重启释放。
- PHP110 真机：ADB 可连接，签名包安装遇到系统滑块人机验证，已向用户请求手动操作；中间包安装已取消，未声称真机验收通过。最终包由 Release 提供。

- v0.2.0 发布验收（2026-10-01）：源代码标签 `22b5331`；[GitHub main CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36823765077) 与 [发布 CI](https://github.com/Yongzhaooo/FeiyuNote/actions/runs/36823765078) 均成功，约 22 MB 签名 APK 已上传到 [v0.2.0 Release](https://github.com/Yongzhaooo/FeiyuNote/releases/tag/v0.2.0)（非草稿、早期预览版）。已从 Release 下载实际附件，以 apksigner 验证签名有效，apkanalyzer 确认包名 com.feiyu.notes、versionCode 2、versionName 0.2.0、debuggable=false。最终附件同时放入 PHP110 的 Download/feiyu-notes-v0.2.0.apk，等待用户手动完成系统验证；未声称真机安装运行通过。本次后续文档提交不改变已验证的发布源码。
