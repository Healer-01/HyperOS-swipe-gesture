package com.healer.hyperos.swipegesture.hook

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.healer.hyperos.swipegesture.ipc.CandidateApp
import com.healer.hyperos.swipegesture.ipc.OverlayBinderClient
import com.healer.hyperos.swipegesture.ipc.OverlayChannelContract
import com.healer.hyperos.swipegesture.ipc.OverlayShowPayload
import com.healer.hyperos.swipegesture.policy.SwipeGestureSettings
import com.healer.hyperos.swipegesture.policy.SwipeGestureStateMachine
import com.healer.hyperos.swipegesture.policy.SwitchMode
import io.github.libxposed.api.XposedModule
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-thin `system_server` controller skeleton.
 *
 * Strict stability rules enforced by design:
 * 1. NEVER instantiate Views, WindowManager overlays, or createPackageContext inside system_server.
 * 2. NEVER perform synchronous ContentProvider queries or Settings.Global writes from input threads.
 * 3. NEVER reference legacy `de.robv.android.xposed.*` classes.
 * 4. All UI updates to SystemUI go through [OverlayBinderClient] using `IBinder.FLAG_ONEWAY`.
 *
 * Next step after local HyperOS 4.0.6.0 ADB dump (see `DESIGN.md` & `scripts/dump_hyperos4_readonly.ps1`):
 * - Bind the verified input channel / GestureMonitor hook to [onEdgeTouchEvent].
 * - Bind verified Back suppression hooks (pilferPointers + BackNavigationController cancel +
 *   InputManagerService KEYCODE_BACK guard) to [isBackSuppressedNow] / [triggerNativeBackCancel].
 */
internal object SystemServerGestureHooks {

    private const val TAG = SwipeGestureModuleEntry.TAG
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val WINDOWING_MODE_PC_FREEFORM = 100
    private const val POST_UP_BACK_SUPPRESSION_GRACE_MS = 450L

    private val installed = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)
    private val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HyperSwipeGesture-Worker").apply { isDaemon = true }
    }

    private val stateMachine = SwipeGestureStateMachine()

    @Volatile
    private var moduleRef: XposedModule? = null

    @Volatile
    private var overlayClient: OverlayBinderClient? = null

    @Volatile
    private var backSuppressedUntilUptimeMs: Long = 0L

    @Volatile
    private var activeCandidates: List<CandidateApp> = emptyList()

    private var pauseRunnable: Runnable? = null

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        moduleRef = module
        module.log(
            Log.INFO,
            TAG,
            "SystemServerGestureHooks initialized (awaiting verified HyperOS 4.0.6.0 hook bindings; see DESIGN.md)",
        )

        // TODO(HyperOS-4.0.6.0): After inspecting dumpsys_input.txt, services.jar, and miui-services.jar:
        // 1. Register host receiver once system Context is available via ensureOverlayHostReceiver(context).
        // 2. Hook verified edge-gesture InputChannel / InputEventReceiver to drive handleTouchEvent(...).
        // 3. Hook verified BackNavigationController cancel + InputManagerService injectInputEvent* guard
        //    checking isBackSuppressedNow().
        @Suppress("UNUSED_EXPRESSION")
        classLoader
    }

    fun ensureOverlayHostReceiver(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        runCatching {
            val filter = IntentFilter(OverlayChannelContract.ACTION_REGISTER_OVERLAY_HOST)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val binder: IBinder? = intent?.extras?.getBinder(
                        OverlayChannelContract.EXTRA_OVERLAY_BINDER,
                    )
                    if (binder != null) {
                        overlayClient = OverlayBinderClient(binder)
                        moduleRef?.log(Log.INFO, TAG, "Bound oneway overlay channel to SystemUI")
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
        }.onFailure { err ->
            receiverRegistered.set(false)
            moduleRef?.log(Log.WARN, TAG, "Failed to register overlay host receiver", err)
        }
    }

    fun isBackSuppressedNow(): Boolean =
        stateMachine.isBackSuppressionLatched ||
            SystemClock.uptimeMillis() <= backSuppressedUntilUptimeMs

    /**
     * Entry point invoked from the verified input hook on a Handler thread inside `system_server`.
     */
    fun handlePointerDown(
        context: Context,
        handler: Handler,
        rawX: Float,
        rawY: Float,
        screenWidth: Int,
        screenHeight: Int,
        edgeInsetPx: Int,
    ) {
        ensureOverlayHostReceiver(context)
        cancelPendingPause(handler)
        activeCandidates = emptyList()

        val settings = RuntimeSettingsHolder.current
        if (!settings.enabled || isSystemUiBlockingGesture(context)) {
            return
        }

        val tracking = stateMachine.onDown(
            rawX = rawX,
            rawY = rawY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            edgeInsetPx = edgeInsetPx,
            settings = settings,
        )
        if (tracking && settings.diagnosticTraceEnabled) {
            moduleRef?.log(
                Log.DEBUG,
                TAG,
                "Edge DOWN tracked rawX=$rawX rawY=$rawY fromLeft=${stateMachine.fromLeft}",
            )
        }
    }

    fun handlePointerMove(
        context: Context,
        handler: Handler,
        rawX: Float,
        rawY: Float,
    ) {
        val settings = RuntimeSettingsHolder.current
        when (val action = stateMachine.onMove(rawX, rawY, settings)) {
            SwipeGestureStateMachine.Action.None -> Unit

            is SwipeGestureStateMachine.Action.ArmPause -> {
                schedulePauseTimer(context, handler, settings)
            }

            SwipeGestureStateMachine.Action.CancelPause -> {
                cancelPendingPause(handler)
            }

            SwipeGestureStateMachine.Action.CancelActiveKeepBackSuppressed -> {
                cancelPendingPause(handler)
                extendBackSuppression(SystemClock.uptimeMillis() + 2000L)
                overlayClient?.hide(cancelled = true)
            }

            is SwipeGestureStateMachine.Action.UpdateSelection -> {
                overlayClient?.updateSelection(action.selectedIndex, action.hitBoundary)
            }

            is SwipeGestureStateMachine.Action.ActivateSwitcher,
            is SwipeGestureStateMachine.Action.CommitSwitch,
            is SwipeGestureStateMachine.Action.EndGesture,
            -> Unit
        }
    }

    fun handleMultiTouchPointerDown(handler: Handler) {
        cancelPendingPause(handler)
        val action = stateMachine.onPointerDown()
        if (action is SwipeGestureStateMachine.Action.EndGesture && action.keepBackSuppressionWindow) {
            extendBackSuppression(SystemClock.uptimeMillis() + POST_UP_BACK_SUPPRESSION_GRACE_MS)
        }
        overlayClient?.hide(cancelled = true)
        activeCandidates = emptyList()
    }

    fun handlePointerUpOrCancel(
        context: Context,
        handler: Handler,
        cancelled: Boolean,
    ) {
        cancelPendingPause(handler)
        val candidatesSnapshot = activeCandidates
        activeCandidates = emptyList()

        when (val action = stateMachine.onUpOrCancel(cancelled)) {
            is SwipeGestureStateMachine.Action.CommitSwitch -> {
                extendBackSuppression(SystemClock.uptimeMillis() + POST_UP_BACK_SUPPRESSION_GRACE_MS)
                overlayClient?.hide(cancelled = false)
                val target = candidatesSnapshot.getOrNull(action.selectedIndex)
                if (target != null && target.taskId > 0) {
                    moveTaskToFrontAsync(context, target.taskId)
                }
            }

            is SwipeGestureStateMachine.Action.EndGesture -> {
                if (action.keepBackSuppressionWindow) {
                    extendBackSuppression(
                        SystemClock.uptimeMillis() + POST_UP_BACK_SUPPRESSION_GRACE_MS,
                    )
                } else {
                    extendBackSuppression(0L)
                }
                overlayClient?.hide(cancelled = true)
            }

            else -> Unit
        }
    }

    private fun schedulePauseTimer(
        context: Context,
        handler: Handler,
        settings: SwipeGestureSettings,
    ) {
        cancelPendingPause(handler)
        val runnable = Runnable {
            pauseRunnable = null
            val maxCount = if (settings.switchMode == SwitchMode.MULTI_APP_MENU) {
                settings.menuMaxCount
            } else {
                1
            }
            val candidates = resolveRecentCandidates(context, settings.blacklistedPackages, maxCount)
            when (val action = stateMachine.onPauseElapsed(candidates.size)) {
                is SwipeGestureStateMachine.Action.ActivateSwitcher -> {
                    activeCandidates = candidates
                    extendBackSuppression(SystemClock.uptimeMillis() + 5000L)
                    triggerNativeBackCancel()
                    overlayClient?.show(
                        OverlayShowPayload(
                            mode = settings.switchMode,
                            fromLeft = action.fromLeft,
                            anchorRawY = action.anchorY,
                            scale = settings.menuScale,
                            selectedIndex = 0,
                            candidates = candidates,
                        ),
                    )
                }

                else -> {
                    activeCandidates = emptyList()
                }
            }
        }
        pauseRunnable = runnable
        handler.postDelayed(runnable, settings.pauseDurationMs.toLong())
    }

    private fun cancelPendingPause(handler: Handler) {
        pauseRunnable?.let { handler.removeCallbacks(it) }
        pauseRunnable = null
    }

    private fun extendBackSuppression(untilUptimeMs: Long) {
        backSuppressedUntilUptimeMs = untilUptimeMs
        overlayClient?.suppressBackUntil(untilUptimeMs)
    }

    private fun triggerNativeBackCancel() {
        // TODO(HyperOS-4.0.6.0): Invoke verified pilferPointers() on the exact winning InputChannel
        // and cancel any in-flight BackNavigationController session cleanly.
    }

    private fun isSystemUiBlockingGesture(context: Context): Boolean {
        // TODO(HyperOS-4.0.6.0): Query KeyguardManager.isKeyguardLocked + HyperOS shade expansion
        // state so lockscreen / notification shade / control center never trigger edge switching.
        return runCatching {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            km?.isKeyguardLocked == true
        }.getOrDefault(false)
    }

    private fun resolveRecentCandidates(
        context: Context,
        blacklist: Set<String>,
        maxCount: Int,
    ): List<CandidateApp> {
        if (maxCount <= 0) return emptyList()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()
        val pm = context.packageManager
        val result = ArrayList<CandidateApp>(maxCount)

        @Suppress("DEPRECATION")
        val tasks = runCatching { am.getRunningTasks(20) }.getOrNull().orEmpty()
        if (tasks.size < 2) return emptyList()

        for (i in 1 until tasks.size) {
            if (result.size >= maxCount) break
            val task = tasks[i]
            val base = task.baseActivity ?: continue
            val pkg = base.packageName
            if (pkg == SwipeGestureModuleEntry.PKG_LAUNCHER) continue
            if (pkg in blacklist) continue
            if (isFloatingOrMiniWindow(task)) continue
            if (result.any { it.packageName == pkg }) continue

            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)

            result.add(CandidateApp(taskId = task.taskId, packageName = pkg, label = label))
        }
        return result
    }

    private fun moveTaskToFrontAsync(context: Context, taskId: Int) {
        workerExecutor.execute {
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.moveTaskToFront(taskId, 0)
            }.onFailure { err ->
                moduleRef?.log(Log.WARN, TAG, "moveTaskToFront($taskId) failed", err)
            }
        }
    }

    private fun isFloatingOrMiniWindow(task: ActivityManager.RunningTaskInfo): Boolean {
        val mode = readWindowingMode(task)
        if (mode == WINDOWING_MODE_FREEFORM || mode == WINDOWING_MODE_PC_FREEFORM) return true

        if (readBooleanField(task, "isMiniWindow") ||
            readBooleanField(task, "mIsMiniWindow") ||
            readBooleanField(task, "inFreeformMode")
        ) {
            return true
        }

        val className = task.baseActivity?.className?.lowercase(Locale.ROOT).orEmpty()
        return className.contains("miniwindow") || className.contains("freeform")
    }

    private fun readWindowingMode(task: ActivityManager.RunningTaskInfo): Int {
        runCatching {
            val configField = findField(task.javaClass, "configuration")
            val config = configField?.get(task) as? Configuration ?: return@runCatching
            val winConfig = config.javaClass.getMethod("getWindowConfiguration").invoke(config)
                ?: return@runCatching
            val mode = winConfig.javaClass.getMethod("getWindowingMode").invoke(winConfig) as? Int
            if (mode != null) return mode
        }
        runCatching {
            val modeField = findField(task.javaClass, "windowingMode")
            if (modeField != null) return modeField.getInt(task)
        }
        return 1
    }

    private fun readBooleanField(target: Any, name: String): Boolean = runCatching {
        findField(target.javaClass, name)?.getBoolean(target) ?: false
    }.getOrDefault(false)

    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }
}
