# Medical Record Android 应用

English version: [README.md](README.md)

Medical Record 是一款私密、离线优先的 Android 个人医疗记录应用，用于管理单个患者的就诊、
附件、用药与提醒历史。本仓库现在包含一个采用全新 Android 身份的绿地式应用；它不会原位升级
或迁移旧 `com.crasuna.medicalrecord` 应用的安装数据。

## v1 范围

- 首页概览：最近就诊、当前用药、今日提醒与全局搜索。
- 就诊列表、详情与编辑。
- 从系统相机、Photo Picker 和存储访问框架导入并加密保存 JPEG、PNG、WebP、HEIC/HEIF
  与 PDF 附件。
- 用药疗程及“当前 / 即将开始 / 已结束 / 全部”筛选。
- 每个药品可保存多个每日提醒意图。
- 完整英文和简体中文资源。
- Material 3 浅色/深色主题、紧凑窗口底部导航、宽窗口侧边导航，以及 Navigation 3
  列表/详情场景。

账号、云同步、OCR、导出、应用锁、可见的患者切换、服药依从性记录、DICOM、视频、音频和
Office 附件不在 v1 范围内。

## 应用身份

| 变体 | Application ID | 应用名称 |
| --- | --- | --- |
| Release | `com.loveluke.medicalrecord` | `Medical Record` / `医疗记录` |
| Debug | `com.loveluke.medicalrecord.debug` | `Medical Record (Debug)` / `医疗记录（调试）` |

两个变体使用不同的 Android 沙箱，因此数据库、文件、偏好、通知、PendingIntent、FileProvider
authority 和 Keystore alias 彼此隔离。

## 架构

项目刻意只保留一个 Gradle 模块 `:app`，用源码包边界组织职责，不建立空模块或完整 Clean
Architecture 层级：

```text
app/src/main/java/com/loveluke/medicalrecord/
├── app/                 Application、Activity、访问门禁、DI、导航、运行时协调
├── core/
│   ├── attachment/     校验与流式导入、AES-GCM 存储、预览和清理
│   ├── database/       Room 实体、DAO、Repository、schema 与 SQLCipher 接入
│   ├── designsystem/   Material 3 主题和共享组件
│   ├── model/          共享不可变模型
│   ├── privacy/        前台与最近任务隐私行为
│   ├── reminder/       闹钟调度、Receiver 和隐私通知
│   └── security/       Keystore envelope、fail-closed 访问与本机数据清除
└── feature/
    ├── home/
    ├── encounter/
    └── medication/
```

常规调用链是 `Compose Screen -> ViewModel -> Repository 或安全 façade -> Room、加密附件存储
或小型 Android 系统适配器`。UI 使用不可变状态、`StateFlow` 和生命周期感知收集；简单动作不
套 UseCase，也不引入第三方 MVI。

导航使用可序列化 Navigation 3 key 和 `NavigationSuiteScaffold`。紧凑窗口使用底部导航，
medium/expanded 窗口使用 rail。就诊与用药路由通过稳定版自定义 Navigation 3
`SceneStrategy` 实现列表/详情双栏，没有使用 `ListDetailPaneScaffold`。

## 本地安全与隐私

- Room schema 从版本 1 开始并由 SQLCipher 加密；导出的 schema 位于 `app/schemas/` 并纳入
  版本控制。
- 每个 application ID 使用一个不可导出的 Android Keystore AES-256 wrapping key，分别包装
  随机 SQLCipher 口令和随机附件主密钥；认证 envelope 位于 `noBackupFilesDir`。
- 每个附件使用独立随机 data key，并写入带认证、带版本的 AES-GCM 容器。原文件名只用于显示，
  不作为内部路径。
- 密钥或数据库认证失败时 fail closed：应用不会静默生成替代密钥或删除数据，只提供重试以及
  两次明确确认的“清除本机数据”。
- 单个附件损坏时只隔离该附件。预览和拍照明文只临时存在，并在成功、失败、取消、生命周期离开
  和冷启动时清理。
- 禁用 Android 备份和设备迁移提取，并在两套备份规则中明确排除敏感路径。
- 提醒通知使用私密可见性和完全脱敏的 public version；不显示诊断、患者、医生、医院或备注。
- 前台截图由用户控制；API 33+ 禁止最近任务截图，API 26–32 使用中性遮罩与临时
  `FLAG_SECURE`。

领域不变量与架构决策见 [CONTEXT.md](CONTEXT.md) 和 [docs/adr](docs/adr)。

## 工具链

- Android Gradle Plugin 9.3.1，使用 AGP built-in Kotlin
- Gradle Wrapper 9.7.0
- Gradle daemon 使用 JDK 25
- Java toolchain、Java source/target 与 Kotlin JVM target 均为 17
- compileSdk / targetSdk 37，minSdk 26
- Kotlin 与 Compose compiler 2.4.10、KSP 2.3.11、Hilt 2.60.1
- Room 2.8.4、SQLCipher Android 4.17.0、AndroidX SQLite 2.7.0
- Jetpack Compose Material 3 与 Navigation 3

所有依赖版本均在 `gradle/libs.versions.toml` 精确锁定，不使用动态版本或预发布版本。显式 Kotlin
2.4.10 classpath 是 AGP 9.3.1 可配置的 higher-KGP 覆盖，不代表它位于 JetBrains fully-supported
矩阵内。如果可复现兼容故障能归因于该覆盖，应移除 higher-KGP 和显式 Compose compiler 覆盖，
恢复 AGP 内置版本。

## 环境要求

- Windows 10 或 11（下列示例使用 PowerShell）
- Gradle daemon 使用 JDK 25
- Android SDK platform 37、当前 build-tools、platform-tools 和 command-line tools
- 执行 instrumentation 测试所需的模拟器或设备

调用 Gradle 前将 `JAVA_HOME` 指向 JDK 25；项目产出的仍是 JVM 17 字节码。

## 构建与验证

在仓库根目录执行：

```powershell
.\gradlew.bat help --warning-mode=all --configuration-cache
.\gradlew.bat help --warning-mode=all --configuration-cache
.\gradlew.bat testDebugUnitTest --warning-mode=all
.\gradlew.bat compileDebugAndroidTestKotlin --warning-mode=all
.\gradlew.bat lintDebug --warning-mode=all
.\gradlew.bat assembleDebug --warning-mode=all
.\gradlew.bat assembleRelease --warning-mode=all
.\gradlew.bat bundleRelease --warning-mode=all
```

有设备时执行：

```powershell
.\gradlew.bat connectedDebugAndroidTest --warning-mode=all
```

instrumentation 测试覆盖 Android 特有的 Room、SQLCipher 和 Keystore 行为。默认设备门禁只覆盖
一个当前选定的在线设备或模拟器；通过门禁还要求 debug 安装与冷启动、核心导航烟雾检查，以及没有
新增应用崩溃或 ANR。仅 `compileDebugAndroidTestKotlin` 成功只代表这些测试可以编译，不代表设备
门禁已通过。

完整兼容性矩阵是非阻塞清单，只在用户明确要求时执行或汇报。权威规则见
[设备验收策略](PROJECT_MEMORY.md#测试与设备验收)。

## 运行 debug 应用

```powershell
.\gradlew.bat installDebug
adb shell cmd package resolve-activity --brief com.loveluke.medicalrecord.debug
adb shell am start -n com.loveluke.medicalrecord.debug/com.loveluke.medicalrecord.app.MainActivity
```

## Release 签名

Debug 使用标准 Android debug key；release 绝不回退到该密钥。未提供签名变量时，release APK/AAB
和 R8 检查有意保持 unsigned。若使用外部管理的 release 密钥，必须同时提供以下四项：

```text
MEDICAL_RECORD_STORE_FILE
MEDICAL_RECORD_STORE_PASSWORD
MEDICAL_RECORD_KEY_ALIAS
MEDICAL_RECORD_KEY_PASSWORD
```

只提供部分变量会在 Gradle 配置阶段明确失败。Keystore 和凭据不得进入 Git。实际准备发布时再决定
采用 Play App Signing + upload key，还是自行管理 app signing key。

## 提醒行为

应用声明 `SCHEDULE_EXACT_ALARM`，不使用 `USE_EXACT_ALARM`。有授权时通过
`setExactAndAllowWhileIdle` 安排下一次单次提醒；没有授权时保留用户意图，并在通知可用时降级为
非精确闹钟，同时提示可能延迟。没有通知权限时仍保留意图，但不安排无意义闹钟。开机、包升级、
时间/时区变化、exact-alarm 授权、Activity 恢复和冷启动都会重新协调下一次提醒。
