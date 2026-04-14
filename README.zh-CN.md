# Medical Record Android 应用

English version: [README.md](README.md)

这是一个离线优先的 Android 应用，用于管理个人病历。

## 当前 MVP 范围

- 就诊时间线
- 就诊详情页
- 就诊附件
  - 拍照
  - 导入图片
  - 导入 PDF
  - 预览和删除附件
- 用药管理
  - 当前 / 全部 / 已结束筛选
  - 剂量、频次、日期范围和备注
- 本地加密存储
  - SQLCipher 用于结构化数据
  - AES/GCM 文件加密用于附件
  - Android Keystore 用于密钥保护

## 技术栈

- Kotlin
- Jetpack Compose
- Navigation Compose
- Hilt
- Room
- SQLCipher

## 项目结构

- `app/src/main/java/com/crasuna/medicalrecord/MainActivity.kt`
  - 应用外壳和导航
- `app/src/main/java/com/crasuna/medicalrecord/EncounterFeature.kt`
  - 就诊列表、编辑、详情和附件预览 UI
- `app/src/main/java/com/crasuna/medicalrecord/MedicationFeature.kt`
  - 用药列表和编辑 UI
- `app/src/main/java/com/crasuna/medicalrecord/DataLayer.kt`
  - 实体、DAO、仓储、DI 模块和业务逻辑
- `app/src/main/java/com/crasuna/medicalrecord/Security.kt`
  - 数据库密钥封装和附件加密处理

## 环境要求

- Windows 10/11
- JDK 17
- Android SDK，包含：
  - `platforms;android-34`
  - `build-tools;33.0.1` 或更新版本
  - `platform-tools`
  - `emulator`
  - `cmdline-tools`

## 本地环境

当前用于配置的机器已具备：

- `JAVA_HOME` 已配置为 Microsoft OpenJDK 17
- `ANDROID_SDK_ROOT` 已配置为 `%LOCALAPPDATA%\\Android\\Sdk`
- `local.properties` 已指向本地 Android SDK 路径

## 构建

在项目根目录执行：

```powershell
.\gradlew.bat tasks
.\gradlew.bat assembleDebug
```

## 在模拟器上运行

列出模拟器：

```powershell
adb devices
emulator -list-avds
```

安装并启动：

```powershell
.\gradlew.bat installDebug
adb shell cmd package resolve-activity --brief com.crasuna.medicalrecord
adb shell am start -n com.crasuna.medicalrecord/.MainActivity
```

## 测试

单元测试：

```powershell
.\gradlew.bat testDebugUnitTest
```

仪器测试：

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## 说明

- UI 现已通过 Android 字符串资源同时支持英文和简体中文。
- 当前 MVP 仅支持单个患者。
- 云同步、OCR、导出、提醒和应用锁故意不在此版本范围内。
