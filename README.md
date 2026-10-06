# 小小樂隊

Android 8.0 以上的離線 MusicXML 播放器。提供首頁、所有曲目、可編輯播放清單、播放佇列、底部迷你播放器，以及整合 MusicXML 演奏控制的現正播放頁。圖示為粗線條、微笑指揮家的上半身。

## 專案文件

- [專案說明](docs/PROJECT_OVERVIEW.md)
- [使用指南](docs/USER_GUIDE.md)
- [系統架構與技術文件](docs/ARCHITECTURE.md)
- [系統設計文件](docs/SYSTEM_DESIGN.md)
- [開發與建置指南](docs/DEVELOPMENT.md)
- [MIT License](LICENSE)

本公開 repository 的文件只描述目前實作狀態。`DISCUSSION_SUMMARY.md` 為內部工作紀錄，不納入提交。

## 已實作

- 曲目庫：匯入 `.musicxml`、`.xml`、`.mxl`，以及含 `musicxml` 字串欄位的 JSON 包裝檔；可查詢，依最近播放、名稱、作曲者、加入日期或長度排序，並可改名與刪除。
- 播放清單：新增、改名、刪除、批次加入／移除曲目，以及長按拖曳排序。
- 播放佇列：上一首／下一首、隨機播放、關閉／清單／單曲三段循環，並可在程序重啟後以暫停狀態恢復曲目與進度。
- 各主要頁面提供固定底部迷你播放器，點擊可進入現正播放頁。
- 匯入檔案保存在 App 私有空間；改名只更新音譜庫的顯示名稱。
- MusicXML：多樂器、多聲部、和弦、休止符、延音線、backup／forward、移調、力度、速度變化、巢狀反覆、多重結尾、D.C.／D.S.／Fine／Coda。
- 匯入時在裝飾音、反覆與結尾展開前檢查 tick、時長、結尾範圍、事件總數及 MXL 項目數／檔名長度，異常檔案會快速拒絕，不會無限制占用記憶體。
- 完整解析 metronome beat-unit、多重附點與 metric modulation；同一 direction 的 `sound tempo` 優先於 metronome 速度，另支援 swing 與有前後速度錨點的 ritardando／accelerando 漸變。
- 支援 arpeggiate／non-arpeggiate、slur／legato、階梯式 glissando／slide，以及 grace note（含 steal-time／make-time）、trill、mordent、turn、tremolo、fermata、breath mark、staccato、tenuto、accent、marcato；grace、trill 與 wavy-line 可跨小節延續。
- Grace note 與力度依 MusicXML 的 voice／staff 隔離；跨小節與反覆展開後仍連接實際演奏的同 voice／staff 主音或前音，不會干擾其他譜表。
- 即時採樣播放：APK 內建 **GeneralUser GS 2.0.3 SoundFont**，使用 **TinySoundFont＋Oboe C++ 引擎**直接輸出音訊，不預先產生整首 WAV，也不依賴手機系統 MIDI 音色。
- 常見 GM 音色：鋼琴、弦樂、吉他、貝斯、木管、銅管、薩克斯風、鼓組等；可替換旋律樂器音色。
- 現正播放頁提供上一首、播放／暫停、下一首、隨機播放，以及關閉／清單／單曲三段循環；播放速度提供 50%、75%、100%、125%、150% 選項，保持音高。
- 播放器可選擇原譜、流行、抒情、自然、浪漫五種演奏風格；速度與風格為全 App 共用，即時播放與 WAV 匯出共用相同的演奏事件計畫。
- 聲部面板提供啟用開關、樂器替換，以及目前發聲樂器的狀態燈；聲部開關不持久保存，替換樂器依曲目保存。
- 切換聲部、替換音色與開關節拍器會即時更新混音，不重建播放器、不重設進度；音色變更作用於新觸發的音符。
- 可即時開關四分音符節拍器；樂譜內的 metronome 標記則完整參與速度解析。
- 同步音符卷軸使用 App 展開後的演奏路徑，橫向顯示時間、縱向顯示音高，並支援點選定位。
- 音符卷軸上方依序顯示繁體中文演奏情境與當下發聲樂器，例如力度、漸強／漸弱、滑音、顫音、音色變化與踏板；樂器依聲部顏色以粗體顯示並自動換行。
- 小節與樂曲效果顯示在同一列；固定底部進度列只在進度條左右垂直置中顯示已播放時間與總時長。
- 保存每份樂譜的替換音色；全域保存播放速度與演奏風格。
- 主畫面右上角齒輪設定：顯示模式為系統（預設）／淺色／深色，立即套用並保存。切換模式不會重啟音訊。
- 設定提供六個活潑主題色彩與 Hue 自訂滑桿；主題色、深色模式、狀態列和 Navigation Bar 會立即同步更新。
- 系統列文字／圖示明暗跟隨顯示模式；樂器名稱與卷軸共用聲部配色。
- 回到其他頁面、切到背景、鎖屏時繼續播放，支援媒體通知及鎖定畫面上一首／播放／下一首／停止控制。
- Android 13 以上在第一次由使用者播放時詢問通知權限；拒絕不會阻止播放。播放器顯示實際 AAudio 輸出裝置，無法取得原生裝置 ID 時明確標示系統推定或系統選擇。
- 設定頁最下方顯示目前音訊輸出、App 版本及 UTC Build 編碼；Build 格式為 `yyyyMMdd.HHmmss.versionCode`。
- 支援多個 `midi-instrument` 身分及其 program／bank／channel，並依每個音符的 instrument 選用音色；樂譜控制與樂器身分在反覆及 D.C.／D.S. 等導航後仍會保留。
- 支援 CC64 半踏板、CC66 sostenuto 與 CC67 soft pedal。即時播放與 WAV 共用樂譜演奏事件及控制行為；只提供 WAV 匯出，速度仍使用原始樂譜速度。
- 內附約 40 秒的原創示範曲「晨光小遊行」，使用鋼琴、長笛、單簧管、大提琴與輕鼓組，包含反覆、力度、漸強／漸弱、踏板、裝飾音、奏法及結尾漸慢。

## 安裝與操作

建置後的 APK：`app/build/outputs/apk/debug/app-debug.apk`。

1. 在 Android 手機安裝 APK；若由檔案管理器安裝，依系統提示允許該來源安裝。
2. 開啟「小小樂隊」，按「示範曲」，或按「＋ 匯入樂譜」選擇 MusicXML／MXL／JSON 包裝的樂譜；匯入成功後進入所有曲目。
3. 在所有曲目或播放清單點選樂曲，採樣庫與音訊裝置載入完成就會自動播放，無需等待整首 WAV 產生。
4. 點選音符卷軸、拖曳進度條，或按「小節」定位。
5. 在聲部面板啟用／停用聲部或替換音色。
6. 音符卷軸上方會依序顯示目前演奏情境及當下發聲樂器；點選卷軸可定位播放位置。
7. 回到其他頁面仍會繼續播放；底部迷你播放器可控制播放並返回現正播放頁。
8. 在主畫面右上角按齒輪，切換顯示模式。預設跟隨手機，音樂不會中斷。
9. 切到其他 App 或鎖屏後繼續播放，可由系統媒體控制暫停或停止。

### 免費 MusicXML 樂譜

可前往 [AICoevolution Shelf](https://www.aicoevolution.com/shelf) 瀏覽並免費下載 MusicXML 樂譜，再由「＋ 匯入樂譜」加入 App。該網站為外部資源，樂譜內容、下載方式與授權條款以網站當下標示為準。

第一次使用會將約 32 MB 的採樣庫複製至 App 私有空間。播放時在背景工作執行緒載入音源、建立索引化事件時間軸與音訊串流，再由 Oboe 音訊回呼持續合成浮點立體聲 PCM。每個樂譜聲部使用獨立內部聲道，避免來源 MIDI channel 衝突。輸出採用裝置取樣率，優先低延遲獨占串流，不支援時依序改用共用及一般效能串流。

音量使用 10 ms 增益過渡；速度調整只改變音符排程，不改變採樣音高。音符卷軸的位置以硬體呈現的音訊 frame timestamp 對應樂譜時間，不支援 timestamp 時使用 frame counter／緩衝估算。定位會清除舊音，重建踏板與 expression 等控制狀態，並重新觸發跨越定位點的長音；不重建定位點之前的完整音色包絡。

WAV 匯出由獨立工作執行緒與獨立合成器處理，仍為原始樂譜速度、44.1 kHz／16-bit 立體聲。匯出不會暫停即時播放，也不會阻擋音譜庫的匯入工作。即時控制的實際延遲取決於手機、音訊緩衝與耳機；藍牙耳機通常較有延遲。

## 建置

建置需要 JDK 17、Android SDK 35、NDK `28.2.13676358` 與 CMake `3.22.1`；Robolectric 測試另外需要 JDK 21（Gradle Java toolchain 自動尋找）。

```powershell
.\gradlew.bat :app:assembleDebug
```

使用 R8 與資源壓縮、並以 Debug 金鑰簽署的測試發行版本：

```powershell
.\gradlew.bat :app:assemblePrerelease
```

輸出為 `app/build/outputs/apk/prerelease/app-prerelease.apk`。`prerelease` Build Type 僅供測試與 GitHub Pre-release，不是正式上線簽署版本。

Android Studio 開啟專案根目錄也可建置。設定 `ANDROID_HOME`，或在本機 `local.properties` 指定 SDK 路徑。

首次建置會下載已固定版本的 TinySoundFont 與 GeneralUser GS 音源；音源下載完成後會驗證檔案大小及 Git blob SHA-1。下載內容與音訊引擎授權文件都會打包進 APK，App 不要求網路或廣泛儲存權限。

安裝到已連線的裝置：

```powershell
adb install -r "app/build/outputs/apk/debug/app-debug.apk"
```

## 驗證

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

只執行不需要原生工具鏈的 JVM／Robolectric 測試時，可使用 `-PskipNative`；此選項不可用來產生交付 APK：

```powershell
.\gradlew.bat -PskipNative :app:testDebugUnitTest
```

- JVM 測試涵蓋精確時間量化、和弦／多聲部、tie 與 instrument 身分、反覆及導航、完整速度語意、swing、跨小節控制與裝飾奏法、各類踏板、MXL、控制事件及 XML 安全處理。
- Robolectric 測試在 Android API 29／36 模擬框架驗證匯入停留音譜庫、自動播放、切頁／背景／Activity 銷毀時持續播放、通知控制、音量步進、主題保存、系統列明暗，以及即時混音／速度／音色操作不重新準備或定位播放器；硬體音訊輸出使用測試替身。
- 原生 C++ 測試以實際 SoundFont 驗證音符精確事件邊界、同音高多聲部、CC11／CC64／CC66／CC67 狀態與 identity release、速度與音高、長音定位、實際採樣、並行 WAV 匯出及回呼中不配置記憶體，另測試 100000 筆跨執行緒控制。
- 此開發環境沒有連線的 Android 裝置或 AVD；實機的音訊輸出與觸控操作仍需裝置驗收。

目前驗證：108 項 JVM／Robolectric 測試（1 項因未設定外部 fixture 而略過、0 項失敗）；Podman 原生 C++ suite 全數通過；arm64-v8a、armeabi-v7a、x86_64 三種 Android ABI 與 debug APK 編譯通過；Android Lint 為 0 errors、11 warnings。APK 已確認不包含已移除的 OSMD JavaScript、HTML、MIDI 匯出器或相關資源。

原生測試可在具備 g++ 的 Linux 環境從根目錄執行 `sh tests/native/run.sh`，或使用 Podman／Docker：

```powershell
.\gradlew.bat :app:prepareAudioInputs
podman build --tag musicxml-native-tests --file "tests/native/Dockerfile" "tests/native"
podman run --rm --mount "type=bind,source=$($PWD.Path),target=/work,readonly" musicxml-native-tests sh tests/native/run.sh
```

音訊回呼不讀寫檔案、不呼叫 JNI、不鎖定控制 mutex；播放／暫停、混音與速度使用 lock-free 最新值 mailbox，定位使用固定大小佇列，因此重連期間不會累積過時控制。定位若與串流 flush 交錯會在 flush 後重播最新要求，避免遺失音符起音。音訊裝置斷線時會退避重連最多 30 秒並恢復位置，期間的使用者定位優先；只有確認為目前實際輸出的裝置移除時才主動暫停，未知路由仍由系統 noisy 廣播安全處理。仍需實機驗證有線／藍牙切換及不同裝置的延遲與耗電。

驗證專案根目錄的 Mozart 樂譜 fixture：

```powershell
$env:MUSICXML_IMPORT_FIXTURE = "$PWD\minuet-in-c-major-k-315a-no-1.musicxml"
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
```

## 支援邊界

- 支援 `score-partwise`，最多 15 個樂器聲部、30 分鐘的展開後樂譜。
- 原生音源預先配置最多 256 個採樣 voices，另預留節拍器聲部；一個音符可能使用多個採樣 voices。
- 匯入檔案與 MXL 解壓後總量限制為 20 MB。
- 支援常見 D.C.／D.S.／Fine／Coda、巢狀反覆與結尾組合；矛盾標記、缺少目標或重複導航跳轉會顯示提示並安全停止或降級。
- 裝飾音與奏法採通用演奏近似；fermata 以 1.5 倍發聲長度處理，breath mark／caesura 以縮短前音產生間隙，複雜的歷史演奏慣例不逐流派區分。
- 使用者開關的節拍器 click 為四分音符；MusicXML metronome 速度標記支援所有 beat-unit、多重附點與 metric modulation。
- 目前尚未提供音源下載商店、收藏／標籤或播放清單。

## 專案結構

| 路徑 | 用途 |
|---|---|
| `app/src/main/java/com/musicxml/player/MainActivity.kt` | 音譜庫、播放器、混音與匯出介面 |
| `PlaybackService.kt` | 即時引擎準備、背景播放、媒體工作階段、通知與音訊焦點 |
| `RealtimePlayer.kt` | Kotlin／JNI 即時播放器介面與音源準備 |
| `Appearance.kt` | 顯示模式、共用聲部配色及常用音量選項 |
| `Score.kt`、`ScoreTimeline.kt` | MusicXML 解析、反覆展開及索引化時間軸 |
| `Library.kt` | 音譜副本與中繼資料保存 |
| `app/src/main/cpp/RealtimeCore.*`、`RealtimeOutput.cpp`、`SoundFont.cpp` | 原生排程、Oboe 串流、音訊時鐘與採樣合成 |
| `SampleRenderer.kt`、`app/src/main/cpp/sampler.cpp`、`OfflineWave.cpp` | 獨立 WAV 匯出 |
| `PianoRoll.kt`、`PerformanceDisplay.kt` | 同步音符卷軸、演奏情境與當下發聲樂器 |
| `design/` | 指揮家圖示與尺寸說明 |

第三方來源與授權：

- [TinySoundFont](https://github.com/schellingb/TinySoundFont)：MIT。
- [Oboe 1.9.3](https://github.com/google/oboe)：Apache-2.0。
- [GeneralUser GS](https://github.com/mrbumpy409/GeneralUser-GS)：GeneralUser GS License v2.0；完整原文隨 APK 打包，可在播放器的授權頁查看。
