# 设备用户旅程测试指南

本指南说明怎样执行和解释 Medical Record 的设备端自动化测试。验收范围、阻塞性和报告口径以
根目录 [`PROJECT_MEMORY.md`](../../PROJECT_MEMORY.md#测试与设备验收) 的“测试与设备验收”章节为
唯一权威来源；本文件只提供可操作命令、覆盖清单和证据布局。

## 隔离身份与安全边界

设备测试使用独立的 `e2e` build type：

| 用途 | Application ID |
| --- | --- |
| E2E 目标应用 | `com.loveluke.medicalrecord.e2e` |
| E2E 测试应用 | `com.loveluke.medicalrecord.e2e.test` |

`connectedE2eAndroidTest` 前的准备任务只允许卸载这两个 E2E 包，以清除上一次失败遗留的安装状态。
不得由设备测试清理 release 包 `com.loveluke.medicalrecord` 或 debug 包
`com.loveluke.medicalrecord.debug`。Android Test Orchestrator 会在测试之间清除 E2E 应用数据，测试
可以创建和删除自己的记录，但不会访问 release/debug 沙箱中的用户业务数据。

## 设备选择

默认验收只选择一个已启动且 ADB 状态为 `device` 的物理设备或模拟器：

- 恰好一个设备在线：直接使用它，也可以显式设置 `ANDROID_SERIAL`。
- 多个设备在线：必须先由用户选择一个，并设置 `ANDROID_SERIAL`；不得默认在所有设备上运行。
- 没有设备在线：设备测试不执行，结论只能写“设备测试未执行”；不得把 instrumentation 编译成功
  当作设备通过，也不得把未执行写成测试失败。

PowerShell 示例：

```powershell
$env:ANDROID_SERIAL = "emulator-5554"
```

## 默认阻塞门禁：Core Journey

`@CoreJourney` 是默认且唯一阻塞的设备门禁，必须完整执行并精确发现 12 条测试。每条测试的硬超时
为 5 分钟。

```powershell
$env:ANDROID_SERIAL = "<adb-serial>"

.\gradlew.bat `
  :app:connectedE2eAndroidTest `
  :app:verifyAndArchiveCoreJourney `
  "-Pandroid.testInstrumentationRunnerArguments.annotation=com.loveluke.medicalrecord.test.CoreJourney" `
  --warning-mode=all `
  --console=plain
```

12 条覆盖如下：

1. MainActivity 冷启动，以及 Home、Encounters、Medications 三个核心页面的往返导航。
2. 创建就诊，Repository 确认，Activity recreate，编辑并验证字段持久化。
3. 删除就诊时先取消、再确认，并验证列表、首页、搜索和 Repository 投影同步删除。
4. 创建用药与两个提醒，Activity recreate，并验证 Current、Upcoming、Ended、All 筛选。
5. 删除用药时先取消、再确认，并验证首页、提醒投影和 Repository 同步删除。
6. 首页搜索同时命中就诊和用药；分别进入详情再返回后，查询词与结果保持。
7. 导入真实加密 JPEG，预览并删除，同时核验 metadata、密文文件和 UI 状态。
8. Room 复合外键拒绝跨患者提醒引用。
9. Android Keystore wrapping key 不可导出，并按安装身份隔离 namespace。
10. key envelope 使用真实 Keystore key 完成加密和解密。
11. SQLCipher 建库、重开、错误密钥拒绝、WAL 和并发事务。
12. Locked 页面只有经过两次明确确认才允许清除本机数据。

Core 通过必须同时满足：

- `connectedE2eAndroidTest` 在选定设备上真实执行。
- JUnit 为 `tests=12`、`failures=0`、`errors=0`、`skipped=0`。
- `verifyAndArchiveCoreJourney` 成功核对计数、逐测试证据和设备 metadata，并完成归档。
- E2E APK 安装、MainActivity 冷启动和所有自动行为断言通过。
- 每条测试自己的验收窗口内没有目标 E2E 应用新增崩溃、`FATAL EXCEPTION` 或 ANR。

全部满足后，结论可以写“当前设备端验收已通过”。该结论只覆盖报告中记录的选定设备，不能泛化为
其他 Android 设备或配置已经验证。

## 按需系统交互：System Interaction

`@SystemInteraction` 只在用户明确要求系统交互验收，或当前任务明确涉及相应系统边界时执行。它必须
完整执行并精确发现 2 条测试，每条测试的硬超时为 15 分钟；它不是默认阻塞门禁。

```powershell
$env:ANDROID_SERIAL = "<adb-serial>"

.\gradlew.bat `
  :app:connectedE2eAndroidTest `
  :app:verifyAndArchiveSystemInteraction `
  "-Pandroid.testInstrumentationRunnerArguments.annotation=com.loveluke.medicalrecord.test.SystemInteraction" `
  --warning-mode=all `
  --console=plain
```

2 条覆盖如下：

1. 通过真实系统 Photo Picker 选择测试 JPEG，导入隔离应用的加密附件存储，并通过解密预览核验尺寸
   和像素内容，同时确认落盘内容不是 JPEG 明文。
2. 真实 Receiver 发布 `VISIBILITY_PRIVATE` 用药提醒，核验 public version 完全脱敏，再从系统通知栏
   点击通知并进入对应的用药详情。

System 通过要求 `tests=2`、`failures=0`、`errors=0`、`skipped=0`，并由
`verifyAndArchiveSystemInteraction` 完成证据校验和归档。System 失败只否定本次被要求的系统交互项目，
不会回溯性否定此前已经通过的 Core 门禁。

## 判定、重跑与人工观察

自动断言是唯一 verdict。终态截图、失败截图、UI hierarchy、exception、logcat 和代理人工观察只用于
留证与诊断，不能把自动化失败改判成通过。

- 禁止静默 skip；任何 `skipped > 0` 都使该分组不通过。
- 不自动重试单条失败测试；修复实现或测试后，必须重新执行对应的完整 12 条或 2 条分组。
- 代理手工烟雾检查默认不执行。只有自动化失败需要定位、用户报告 UI/交互问题，或用户明确要求人工
  观察时才执行；手工结果不能替代自动化门禁。
- `:app:testE2eUnitTest` 是主机 JVM 测试；`:app:compileE2eAndroidTestKotlin` 只验证 instrumentation
  编译。两者都不能证明设备测试已经执行或通过。

## 结果与证据

Android Gradle Plugin 的原始 instrumentation 结果位于：

```text
app/build/outputs/androidTest-results/connected/e2e/
```

Test Storage Service 收集的原始逐测试附加输出位于：

```text
app/build/outputs/connected_android_test_additional_output/e2eAndroidTest/connected/
```

这两个原始目录会被后续 `connectedE2eAndroidTest` 运行替换；需要长期引用或比较结果时，应使用下面按
设备和分组保存的归档副本。

校验任务按真实 ADB serial 和分组归档到：

```text
app/build/outputs/androidTest-artifacts/<adb-serial>/core/
app/build/outputs/androidTest-artifacts/<adb-serial>/system-interaction/
```

每个归档目录包含：

- `report.txt`：最终 verdict、精确计数、ADB serial、设备内 serial、AVD、API、ABI、page size、
  分辨率/密度、JUnit 路径、测试身份和对应证据路径。
- `junit.xml`：本次选定分组的 JUnit XML 副本。
- `evidence/journeys/<class>/<method>/device-metadata.txt`：逐测试设备 metadata。
- `evidence/journeys/<class>/<method>/terminal.png`：通过测试的终态截图。
- 失败时的 `failure.png`、`exception.txt`、`logcat.txt` 和 `ui-hierarchy.xml` 保留在原始附加输出中，
  用于诊断；通过的 XML 若与失败证据冲突，归档任务会拒绝通过。

报告中的 `serial` 是主机 ADB 选择使用的 serial；`deviceSerial` 是设备内
`ro.serialno`。两者含义不同，不能互相替代。

## 标准结论措辞

- 一个设备在线，Core 自动化与归档校验全部通过：写“当前设备端验收已通过”，并记录设备及证据
  路径。
- 只有 instrumentation 编译结果，未在设备执行：写“设备测试未执行”。
- 当前设备发生测试失败、安装/启动失败、目标应用崩溃或 ANR：写“当前设备门禁失败”，并引用 JUnit
  或逐测试诊断证据。
- 多个设备在线但尚未选择：先请求用户指定一个，不擅自执行设备矩阵。
- 用户单独要求某个系统交互或兼容性项目：只报告该项目的实际结果，不回溯性否定此前 Core 结论。

完整兼容性矩阵不是默认门禁，日常默认不执行、不展开、不汇报。矩阵的规范性定义只保留在
[`PROJECT_MEMORY.md`](../../PROJECT_MEMORY.md#非阻塞兼容性清单)；本指南不复制其项目清单。
