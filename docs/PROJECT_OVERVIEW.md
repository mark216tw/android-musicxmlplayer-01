# 小小樂隊專案說明

## 專案定位

小小樂隊是 Android 離線 MusicXML 播放器，讓使用者匯入樂譜、使用內建採樣音源播放、檢視同步音符卷軸，並對各聲部進行練習控制。

目前版本支援 Android 8.0（API 26）以上，App application id 為 `com.musicxml.player`。

## 目前功能

- 匯入 `.musicxml`、`.xml`、`.mxl` 及 JSON 包裝的 MusicXML。
- 支援多聲部、voice、staff、和弦、休止符、移調、tie、backup／forward、反覆與 D.C.／D.S. 導航。
- 支援力度、速度標記、swing、ritardando／accelerando、grace note、trill、tremolo、glissando、articulation 與踏板控制。
- 以 GeneralUser GS 2.0.3、TinySoundFont 與 Oboe 進行即時採樣播放。
- 提供首頁、可查詢與排序的所有曲目、可拖曳排序的播放清單，以及各頁共用的迷你播放器。
- 支援播放佇列、上一首／下一首、隨機播放、關閉／清單／單曲三段循環，以及程序重啟後的暫停恢復。
- 支援停止、小節定位、音符卷軸定位及 50%、75%、100%、125%、150% 播放速度。
- 支援原譜、流行、抒情、自然、浪漫演奏風格；速度與風格全 App 共用，並共用即時播放與 WAV 匯出的演奏事件計畫。
- 支援每聲部啟用開關、樂器替換及節拍器。
- 支援背景播放、鎖定畫面、MediaSession 與媒體通知。
- Android 13 以上第一次主動播放時可請求通知權限，拒絕不會阻止播放。
- 顯示目前音訊輸出；無法取得實際裝置 ID 時明確標示系統推定或系統選擇。
- 支援 WAV 匯出，匯出使用原始樂譜速度及目前選擇的演奏風格。
- 使用者可由 [AICoevolution Shelf](https://www.aicoevolution.com/shelf) 取得免費 MusicXML 樂譜；外部內容與授權以來源網站標示為準。

## 安全與支援限制

- 最多 15 個樂器聲部，另保留 1 個節拍器聲部。
- 展開後樂譜最長 30 分鐘。
- 匯入檔案與 MXL 解壓後總量限制為 20 MB。
- 解析器限制 tick、展開事件、tempo ramp、MXL entry 數量與檔名長度。
- 原生採樣器預配置最多 256 個 voices。
- 硬體音訊路由、藍牙延遲、鎖屏長時間播放與耗電仍應以實機驗證。

## 文件索引

- [使用指南](USER_GUIDE.md)
- [系統架構與技術文件](ARCHITECTURE.md)
- [系統設計文件](SYSTEM_DESIGN.md)
- [開發與建置指南](DEVELOPMENT.md)
