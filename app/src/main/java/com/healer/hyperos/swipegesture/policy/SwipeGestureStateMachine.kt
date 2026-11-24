package com.healer.hyperos.swipegesture.policy

import kotlin.math.abs

/**
 * Pure, framework-independent state machine for edge swipe-and-hold app switching.
 *
 * Design invariants:
 * 1. Normal quick edge swipes never trigger back suppression; native HyperOS back remains untouched.
 * 2. Once pause completes and the gesture enters [Phase.ACTIVE], system back suppression is latched
 *    for the remainder of the gesture — including if the user cancels by sliding back to the edge.
 * 3. Slide-back cancellation uses hysteresis ([CANCEL_HYSTERESIS_FACTOR]) so slight thumb jitter
 *    near the activation threshold does not flicker or prematurely dismiss the menu.
 */
class SwipeGestureStateMachine {

    enum class Phase {
        IDLE,
        TRACKING_EDGE,
        ARMED_WAIT_PAUSE,
        ACTIVE,
        CANCELLED_AFTER_ACTIVE,
    }

    sealed interface Action {
        data object None : Action

        data class ArmPause(
            val fromLeft: Boolean,
            val anchorY: Float,
        ) : Action

        data object CancelPause : Action

        data class ActivateSwitcher(
            val fromLeft: Boolean,
            val anchorY: Float,
        ) : Action

        data class UpdateSelection(
            val selectedIndex: Int,
            val hitBoundary: Boolean,
        ) : Action

        /** Menu dismissed by sliding back to edge; back suppression MUST remain latched until UP. */
        data object CancelActiveKeepBackSuppressed : Action

        data class CommitSwitch(
            val selectedIndex: Int,
        ) : Action

        data class EndGesture(
            val keepBackSuppressionWindow: Boolean,
        ) : Action
    }

    var phase: Phase = Phase.IDLE
        private set

    var fromLeft: Boolean = false
        private set

    var selectedIndex: Int = 0
        private set

    /** True whenever the current touch stream has been claimed and must not leak system Back. */
    val isBackSuppressionLatched: Boolean
        get() = phase == Phase.ACTIVE || phase == Phase.CANCELLED_AFTER_ACTIVE

    private var downX: Float = 0f
    private var lastY: Float = 0f
    private var selectionAnchorY: Float = 0f
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var candidateCount: Int = 0

    fun onDown(
        rawX: Float,
        rawY: Float,
        screenWidth: Int,
        screenHeight: Int,
        edgeInsetPx: Int,
        settings: SwipeGestureSettings,
    ): Boolean {
        resetInternal()
        if (!settings.enabled || screenWidth <= 0 || screenHeight <= 0 || edgeInsetPx <= 0) {
            return false
        }

        val yRatio = rawY / screenHeight.toFloat()
        if (yRatio < settings.triggerTopRatio || yRatio > settings.triggerBottomRatio) {
            return false
        }

        val inLeftEdge = settings.leftEdgeEnabled && rawX <= edgeInsetPx
        val inRightEdge = settings.rightEdgeEnabled && rawX >= (screenWidth - edgeInsetPx)
        if (!inLeftEdge && !inRightEdge) {
            return false
        }

        phase = Phase.TRACKING_EDGE
        fromLeft = inLeftEdge
        downX = rawX
        lastY = rawY
        selectionAnchorY = rawY
        this.screenWidth = screenWidth
        this.screenHeight = screenHeight
        return true
    }

    fun onMove(
        rawX: Float,
        rawY: Float,
        settings: SwipeGestureSettings,
    ): Action {
        lastY = rawY
        val inwardDistance = if (fromLeft) rawX - downX else downX - rawX
        val activateThreshold = screenWidth * settings.slideDistanceRatio

        return when (phase) {
            Phase.IDLE,
            Phase.CANCELLED_AFTER_ACTIVE,
            -> Action.None

            Phase.TRACKING_EDGE -> {
                if (inwardDistance >= activateThreshold) {
                    phase = Phase.ARMED_WAIT_PAUSE
                    Action.ArmPause(fromLeft = fromLeft, anchorY = rawY)
                } else {
                    Action.None
                }
            }

            Phase.ARMED_WAIT_PAUSE -> {
                if (inwardDistance < activateThreshold) {
                    phase = Phase.TRACKING_EDGE
                    Action.CancelPause
                } else {
                    Action.None
                }
            }

            Phase.ACTIVE -> {
                val cancelThreshold = activateThreshold * CANCEL_HYSTERESIS_FACTOR
                if (inwardDistance < cancelThreshold) {
                    phase = Phase.CANCELLED_AFTER_ACTIVE
                    Action.CancelActiveKeepBackSuppressed
                } else if (settings.switchMode == SwitchMode.MULTI_APP_MENU && candidateCount > 1) {
                    handleVerticalSelection(rawY, settings.menuStepRatio)
                } else {
                    Action.None
                }
            }
        }
    }

    fun onPauseElapsed(availableCandidates: Int): Action {
        if (phase != Phase.ARMED_WAIT_PAUSE) {
            return Action.None
        }
        if (availableCandidates <= 0) {
            resetInternal()
            return Action.EndGesture(keepBackSuppressionWindow = false)
        }
        phase = Phase.ACTIVE
        candidateCount = availableCandidates
        selectedIndex = 0
        selectionAnchorY = lastY
        return Action.ActivateSwitcher(fromLeft = fromLeft, anchorY = lastY)
    }

    fun onPointerDown(): Action {
        val suppress = isBackSuppressionLatched
        resetInternal()
        return if (suppress) {
            Action.EndGesture(keepBackSuppressionWindow = true)
        } else {
            Action.EndGesture(keepBackSuppressionWindow = false)
        }
    }

    fun onUpOrCancel(cancelled: Boolean): Action {
        val action = when (phase) {
            Phase.ACTIVE -> {
                if (!cancelled && candidateCount > 0) {
                    Action.CommitSwitch(selectedIndex = selectedIndex)
                } else {
                    Action.EndGesture(keepBackSuppressionWindow = true)
                }
            }

            Phase.CANCELLED_AFTER_ACTIVE -> {
                Action.EndGesture(keepBackSuppressionWindow = true)
            }

            Phase.TRACKING_EDGE,
            Phase.ARMED_WAIT_PAUSE,
            Phase.IDLE,
            -> {
                Action.EndGesture(keepBackSuppressionWindow = false)
            }
        }
        resetInternal()
        return action
    }

    private fun handleVerticalSelection(rawY: Float, stepRatio: Float): Action {
        val stepPx = (screenHeight * stepRatio).coerceAtLeast(1f)
        val deltaY = rawY - selectionAnchorY
        if (abs(deltaY) < stepPx) {
            return Action.None
        }

        // Top item (index 0) is the immediately previous app; dragging downward selects older apps.
        val direction = if (deltaY > 0f) 1 else -1
        selectionAnchorY = rawY
        val target = selectedIndex + direction
        return if (target in 0 until candidateCount) {
            selectedIndex = target
            Action.UpdateSelection(selectedIndex = target, hitBoundary = false)
        } else {
            Action.UpdateSelection(selectedIndex = selectedIndex, hitBoundary = true)
        }
    }

    private fun resetInternal() {
        phase = Phase.IDLE
        fromLeft = false
        selectedIndex = 0
        downX = 0f
        lastY = 0f
        selectionAnchorY = 0f
        screenWidth = 0
        screenHeight = 0
        candidateCount = 0
    }

    companion object {
        const val CANCEL_HYSTERESIS_FACTOR = 0.65f
    }
}
