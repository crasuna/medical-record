# 项目记忆

## 项目概览

`Medical Record` 是一个离线优先的 Android 病历管理应用，当前处于单患者本地管理的 MVP 阶段，重点覆盖就诊记录、附件留存、用药管理、本地提醒与加密存储。

## 当前架构

### UI

- `app/src/main/java/com/crasuna/medicalrecord/MainActivity.kt`
  - 负责应用壳、主题注入、底部导航和 Navigation Compose 路由入口。
  - 当前一级页面为 `home`、`encounters` 和 `medications`。
- `app/src/main/java/com/crasuna/medicalrecord/HomeFeature.kt`
  - 承担首页总览与全局搜索相关 Compose UI 和 ViewModel。
  - 覆盖首页概览统计、最近就诊、当前用药、今日提醒和跨就诊/用药搜索结果分组展示。
- `app/src/main/java/com/crasuna/medicalrecord/MedicalRecordDesignSystem.kt`
  - 承载当前共享设计系统。
  - 包含自定义 `MedicalRecordTheme`、颜色、Typography、Shapes、间距 token，以及卡片、顶部栏、按钮、表单、搜索框、空状态等复用 UI 组件。
  - 当前视觉方向是浅色、干净、偏医疗专业感，不支持 dark mode。
- `app/src/main/java/com/crasuna/medicalrecord/EncounterFeature.kt`
  - 承担就诊相关 Compose UI 和 ViewModel。
  - 覆盖就诊列表、就诊编辑、就诊详情、附件管理和附件预览。
- `app/src/main/java/com/crasuna/medicalrecord/MedicationFeature.kt`
  - 承担用药相关 Compose UI 和 ViewModel。
  - 覆盖用药列表、筛选、用药编辑、删除确认和提醒时间编辑。
  - 当前用药编辑页已采用分组卡片结构，并展示提醒区块。

### 数据层

- `app/src/main/java/com/crasuna/medicalrecord/DataLayer.kt`
  - 当前仍是聚合文件，同时承载：
  - Room `Entity`
  - `Dao`
  - `RoomDatabase`
  - Repository 接口与实现
  - Hilt `AppModule`
  - 首页聚合目前通过 Repository 暴露的全量就诊明细流和全量用药流在内存中完成，不依赖额外表或 FTS。
- 当前核心数据模型：
  - `EncounterEntity`
  - `EncounterAttachmentEntity`
  - `MedicationEntity`
  - `MedicationReminderEntity`
  - `MedicationWithReminders`
- 当前数据库版本为 `2`，包含 `MIGRATION_1_2`，用于新增用药提醒表。

### 安全

- `app/src/main/java/com/crasuna/medicalrecord/Security.kt`
  - `SecurePassphraseManager` 负责数据库口令生成、封装与读取。
  - `FileEncryptionManager` 负责附件文件和缩略图的 AES/GCM 加解密。
- 结构化数据存储使用 SQLCipher。
- 数据库口令和附件密钥通过 Android Keystore 保护。

### 国际化

- 应用名和 UI 文案已迁移到资源文件。
- 英文资源位于 `app/src/main/res/values/strings.xml`。
- 简体中文资源位于 `app/src/main/res/values-zh-rCN/strings.xml`。
- 当前语言策略为跟随系统语言，不支持应用内手动切换。

### 平台集成

- `app/src/main/java/com/crasuna/medicalrecord/MedicalRecordApp.kt`
  - 作为 `@HiltAndroidApp` 的 `Application` 入口。
  - 启动时初始化用药提醒通知渠道，并重同步有效提醒。
- `app/src/main/AndroidManifest.xml`
  - 注册 `MainActivity`、`FileProvider`、提醒相关 `BroadcastReceiver`。
  - 已声明 `POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`、`SCHEDULE_EXACT_ALARM`。
  - 已通过 `backup_rules.xml` 和 `data_extraction_rules.xml` 禁用云备份与设备迁移备份，避免本地医疗数据被系统自动迁移。
- 附件导入依赖系统内容选择器、拍照返回和 `PdfRenderer`。
- 用药提醒依赖 `AlarmManager`、系统通知和通知点击回到编辑页的参数跳转。
- Launcher 图标已包含兼容型位图资源、adaptive icon 和 monochrome icon。

## 已实现功能

### 首页总览与搜索

- 新增首页一级入口，作为默认启动页。
- 首页在空搜索时展示概览统计、最近就诊、当前用药和今日提醒。
- 首页支持直接跳转到就诊列表、用药列表，以及快速新建就诊/用药。
- 首页支持统一搜索就诊和用药，并按结果分组展示。
- 就诊搜索覆盖医院、科室、医生、主诉、诊断、处置、备注和附件文件名。
- 用药搜索覆盖药品名称、剂量、频次和备注。

### 就诊管理

- 就诊列表展示。
- 新建和编辑就诊。
- 查看就诊详情。
- 删除就诊。
- 记录医院、科室、医生、主诉、诊断、处置、备注、就诊日期和时间。
- 就诊相关页面已切换到统一的医疗风格卡片式布局。

### 附件管理

- 在就诊详情页内管理附件。
- 支持拍照导入。
- 支持导入图片。
- 支持导入 PDF。
- 支持缩略图与附件预览。
- 支持删除附件。
- PDF 预览支持页数显示和翻页。

### 用药管理

- 用药列表展示。
- `Current / All / Ended` 筛选。
- 新建和编辑用药。
- 删除用药。
- 记录药品名称、剂量、频次、开始日期、结束日期和备注。
- 支持为每个药品配置多个每日提醒时间。
- 用药列表卡片显示日期区间和提醒摘要。
- 提醒保存后会根据通知权限和 exact alarm 能力决定是否立即调度本地提醒。
- 点击提醒通知会直接打开对应药品编辑页。

### 本地安全存储

- 结构化数据使用 Room + SQLCipher。
- 附件文件使用 AES/GCM 加密后落盘。
- 数据库口令和附件密钥使用 Android Keystore 保护。
- 当前工作模式为离线优先、本地单机。

### 双语支持

- UI 已支持英文和简体中文。
- 应用名会随系统语言切换。
- 提醒相关文案和通知渠道文案也已双语化。
- README 采用双文件方案：`README.md` 和 `README.zh-CN.md`。

## 重要依赖与系统集成

- Kotlin
- Jetpack Compose
- Navigation Compose
- Hilt
- Room
- SQLCipher
- Android Keystore
- AlarmManager
- NotificationManagerCompat
- Coil
- `PdfRenderer`
- `FileProvider`

## 重要约束与已知问题

- 当前是单患者应用，没有多患者切换能力。
- 当前没有云同步、账号体系、远程备份或跨端同步。
- 当前没有 OCR、导出、应用锁、设置页。
- 当前已有本地提醒，但没有服药打卡、依从性统计、提醒历史或稍后提醒。
- `DataLayer.kt` 仍然过重，后续继续扩展时应考虑拆分为 `Entity / Dao / Repository / DI`。
- `MedicationEntity.frequency` 仍是自由文本，这会限制后续自动推断提醒规则的可靠性。
- 当前数据库 `exportSchema = false`，如果未来继续增加迁移，需要更严格地管理 schema 演进。
- 用药提醒依赖系统通知权限和 Android 12+ exact alarm 能力；权限被关闭时，提醒配置会保留，但不会立即调度。
- 在当前 PowerShell/终端链路下，中文 Markdown 和 XML 读取时可能显示乱码；这通常是终端编码问题，不一定代表文件内容损坏。后续查看中文文档时优先使用支持 UTF-8 的编辑器确认。

## 下一步待办

### 已讨论但未实现

- 服药打卡与依从性统计
  - 当前提醒只负责通知，不记录“已服药”“稍后提醒”或历史完成情况。
- 数据层拆分
  - `DataLayer.kt` 已明显偏重，适合后续拆成多文件。
- 导出与隐私增强
  - 当前仍没有导出、应用锁和设置页，这些仍是医疗数据产品后续应优先补齐的能力。

## 维护规则

- 只要发生以下变化，就必须同步更新本文件：
  - 新增或删除功能
  - 架构拆分或模块边界变化
  - 数据模型、数据库版本或迁移变化
  - 权限、通知、后台任务、系统集成变化
  - 国际化策略变化
  - 重要约束、已知问题或下一步优先级变化
- 本文件记录“当前真实状态”和“已明确但未实现的方向”，不要把讨论中的能力写成已完成。
- 本文件不是提交日志，不记录每次小改动，只保留对后续实现判断有长期价值的信息。
