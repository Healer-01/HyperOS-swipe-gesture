package com.healer.hyperos.swipegesture.policy

import android.content.SharedPreferences

enum class SwitchMode(val wireValue: Int) {
    /** Opposite-edge vertical frosted-glass capsule menu showing recent apps. */
    MULTI_APP_MENU(0),

    /** Single-app quick switch to the immediately previous non-launcher task. */
    QUICK_PREVIOUS(1),
    ;

    companion object {
        fun fromWireValue(value: Int): SwitchMode =
            entries.firstOrNull { it.wireValue == value } ?: MULTI_APP_MENU
    }
}

data class SwipeGestureSettings(
    val enabled: Boolean = true,
    val switchMode: SwitchMode = SwitchMode.MULTI_APP_MENU,
    val leftEdgeEnabled: Boolean = true,
    val rightEdgeEnabled: Boolean = true,
    /** Normalized top boundary of the active edge trigger zone (0f..1f). */
    val triggerTopRatio: Float = DEFAULT_TRIGGER_TOP_RATIO,
    /** Normalized bottom boundary of the active edge trigger zone (0f..1f). */
    val triggerBottomRatio: Float = DEFAULT_TRIGGER_BOTTOM_RATIO,
    /** Required inward horizontal drag distance relative to screen width. */
    val slideDistanceRatio: Float = DEFAULT_SLIDE_DISTANCE_RATIO,
    /** Hold duration in milliseconds after crossing slideDistanceRatio. */
    val pauseDurationMs: Int = DEFAULT_PAUSE_DURATION_MS,
    /** Maximum recent app entries displayed in multi-app menu mode. */
    val menuMaxCount: Int = DEFAULT_MENU_MAX_COUNT,
    /** Vertical drag distance per selection step relative to screen height. */
    val menuStepRatio: Float = DEFAULT_MENU_STEP_RATIO,
    /** Visual scale multiplier for the opposite-edge capsule overlay. */
    val menuScale: Float = DEFAULT_MENU_SCALE,
    /** Emit verbose read-only gesture / back-path diagnostics to logcat. */
    val diagnosticTraceEnabled: Boolean = false,
    /** Packages hidden from candidate switching targets. */
    val blacklistedPackages: Set<String> = emptySet(),
) {
    fun sanitized(): SwipeGestureSettings {
        val rawTop = sanitizeFloat(triggerTopRatio, DEFAULT_TRIGGER_TOP_RATIO)
            .coerceIn(MIN_TRIGGER_TOP_RATIO, MAX_TRIGGER_TOP_RATIO)
        val rawBottom = sanitizeFloat(triggerBottomRatio, DEFAULT_TRIGGER_BOTTOM_RATIO)
            .coerceIn(MIN_TRIGGER_BOTTOM_RATIO, MAX_TRIGGER_BOTTOM_RATIO)
        val (safeTop, safeBottom) = if (rawBottom - rawTop < MIN_TRIGGER_SPAN_RATIO) {
            val adjustedBottom = (rawTop + MIN_TRIGGER_SPAN_RATIO).coerceAtMost(MAX_TRIGGER_BOTTOM_RATIO)
            val adjustedTop = (adjustedBottom - MIN_TRIGGER_SPAN_RATIO).coerceAtLeast(MIN_TRIGGER_TOP_RATIO)
            adjustedTop to adjustedBottom
        } else {
            rawTop to rawBottom
        }

        val cleanBlacklist = blacklistedPackages
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        return copy(
            triggerTopRatio = safeTop,
            triggerBottomRatio = safeBottom,
            slideDistanceRatio = sanitizeFloat(slideDistanceRatio, DEFAULT_SLIDE_DISTANCE_RATIO)
                .coerceIn(MIN_SLIDE_DISTANCE_RATIO, MAX_SLIDE_DISTANCE_RATIO),
            pauseDurationMs = pauseDurationMs.coerceIn(MIN_PAUSE_DURATION_MS, MAX_PAUSE_DURATION_MS),
            menuMaxCount = menuMaxCount.coerceIn(MIN_MENU_MAX_COUNT, MAX_MENU_MAX_COUNT),
            menuStepRatio = sanitizeFloat(menuStepRatio, DEFAULT_MENU_STEP_RATIO)
                .coerceIn(MIN_MENU_STEP_RATIO, MAX_MENU_STEP_RATIO),
            menuScale = sanitizeFloat(menuScale, DEFAULT_MENU_SCALE)
                .coerceIn(MIN_MENU_SCALE, MAX_MENU_SCALE),
            blacklistedPackages = cleanBlacklist,
        )
    }

    companion object {
        const val DEFAULT_TRIGGER_TOP_RATIO = 0.12f
        const val DEFAULT_TRIGGER_BOTTOM_RATIO = 0.82f
        const val MIN_TRIGGER_TOP_RATIO = 0.04f
        const val MAX_TRIGGER_TOP_RATIO = 0.80f
        const val MIN_TRIGGER_BOTTOM_RATIO = 0.20f
        const val MAX_TRIGGER_BOTTOM_RATIO = 0.96f
        const val MIN_TRIGGER_SPAN_RATIO = 0.16f

        const val DEFAULT_SLIDE_DISTANCE_RATIO = 0.22f
        const val MIN_SLIDE_DISTANCE_RATIO = 0.08f
        const val MAX_SLIDE_DISTANCE_RATIO = 0.45f

        const val DEFAULT_PAUSE_DURATION_MS = 350
        const val MIN_PAUSE_DURATION_MS = 120
        const val MAX_PAUSE_DURATION_MS = 1500

        const val DEFAULT_MENU_MAX_COUNT = 5
        const val MIN_MENU_MAX_COUNT = 2
        const val MAX_MENU_MAX_COUNT = 10

        const val DEFAULT_MENU_STEP_RATIO = 0.065f
        const val MIN_MENU_STEP_RATIO = 0.03f
        const val MAX_MENU_STEP_RATIO = 0.15f

        const val DEFAULT_MENU_SCALE = 1.0f
        const val MIN_MENU_SCALE = 0.70f
        const val MAX_MENU_SCALE = 1.40f

        private fun sanitizeFloat(value: Float, fallback: Float): Float =
            if (value.isFinite()) value else fallback
    }
}

object SettingsContract {
    const val PREFS_NAME = "swipe_gesture_settings"

    const val KEY_ENABLED = "enabled"
    const val KEY_SWITCH_MODE = "switch_mode"
    const val KEY_LEFT_EDGE_ENABLED = "left_edge_enabled"
    const val KEY_RIGHT_EDGE_ENABLED = "right_edge_enabled"
    const val KEY_TRIGGER_TOP_RATIO = "trigger_top_ratio"
    const val KEY_TRIGGER_BOTTOM_RATIO = "trigger_bottom_ratio"
    const val KEY_SLIDE_DISTANCE_RATIO = "slide_distance_ratio"
    const val KEY_PAUSE_DURATION_MS = "pause_duration_ms"
    const val KEY_MENU_MAX_COUNT = "menu_max_count"
    const val KEY_MENU_STEP_RATIO = "menu_step_ratio"
    const val KEY_MENU_SCALE = "menu_scale"
    const val KEY_DIAGNOSTIC_TRACE = "diagnostic_trace"
    const val KEY_BLACKLIST_CSV = "blacklist_csv"

    fun parseBlacklist(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun formatBlacklist(packages: Set<String>): String =
        packages.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
            .joinToString(",")

    fun read(prefs: SharedPreferences): SwipeGestureSettings = SwipeGestureSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        switchMode = SwitchMode.fromWireValue(
            prefs.getInt(KEY_SWITCH_MODE, SwitchMode.MULTI_APP_MENU.wireValue),
        ),
        leftEdgeEnabled = prefs.getBoolean(KEY_LEFT_EDGE_ENABLED, true),
        rightEdgeEnabled = prefs.getBoolean(KEY_RIGHT_EDGE_ENABLED, true),
        triggerTopRatio = prefs.getFloat(
            KEY_TRIGGER_TOP_RATIO,
            SwipeGestureSettings.DEFAULT_TRIGGER_TOP_RATIO,
        ),
        triggerBottomRatio = prefs.getFloat(
            KEY_TRIGGER_BOTTOM_RATIO,
            SwipeGestureSettings.DEFAULT_TRIGGER_BOTTOM_RATIO,
        ),
        slideDistanceRatio = prefs.getFloat(
            KEY_SLIDE_DISTANCE_RATIO,
            SwipeGestureSettings.DEFAULT_SLIDE_DISTANCE_RATIO,
        ),
        pauseDurationMs = prefs.getInt(
            KEY_PAUSE_DURATION_MS,
            SwipeGestureSettings.DEFAULT_PAUSE_DURATION_MS,
        ),
        menuMaxCount = prefs.getInt(
            KEY_MENU_MAX_COUNT,
            SwipeGestureSettings.DEFAULT_MENU_MAX_COUNT,
        ),
        menuStepRatio = prefs.getFloat(
            KEY_MENU_STEP_RATIO,
            SwipeGestureSettings.DEFAULT_MENU_STEP_RATIO,
        ),
        menuScale = prefs.getFloat(
            KEY_MENU_SCALE,
            SwipeGestureSettings.DEFAULT_MENU_SCALE,
        ),
        diagnosticTraceEnabled = prefs.getBoolean(KEY_DIAGNOSTIC_TRACE, false),
        blacklistedPackages = parseBlacklist(prefs.getString(KEY_BLACKLIST_CSV, "")),
    ).sanitized()

    fun write(prefs: SharedPreferences, settings: SwipeGestureSettings) {
        val clean = settings.sanitized()
        prefs.edit()
            .putBoolean(KEY_ENABLED, clean.enabled)
            .putInt(KEY_SWITCH_MODE, clean.switchMode.wireValue)
            .putBoolean(KEY_LEFT_EDGE_ENABLED, clean.leftEdgeEnabled)
            .putBoolean(KEY_RIGHT_EDGE_ENABLED, clean.rightEdgeEnabled)
            .putFloat(KEY_TRIGGER_TOP_RATIO, clean.triggerTopRatio)
            .putFloat(KEY_TRIGGER_BOTTOM_RATIO, clean.triggerBottomRatio)
            .putFloat(KEY_SLIDE_DISTANCE_RATIO, clean.slideDistanceRatio)
            .putInt(KEY_PAUSE_DURATION_MS, clean.pauseDurationMs)
            .putInt(KEY_MENU_MAX_COUNT, clean.menuMaxCount)
            .putFloat(KEY_MENU_STEP_RATIO, clean.menuStepRatio)
            .putFloat(KEY_MENU_SCALE, clean.menuScale)
            .putBoolean(KEY_DIAGNOSTIC_TRACE, clean.diagnosticTraceEnabled)
            .putString(KEY_BLACKLIST_CSV, formatBlacklist(clean.blacklistedPackages))
            .apply()
    }
}
