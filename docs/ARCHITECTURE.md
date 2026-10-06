# 系統架構與技術文件

## 分層

```text
MainActivity
    │ UI、使用者操作、音符卷軸
    ▼
PlaybackService
    │ 播放生命週期、音訊焦點、MediaSession、背景播放
    ▼
RealtimePlayer / SampleRenderer
    │ Kotlin 與 JNI 邊界、事件陣列、WAV 匯出
    ▼
RealtimeOutput / RealtimeCore
    │ Oboe 串流、無鎖控制、事件排程
    ▼
TinySoundFont + GeneralUser GS
```

## MusicXML 資料流

1. `MusicXml.parse()` 讀取 XML、MXL 或 JSON 包裝內容。
2. 解析器建立 voice、staff、instrument、note、tempo、control 與 context。
3. 反覆、結尾與導航標記展開為線性演奏路徑。
4. `Score` 保存 UI、音符卷軸與定位使用的 canonical 樂譜。
5. `ScoreTimeline` 提供 note、bar、context 與 control 的索引查詢。

解析階段同時套用 grace、trill、tremolo、glissando、swing、力度與踏板語意。

## 播放資料流

`RealtimePlayer` 將 `Score` 轉為毫秒級 JNI 陣列：

- note start／end。
- part channel。
- pitch、velocity、program、bank、percussion。
- control start／end、controller、from／to。

原生層不解析 MusicXML，只處理已準備完成的音符與控制事件，因此音訊 callback 不需要配置記憶體、呼叫 JNI 或取得鎖。

## 控制策略

- 播放／暫停、速度與混音採最新值 mailbox。
- 定位使用固定大小 transport queue。
- 音訊裝置中斷時由 worker thread 執行退避重連。
- 目前實際輸出裝置 ID 由 Oboe stream 提供，Kotlin 再映射至 `AudioDeviceInfo`。
- 未知路由不以 connected device topology 推測實際輸出。

## Android 元件

- `MainActivity`：原生 Android Views、音譜庫、播放器與設定。
- `PlaybackService`：前景 media playback service、音訊焦點、媒體通知與路由監控。
- `MediaSession`：鎖定畫面、通知與外部媒體控制。
- `AudioDeviceCallback`：裝置拓撲改變時觸發路由重試與顯示更新。

## 主要檔案

| 路徑 | 職責 |
|---|---|
| `Score.kt` | MusicXML 解析、展開與演奏語意 |
| `ScoreTimeline.kt` | 時間區間與 context 索引 |
| `PlaybackService.kt` | 播放生命週期與背景服務 |
| `RealtimePlayer.kt` | Kotlin/JNI 即時播放器 |
| `SampleRenderer.kt` | WAV 匯出 |
| `RealtimeCore.*` | 原生事件排程與控制 |
| `RealtimeOutput.cpp` | Oboe stream 與重連 |
| `MainActivity.kt` | UI 與使用者操作 |
