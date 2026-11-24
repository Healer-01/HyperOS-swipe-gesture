package com.healer.hyperos.swipegesture.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeGestureSettingsTest {

    @Test
    fun sanitizedClampsOutOfRangeValuesAndPreservesVerticalSpan() {
        val dirty = SwipeGestureSettings(
            triggerTopRatio = 0.90f,
            triggerBottomRatio = 0.20f,
            slideDistanceRatio = 0.99f,
            pauseDurationMs = 99999,
            menuMaxCount = 100,
            menuStepRatio = Float.NaN,
            menuScale = -5f,
            blacklistedPackages = setOf("  com.example.a ", "", "   ", "com.example.b"),
        ).sanitized()

        assertTrue(dirty.triggerBottomRatio - dirty.triggerTopRatio >= SwipeGestureSettings.MIN_TRIGGER_SPAN_RATIO - 1e-4f)
        assertEquals(SwipeGestureSettings.MAX_SLIDE_DISTANCE_RATIO, dirty.slideDistanceRatio, 1e-5f)
        assertEquals(SwipeGestureSettings.MAX_PAUSE_DURATION_MS, dirty.pauseDurationMs)
        assertEquals(SwipeGestureSettings.MAX_MENU_MAX_COUNT, dirty.menuMaxCount)
        assertEquals(SwipeGestureSettings.DEFAULT_MENU_STEP_RATIO, dirty.menuStepRatio, 1e-5f)
        assertEquals(SwipeGestureSettings.MIN_MENU_SCALE, dirty.menuScale, 1e-5f)
        assertEquals(setOf("com.example.a", "com.example.b"), dirty.blacklistedPackages)
    }

    @Test
    fun blacklistCsvRoundTripsCleanly() {
        val formatted = SettingsContract.formatBlacklist(setOf("com.b", " com.a ", ""))
        assertEquals("com.a,com.b", formatted)
        assertEquals(setOf("com.a", "com.b"), SettingsContract.parseBlacklist(formatted))
    }
}
