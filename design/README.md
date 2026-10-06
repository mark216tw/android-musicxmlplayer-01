# MusicXML 播放器圖示

圖案為微笑指揮家的上半身，雙手正在指揮，右側手持指揮棒。採用粗深藍描邊、青綠外套、珊瑚紅領結與暖黃色背景。

## 尺寸

- 完整畫布：108 × 108 dp。
- 外框裁切參考區：置中 72 × 72 dp，座標 18–90。
- 核心安全區：置中 66 × 66 dp，座標 21–87。
- 核心圖案目標：59.4 × 59.4 dp（安全區邊長的 90%，含描邊）。圖案使用 0.94 倍縮放，保留安全區邊距。
- 72 × 72 dp 是遮罩裁切參考範圍，不在圖案中繪製實體外框；實際遮罩形狀由 Android Launcher 決定。

## 檔案

- `app-icon.svg`：完整彩色預覽，可用瀏覽器開啟；向量可無損縮放。
- `../app/src/main/res/drawable/ic_launcher_foreground.xml`：透明前景 VectorDrawable。
- `../app/src/main/res/drawable/ic_launcher_background.xml`：背景 VectorDrawable。
- `../app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`：Android 8.0 以上的 adaptive icon。

Android App 的 Manifest 已設定 `android:icon="@mipmap/ic_launcher"`，最低支援 Android 8.0。指揮棒向上內收，讓核心內容保留在圓形安全範圍內。

dp 是 Android 邏輯尺寸，不是固定像素數；例如 xxxhdpi 的完整 108 dp 圖層相當於 432 × 432 px。此版本使用向量資源，不需要為每個密度另存點陣圖。
