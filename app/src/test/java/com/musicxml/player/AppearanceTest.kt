package com.musicxml.player

import android.app.Application
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppearanceTest {
    private lateinit var application: Application

    @Before fun setup() {
        application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("settings", 0).edit().clear().commit()
    }

    @Test fun sixThemePresetsAndCustomHueShareOnePersistedValue() {
        assertEquals(6, Appearance.themePresets.size)
        Appearance.themePresets.forEach { preset ->
            Appearance.setHue(application, preset.hue)
            assertEquals(preset.hue, Appearance.hue(application))
        }
        Appearance.setHue(application, 359)
        assertEquals(359, Appearance.hue(application))
        Appearance.setHue(application, 721)
        assertEquals(1, Appearance.hue(application))
    }

    @Test fun lightAndDarkAccentsRemainDifferentAndVisible() {
        val hue = Appearance.themePresets.first().hue
        val light = Appearance.accent(hue, false)
        val dark = Appearance.accent(hue, true)
        assertNotEquals(light, dark)
        assertTrue(Color.alpha(light) == 255)
        assertTrue(Color.alpha(dark) == 255)
        assertNotEquals(UiColors(false, hue).accent, UiColors(true, hue).accent)
    }
}
