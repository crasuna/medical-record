# 项目记忆

## 项目概览

`Medical Record` 是一个离线优先的 Android 病历管理应用，当前处于单患者本地管理的 MVP 阶段，重点覆盖就诊记录、附件留存、用药管理和本地加密存储。

## 当前架构

### UI

- `app/src/main/java/com/crasuna/medicalrecord/MainActivity.kt`
  - 应用壳、主题、底部导航和 Navigation Compose 路由入口。
  - 当前一级页面只有 `encounters` 和 `medications`。
- `app/src/main/java/com/crasuna/medicalrecord/EncounterFeature.kt`
  - 就诊相关的 Compose UI 和 ViewModel。
  - 覆盖就诊列表、就诊编辑、就诊详情、附件预览。
  - 负责日期/时间选择、附件导入入口、附件预览 UI。
- `app/src/main/java/com/crasuna/medicalrecord/MedicationFeature.kt`
  - 用药相关的 Compose UI 和 ViewModel。
  - 覆盖用药列表、筛选、用药编辑、删除确认、提醒时间编辑。
  - 当前已支持在编辑页配置多个每日提醒时间，并在列表显示提醒摘要。

### 数据层

- `app/src/main/java/com/crasuna/medicalrecord/DataLayer.kt`
  - 当前是一个聚合文件，同时承载：
  - Room `Entity`
  - `Dao`
  - `RoomDatabase`
  - `Repository` 接口与离线实现
  - Hilt `AppModule`
  - 一部分列表过滤业务逻辑
- 当前核心数据模型：
  - `EncounterEntity`
  - `EncounterAttachmentEntity`
  - `MedicationEntity`
  - `MedicationReminderEntity`
- 当前数据库版本为 `2`，`exportSchema = false`。
- 当前已包含 `MedicationWithReminders` 关系模型和 `MIGRATION_1_2`，用于为既有用药数据补充提醒表。
- 目前仍然没有通知状态表、同步队列表、导出记录表等额外模型。

### 安全

- `app/src/main/java/com/crasuna/medicalrecord/Security.kt`
  - `SecurePassphraseManager` 负责数据库口令生成、封装与读取。
  - `FileEncryptionManager` 负责附件文件和缩略图的 AES/GCM 加解密。
- 数据库存储使用 SQLCipher。
- 附件密钥和数据库密钥通过 Android Keystore 保护。

### 国际化

- 应用名和 UI 文案已从硬编码迁移到资源文件。
- 英文资源位于 `app/src/main/res/values/strings.xml`。
- 简体中文资源位于 `app/src/main/res/values-zh-rCN/strings.xml`。
- 当前语言策略是跟随系统语言切换，不支持应用内手动切换语言。

### 平台集成

- `app/src/main/java/com/crasuna/medicalrecord/MedicalRecordApp.kt`
  - 作为 `@HiltAndroidApp` 的 `Application` 入口。
  - 启动时会初始化用药提醒通知渠道，并重同步所有有效提醒。
- `app/src/main/AndroidManifest.xml`
  - 当前注册了 `MainActivity`、`FileProvider`、提醒触发 `BroadcastReceiver` 和开机/升级重同步 `BroadcastReceiver`。
  - 已声明 `POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`、`SCHEDULE_EXACT_ALARM`。
- 附件导入依赖系统内容选择器、拍照返回和 `PdfRenderer`。
- 用药提醒依赖 `AlarmManager`、系统通知和通知点击回到编辑页的深链式参数跳转。
- 应用现在提供显式 `android:icon` / `android:roundIcon`、adaptive icon 和 monochrome launcher icon，用于更好适配不同 launcher 的图标样式与标题展示。

## 已实现功能

### 就诊管理

- 就诊列表展示。
- 新建和编辑就诊。
- 查看就诊详情。
- 删除就诊。
- 记录医院、科室、医生、主诉、诊断、处置、备注、就诊日期和时间。

### 附件管理

- 在就诊详情内管理附件。
- 支持拍照导入。
- 支持导入图片。
- 支持导入 PDF。
- 支持附件缩略图/预览。
- 支持删除附件。
- PDF 预览支持页数展示和翻页。

### 用药管理

- 用药列表展示。
- `Current / All / Ended` 筛选。
- 新建和编辑用药。
- 删除用药。
- 记录药品名称、剂量、频次、开始日期、结束日期和备注。
- 可为每个药品配置多个每日提醒时间。
- 用药列表卡片会显示提醒时间摘要。
- 保存后会根据通知权限和精确提醒能力决定是否立即调度本地提醒。
- 通知点击后会直接打开对应药品的编辑页。
- 当前“频次”是自由文本，不是结构化枚举。

### 本地安全存储

- 结构化数据使用 Room + SQLCipher。
- 附件文件使用 AES/GCM 加密后落盘。
- 数据库口令和附件密钥使用 Android Keystore 保护。
- 应用当前是离线优先，本地单机工作。

### 双语支持

- UI 已支持英文和简体中文。
- 应用名随系统语言切换。
- 提醒相关文案和通知渠道文案也已双语化。
- README 目前有英文版 `README.md` 和中文版 `README.zh-CN.md`。

## 重要依赖与系统集成

- Kotlin
- Jetpack Compose
- Navigation Compose
- Hilt
- Room
- AlarmManager
- NotificationManagerCompat
- SQLCipher
- Android Keystore
- Coil
- `PdfRenderer`
- `FileProvider`

## 重要约束与已知问题

- 当前是单患者应用，没有多患者切换能力。
- 当前没有云同步、账号体系、远程备份或跨端同步。
- 当前没有 OCR、导出、云同步、应用锁、设置页。
- 当前已经有本地提醒和通知，但没有服药打卡、依从性统计、提醒历史或稍后提醒。
- `DataLayer.kt` 当前同时承载实体、DAO、仓储、DI 和部分业务逻辑，后续继续扩展时要注意文件职责已经偏重。
- 用药的 `frequency` 目前是自由文本，这会限制后续自动推断提醒规则的可靠性。
- 当前数据库 `exportSchema = false`，后续一旦开始增加迁移，需要更严格地管理 schema 演进。
- 用药提醒依赖系统通知权限和 Android 12+ 的 exact alarm 能力；权限被关闭时配置会保留，但不会立即调度。
- 当前终端/PowerShell 输出里，`README.zh-CN.md` 的中文显示存在乱码现象；需要后续确认文件编码、终端编码和查看器链路，避免误判内容损坏。

## 下一步待办

### 已讨论但未实现

- 服药打卡与依从性统计
  - 当前提醒只负责通知，不记录“已服药”“稍后提醒”或历史完成情况。
- 数据层拆分
  - `DataLayer.kt` 已继续变重，后续适合拆成 `Entity / Dao / Repository / DI` 多文件，降低维护成本。

## 维护规则

- 只要发生以下变化，就必须同步更新本文件：
  - 新增或删除功能
  - 架构拆分或模块边界变化
  - 数据模型、数据库版本或迁移变化
  - 权限、通知、后台任务、系统集成变化
  - 国际化策略变化
  - 重要约束、已知问题或下一步优先级变化
- 本文件记录“当前真实状态”和“已明确但未实现的方向”，不要把讨论中的能力写成已完成。
- 本文件不是提交日志，不记录每次小改动；只保留对后续开发判断有长期价值的信息。
