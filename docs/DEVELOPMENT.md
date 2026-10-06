# 開發與建置指南

## 建置需求

- JDK 17。
- Robolectric 測試使用 JDK 21 toolchain。
- Android SDK 35。
- Android NDK `28.2.13676358`。
- CMake `3.22.1`。
- Gradle wrapper 已包含於 repository。

## 建置 APK

```powershell
.\gradlew.bat :app:assembleDebug
```

APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## JVM／Robolectric 測試

```powershell
.\gradlew.bat -PskipNative :app:testDebugUnitTest
```

`-PskipNative` 僅用於 JVM 測試與不需要 native toolchain 的任務，不可用來產生交付 APK。

## 原生測試

若主機有 `g++`：

```sh
sh tests/native/run.sh
```

也可使用 Podman：

```powershell
.\gradlew.bat :app:prepareAudioInputs
podman build --tag musicxml-native-tests --file "tests/native/Dockerfile" "tests/native"
podman run --rm --mount "type=bind,source=$($PWD.Path),target=/work,readonly" musicxml-native-tests sh tests/native/run.sh
```

## 完整驗證

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:externalNativeBuildDebug :app:lintDebug :app:assembleDebug
```

## 原始碼規範

- Kotlin 與 C++ 修改使用最小必要變更。
- 不在 audio callback 進行檔案 I/O、JNI、配置或鎖定。
- 新功能應同時補充 JVM／Robolectric 或 native 測試。
- 不將使用者資料、SDK 路徑、簽章檔或本機設定提交到 repository。
- `DISCUSSION_SUMMARY.md` 為內部工作紀錄，已列入 `.gitignore`。

## 第三方元件

- TinySoundFont：MIT。
- Oboe：Apache-2.0。
- GeneralUser GS：依其授權條款使用。

第三方授權與音源授權文件會隨 App 資源提供；本 repository 的 MIT License 僅適用於本專案程式碼與文件，不取代第三方元件授權。
