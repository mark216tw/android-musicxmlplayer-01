package com.musicxml.player

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Looper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerRedesignTest {
    private lateinit var application: Application
    private var serviceController: ServiceController<PlaybackService>? = null

    @Before fun setup() {
        application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("library", Context.MODE_PRIVATE).edit().clear().commit()
        application.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit()
        application.filesDir.resolve("scores").deleteRecursively()
    }

    @After fun cleanup() {
        serviceController?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun addTracks(): Pair<LibraryEntry, LibraryEntry> {
        val bytes = application.assets.open("demo.musicxml").use { it.readBytes() }
        val score = MusicXml.parse(bytes)
        val library = Library(application)
        val first = library.add(bytes, score).apply { name = "第一首" }
        val second = library.add(bytes, score).apply { name = "第二首" }
        library.save()
        return first to second
    }

    @Test fun playlistCrudPersistsOrderAndCleansDeletedTracks() {
        val (first, second) = addTracks()
        var library = Library(application)
        val playlist = library.createPlaylist("練習")
        library.addToPlaylist(playlist, listOf(first.id, second.id, first.id))
        assertEquals(listOf(first.id, second.id), playlist.trackIds)

        library.moveInPlaylist(playlist, 1, 0)
        library = Library(application)
        val restored = library.playlists.single()
        assertEquals("練習", restored.name)
        assertEquals(listOf(second.id, first.id), restored.trackIds)

        library.remove(library.entries.first { it.id == second.id })
        assertEquals(listOf(first.id), Library(application).playlists.single().trackIds)
    }

    @Test fun globalPlaybackPreferencesMigrateFromMostRecentTrack() {
        val (first, second) = addTracks()
        val library = Library(application)
        library.entries.first { it.id == first.id }.apply {
            lastPlayed = 10; speed = 0.75f; performanceStyle = PerformanceStyle.POP
        }
        library.entries.first { it.id == second.id }.apply {
            lastPlayed = 20; speed = 1.25f; performanceStyle = PerformanceStyle.ROMANTIC
        }
        library.save()

        val persistence = PlaybackPersistence(application)
        assertEquals(1.25f, persistence.speed(Library(application)), 0f)
        assertEquals(PerformanceStyle.ROMANTIC, persistence.style(Library(application)))
        persistence.saveGlobals(0.5f, PerformanceStyle.NATURAL)
        assertEquals(0.5f, persistence.speed(Library(application)), 0f)
        assertEquals(PerformanceStyle.NATURAL, persistence.style(Library(application)))
    }

    @Test fun sessionRestoreDropsMissingAndDuplicateTrackIds() {
        val (first, second) = addTracks()
        val persistence = PlaybackPersistence(application)
        persistence.saveSession(PlaybackSessionSnapshot(
            listOf(first.id, "missing", second.id),
            listOf(second.id, second.id, "missing", first.id),
            second.id, 1234, shuffle = true, repeatMode = RepeatMode.ALL
        ))

        val restored = persistence.loadSession(setOf(first.id, second.id))!!
        assertEquals(listOf(first.id, second.id), restored.queueIds)
        assertEquals(listOf(second.id, first.id), restored.playOrderIds)
        assertEquals(1234, restored.positionMillis)
        assertTrue(restored.shuffle)
        assertEquals(RepeatMode.ALL, restored.repeatMode)
    }

    @Test fun serviceOwnsQueueAndSupportsSkipShuffleAndRepeatModes() {
        val (first, second) = addTracks()
        serviceController = Robolectric.buildService(PlaybackService::class.java).create()
        val service = serviceController!!.get()
        service.preparePlayer = { QueueFakePlayer() }
        shadowOf(application.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        service.playQueue(listOf(first.id, second.id), first.id)
        await { service.player != null && !service.rendering }
        assertEquals(first.id, service.entry?.id)
        assertEquals(listOf(first.id, second.id), service.queueIds)
        assertTrue(service.hasNext)

        assertTrue(service.skipNext())
        await { service.entry?.id == second.id && service.player != null && !service.rendering }
        assertTrue(service.hasPrevious)
        assertFalse(service.hasNext)

        service.cycleRepeatMode()
        assertEquals(RepeatMode.ALL, service.repeatMode)
        assertTrue(service.hasNext)
        service.cycleRepeatMode()
        assertEquals(RepeatMode.ONE, service.repeatMode)
        service.cycleRepeatMode()
        assertEquals(RepeatMode.OFF, service.repeatMode)

        service.setShuffle(true)
        assertTrue(service.shuffleEnabled)
        assertEquals(second.id, service.playOrderIds.first())
        assertEquals(service.queueIds.toSet(), service.playOrderIds.toSet())
    }

    @Test fun completedTrackAdvancesToNextQueueItem() {
        val (first, second) = addTracks()
        serviceController = Robolectric.buildService(PlaybackService::class.java).create()
        val service = serviceController!!.get()
        val players = mutableListOf<QueueFakePlayer>()
        service.preparePlayer = { QueueFakePlayer().also(players::add) }
        shadowOf(application.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        service.playQueue(listOf(first.id, second.id), first.id)
        await { players.isNotEmpty() && service.player != null && !service.rendering }

        players.first().finish()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        await { service.entry?.id == second.id && players.size == 2 && !service.rendering }
        assertTrue(service.isPlaying)
    }

    @Test fun pauseWhileTrackIsLoadingPreventsCapturedAutoplay() {
        val (first, _) = addTracks()
        serviceController = Robolectric.buildService(PlaybackService::class.java)
        val service = serviceController!!.get()
        service.preparePlayer = { QueueFakePlayer() }
        serviceController!!.create()
        shadowOf(application.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        service.playQueue(listOf(first.id), first.id)
        service.pause()
        await { service.player != null && !service.rendering }
        assertFalse(service.wantsPlayback)
        assertFalse(service.isPlaying)
    }

    @Test fun repeatAllRestartsASingleTrackQueue() {
        val (first, _) = addTracks()
        serviceController = Robolectric.buildService(PlaybackService::class.java)
        val service = serviceController!!.get()
        val players = mutableListOf<QueueFakePlayer>()
        service.preparePlayer = { QueueFakePlayer().also(players::add) }
        serviceController!!.create()
        shadowOf(application.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        service.playQueue(listOf(first.id), first.id)
        await { players.size == 1 && !service.rendering }
        service.cycleRepeatMode()
        assertEquals(RepeatMode.ALL, service.repeatMode)

        players.first().finish()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(60))
        await { players.size == 2 && !service.rendering }
        assertEquals(first.id, service.entry?.id)
        assertTrue(service.isPlaying)
    }

    @Test fun notificationPlayDuringSessionRestoreWaitsForParsing() {
        val (first, _) = addTracks()
        PlaybackPersistence(application).saveSession(PlaybackSessionSnapshot(
            listOf(first.id), listOf(first.id), first.id, 250, shuffle = false, repeatMode = RepeatMode.OFF
        ))
        serviceController = Robolectric.buildService(PlaybackService::class.java)
        val service = serviceController!!.get()
        service.preparePlayer = { QueueFakePlayer() }
        serviceController!!.create()
        shadowOf(application.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        service.onStartCommand(android.content.Intent(application, PlaybackService::class.java).setAction(PlaybackService.ACTION_PLAY), 0, 1)
        await { service.player != null && !service.rendering }
        assertEquals(first.id, service.entry?.id)
        assertTrue(service.wantsPlayback)
        assertTrue(service.isPlaying)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("Timed out waiting for queue state", condition())
    }
}

private class QueueFakePlayer : RealtimePlayer {
    private var phase = RealtimePhase.PAUSED
    private var position = 0
    override val currentPosition get() = position
    override val isPlaying get() = phase == RealtimePhase.PLAYING
    override val isFinished get() = phase == RealtimePhase.FINISHED
    override val errorMessage: String? = null
    override val status get() = RealtimeStatus(phase, position, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0)
    override fun play() { phase = RealtimePhase.PLAYING }
    override fun pause() { phase = RealtimePhase.PAUSED }
    override fun seekMillis(position: Int) { this.position = position; if (phase == RealtimePhase.FINISHED) phase = RealtimePhase.PAUSED }
    override fun setSpeed(speed: Float) {}
    override fun setMix(enabled: List<Boolean>, volumes: List<Int>, programs: List<Int>, overrides: List<Boolean>) {}
    override fun retryAudioRoute() {}
    override fun close() { phase = RealtimePhase.CLOSED }
    fun finish() { phase = RealtimePhase.FINISHED }
}
