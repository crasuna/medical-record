# Medical Record Android App

中文说明：[README.zh-CN.md](README.zh-CN.md)

An offline-first Android app for managing personal medical history.

## Current MVP scope

- Home overview dashboard
  - Overview stats
  - Recent encounters
  - Current medications
  - Today's reminders
- Global search
  - Search across encounters and medications
  - Search encounter attachments by file name
- Encounter timeline
- Encounter detail view
- Encounter attachments
  - Capture photo
  - Import image
  - Import PDF
  - Preview and delete attachments
- Medication management
  - Current / all / ended filters
  - Dose, frequency, date range, and notes
- Local encrypted storage
  - SQLCipher for structured data
  - AES/GCM file encryption for attachments
  - Android Keystore for key protection

## Tech stack

- Kotlin
- Jetpack Compose
- Navigation Compose
- Hilt
- Room
- SQLCipher

## Project structure

- `app/src/main/java/com/crasuna/medicalrecord/MainActivity.kt`
  - App shell and navigation
- `app/src/main/java/com/crasuna/medicalrecord/HomeFeature.kt`
  - Home dashboard, overview aggregation, and global search UI
- `app/src/main/java/com/crasuna/medicalrecord/EncounterFeature.kt`
  - Encounter list, editor, detail, and attachment preview UI
- `app/src/main/java/com/crasuna/medicalrecord/MedicationFeature.kt`
  - Medication list and editor UI
- `app/src/main/java/com/crasuna/medicalrecord/DataLayer.kt`
  - Entities, DAOs, repositories, DI module, and business logic
- `app/src/main/java/com/crasuna/medicalrecord/Security.kt`
  - Database key wrapping and encrypted attachment handling

## Requirements

- Windows 10/11
- JDK 17
- Android SDK with:
  - `platforms;android-34`
  - `build-tools;33.0.1` or newer
  - `platform-tools`
  - `emulator`
  - `cmdline-tools`

## Local environment

The machine used for setup now has:

- `JAVA_HOME` configured to Microsoft OpenJDK 17
- `ANDROID_SDK_ROOT` configured to `%LOCALAPPDATA%\\Android\\Sdk`
- `local.properties` pointing to the local Android SDK path

## Build

From the project root:

```powershell
.\gradlew.bat tasks
.\gradlew.bat assembleDebug
```

## Run on emulator

List emulators:

```powershell
adb devices
emulator -list-avds
```

Install and launch:

```powershell
.\gradlew.bat installDebug
adb shell cmd package resolve-activity --brief com.crasuna.medicalrecord
adb shell am start -n com.crasuna.medicalrecord/.MainActivity
```

## Test

Unit tests:

```powershell
.\gradlew.bat testDebugUnitTest
```

Instrumentation tests:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

## Notes

- The UI now supports English and Simplified Chinese through Android string resources.
- The app is single-patient only in the current MVP.
- Cloud sync, OCR, export, app lock, and a dedicated settings screen are intentionally out of scope for this version.
