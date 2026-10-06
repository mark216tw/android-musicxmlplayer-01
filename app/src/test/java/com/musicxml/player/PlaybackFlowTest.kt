package com.musicxml.player

import android.app.Application
import android.app.AlertDialog
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowPopupMenu
import org.robolectric.shadows.ShadowToast
import android.os.SystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackFlowTest {
    private lateinit var application: Application
    private lateinit var serviceController: ServiceController<PlaybackService>
    private lateinit var playback: PlaybackService
    private var activityController: ActivityController<MainActivity>? = null
    private val renders = AtomicInteger()

    @Before fun setup() {
        application = RuntimeEnvironment.getApplication()
        serviceController = Robolectric.buildService(PlaybackService::class.java).create()
        playback = serviceController.get()
        playback.preparePlayer = { score ->
            renders.incrementAndGet(); FakeRealtimePlayer(score.millisAt(score.endTick))
        }
        shadowOf(application.getSystemService(Context.AUDIO_SERVICE) as AudioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        shadowOf(application).apply {
            setComponentNameAndServiceForBindService(ComponentName(application, PlaybackService::class.java), playback.onBind(null))
            setBindServiceCallsOnServiceConnectedDirectly(true)
            setUnbindServiceCallsOnServiceDisconnected(false)
        }
    }
    @After fun cleanup() {
        activityController?.pause()?.stop()?.destroy()
        serviceController.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun activity(): MainActivity {
        if (activityController == null) activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        return activityController!!.get()
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun button(label: String): Button = views(activity().window.decorView).filterIsInstance<Button>().first { it.text.toString() == label }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle() }
        assertTrue("Timed out waiting for service/UI state; error=${playback.errorMessage}", condition())
    }
    private fun tempWav() = File.createTempFile("playback-export-", ".wav", application.cacheDir).apply { delete() }
    private fun requestWavExport() {
        views(activity().window.decorView).single { it.tag == "moreButton" }.performClick()
        val popup = ShadowPopupMenu.getLatestPopupMenu()
        val export = popup.menu.getItem(0)
        assertEquals("匯出 WAV 音訊", export.title.toString())
        assertTrue(popup.menu.performIdentifierAction(export.itemId, 0))
    }
    private fun deliverExportResult(destination: File) {
        requestWavExport()
        val request = shadowOf(activity()).nextStartedActivityForResult
        assertEquals(11, request.requestCode)
        shadowOf(activity()).receiveResult(request.intent, Activity.RESULT_OK,
            Intent().setData(Uri.fromFile(destination)))
    }
    private fun importAndPlay() {
        views(activity().window.decorView).single { it.tag == "tracksHomeCard" }.performClick()
        button("示範曲").performClick()
        await { Library(application).entries.size == 1 }
        button("播放").performClick()
        await { playback.player != null && !playback.rendering }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Autoplay should start once prepared; error=${playback.errorMessage}", playback.isPlaying)
    }
    @Test fun redesignedHomeProvidesPlaylistAndTrackEntrances() {
        val all = views(activity().window.decorView)
        val playlists = all.single { it.tag == "playlistHomeCard" }
        val tracks = all.single { it.tag == "tracksHomeCard" }
        assertTrue(playlists.isClickable)
        assertTrue(tracks.isClickable)
        assertTrue(all.none { it is Button && it.text.toString() == "瀏覽播放清單" })
        assertTrue(all.none { it is Button && it.text.toString() == "瀏覽所有曲目" })
        assertTrue(views(activity().window.decorView).none { it is Button && it.text.toString() == "＋ 匯入樂譜" })
        assertTrue(views(activity().window.decorView).none { it is Button && it.text.toString() == "示範曲" })

        playlists.performClick()
        assertNotNull(button("＋ 新增播放清單"))
        activity().onBackPressed()
        views(activity().window.decorView).single { it.tag == "tracksHomeCard" }.performClick()
        assertNotNull(button("＋ 匯入"))
    }
    @Test fun settingsThemeControlsAreLinkedAndSystemBackReturnsHome() {
        views(activity().window.decorView).filterIsInstance<ImageButton>().first { it.contentDescription == "設定" }.performClick()
        assertEquals(6, views(activity().window.decorView).count { it.tag?.toString()?.startsWith("themePreset:") == true })
        val slider = views(activity().window.decorView).single { it.tag == "accentHueSlider" } as SeekBar
        assertEquals(359, slider.max)
        slider.progress = Appearance.themePresets[2].hue
        assertEquals(Appearance.themePresets[2].hue, Appearance.hue(application))
        activity().onBackPressed()
        assertNotNull(views(activity().window.decorView).singleOrNull { it.tag == "tracksHomeCard" })
    }
    @Test fun appInfoMovesFromAllTracksToBottomOfSettings() {
        views(activity().window.decorView).single { it.tag == "tracksHomeCard" }.performClick()
        assertNull(views(activity().window.decorView).singleOrNull { it.tag == "appInfoFooter" })

        views(activity().window.decorView).filterIsInstance<ImageButton>().first { it.contentDescription == "設定" }.performClick()
        val all = views(activity().window.decorView)
        val footer = all.single { it.tag == "appInfoFooter" }
        assertNotNull(all.singleOrNull { it.tag == "audioRoute" })
        assertNotNull(all.singleOrNull { it.tag == "appVersion" })
        assertNotNull(all.singleOrNull { it.tag == "appBuild" })
        val content = views(activity().window.decorView).single { it.tag == "contentScroll" } as ScrollView
        assertEquals(footer.parent, content.getChildAt(0))
    }
    @Test fun miniPlayerUsesIconForTransport() {
        importAndPlay()
        views(activity().window.decorView).filterIsInstance<ImageButton>().first { it.contentDescription == "返回" }.performClick()
        val mini = views(activity().window.decorView).single { it.tag == "miniPlayPauseButton" } as ImageButton
        assertEquals("暫停", mini.contentDescription)
        mini.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("播放", mini.contentDescription)
    }
    @Test fun redesignedPlayerReturnsToLibraryWithPersistentMiniPlayer() {
        importAndPlay()
        val tags = views(activity().window.decorView).mapNotNull { it.tag?.toString() }
        assertTrue("shuffleButton" in tags)
        assertTrue("previousButton" in tags)
        assertTrue("nextButton" in tags)
        assertTrue("repeatButton" in tags)
        views(activity().window.decorView).filterIsInstance<ImageButton>().first { it.contentDescription == "返回" }.performClick()
        assertNotNull(views(activity().window.decorView).singleOrNull { it.tag == "miniPlayer" })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertNotNull(views(activity().window.decorView).singleOrNull { it.tag == "miniPlayer" })
    }
    @Test fun playerUsesCircularTransportAndContextualActions() {
        importAndPlay()
        val all = views(activity().window.decorView)
        val play = all.single { it.tag == "playPauseButton" } as ImageButton
        assertEquals(GradientDrawable.OVAL, (play.background as GradientDrawable).shape)
        assertEquals(play.layoutParams.width, play.layoutParams.height)
        assertEquals(52, (play.layoutParams.width / application.resources.displayMetrics.density).toInt())
        assertTrue(all.none { it is Button && it.text.toString() == "停止" })
        assertTrue(all.none { it is Button && it.text.toString() == "匯出 WAV 音訊" })

        val measure = all.single { it.tag == "measureButton" } as ImageButton
        assertEquals("跳至小節", measure.contentDescription)
        assertTrue(views(measure.parent as View).filterIsInstance<TextView>().any { it.text == "音符卷軸" })
        assertNotNull(all.singleOrNull { it.tag == "moreButton" })

        val instrument = all.filterIsInstance<Button>().first { it.text.toString() == "平台鋼琴" }
        assertEquals(16f, instrument.textSize / application.resources.displayMetrics.scaledDensity, 0.1f)
    }
    @Test fun importingStaysInLibraryAndPlayStartsWithoutSecondTap() {
        views(activity().window.decorView).single { it.tag == "tracksHomeCard" }.performClick()
        button("示範曲").performClick()
        await { Library(application).entries.size == 1 }
        assertNull(playback.score)
        assertTrue(views(activity().window.decorView).filterIsInstance<ImageButton>().any { it.contentDescription == "設定" })
        button("播放").performClick()
        await { playback.player != null && !playback.rendering }
        assertTrue(playback.isPlaying)
        assertEquals(1, renders.get())
        assertTrue(views(activity().window.decorView).none { it is android.webkit.WebView })
        assertNotNull(button("音源引擎授權"))
        val roll = views(activity().window.decorView).filterIsInstance<PianoRoll>().single()
        val performance = views(activity().window.decorView).filterIsInstance<TextView>().single { it.tag == "performanceContext" }
        val instruments = views(activity().window.decorView).filterIsInstance<TextView>().single { it.tag == "activeInstruments" }
        assertSame(roll.parent, instruments.parent)
        val effectsRow = performance.parent as ViewGroup
        assertEquals("measureEffectsRow", effectsRow.tag)
        assertSame(roll.parent, effectsRow.parent)
        assertTrue(effectsRow.indexOfChild(performance) > effectsRow.indexOfChild(effectsRow.findViewWithTag("measureStatus")))
        val playerColumn = roll.parent as ViewGroup
        assertTrue(playerColumn.indexOfChild(effectsRow) < playerColumn.indexOfChild(instruments))
        assertTrue(playerColumn.indexOfChild(instruments) < playerColumn.indexOfChild(roll))
        val score = playback.score!!
        assertEquals(5, score.parts.size)
        val activeLabels = roll.activeParts.sorted().map { roll.instrumentLabels[it] }
        assertEquals(activeLabels.joinToString("　"), instruments.text.toString())
        assertTrue(roll.activeParts.all { it in score.parts.indices })
        val reservedHeight = instruments.minHeight
        playback.pause()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals("", instruments.text.toString())
        assertEquals(reservedHeight, instruments.minHeight)
        val allEnabled = score.parts.map { true }
        roll.updatePlayback(0, allEnabled, true, playback.programs, playback.programOverrides)
        assertEquals(score.parts.map { InstrumentNames.name(it.program, it.percussion) }, roll.instrumentLabels)
        assertEquals(listOf("平台鋼琴", "長笛"), roll.instrumentLabels.take(2))
        assertEquals(score.timeline.activeNotes(0).map { it.part }.toSet(), roll.activeParts)
        roll.updatePlayback(0, allEnabled, false, playback.programs, playback.programOverrides)
        assertTrue(roll.activeParts.isEmpty())
        val lamps = views(activity().window.decorView).filterIsInstance<TextView>().filter {
            it.text.toString().startsWith("○ ") || it.text.toString().startsWith("● ")
        }
        assertEquals(score.parts.indices.map { PartColors.color(it, false) }, lamps.map { it.currentTextColor })
    }
    @Ignore("Superseded by the home, track library, and persistent mini-player layout")
    @Test fun libraryFooterShowsAudioRouteVersionAndUtcBuildAtContentBottom() {
        var all = views(activity().window.decorView)
        var footer = all.single { it.tag == "appInfoFooter" } as ViewGroup
        var list = all.single { it.tag == "libraryList" }
        val root = footer.parent as ViewGroup
        assertSame(footer, root.getChildAt(root.childCount - 1))
        assertTrue(root.indexOfChild(footer) > root.indexOfChild(list))
        assertTrue((all.single { it.tag == "audioRoute" } as TextView).text.startsWith("音訊輸出："))
        assertEquals("版本 ${BuildConfig.APP_VERSION_NAME}", (all.single { it.tag == "appVersion" } as TextView).text.toString())
        assertTrue((all.single { it.tag == "appBuild" } as TextView).text.toString()
            .matches(Regex("Build \\d{8}\\.\\d{6}\\.${BuildConfig.APP_VERSION_CODE}")))

        importAndPlay()
        assertFalse(views(activity().window.decorView).any { it.tag == "audioRoute" })
        button("‹ 音譜庫").performClick()
        all = views(activity().window.decorView)
        footer = all.single { it.tag == "appInfoFooter" } as ViewGroup
        list = all.single { it.tag == "libraryList" }
        assertTrue((footer.parent as ViewGroup).indexOfChild(footer) > (footer.parent as ViewGroup).indexOfChild(list))
        assertEquals(1, all.count { it.tag == "audioRoute" })
    }
    @Test fun pianoRollHeightDoesNotDependOnInstrumentNamesAndSeeksOnlyOnGridTaps() {
        val parts = (0 until 8).map { Part("P$it", "聲部 ${it + 1}", it * 8, false) }
        val notes = parts.indices.map { Note(0, PPQ, 60 + it, 88, it, program = parts[it].program) }
        val score = Score("測試", "", parts, notes, listOf(Tempo(0, 120.0)), listOf(Bar("1", 0, PPQ)), PPQ, emptyList())
        var sought = -1
        val roll = PianoRoll(application, score, false) { sought = it }
        roll.updatePlayback(0, parts.map { true }, true, parts.map { it.program }, parts.map { false })
        val width = (180 * application.resources.displayMetrics.density).toInt()
        roll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals((250 * application.resources.displayMetrics.density).toInt(), roll.measuredHeight)
        roll.layout(0, 0, roll.measuredWidth, roll.measuredHeight)
        roll.draw(Canvas(Bitmap.createBitmap(roll.measuredWidth, roll.measuredHeight, Bitmap.Config.ARGB_8888)))
        fun touch(action: Int, x: Float, y: Float) = roll.onTouchEvent(MotionEvent.obtain(0, 0, action, x, y, 0))
        touch(MotionEvent.ACTION_DOWN, width / 2f, 5f); touch(MotionEvent.ACTION_UP, width / 2f, 5f)
        assertEquals(-1, sought)
        touch(MotionEvent.ACTION_DOWN, width / 2f, roll.height - 10f); touch(MotionEvent.ACTION_UP, width / 2f, roll.height - 10f)
        assertTrue(sought >= 0)
        sought = -1
        touch(MotionEvent.ACTION_DOWN, width / 2f, roll.height - 10f)
        touch(MotionEvent.ACTION_MOVE, width / 2f + 80, roll.height - 10f)
        touch(MotionEvent.ACTION_UP, width / 2f + 80, roll.height - 10f)
        assertEquals(-1, sought)
    }
    @Test fun allRestPianoRollDrawsAndHasAccurateAccessibilityText() {
        val score = Score("休止", "", listOf(Part("P", "鋼琴", 0, false)), emptyList(),
            listOf(Tempo(0, 90.0)), listOf(Bar("1", 0, PPQ * 4)), PPQ * 4, emptyList())
        val roll = PianoRoll(application, score, false) {}
        roll.updatePlayback(0, listOf(true), false, listOf(0), listOf(false))
        val width = 400
        roll.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        roll.layout(0, 0, width, roll.measuredHeight)
        roll.draw(Canvas(Bitmap.createBitmap(width, roll.measuredHeight, Bitmap.Config.ARGB_8888)))
        assertEquals("音符卷軸，全曲休止", roll.contentDescription)
        assertTrue(roll.activeParts.isEmpty())
    }
    @Ignore("Superseded by the five-control transport and persistent mini-player")
    @Test fun playerTransportIsStickyTaggedAndHiddenOnOtherPages() {
        val libraryHost = views(activity().window.decorView).single { it.tag == "stickyHost" }
        assertEquals(View.GONE, libraryHost.visibility)
        importAndPlay()
        val all = views(activity().window.decorView)
        val host = all.single { it.tag == "stickyHost" } as ViewGroup
        val scroll = all.single { it.tag == "contentScroll" } as ScrollView
        val position = all.single { it.tag == "positionSeekBar" } as SeekBar
        val positionRow = all.single { it.tag == "positionRow" } as ViewGroup
        val elapsed = all.single { it.tag == "elapsedTime" } as TextView
        val total = all.single { it.tag == "totalTime" } as TextView
        val transport = all.single { it.tag == "transportRow" }
        val measureStatus = all.single { it.tag == "measureStatus" } as TextView
        val performance = all.single { it.tag == "performanceContext" }
        assertEquals(View.VISIBLE, host.visibility)
        assertSame(host, positionRow.parent)
        assertSame(positionRow, position.parent)
        assertSame(positionRow, elapsed.parent)
        assertSame(positionRow, total.parent)
        assertEquals("0:00", elapsed.text.toString())
        assertTrue(total.text.toString().matches(Regex("\\d+:\\d{2}")))
        assertEquals(Gravity.CENTER, elapsed.gravity)
        assertEquals(Gravity.CENTER, total.gravity)
        assertFalse(elapsed.includeFontPadding)
        assertFalse(total.includeFontPadding)
        assertEquals(position.layoutParams.height, elapsed.layoutParams.height)
        assertEquals(position.layoutParams.height, total.layoutParams.height)
        assertFalse(measureStatus.text.toString().contains("/"))
        assertSame(measureStatus.parent, performance.parent)
        assertSame(host, transport.parent)
        assertNotSame(scroll, position.parent)
        listOf("playPauseButton", "stopButton", "measureButton", "repeatOneButton").forEach { tag ->
            val control = views(host).single { it.tag == tag }
            assertTrue(control is ImageButton)
            assertEquals(48, (control.layoutParams.height / application.resources.displayMetrics.density).toInt())
            assertTrue(control.contentDescription.isNotBlank())
        }
        button("‹ 音譜庫").performClick()
        assertEquals(View.GONE, views(activity().window.decorView).single { it.tag == "stickyHost" }.visibility)
    }
    @Ignore("Superseded by simplified part switches")
    @Test fun mixButtonsUpdatePlayerInPlaceWithoutLosingScrollOrViewIdentity() {
        importAndPlay()
        val before = views(activity().window.decorView)
        val scroll = before.single { it.tag == "contentScroll" } as ScrollView
        val roll = before.filterIsInstance<PianoRoll>().single()
        val position = before.single { it.tag == "positionSeekBar" }
        scroll.scrollTo(0, 120)
        val scrollY = scroll.scrollY
        before.filterIsInstance<Button>().first { it.text == "獨奏" }.performClick()
        var after = views(activity().window.decorView)
        assertSame(scroll, after.single { it.tag == "contentScroll" })
        assertSame(roll, after.filterIsInstance<PianoRoll>().single())
        assertSame(position, after.single { it.tag == "positionSeekBar" })
        assertEquals(scrollY, scroll.scrollY)
        assertTrue(after.filterIsInstance<Button>().any { it.text == "取消獨奏" })
        after.filterIsInstance<CheckBox>().first { it.text == "靜音" }.performClick()
        after = views(activity().window.decorView)
        assertSame(roll, after.filterIsInstance<PianoRoll>().single())
        assertTrue(after.filterIsInstance<CheckBox>().first { it.text == "靜音" }.contentDescription.toString().contains("鋼琴"))
    }
    @Ignore("All-mute was intentionally removed from the simplified part controls")
    @Test fun allMuteButtonTogglesEveryPartWithOneControl() {
        importAndPlay()
        val engine = playback.player as FakeRealtimePlayer
        val toggle = views(activity().window.decorView).single { it.tag == "allMuteButton" } as Button

        toggle.performClick()
        assertTrue(playback.muted.all { it })
        assertTrue(views(activity().window.decorView).filterIsInstance<CheckBox>().filter { it.text == "靜音" }.all { it.isChecked })
        assertEquals("取消全部靜音", toggle.text.toString())
        assertTrue(engine.enabled.take(playback.muted.size).none { it })

        toggle.performClick()
        assertTrue(playback.muted.none { it })
        assertEquals("全部靜音", toggle.text.toString())
        assertTrue(engine.enabled.take(playback.muted.size).all { it })
    }
    @Test fun exportUiLaunchesWavDocumentDirectlyAndContainsNoMidiChoice() {
        importAndPlay()
        assertTrue(views(activity().window.decorView).none { it is TextView && it.text.toString().contains("MIDI") })
        requestWavExport()
        val request = shadowOf(activity()).nextStartedActivityForResult
        assertEquals(11, request.requestCode)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.action)
        assertEquals("audio/wav", request.intent.type)
        assertTrue(request.intent.getStringExtra(Intent.EXTRA_TITLE)!!.endsWith(".wav"))
    }
    @Test fun speedAndPerformanceStyleShareARowAndStyleChoicesExposeDescriptions() {
        importAndPlay()
        val speed = views(activity().window.decorView).single { it.tag == "speedButton" }
        val style = views(activity().window.decorView).single { it.tag == "performanceStyleButton" } as Button
        assertSame(speed.parent, style.parent)

        style.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        val list = dialog.listView
        assertEquals(5, list.adapter.count)
        PerformanceStyle.options.forEachIndexed { index, option ->
            assertEquals(1, option.count { it == '\n' })
            assertEquals("${PerformanceStyle.entries[index].label}\n${PerformanceStyle.entries[index].description}", option)
            val row = list.adapter.getView(index, null, list)
            val label = views(row).filterIsInstance<TextView>().first()
            assertEquals(option, label.text.toString())
            assertEquals(option, label.createAccessibilityNodeInfo().text.toString())
        }
    }
    @Test fun exportRunsOffMainThreadAndUsesOneImmutableServiceSnapshot() {
        importAndPlay()
        playback.setPerformanceStyle(PerformanceStyle.ROMANTIC)
        await { !playback.rendering && playback.player != null }
        playback.metronome = true
        playback.muted[0] = true
        playback.solo = 1
        playback.volumes[0] = 25
        playback.volumes[1] = 125
        playback.setProgram(0, 40)
        val expectedScore = playback.score!!
        assertEquals(5, expectedScore.parts.size)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val captured = AtomicReference<WavExportSnapshot>()
        val ranOffMain = AtomicBoolean(false)
        activity().renderExport = { _, snapshot, output ->
            ranOffMain.set(Looper.myLooper() != Looper.getMainLooper())
            captured.set(snapshot)
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            output.outputStream().use { it.write(byteArrayOf(1, 2, 3)) }
        }
        val destination = tempWav()

        deliverExportResult(destination)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        playback.metronome = false
        playback.muted.fill(false)
        playback.solo = -1
        playback.volumes.fill(10)
        playback.programs.fill(12)
        playback.programOverrides.fill(false)
        playback.setPerformanceStyle(PerformanceStyle.POP)
        release.countDown()

        await { destination.length() == 3L }
        val snapshot = captured.get()
        assertTrue(ranOffMain.get())
        assertSame(expectedScore, snapshot.score)
        assertTrue(snapshot.includeMetronome)
        assertEquals(PerformanceStyle.ROMANTIC, snapshot.style)
        assertEquals(expectedScore.parts.indices.map { it == 1 } + true, snapshot.enabled)
        assertEquals(expectedScore.parts.indices.map { if (it == 0) 25 else if (it == 1) 125 else 100 } + 80,
            snapshot.volumes)
        assertEquals(expectedScore.parts.mapIndexed { index, part -> if (index == 0) 40 else part.program } + 0,
            snapshot.programs)
        assertEquals(expectedScore.parts.indices.map { it == 0 } + false, snapshot.overrides)
    }
    @Test fun exportFailureIsShownAndDoesNotBlockTheNextExport() {
        importAndPlay()
        val calls = AtomicInteger()
        activity().renderExport = { _, _, output ->
            if (calls.incrementAndGet() == 1) error("renderer failed")
            output.outputStream().use { it.write(byteArrayOf(7, 8)) }
        }

        deliverExportResult(tempWav())
        await {
            val latest = ShadowDialog.getLatestDialog() as? AlertDialog
            latest?.findViewById<TextView>(android.R.id.message)?.text?.toString() == "renderer failed"
        }
        val failure = ShadowDialog.getLatestDialog() as AlertDialog
        val titleId = application.resources.getIdentifier("alertTitle", "id", "android")
        assertEquals("無法完成", failure.findViewById<TextView>(titleId).text.toString())
        failure.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        val destination = tempWav()
        deliverExportResult(destination)
        await { calls.get() == 2 && destination.length() == 2L }
    }
    @Test fun destroyingActivityInterruptsExportAndSuppressesItsSuccessCallback() {
        importAndPlay()
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        activity().renderExport = { _, _, _ ->
            started.countDown()
            try { CountDownLatch(1).await() }
            catch (e: InterruptedException) { interrupted.countDown(); throw e }
        }

        deliverExportResult(tempWav())
        assertTrue(started.await(5, TimeUnit.SECONDS))
        activityController!!.pause().stop().destroy()
        activityController = null

        assertTrue(interrupted.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertNotEquals("已匯出", ShadowToast.getTextOfLatestToast())
    }
    @Test fun importedDurationPersistsAndLibraryCardUsesSeparateMetadataRows() {
        val bytes = application.assets.open("demo.musicxml").use { it.readBytes() }
        val parsed = MusicXml.parse(bytes)
        val expected = parsed.millisAt(parsed.endTick)
        val added = Library(application).add(bytes, parsed)
        assertEquals(expected, added.durationMillis)
        assertEquals(expected, Library(application).entries.single().durationMillis)

        assertEquals("小小樂隊", activity().getString(R.string.app_name))
        val composer = views(activity().window.decorView).filterIsInstance<TextView>()
            .single { it.text.toString() == parsed.composer }
        val card = composer.parent as ViewGroup
        assertEquals(added.name, (card.getChildAt(0) as TextView).text.toString())
        assertSame(composer, card.getChildAt(1))
        val metadata = card.getChildAt(2) as ViewGroup
        assertEquals(added.instruments, (metadata.getChildAt(0) as TextView).text.toString())
        assertEquals("%d:%02d".format(expected / 60000, expected / 1000 % 60),
            (metadata.getChildAt(1) as TextView).text.toString())
        assertEquals(1f, (metadata.getChildAt(0).layoutParams as android.widget.LinearLayout.LayoutParams).weight, 0f)
    }
    @Ignore("Old library card hierarchy was replaced by the all-tracks screen")
    @Test fun legacyDurationShowsPlaceholderAndBackfillsAvailablePrivateXml() {
        val bytes = application.assets.open("demo.musicxml").use { it.readBytes() }
        val parsed = MusicXml.parse(bytes)
        val expected = parsed.millisAt(parsed.endTick)
        val library = Library(application)
        val available = library.add(bytes, parsed)
        val unavailable = library.add(bytes, parsed)
        assertTrue(library.file(unavailable).delete())
        val prefs = application.getSharedPreferences("library", Context.MODE_PRIVATE)
        val legacy = JSONArray(prefs.getString("entries", "[]"))
        for (i in 0 until legacy.length()) legacy.getJSONObject(i).remove("durationMillis")
        assertTrue(prefs.edit().putString("entries", legacy.toString()).commit())

        assertTrue(views(activity().window.decorView).filterIsInstance<TextView>().any { it.text == "--:--" })
        await { Library(application).entries.first { it.id == available.id }.durationMillis == expected }
        val duration = "%d:%02d".format(expected / 60000, expected / 1000 % 60)
        assertTrue(views(activity().window.decorView).filterIsInstance<TextView>().any { it.text == duration })
        assertTrue(views(activity().window.decorView).filterIsInstance<TextView>().any { it.text == "--:--" })
    }
    @Test fun durationBackfillMergesWithLatestPlaybackSettings() {
        val bytes = application.assets.open("demo.musicxml").use { it.readBytes() }
        val parsed = MusicXml.parse(bytes)
        val original = Library(application)
        val entry = original.add(bytes, parsed)
        entry.durationMillis = null
        original.save()
        val stale = Library(application)
        val latest = Library(application)
        latest.entries.single().speed = 1.25f
        latest.save()

        assertTrue(stale.mergeDurations(mapOf(entry.id to parsed.millisAt(parsed.endTick))))
        val reloaded = Library(application).entries.single()
        assertEquals(1.25f, reloaded.speed, 0f)
        assertEquals(parsed.millisAt(parsed.endTick), reloaded.durationMillis)
    }
    @Ignore("Old single-library navigation was replaced by home and all-tracks screens")
    @Test fun importingAnotherScoreWhileListeningKeepsTheCurrentPlayer() {
        importAndPlay(); val player = playback.player; val id = playback.entry!!.id
        button("‹ 音譜庫").performClick()
        button("示範曲").performClick()
        await { Library(application).entries.size == 2 }
        assertTrue(playback.isPlaying); assertSame(player, playback.player)
        assertEquals(id, playback.entry!!.id); assertEquals(1, renders.get())
        assertTrue(views(activity().window.decorView).filterIsInstance<ImageButton>().any { it.contentDescription == "設定" })
    }
    @Ignore("Old library return button was replaced by navigation and the mini-player")
    @Test fun returningToLibraryBackgroundAndActivityDestructionKeepSessionPlaying() {
        importAndPlay()
        val player = playback.player!!
        player.seekMillis(5000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        button("‹ 音譜庫").performClick()
        assertTrue(playback.isPlaying)
        assertTrue(playback.currentPosition in 5000..6000)
        button("播放").performClick()
        assertSame(player, playback.player)
        assertTrue(playback.currentPosition >= 5000)
        button("‹ 音譜庫").performClick()
        button("返回播放頁").performClick()
        assertSame(player, playback.player)
        assertEquals(1, renders.get())
        activityController!!.pause().stop()
        assertTrue(playback.isPlaying)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(playback.currentPosition > 5000)
        activityController!!.destroy(); activityController = null
        assertTrue(playback.isPlaying)
        assertSame(player, playback.player)
        assertTrue(shadowOf(playback).isLastForegroundNotificationAttached)
    }
    @Ignore("Old player-entry timing assertion is superseded by queue loading tests")
    @Test fun preparingAudioThenBackgroundingStillAutoplaysWhenReady() {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val originalFactory = playback.preparePlayer
        playback.preparePlayer = { s ->
            started.countDown()
            check(gate.await(5, TimeUnit.SECONDS))
            originalFactory(s)
        }
        button("示範曲").performClick()
        await { Library(application).entries.size == 1 }
        button("播放").performClick()
        await { playback.rendering }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        activityController!!.pause().stop()
        gate.countDown()
        await { playback.isPlaying }
        assertTrue(shadowOf(playback).isLastForegroundNotificationAttached)
        activityController!!.destroy(); activityController = null
    }
    @Test fun transientFocusLossDuringPreparationResumesAfterFocusReturns() {
        importAndPlay()
        val audio = shadowOf(application.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        val focusListener = audio.lastAudioFocusRequest.listener
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val originalFactory = playback.preparePlayer
        playback.preparePlayer = { s ->
            started.countDown()
            check(gate.await(5, TimeUnit.SECONDS))
            originalFactory(s)
        }
        val s = playback.score!!
        playback.open(LibraryEntry("focus-test", "焦點測試", s.composer, s.parts.joinToString("、") { it.name }, 0), s)
        assertTrue(started.await(5, TimeUnit.SECONDS))

        focusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(playback.wantsPlayback)
        gate.countDown()
        await { !playback.rendering && playback.player != null }
        assertFalse(playback.isPlaying)

        focusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        await { playback.isPlaying }
        assertTrue(playback.wantsPlayback)
    }
    @Ignore("Old settings navigation hierarchy was replaced")
    @Test fun settingsUseGearPersistModeAndDoNotRestartMusic() {
        importAndPlay(); val player = playback.player
        button("‹ 音譜庫").performClick()
        assertEquals(DisplayMode.SYSTEM, Appearance.mode(application))
        views(activity().window.decorView).filterIsInstance<ImageButton>().first { it.contentDescription == "設定" }.performClick()
        views(activity().window.decorView).filterIsInstance<RadioButton>().first { it.text == "深色" }.performClick()
        assertEquals(DisplayMode.DARK, Appearance.mode(application))
        assertTrue(Appearance.dark(application))
        assertTrue(playback.isPlaying); assertSame(player, playback.player); assertEquals(1, renders.get())
        val lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        assertEquals(0, activity().window.decorView.systemUiVisibility and lightFlags)
        views(activity().window.decorView).filterIsInstance<RadioButton>().first { it.text == "淺色" }.performClick()
        assertEquals(lightFlags, activity().window.decorView.systemUiVisibility and lightFlags)
        val arrow = views(activity().window.decorView).filterIsInstance<ImageButton>().first { it.contentDescription == "返回" }
        val header = arrow.parent as ViewGroup
        assertSame(arrow, header.getChildAt(0))
        assertEquals("設定", (header.getChildAt(1) as TextView).text.toString())
        assertFalse(views(activity().window.decorView).filterIsInstance<Button>().any { it.text == "‹ 返回" })
        arrow.performClick()
        assertTrue(views(activity().window.decorView).filterIsInstance<ImageButton>().any { it.contentDescription == "設定" })
        assertTrue(playback.isPlaying); assertSame(player, playback.player)
    }
    @Ignore("Part volume controls were intentionally removed")
    @Test fun volumeButtonsUsePartAccessibilityDarkColorsAndRemainOperableWhenMuted() {
        Appearance.setMode(application, DisplayMode.DARK)
        importAndPlay()
        assertEquals(5, playback.score!!.parts.size)
        assertEquals(5, views(activity().window.decorView).count { it.tag?.toString()?.startsWith("volumeButton:") == true })
        val first = views(activity().window.decorView).single { it.tag == "volumeButton:0" } as Button
        val second = views(activity().window.decorView).single { it.tag == "volumeButton:1" } as Button
        assertEquals(PartColors.color(0, true), first.currentTextColor)
        assertEquals(PartColors.color(1, true), second.currentTextColor)
        assertEquals("鋼琴：音量 100，開啟音量選單", first.contentDescription.toString())
        assertEquals("長笛：音量 100，開啟音量選單", second.contentDescription.toString())

        views(activity().window.decorView).filterIsInstance<CheckBox>().first { it.text == "靜音" }.performClick()
        assertTrue(playback.muted[0])
        assertTrue(first.isEnabled)
        assertEquals(UiColors(true).muted, first.currentTextColor)
        assertEquals(1f, first.alpha, 0f)
        first.performClick()
        assertEquals("volumeDialog:0", (ShadowDialog.getLatestDialog() as AlertDialog).window?.decorView?.tag)
    }
    @Ignore("Part volume controls were intentionally removed")
    @Test fun volumeMenuShowsEveryOptionAndSelectingUpdatesLiveMixAndOnlyCurrentSong() {
        importAndPlay()
        val currentId = playback.entry!!.id
        val bytes = application.assets.open("demo.musicxml").use { it.readBytes() }
        val other = Library(application).add(bytes, MusicXml.parse(bytes))
        val engine = playback.player as FakeRealtimePlayer
        engine.seekMillis(5000)
        val volume = views(activity().window.decorView).single { it.tag == "volumeButton:0" } as Button

        volume.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("volumeDialog:0", dialog.window?.decorView?.tag)
        val list = dialog.listView
        assertEquals("volumeList:0", list.tag)
        assertEquals(android.widget.AbsListView.CHOICE_MODE_SINGLE, list.choiceMode)
        assertEquals(VolumeOptions.labels.size, list.adapter.count)
        VolumeOptions.labels.indices.forEach { i ->
            assertEquals(VolumeOptions.labels[i], list.adapter.getItem(i).toString())
        }
        assertEquals(VolumeOptions.indexOf(100), list.checkedItemPosition)

        val selected = VolumeOptions.indexOf(50)
        list.performItemClick(list.getChildAt(selected), selected, list.adapter.getItemId(selected))
        assertEquals("音量：50", volume.text.toString())
        assertEquals("鋼琴：音量 50，開啟音量選單", volume.contentDescription.toString())
        val expectedVolumes = listOf(50) + List(4) { VolumeOptions.DEFAULT }
        assertEquals(expectedVolumes, playback.volumes)
        assertEquals(expectedVolumes + 80, engine.volumes)
        assertSame(engine, playback.player)
        assertEquals(1, renders.get())
        assertTrue(engine.currentPosition >= 5000)
        val saved = Library(application).entries.associateBy { it.id }
        assertEquals(expectedVolumes, saved.getValue(currentId).volumes)
        assertTrue(saved.getValue(other.id).volumes.isEmpty())
    }
    @Ignore("Part volume controls were intentionally removed")
    @Test fun legacyVolumesResetToDefaultAndPersistWhenSongOpens() {
        val bytes = application.assets.open("demo.musicxml").use { it.readBytes() }
        val parsed = MusicXml.parse(bytes)
        assertEquals(5, parsed.parts.size)
        val library = Library(application); val entry = library.add(bytes, parsed)
        entry.volumes = listOf(102, 127, 101, 126, -1); library.save()
        button("播放").performClick()
        await { playback.player != null && !playback.rendering }
        val defaults = List(5) { VolumeOptions.DEFAULT }
        assertEquals(defaults, playback.volumes)
        assertEquals(defaults, Library(application).entries.single().volumes)
        val buttons = views(activity().window.decorView).filterIsInstance<Button>()
            .filter { it.tag?.toString()?.startsWith("volumeButton:") == true }
        assertEquals(List(5) { "音量：100" }, buttons.map { it.text.toString() })
    }
    @Ignore("Part volume controls were intentionally removed")
    @Test fun zeroVolumeDoesNotMuteThePart() {
        importAndPlay()
        val engine = playback.player as FakeRealtimePlayer
        val volume = views(activity().window.decorView).single { it.tag == "volumeButton:0" } as Button
        volume.performClick()
        val list = (ShadowDialog.getLatestDialog() as AlertDialog).listView
        list.performItemClick(list.getChildAt(0), 0, list.adapter.getItemId(0))

        assertEquals(0, playback.volumes[0])
        assertFalse(playback.muted[0])
        assertTrue(engine.enabled[0])
        assertEquals(0, engine.volumes[0])
        assertFalse(views(activity().window.decorView).filterIsInstance<CheckBox>().first { it.text == "靜音" }.isChecked)
        assertEquals(listOf(0) + List(4) { VolumeOptions.DEFAULT }, Library(application).entries.single().volumes)
    }
    @Test fun notificationTransportAndHeadphoneDisconnectControlPlayback() {
        importAndPlay()
        val notification = shadowOf(playback).lastForegroundNotification
        assertEquals(2, notification.actions.size)
        playback.onStartCommand(Intent().setAction(PlaybackService.ACTION_PAUSE), 0, 1)
        assertFalse(playback.isPlaying)
        playback.onStartCommand(Intent().setAction(PlaybackService.ACTION_PLAY), 0, 2)
        assertTrue(playback.isPlaying)
        application.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(playback.isPlaying)
        playback.onStartCommand(Intent().setAction(PlaybackService.ACTION_STOP), 0, 3)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(playback.wantsPlayback)
        assertEquals(0, playback.currentPosition)
        assertTrue(shadowOf(playback).isForegroundStopped)
    }
    @Test fun routeLabelsUseActualIdAndNeverGuessAnUnselectedConnectedDevice() {
        val outputs = listOf(
            OutputDevice(7, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Phone"),
            OutputDevice(9, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds")
        )
        assertEquals("音訊輸出：藍牙音訊（Buds）", AudioRouteDisplay.label(9, outputs))
        assertEquals("音訊輸出：系統選擇", AudioRouteDisplay.label(0, outputs))
        assertEquals("音訊輸出：系統選擇", AudioRouteDisplay.label(12, outputs,
            listOf(OutputDevice(9, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds"))))
        assertEquals("音訊輸出：裝置喇叭（Phone）（系統推定）", AudioRouteDisplay.label(0, outputs,
            listOf(OutputDevice(7, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Phone"))))
    }
    @Test fun removedDevicePausesOnlyActiveMatchingKnownActualRoute() {
        assertTrue(AudioRouteDisplay.shouldPauseForRemoval(true, 9, listOf(9)))
        assertFalse(AudioRouteDisplay.shouldPauseForRemoval(false, 9, listOf(9)))
        assertFalse(AudioRouteDisplay.shouldPauseForRemoval(true, 9, listOf(7)))
        assertFalse(AudioRouteDisplay.shouldPauseForRemoval(true, 0, listOf(9)))
    }
    @Test fun liveMixSpeedAndProgramControlsDoNotReprepareOrSeek() {
        importAndPlay()
        val engine = playback.player as FakeRealtimePlayer
        val score = playback.score!!
        assertEquals(5, score.parts.size)
        assertEquals(List(score.parts.size + 1) { false }, engine.overrides)
        engine.seekMillis(5000)
        playback.volumes[0] = 50; playback.remix()
        playback.muted[1] = true; playback.remix()
        playback.solo = 0; playback.remix()
        playback.setProgram(0, 40)
        playback.metronome = true; playback.setSpeed(0.75f)
        assertSame(engine, playback.player); assertEquals(1, renders.get())
        assertTrue(playback.isPlaying); assertTrue(engine.currentPosition >= 5000)
        assertEquals(score.parts.indices.map { it == 0 } + true, engine.enabled)
        assertEquals(score.parts.indices.map { if (it == 0) 50 else 100 } + 80, engine.volumes)
        assertEquals(score.parts.mapIndexed { index, part -> if (index == 0) 40 else part.program } + 0, engine.programs)
        assertEquals(score.parts.indices.map { it == 0 } + false, engine.overrides)
        assertEquals(0.75f, engine.speed, 0f)
        playback.setProgram(0, playback.score!!.parts[0].program)
        assertTrue(engine.overrides[0])
        assertTrue(Library(application).entries.single().programOverrides[0])
    }
    @Ignore("Speed is now global rather than persisted per track")
    @Test fun playbackSpeedMenuUsesOptionsPersistsResetsAndDoesNotReprepare() {
        importAndPlay()
        val engine = playback.player
        val speedButton = views(activity().window.decorView).single { it.tag == "speedButton" } as Button
        assertEquals("播放速度：100%", speedButton.text.toString())
        speedButton.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertEquals("speedDialog", dialog.window?.decorView?.tag)
        val speedList = dialog.listView
        assertEquals("speedList", speedList.tag)
        speedList.performItemClick(speedList.getChildAt(3), 3, speedList.adapter.getItemId(3))
        assertEquals(1.25f, playback.speed, 0.0001f)
        assertEquals("播放速度：125%", speedButton.text.toString())
        assertSame(engine, playback.player)
        assertEquals(1, renders.get())
        assertEquals(1.25f, Library(application).entries.single().speed, 0.0001f)

        playback.setSpeed(1.35f)
        assertEquals(1.0f, playback.speed, 0.0001f)
        assertEquals(1.0f, Library(application).entries.single().speed, 0.0001f)
    }
    @Test fun rapidPauseAndPlayAlwaysHonorsTheLastRequest() {
        importAndPlay()
        val engine = playback.player
        repeat(100) { playback.pause(); playback.play() }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertSame(engine, playback.player)
        assertTrue(playback.wantsPlayback)
        assertEquals(TransportPhase.PLAYING, playback.transportPhase)
    }
    @Test fun naturalCompletionSeeksToZeroAndReplaysOnTheSameEngine() {
        importAndPlay()
        val engine = playback.player as FakeRealtimePlayer
        engine.phase = RealtimePhase.FINISHED
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))

        assertTrue(playback.completed)
        assertEquals(TransportPhase.COMPLETED, playback.transportPhase)
        assertEquals(0, playback.currentPosition)
        assertFalse(playback.wantsPlayback)
        val replay = views(activity().window.decorView).single { it.tag == "playPauseButton" } as ImageButton
        assertEquals("重新播放", replay.contentDescription.toString())

        replay.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertSame(engine, playback.player)
        assertEquals(1, renders.get())
        assertFalse(playback.completed)
        assertEquals(TransportPhase.PLAYING, playback.transportPhase)
        assertTrue(playback.currentPosition < 500)
    }
    @Test fun seekingAfterCompletionStaysPausedAndPlaysFromTheSelectedPosition() {
        importAndPlay()
        val engine = playback.player as FakeRealtimePlayer
        engine.phase = RealtimePhase.FINISHED
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        val middleTick = playback.score!!.endTick / 2

        playback.seek(middleTick)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(160))
        val selected = playback.currentPosition
        assertFalse(playback.completed)
        assertEquals(TransportPhase.PAUSED, playback.transportPhase)
        assertTrue(selected > 0)

        playback.play()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals(TransportPhase.PLAYING, playback.transportPhase)
        assertTrue(playback.currentPosition >= selected)
    }
    @Ignore("Superseded by the three-state queue repeat control")
    @Test fun repeatOneRestartsOnTheSameEngineWithoutCompleting() {
        importAndPlay()
        val engine = playback.player as FakeRealtimePlayer
        val repeat = views(activity().window.decorView).single { it.tag == "repeatOneButton" } as ImageButton
        assertEquals("開啟單曲循環", repeat.contentDescription.toString())
        repeat.performClick()
        assertTrue(playback.repeatOne)
        assertTrue(repeat.isActivated)
        assertEquals("關閉單曲循環", repeat.contentDescription.toString())

        engine.phase = RealtimePhase.FINISHED
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))

        assertSame(engine, playback.player)
        assertEquals(1, renders.get())
        assertFalse(playback.completed)
        assertTrue(playback.wantsPlayback)
        assertEquals(TransportPhase.PLAYING, playback.transportPhase)
        assertTrue(playback.currentPosition < 500)
    }
    @Ignore("Superseded by the three-state queue repeat control")
    @Test fun repeatOneCompletionDuringTransientFocusLossResumesFromStartOnGain() {
        importAndPlay()
        val engine = playback.player as FakeRealtimePlayer
        val repeat = views(activity().window.decorView).single { it.tag == "repeatOneButton" } as ImageButton
        repeat.performClick()
        val audio = shadowOf(application.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        val focusListener = audio.lastAudioFocusRequest.listener

        focusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        engine.phase = RealtimePhase.FINISHED
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertFalse(playback.wantsPlayback)
        assertFalse(playback.completed)
        assertEquals(TransportPhase.PAUSED, playback.transportPhase)
        assertEquals(0, playback.currentPosition)

        focusListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertTrue(playback.wantsPlayback)
        assertEquals(TransportPhase.PLAYING, playback.transportPhase)
        assertTrue(playback.currentPosition < 500)
    }
    @Test fun reconnectIsBufferingNotFatalAndRecoversWithoutReprepare() {
        importAndPlay()
        val engine = playback.player as FakeRealtimePlayer
        engine.nativeError = "暫時無法連線"
        engine.phase = RealtimePhase.RECONNECTING
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))

        assertEquals(TransportPhase.RECONNECTING, playback.transportPhase)
        assertFalse(playback.isPlaying)
        assertTrue(playback.wantsPlayback)
        assertNull(playback.errorMessage)
        assertSame(engine, playback.player)

        engine.nativeError = null
        engine.phase = RealtimePhase.PLAYING
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals(TransportPhase.PLAYING, playback.transportPhase)
        assertTrue(playback.isPlaying)
        assertEquals(1, renders.get())
    }
    @Test fun routeUnavailableIsRetryableWithTheSameEngine() {
        importAndPlay()
        val engine = playback.player as FakeRealtimePlayer
        engine.phase = RealtimePhase.ROUTE_UNAVAILABLE
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))

        assertEquals(TransportPhase.ROUTE_UNAVAILABLE, playback.transportPhase)
        assertFalse(playback.wantsPlayback)
        assertFalse(playback.isPlaying)
        assertTrue(engine.pauseCalls > 0)
        val retry = views(activity().window.decorView).single { it.tag == "playPauseButton" } as ImageButton
        assertEquals("重試", retry.contentDescription.toString())

        retry.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals(1, engine.retryCalls)
        assertSame(engine, playback.player)
        assertEquals(1, renders.get())
        assertEquals(TransportPhase.PLAYING, playback.transportPhase)
    }
    @Test fun startingAndReconnectPhasesNeverReportPlaying() {
        importAndPlay()
        val engine = playback.player as FakeRealtimePlayer
        engine.phase = RealtimePhase.STARTING
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals(TransportPhase.STARTING, playback.transportPhase)
        assertFalse(playback.isPlaying)
        assertEquals("暫停", views(activity().window.decorView).single { it.tag == "playPauseButton" }.contentDescription.toString())

        engine.phase = RealtimePhase.RECONNECTING
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        assertEquals(TransportPhase.RECONNECTING, playback.transportPhase)
        assertFalse(playback.isPlaying)
    }
    @Test @Config(sdk = [36]) fun modernSystemBarsUseDarkIconsOnLightAndLightIconsOnDark() {
        Appearance.setMode(application, DisplayMode.LIGHT)
        val a = activity()
        val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        assertEquals(flags, a.window.insetsController!!.systemBarsAppearance and flags)
        views(a.window.decorView).filterIsInstance<ImageButton>().first { it.contentDescription == "設定" }.performClick()
        views(a.window.decorView).filterIsInstance<RadioButton>().first { it.text == "深色" }.performClick()
        assertEquals(0, a.window.insetsController!!.systemBarsAppearance and flags)
    }
    @Test fun systemDisplayModeFollowsSystemNightChanges() {
        val a = activity()
        assertEquals(DisplayMode.SYSTEM, Appearance.mode(application))
        RuntimeEnvironment.setQualifiers("+night")
        a.onConfigurationChanged(application.resources.configuration)
        assertTrue(Appearance.dark(a))
        assertEquals(0, a.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        RuntimeEnvironment.setQualifiers("+notnight")
        a.onConfigurationChanged(application.resources.configuration)
        assertFalse(Appearance.dark(a))
        assertEquals(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR, a.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationPermissionApi33Test {
    @Test fun deniedPermissionDoesNotDelayPlaybackAndIsRequestedOnlyOnce() {
        val events = mutableListOf<String>()
        var requested = false
        fun explicitPlay() = NotificationPermissionPrompt.runAfterPlayback(
            33, granted = false, alreadyRequested = requested,
            playback = { events += "play" },
            requestPermission = { requested = true; events += "request" }
        )

        explicitPlay()
        explicitPlay()

        assertEquals(listOf("play", "request", "play"), events)
    }

    @Test fun grantedOrPreApi33NeverRequestsNotificationPermission() {
        var prompts = 0
        NotificationPermissionPrompt.runAfterPlayback(33, true, false, {}, { prompts++ })
        NotificationPermissionPrompt.runAfterPlayback(32, false, false, {}, { prompts++ })
        assertEquals(0, prompts)
    }
}

private class FakeRealtimePlayer(private val duration: Int) : RealtimePlayer {
    private var position = 0.0
    private var anchor = SystemClock.uptimeMillis()
    var phase = RealtimePhase.PAUSED
    var nativeError: String? = null
    var retryCalls = 0
    var pauseCalls = 0
    private var rateSpeed = 1f
    val speed get() = rateSpeed
    var enabled = emptyList<Boolean>()
    var volumes = emptyList<Int>()
    var programs = emptyList<Int>()
    var overrides = emptyList<Boolean>()
    override val currentPosition get() = (position + if (phase == RealtimePhase.PLAYING) (SystemClock.uptimeMillis() - anchor) * speed else 0f).toInt().coerceAtMost(duration)
    override val isPlaying get() = phase == RealtimePhase.PLAYING
    override val isFinished get() = phase == RealtimePhase.FINISHED || currentPosition >= duration
    override val errorMessage get() = nativeError
    override val status get() = RealtimeStatus(
        phase, currentPosition, if (phase == RealtimePhase.ERROR) 1 else 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0
    )
    override fun play() { if (phase != RealtimePhase.PLAYING) { anchor = SystemClock.uptimeMillis(); phase = RealtimePhase.PLAYING } }
    override fun pause() { pauseCalls++; position = currentPosition.toDouble(); phase = RealtimePhase.PAUSED }
    override fun seekMillis(position: Int) { this.position = position.toDouble(); anchor = SystemClock.uptimeMillis() }
    override fun setSpeed(speed: Float) { position = currentPosition.toDouble(); anchor = SystemClock.uptimeMillis(); rateSpeed = speed }
    override fun setMix(enabled: List<Boolean>, volumes: List<Int>, programs: List<Int>, overrides: List<Boolean>) {
        this.enabled = enabled; this.volumes = volumes; this.programs = programs; this.overrides = overrides
    }
    override fun retryAudioRoute() { retryCalls++ }
    override fun close() { phase = RealtimePhase.CLOSED }
}
