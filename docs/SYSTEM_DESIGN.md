# 系統設計文件

## 設計目標

- 離線播放 MusicXML，不依賴系統 MIDI 音源。
- 保持原始樂譜與實際播放事件可追蹤。
- 播放中切頁、背景與鎖屏不丟失狀態。
- 音訊 callback 維持低延遲與無配置特性。
- 對異常或惡意大型輸入提供明確資源上限。

## 狀態模型

播放狀態由 `PlaybackService` 持有，主要狀態包括：

- `PREPARING`：建立採樣器與音訊串流。
- `STARTING`：等待原生輸出開始。
- `PLAYING`：音訊正常輸出。
- `PAUSED`：保留目前位置但停止輸出。
- `RECONNECTING`：音訊裝置中斷並退避重連。
- `ROUTE_UNAVAILABLE`：重連期限耗盡或輸出不可用。
- `COMPLETED`：播放完成且等待使用者重播或定位。
- `ERROR`：不可恢復錯誤。

## 位置與定位

UI 以 `Score.tickAt()` 與 `Score.millisAt()` 進行 canonical score time 查詢。

定位流程：

1. 使用者點選卷軸、進度列或小節。
2. UI 將位置轉為 score tick。
3. Service 將 tick 轉為毫秒。
4. Native player 清除目前 voices、重建 controls，並恢復跨越定位點的長音。

定位不會重建整個 MusicXML score。

## 音訊路由設計

路由辨識優先順序：

1. Oboe／AAudio stream 的實際 `deviceId`。
2. 對照 `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` 的裝置名稱與類型。
3. Android 13 以上在無實際 ID 時使用 media attributes 的系統推定路由。
4. 仍無法確認時顯示「系統選擇」。

移除裝置只有在 ID 等於目前實際輸出裝置時才主動暫停；`ACTION_AUDIO_BECOMING_NOISY` 仍是安全暫停機制。

## 通知權限設計

- Manifest 宣告 `POST_NOTIFICATIONS`。
- Android 13 以上在第一次使用者主動播放後請求。
- 權限對話框不阻塞播放，也不影響拒絕後的音訊。
- MediaSession／通知 action 不觸發 Activity 權限請求。
- 權限結果由 Activity 保存，避免重複請求。

## 資料保存

音譜檔案存於 App 私有目錄，設定以 SharedPreferences JSON 保存。每首曲目可保存：

- 名稱與匯入時間。
- 最近播放時間。
- 播放速度。
- 聲部音量。
- 聲部 program 與 override。
- 原始曲長。

聲部音量只保存 0、25、50、75、100、125；缺少欄位或舊有非選項值使用安全預設值 100。其他缺少欄位的舊資料也使用各自的安全預設值。

## 安全邊界

- XML parser 停用外部 entity 與 DTD 風險。
- 匯入與 MXL 解壓限制總大小。
- tick 與 duration 使用受檢查算術。
- 展開事件、tempo ramp、ZIP entry 與檔名長度均有上限。
- score part、播放時長與 native voices 均有上限。
