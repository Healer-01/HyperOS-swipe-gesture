package com.healer.hyperos.swipegesture.policy

import android.content.SharedPreferences
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

    @Test
    fun settingsContractRoundTripsAllFieldsThroughSharedPreferences() {
        val prefs = InMemorySharedPreferences()
        val expected = SwipeGestureSettings(
            enabled = false,
            switchMode = SwitchMode.QUICK_PREVIOUS,
            overlayMaterial = OverlayMaterial.FROSTED_BLUR,
            overlayThemeMode = OverlayThemeMode.DARK,
            leftEdgeEnabled = false,
            rightEdgeEnabled = true,
            triggerTopRatio = 0.18f,
            triggerBottomRatio = 0.74f,
            slideDistanceRatio = 0.30f,
            pauseDurationMs = 520,
            menuMaxCount = 7,
            menuStepRatio = 0.09f,
            menuScale = 1.25f,
            diagnosticTraceEnabled = true,
            blacklistedPackages = setOf("com.tencent.mm", "com.android.settings"),
        )

        SettingsContract.write(prefs, expected)
        val actual = SettingsContract.read(prefs)

        assertEquals(expected, actual)
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(map)

        override fun getString(key: String?, defValue: String?): String? =
            (map[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (map[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (map[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (map[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (map[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?) = apply {
                if (key != null) pending[key] = value
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply {
                if (key != null) pending[key] = values?.let { LinkedHashSet(it) }
            }

            override fun putInt(key: String?, value: Int) = apply {
                if (key != null) pending[key] = value
            }

            override fun putLong(key: String?, value: Long) = apply {
                if (key != null) pending[key] = value
            }

            override fun putFloat(key: String?, value: Float) = apply {
                if (key != null) pending[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean) = apply {
                if (key != null) pending[key] = value
            }

            override fun remove(key: String?) = apply {
                if (key != null) pending[key] = null
            }

            override fun clear() = apply {
                clearRequested = true
            }

            override fun commit(): Boolean {
                if (clearRequested) map.clear()
                for ((k, v) in pending) {
                    if (v == null) map.remove(k) else map[k] = v
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
    }
}
