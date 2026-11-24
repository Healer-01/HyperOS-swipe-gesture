package com.healer.hyperos.swipegesture.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeGestureStateMachineTest {

    private val defaultSettings = SwipeGestureSettings(
        enabled = true,
        switchMode = SwitchMode.MULTI_APP_MENU,
        leftEdgeEnabled = true,
        rightEdgeEnabled = true,
        triggerTopRatio = 0.12f,
        triggerBottomRatio = 0.82f,
        slideDistanceRatio = 0.20f,
        pauseDurationMs = 350,
        menuMaxCount = 5,
        menuStepRatio = 0.05f,
    )

    @Test
    fun normalQuickSwipeLeavesNativeBackUntouched() {
        val sm = SwipeGestureStateMachine()
        assertTrue(sm.onDown(10f, 1000f, 1000, 2000, 48, defaultSettings))
        assertEquals(
            SwipeGestureStateMachine.Action.ArmPause(fromLeft = true, anchorY = 1000f),
            sm.onMove(250f, 1000f, defaultSettings),
        )
        assertFalse(sm.isBackSuppressionLatched)

        // Finger lifts before pauseDurationMs elapses -> normal system Back MUST work.
        val end = sm.onUpOrCancel(cancelled = false)
        assertEquals(
            SwipeGestureStateMachine.Action.EndGesture(keepBackSuppressionWindow = false),
            end,
        )
        assertEquals(SwipeGestureStateMachine.Phase.IDLE, sm.phase)
    }

    @Test
    fun pauseAndVerticalDragSelectsAppsTopToBottomAndClampsBounds() {
        val sm = SwipeGestureStateMachine()
        assertTrue(sm.onDown(990f, 1000f, 1000, 2000, 48, defaultSettings))
        assertEquals(
            SwipeGestureStateMachine.Action.ArmPause(fromLeft = false, anchorY = 1000f),
            sm.onMove(760f, 1000f, defaultSettings),
        )

        val activated = sm.onPauseElapsed(availableCandidates = 3)
        assertEquals(
            SwipeGestureStateMachine.Action.ActivateSwitcher(fromLeft = false, anchorY = 1000f),
            activated,
        )
        assertTrue(sm.isBackSuppressionLatched)
        assertEquals(0, sm.selectedIndex)

        // Step is 2000 * 0.05 = 100px. Drag down 110px -> index 1.
        assertEquals(
            SwipeGestureStateMachine.Action.UpdateSelection(selectedIndex = 1, hitBoundary = false),
            sm.onMove(750f, 1110f, defaultSettings),
        )
        // Drag down another 105px -> index 2.
        assertEquals(
            SwipeGestureStateMachine.Action.UpdateSelection(selectedIndex = 2, hitBoundary = false),
            sm.onMove(750f, 1215f, defaultSettings),
        )
        // Drag down past bottom -> clamps at index 2 with hitBoundary = true.
        assertEquals(
            SwipeGestureStateMachine.Action.UpdateSelection(selectedIndex = 2, hitBoundary = true),
            sm.onMove(750f, 1320f, defaultSettings),
        )
        // Drag back up -> index 1.
        assertEquals(
            SwipeGestureStateMachine.Action.UpdateSelection(selectedIndex = 1, hitBoundary = false),
            sm.onMove(750f, 1210f, defaultSettings),
        )

        assertEquals(
            SwipeGestureStateMachine.Action.CommitSwitch(selectedIndex = 1),
            sm.onUpOrCancel(cancelled = false),
        )
        assertEquals(SwipeGestureStateMachine.Phase.IDLE, sm.phase)
    }

    @Test
    fun slideBackAfterActivationCancelsMenuWhileKeepingBackSuppressed() {
        val sm = SwipeGestureStateMachine()
        assertTrue(sm.onDown(10f, 1000f, 1000, 2000, 48, defaultSettings))
        sm.onMove(240f, 1000f, defaultSettings)
        sm.onPauseElapsed(availableCandidates = 4)

        // Slight inward jitter still above hysteresis (200 * 0.65 = 130px) does NOT cancel.
        assertEquals(
            SwipeGestureStateMachine.Action.None,
            sm.onMove(160f, 1000f, defaultSettings),
        )
        assertEquals(SwipeGestureStateMachine.Phase.ACTIVE, sm.phase)

        // Sliding back near edge (< 130px) cancels menu, but MUST keep Back suppressed!
        assertEquals(
            SwipeGestureStateMachine.Action.CancelActiveKeepBackSuppressed,
            sm.onMove(80f, 1000f, defaultSettings),
        )
        assertTrue(sm.isBackSuppressionLatched)

        // Releasing finger after slide-back cancel STILL keeps post-UP suppression window.
        assertEquals(
            SwipeGestureStateMachine.Action.EndGesture(keepBackSuppressionWindow = true),
            sm.onUpOrCancel(cancelled = false),
        )
    }

    @Test
    fun edgeAndVerticalHeightBoundsAreEnforced() {
        val sm = SwipeGestureStateMachine()
        val leftOnly = defaultSettings.copy(leftEdgeEnabled = true, rightEdgeEnabled = false)

        // Right edge disabled
        assertFalse(sm.onDown(990f, 1000f, 1000, 2000, 48, leftOnly))
        // Above triggerTopRatio (0.12 * 2000 = 240)
        assertFalse(sm.onDown(10f, 200f, 1000, 2000, 48, leftOnly))
        // Below triggerBottomRatio (0.82 * 2000 = 1640)
        assertFalse(sm.onDown(10f, 1700f, 1000, 2000, 48, leftOnly))
        // Inside valid zone
        assertTrue(sm.onDown(10f, 900f, 1000, 2000, 48, leftOnly))
    }

    @Test
    fun quickPreviousModeDoesNotMoveSelectionOnVerticalDrag() {
        val sm = SwipeGestureStateMachine()
        val quick = defaultSettings.copy(switchMode = SwitchMode.QUICK_PREVIOUS)

        assertTrue(sm.onDown(10f, 1000f, 1000, 2000, 48, quick))
        sm.onMove(250f, 1000f, quick)
        sm.onPauseElapsed(availableCandidates = 1)

        assertEquals(
            SwipeGestureStateMachine.Action.None,
            sm.onMove(260f, 1400f, quick),
        )
        assertEquals(
            SwipeGestureStateMachine.Action.CommitSwitch(selectedIndex = 0),
            sm.onUpOrCancel(cancelled = false),
        )
    }

    @Test
    fun multiTouchAfterActivationKeepsBackSuppressed() {
        val sm = SwipeGestureStateMachine()
        assertTrue(sm.onDown(10f, 1000f, 1000, 2000, 48, defaultSettings))
        sm.onMove(240f, 1000f, defaultSettings)
        sm.onPauseElapsed(availableCandidates = 2)

        assertEquals(
            SwipeGestureStateMachine.Action.EndGesture(keepBackSuppressionWindow = true),
            sm.onPointerDown(),
        )
        assertEquals(SwipeGestureStateMachine.Phase.IDLE, sm.phase)
    }

    @Test
    fun slidingBackBeforePauseCancelsArmWithoutSuppressingBack() {
        val sm = SwipeGestureStateMachine()
        assertTrue(sm.onDown(10f, 1000f, 1000, 2000, 48, defaultSettings))
        assertEquals(
            SwipeGestureStateMachine.Action.ArmPause(fromLeft = true, anchorY = 1000f),
            sm.onMove(250f, 1000f, defaultSettings),
        )
        assertEquals(
            SwipeGestureStateMachine.Action.CancelPause,
            sm.onMove(120f, 1000f, defaultSettings),
        )
        assertFalse(sm.isBackSuppressionLatched)
        assertEquals(
            SwipeGestureStateMachine.Action.EndGesture(keepBackSuppressionWindow = false),
            sm.onUpOrCancel(cancelled = false),
        )
    }

    @Test
    fun menuAnchorUsesInitialDownYEvenIfThumbDriftsVerticallyDuringSwipe() {
        val sm = SwipeGestureStateMachine()
        assertTrue(sm.onDown(10f, 900f, 1000, 2000, 48, defaultSettings))
        // Thumb drifts down to y=1260 while swiping inward to arm pause.
        assertEquals(
            SwipeGestureStateMachine.Action.ArmPause(fromLeft = true, anchorY = 900f),
            sm.onMove(240f, 1260f, defaultSettings),
        )
        // Menu anchorY MUST remain initial downY (900f), not drifted lastY (1260f).
        assertEquals(
            SwipeGestureStateMachine.Action.ActivateSwitcher(fromLeft = true, anchorY = 900f),
            sm.onPauseElapsed(availableCandidates = 3),
        )
        // Vertical selection starts smoothly from drifted finger position (1260f):
        // small move to 1275f does not jump; moving +110px to 1370f selects index 1.
        assertEquals(
            SwipeGestureStateMachine.Action.None,
            sm.onMove(245f, 1275f, defaultSettings),
        )
        assertEquals(
            SwipeGestureStateMachine.Action.UpdateSelection(selectedIndex = 1, hitBoundary = false),
            sm.onMove(245f, 1370f, defaultSettings),
        )
    }
}
