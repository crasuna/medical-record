# Medical Record

中文说明：[README.zh-CN.md](README.zh-CN.md)

Medical Record is a private, offline-first Android application for keeping one person's encounter,
attachment, medication, and reminder history. This repository contains a greenfield application
with a new Android identity; it does not upgrade or migrate installations of the former
`com.crasuna.medicalrecord` app.

## Version 1 scope

- Home overview with recent encounters, current medications, today's reminders, and global search.
- Encounter list, detail, and editor.
- Encrypted JPEG, PNG, WebP, HEIC/HEIF, and PDF attachments from the system camera, Photo Picker,
  and Storage Access Framework.
- Medication courses with current, upcoming, ended, and all filters.
- Multiple daily reminder intentions per medication.
- Complete English and Simplified Chinese resources.
- Material 3 light and dark themes, compact bottom navigation, wide-window navigation rail, and
  Navigation 3 list/detail scenes.

Accounts, cloud synchronization, OCR, export, app lock, visible patient switching, adherence
tracking, DICOM, video, audio, and Office attachments are intentionally outside version 1.

## Application identities

| Variant | Application ID | App name |
| --- | --- | --- |
| Release | `com.loveluke.medicalrecord` | `Medical Record` / `医疗记录` |
| Debug | `com.loveluke.medicalrecord.debug` | `Medical Record (Debug)` / `医疗记录（调试）` |

The variants use separate Android sandboxes. Their databases, files, preferences, notifications,
PendingIntents, FileProvider authorities, and Keystore aliases therefore remain isolated.

## Architecture

The project intentionally uses one Gradle module, `:app`, with source boundaries instead of empty
modules or a full Clean Architecture stack:

```text
app/src/main/java/com/loveluke/medicalrecord/
├── app/                 Application, activity, access gate, DI, navigation, runtime coordination
├── core/
│   ├── attachment/     Validated streaming import, AES-GCM storage, preview and cleanup
│   ├── database/       Room entities, DAOs, repositories, schema and SQLCipher integration
│   ├── designsystem/   Material 3 theme and shared components
│   ├── model/          Shared immutable models
│   ├── privacy/        Foreground/recents privacy behavior
│   ├── reminder/       Alarm scheduling, receivers and privacy-preserving notifications
│   └── security/       Keystore envelopes, fail-closed access and local-data clearing
└── feature/
    ├── home/
    ├── encounter/
    └── medication/
```

The normal call path is `Compose screen -> ViewModel -> repository or security facade -> Room,
encrypted attachment storage, or a small Android system adapter`. UI state is immutable and exposed
with `StateFlow`; screens collect it with lifecycle awareness. Simple actions are not wrapped in
UseCase classes, and no third-party MVI framework is used.

Navigation uses serializable Navigation 3 keys and `NavigationSuiteScaffold`. Compact windows use a
bottom bar; medium and expanded windows use a rail. Encounter and medication routes use a stable
custom Navigation 3 `SceneStrategy` for list/detail presentation rather than
`ListDetailPaneScaffold`.

## Local security and privacy

- Room schema version 1 is encrypted with SQLCipher. The exported schema is tracked under
  `app/schemas/`.
- A non-exportable AES-256 Android Keystore key wraps two independent random secrets: the SQLCipher
  passphrase and the attachment master key. Authenticated envelopes live in `noBackupFilesDir`.
- Every attachment has a random data key and is stored in an authenticated, versioned AES-GCM
  container in app-private storage. Original display names are never storage paths.
- Key or database authentication failure fails closed. The app never silently generates a
  replacement key or deletes data; recovery offers retry and a twice-confirmed local-data clear.
- A corrupt attachment is quarantined independently. Decrypted preview and camera plaintext are
  temporary and are cleaned on success, failure, cancellation, lifecycle exit, and cold start.
- Android backup and device-transfer extraction are disabled and sensitive paths are explicitly
  excluded.
- Reminder notifications use a private visibility level and a redacted public version. They never
  expose diagnoses, patient identity, clinicians, hospitals, or notes.
- Foreground screenshots remain user-controlled. Recent-task content is suppressed on API 33+ and
  covered with a neutral privacy surface on API 26-32.

See [CONTEXT.md](CONTEXT.md) and [docs/adr](docs/adr) for domain invariants and architectural
decisions.

## Toolchain

- Android Gradle Plugin 9.3.1 with AGP built-in Kotlin
- Gradle Wrapper 9.7.0
- Gradle daemon JDK 25
- Java toolchain, Java source/target, and Kotlin JVM target 17
- compileSdk / targetSdk 37; minSdk 26
- Kotlin and Compose compiler 2.4.10, KSP 2.3.11, Hilt 2.60.1
- Room 2.8.4, SQLCipher Android 4.17.0, AndroidX SQLite 2.7.0
- Jetpack Compose Material 3 and Navigation 3

All dependency versions are pinned in `gradle/libs.versions.toml`; dynamic and prerelease versions
are not used. The explicit Kotlin 2.4.10 classpath is a configurable higher-KGP override for AGP
9.3.1, not a claim of inclusion in JetBrains' fully-supported version matrix. If a reproducible
compatibility failure is attributable to that override, remove the higher-KGP and explicit Compose
compiler override and use AGP's built-in versions.

## Requirements

- Windows 10 or 11 (the included examples use PowerShell)
- JDK 25 for the Gradle daemon
- Android SDK platform 37, current build-tools, platform-tools, and command-line tools
- An emulator or device for instrumentation tests

Set `JAVA_HOME` to JDK 25 before invoking Gradle. The project still emits JVM 17 bytecode.

## Build and verify

From the repository root:

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

Run instrumentation tests when a device is available:

```powershell
.\gradlew.bat connectedDebugAndroidTest --warning-mode=all
```

Instrumentation covers Android-specific Room/SQLCipher/Keystore behavior. By default, the device
gate covers one selected online device or emulator. Passing it requires the instrumentation suite,
debug installation and cold start, core navigation smoke checks, and no new app crash or ANR. A
successful `compileDebugAndroidTestKotlin` only proves those tests compile.

The broader compatibility matrix is non-blocking and is evaluated or reported only when explicitly
requested. See the authoritative [device acceptance policy](PROJECT_MEMORY.md#测试与设备验收).

## Run the debug app

```powershell
.\gradlew.bat installDebug
adb shell cmd package resolve-activity --brief com.loveluke.medicalrecord.debug
adb shell am start -n com.loveluke.medicalrecord.debug/com.loveluke.medicalrecord.app.MainActivity
```

## Release signing

Debug uses the standard Android debug key. Release never falls back to that key. With no signing
variables present, release APK/AAB and R8 checks are intentionally unsigned. For an externally
managed release key, all four variables must be supplied together:

```text
MEDICAL_RECORD_STORE_FILE
MEDICAL_RECORD_STORE_PASSWORD
MEDICAL_RECORD_KEY_ALIAS
MEDICAL_RECORD_KEY_PASSWORD
```

A partial configuration fails during Gradle configuration. Keystores and credentials must not be
added to Git. Decide between Play App Signing plus an upload key and self-managed app signing only
when preparing an actual release.

## Reminder behavior

The app requests `SCHEDULE_EXACT_ALARM`, not `USE_EXACT_ALARM`. With access it schedules the next
single occurrence using `setExactAndAllowWhileIdle`; without access it retains the user's intention,
uses an inexact alarm when notifications are available, and communicates possible delay. If
notification permission is absent, intentions remain stored but meaningless alarms are not
scheduled. Boot, package upgrade, time, time-zone, exact-alarm grant, resume, and cold-start paths
reconcile the next occurrence.
