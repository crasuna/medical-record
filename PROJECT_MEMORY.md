# 项目记忆

## 项目定位

`Medical Record` 是一个离线优先、单患者、无账号的 Android 医疗记录应用。当前代码是保留 Git
历史后的绿地式重建，不是旧 `com.crasuna.medicalrecord` 的原位升级。旧应用只作为功能、字段、
信息结构与视觉参考；旧数据库、附件、Keystore、提醒状态、签名和安装兼容性均不迁移。

- release identity：`com.loveluke.medicalrecord`
- debug identity：`com.loveluke.medicalrecord.debug`
- minSdk 26，compileSdk / targetSdk 37
- schema version 1，versionCode 1
- 默认英文，完整简体中文；跟随系统语言，无应用内语言页面
- 浅色和深色主题；固定医疗青绿色色板，不使用动态取色

## v1 产品范围

已实现：

- 首页概览、最近就诊、当前用药、今日提醒和全局搜索。
- 就诊列表、详情、新建、编辑和删除。
- 系统相机、Photo Picker 批量图片、SAF 批量 PDF 导入。
- 图片与 PDF 加密存储、预览、隔离和删除。
- 用药疗程、Current / Upcoming / Ended / All 筛选、多每日提醒时间。
- exact alarm 能力降级、通知权限恢复、开机/升级/时间/时区变化后的提醒重排。
- 手机底部导航、宽窗口 rail，以及就诊/用药列表详情双栏场景。
- 最近任务隐私遮罩和两次确认的本机敏感数据清除。

明确不实现：账号、云同步、OCR、导出、应用锁、多患者切换 UI、服药打卡与依从性记录、DICOM、
视频/音频/Office 附件、遥测。

## 领域模型

- `UserAccount` 是未来账号和同步身份，v1 不存在。
- `PatientProfile` 是医疗数据所属患者。首次成功建库时创建一个隐藏默认 profile，使用随机 canonical
  UUID；安装内稳定，v1 不展示患者管理入口。
- `Encounter` 拥有零个或多个 `Attachment`。
- `Medication` 表示一个含起始日与可选结束日的疗程；Current 语义是
  `startDate <= today && (endDate == null || endDate >= today)`。
- `Reminder` 是持久化的用户意图；通知权限、exact-alarm 能力和系统当前镜像的一个单次闹钟是不同
  状态。

所有就诊、附件、用药和提醒均带 `patientId`。附件和提醒使用复合外键，防止跨患者父子引用；父对象
更新使用 `@Upsert`，避免 SQLite `REPLACE` 触发级联删除。用药与归一化提醒在同一 Room 事务保存。

## 源码与架构

项目只有一个 Gradle 模块 `:app`，源码根包为 `com.loveluke.medicalrecord`：

- `app/`：Application、MainActivity、访问控制、DI、Navigation 3、提醒运行时协调与数据库实例登记。
- `core/database/`：Room entity、DAO、Repository、SQLCipher 接入和 schema。
- `core/security/`：Keystore wrapping key、secret envelope、fail-closed 解锁和本机数据清除。
- `core/attachment/`：内容校验、AES-GCM 容器、导入、相机、预览、删除与孤儿文件清理。
- `core/reminder/`：提醒计划、AlarmManager、Receiver、权限和通知。
- `core/privacy/`：最近任务遮罩与截图策略。
- `core/designsystem/`、`core/model/`：主题、共享组件和不可变模型。
- `feature/home/`、`feature/encounter/`、`feature/medication/`：Compose UI 与 ViewModel。

调用链是 `Compose Screen -> ViewModel -> Repository/安全 façade -> Room/加密附件/系统适配器`。
UI 使用不可变 `UiState`、用户 action、`StateFlow` 与 `collectAsStateWithLifecycle`。不使用完整 Clean
Architecture、简单 UseCase 包装或第三方 MVI。

导航采用可序列化 Navigation 3 key 与 `NavigationSuiteScaffold`。compact 使用 bottom bar，
medium/expanded 使用 rail。就诊和用药使用稳定版自定义 Navigation 3 `SceneStrategy` 实现双栏，
没有依赖 RC 版 `adaptive-navigation3`，也没有使用 `ListDetailPaneScaffold`。

## 数据库

Room 数据库从 schema v1 开始，`exportSchema = true`，JSON 位于：

`app/schemas/com.loveluke.medicalrecord.core.database.AppDatabase/1.json`

表：

- `patient_profiles`
- `encounters`
- `attachments`
- `medications`
- `reminders`
- `reminder_schedule_state`

数据库通过 trigger 补强最多一个默认患者、提醒分钟范围、用药日期、必填就诊医院、附件类型/大小/
路径/隔离状态等约束。内部附件路径只允许 UUID 形式的 `original/<id>.mra` 与
`thumbnail/<id>.mrt`。

## 安全存储与失败恢复

- Android Keystore 中的不可导出 AES-256 key 不绑定生物识别或设备凭据，也不强制 StrongBox。
- wrapping key 分别包装随机 SQLCipher 数据库口令和随机附件主密钥；带认证 envelope 位于
  `noBackupFilesDir`。
- 启动时先验证/provision 附件主密钥，再打开并运行时验证 SQLCipher、WAL 与 SQLite，创建/读取隐藏
  默认患者，最后完成附件孤儿清理；全部成功后才发布 `Ready`。
- 解锁失败会进入 `Locked`，不会静默重建密钥、删除数据或回退未加密 Room。
- 用户清除本机数据必须两次确认并持有单次消费授权。清理只覆盖当前 variant 的闹钟、通知、数据库、
  附件、envelope、临时明文、偏好和 Keystore alias；进入删除阶段后要求进程重启。
- `allowBackup=false`，Android 12+ `dataExtractionRules` 与 Android 11- `fullBackupContent` 均明确
  排除敏感数据。

## 附件

- 每批最多 10 个，单文件最多 50 MiB；一次就诊总附件数不设硬上限。
- 允许 PDF/JPEG/PNG/WebP/HEIC/HEIF；拒绝视频、音频、Office、压缩包、SVG、GIF、TIFF、DICOM。
- 同时验证声明 MIME、magic/结构和 Android 平台可解析性，流式实施大小限制，并逐项报告成功/失败。
- URI 内容立即复制并加密，不依赖长期 URI 权限；内部路径使用 UUID，原文件名仅显示。
- 每附件使用随机 AES-256 data key；attachment master key 只包装 data key。patient、encounter、
  attachment、payload kind 和版本均进入 AAD。
- 单附件认证/格式失败只隔离该附件；缩略图可重建。
- 相机与预览临时明文在成功、失败、取消、生命周期退出和冷启动时清理；FileProvider 仅暴露相机根。
- 删除通过同目录 tombstone 两阶段事务协调密文与 Room metadata，冷启动会依据数据库引用恢复或最终
  删除 tombstone，避免部分删除造成不可恢复数据丢失。

## 提醒与隐私

- 声明 `SCHEDULE_EXACT_ALARM`，不声明 `USE_EXACT_ALARM`。
- 有授权时 `setExactAndAllowWhileIdle`；无授权时在通知可用的前提下降级
  `setAndAllowWhileIdle` 并显示可能延迟。
- 每次只安排全局下一次单次闹钟。开机、包升级、时间/时区变化、exact-alarm grant、Activity resume
  和冷启动都会 reconcile。
- 没有 `POST_NOTIFICATIONS` 时仍保存提醒意图，但取消平台闹钟并标记不可用；恢复权限后安排未来
  提醒，不循环索权。
- 通知 `VISIBILITY_PRIVATE`，public version 完全脱敏；private 内容只含药名、剂量和计划时间。
- v1 无全屏通知、已服用或跳过 action。
- API 33+ 调用 `setRecentsScreenshotEnabled(false)`；API 26–32 在非前台使用中性 Compose 遮罩和
  临时 `FLAG_SECURE`。前台允许用户主动截图/录屏，不记录截图行为。

## 工具链和版本策略

- AGP 9.3.1，Gradle 9.7.0，Gradle daemon JDK 25。
- Java toolchain/source/target 17，Kotlin JVM target 17。
- AGP built-in Kotlin，不应用 `org.jetbrains.kotlin.android`。
- higher KGP 与 Compose compiler 2.4.10、KSP 2.3.11、Hilt 2.60.1、Coroutines 1.11.0。
- Room 2.8.4、SQLCipher Android 4.17.0、AndroidX SQLite/Framework 2.7.0。
- 其他 AndroidX/Compose/Navigation 均在 version catalog 精确锁定稳定版；禁止动态版本和
  alpha/beta/RC/snapshot。

AGP 9.3.1 + KGP 2.4.10 是可配置组合，但不宣称处于 JetBrains fully-supported 矩阵。如果真实构建
故障能归因于 higher-KGP/Compose 覆盖，只移除该覆盖并恢复 AGP 内置版本。若可验证的设备问题能归因
于 SQLite 2.7.0，只回退 SQLite 到 2.6.2。

## 签名与发布

- debug 使用标准 debug key。
- release 不得回退 debug key；没有凭据时仅做 unsigned release、R8 与 AAB 验证。
- 外部签名入口变量：`MEDICAL_RECORD_STORE_FILE`、`MEDICAL_RECORD_STORE_PASSWORD`、
  `MEDICAL_RECORD_KEY_ALIAS`、`MEDICAL_RECORD_KEY_PASSWORD`。必须 0 个或 4 个；部分配置直接失败。
- Keystore、`.jks` 和密码不得进入 Git；不在日常开发中创建长期正式签名密钥。

## 测试与设备验收

JVM 测试覆盖 Repository、关键 ViewModel、Room 约束、密钥 envelope、附件加密/篡改/清理、提醒、
Navigation scene 和隐私行为。Android instrumentation 覆盖真实 Room + SQLCipher 建库/重开/错误
密钥/WAL/事务与 Android Keystore 行为。

### 当前设备门禁

除非用户明确扩大范围，默认设备端验收只覆盖一个当前选定、已启动且 ADB 状态为 `device` 的物理
设备或模拟器。恰好一个设备在线时直接使用；多个设备在线时先由用户选定，不默认把所有设备纳入
门禁；没有在线设备时写“设备测试未执行”，不能用 instrumentation 编译成功替代设备通过，也不能
把未执行写成测试失败。

当前设备门禁只有在以下条件全部满足时才通过：

- `connectedDebugAndroidTest` 已在选定设备实际执行，所有发现的测试均通过，且 `failures=0`、
  `errors=0`、`skipped=0`。
- debug APK 安装成功，`MainActivity` 冷启动成功。
- Home、Encounters、Medications 可以完成往返导航；页面标题、主要操作以及空状态或已有数据状态
  正常，验收过程不创建或删除业务数据。
- 验收窗口内没有新增应用崩溃、`FATAL EXCEPTION` 或 ANR。

验收报告记录设备 serial 或 AVD、API、ABI、page size、分辨率/密度和 instrumentation 报告位置。
满足上述条件时写“当前设备端验收已通过”；该结论只覆盖选定设备，不代表其他 Android 配置已经
验证。

### 非阻塞兼容性清单

API 26、最新 API、16 KiB page size、手机、折叠屏、平板/桌面窗口、浅深色、英中、大字体、权限
拒绝/恢复、重启、时区变化、最近任务遮罩，以及相机、Photo Picker 和 SAF 属于按需执行的兼容性
清单，不是默认设备门禁。

除非用户明确要求兼容矩阵、跨设备兼容性或其中某个配置，代理不执行、不展开，也不把未覆盖项写入
验收结果、未解决问题、警告或发布阻塞项。按要求执行矩阵检查时，应单独报告实际覆盖情况；矩阵未
执行或部分执行不改变此前当前设备门禁的通过状态。

## 维护规则

- 本文件记录当前真实状态和长期约束，不是提交日志。
- 功能范围、架构边界、数据模型/schema、安全/权限、后台行为、国际化或发布策略发生变化时同步更新。
