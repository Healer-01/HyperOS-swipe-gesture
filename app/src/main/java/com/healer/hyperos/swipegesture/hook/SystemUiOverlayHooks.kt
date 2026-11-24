package com.healer.hyperos.swipegesture.hook

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.healer.hyperos.swipegesture.ipc.OverlayBinderHost
import com.healer.hyperos.swipegesture.ipc.OverlayChannelContract
import com.healer.hyperos.swipegesture.ipc.OverlayShowPayload
import com.healer.hyperos.swipegesture.ipc.SystemServerControlClient
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `com.android.systemui` overlay host & shade/control-center state bridge.
 *
 * Hosts the opposite-edge frosted-glass capsule menu safely outside `system_server`:
 * - Always resident (immune to HyperOS Millet/Greeze freezing).
 * - Uses SystemUI's privileged `TYPE_STATUS_BAR_SUB_PANEL` (`2017`, layer 181000 > IME 131000)
 *   with automatic fallback to `TYPE_APPLICATION_OVERLAY`.
 * - Receives non-blocking oneway Binder commands from `system_server` and pushes real-time
 *   shade / control-center / keyguard blocking transitions back to `system_server`.
 */
internal object SystemUiOverlayHooks {

    private const val TAG = SwipeGestureModuleEntry.TAG
    private const val TYPE_STATUS_BAR_SUB_PANEL = 2017

    private val installed = AtomicBoolean(false)
    private val shadeHookInstalled = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var moduleRef: XposedModule? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var backSuppressedUntilUptimeMs: Long = 0L

    @Volatile
    private var systemControlClient: SystemServerControlClient? = null

    @Volatile
    private var lastReportedBlocked: Boolean = false

    private var activeOverlayView: OppositeEdgeCapsuleView? = null
    private var exitingOverlayView: OppositeEdgeCapsuleView? = null

    private val overlayBinderHost = OverlayBinderHost(
        object : OverlayBinderHost.Listener {
            override fun onShow(payload: OverlayShowPayload) {
                mainHandler.post { showOverlayOnMain(payload) }
            }

            override fun onUpdateSelection(selectedIndex: Int, hitBoundary: Boolean) {
                mainHandler.post { updateSelectionOnMain(selectedIndex, hitBoundary) }
            }

            override fun onHide(cancelled: Boolean) {
                mainHandler.post { hideOverlayOnMain(cancelled) }
            }

            override fun onSuppressBackUntil(untilUptimeMs: Long) {
                backSuppressedUntilUptimeMs = untilUptimeMs
            }

            override fun onSystemControlBound(controlBinder: IBinder) {
                val client = SystemServerControlClient(controlBinder)
                systemControlClient = client
                client.setSystemUiBlocking(lastReportedBlocked)
                moduleRef?.log(Log.INFO, TAG, "Bound reverse control channel to system_server")
            }
        },
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        moduleRef = module

        hookApplicationCreate(module)
        hookNotificationShadeState(module, classLoader)
    }

    fun isBackSuppressedNow(): Boolean = SystemClock.uptimeMillis() <= backSuppressedUntilUptimeMs

    private fun hookApplicationCreate(module: XposedModule) {
        runCatching {
            val onCreate = Application::class.java.getDeclaredMethod("onCreate")
            module.hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val app = chain.thisObject as? Application
                    if (app != null && appContext == null) {
                        val ctx = app.applicationContext
                        appContext = ctx
                        RuntimeSettingsHolder.ensureBound(module)
                        if (!shadeHookInstalled.get()) {
                            hookNotificationShadeState(module, app.classLoader)
                        }
                        registerHostWithSystemServer(ctx)
                        scheduleRegistrationRetryIfUnbound(ctx, delayMs = 1500L)
                        scheduleRegistrationRetryIfUnbound(ctx, delayMs = 4500L)
                    }
                    result
                }
            module.log(Log.INFO, TAG, "SystemUI Application.onCreate hook installed")
        }.onFailure { err ->
            module.log(Log.WARN, TAG, "Failed to hook SystemUI Application.onCreate", err)
        }
    }

    private fun hookNotificationShadeState(module: XposedModule, classLoader: ClassLoader) {
        if (shadeHookInstalled.get()) return
        runCatching {
            val controllerClass = Class.forName(
                "com.android.systemui.shade.NotificationShadeWindowControllerImpl",
                false,
                classLoader,
            )
            val stateClass = Class.forName(
                "com.android.systemui.shade.NotificationShadeWindowState",
                false,
                classLoader,
            )
            val applyMethod = controllerClass.getDeclaredMethod("apply", stateClass)
            val fields = ShadeStateFields.resolve(stateClass)

            if (!shadeHookInstalled.compareAndSet(false, true)) return
            module.hook(applyMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val state = chain.args.getOrNull(0)
                    if (state != null) {
                        val blocked = fields.isGestureBlocked(state)
                        if (blocked != lastReportedBlocked) {
                            lastReportedBlocked = blocked
                            systemControlClient?.setSystemUiBlocking(blocked)
                        }
                    }
                    result
                }
            module.log(Log.INFO, TAG, "Hooked NotificationShadeWindowControllerImpl.apply")
        }.onFailure { err ->
            shadeHookInstalled.set(false)
            module.log(Log.WARN, TAG, "Failed to hook NotificationShadeWindowControllerImpl.apply", err)
        }
    }

    private fun scheduleRegistrationRetryIfUnbound(context: Context, delayMs: Long) {
        mainHandler.postDelayed(
            {
                if (systemControlClient == null) {
                    registerHostWithSystemServer(context)
                }
            },
            delayMs,
        )
    }

    private fun registerHostWithSystemServer(context: Context) {
        runCatching {
            val intent = Intent(OverlayChannelContract.ACTION_REGISTER_OVERLAY_HOST).apply {
                setPackage("android")
                putExtras(
                    Bundle().apply {
                        putBinder(OverlayChannelContract.EXTRA_OVERLAY_BINDER, overlayBinderHost)
                    },
                )
            }
            context.sendBroadcast(intent)
            moduleRef?.log(Log.INFO, TAG, "Dispatched SystemUI overlay Binder token to system_server")
        }.onFailure { err ->
            moduleRef?.log(Log.WARN, TAG, "Failed to dispatch overlay Binder token", err)
        }
    }

    private fun showOverlayOnMain(payload: OverlayShowPayload) {
        val ctx = appContext ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        removeOverlayImmediately(wm)

        runCatching {
            RuntimeSettingsHolder.ensureBound(moduleRef)
            val effectivePayload = if (RuntimeSettingsHolder.isBound) {
                val uiSettings = RuntimeSettingsHolder.refresh()
                payload.copy(
                    scale = uiSettings.menuScale,
                    material = uiSettings.overlayMaterial,
                    themeMode = uiSettings.overlayThemeMode,
                )
            } else {
                payload
            }

            val view = OppositeEdgeCapsuleView(ctx, effectivePayload)
            val displayBounds = runCatching { wm.maximumWindowMetrics.bounds }.getOrNull()
            val screenWidth = displayBounds?.width()?.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
            val screenHeight = displayBounds?.height()?.takeIf { it > 0 } ?: ctx.resources.displayMetrics.heightPixels

            val marginX = ((screenWidth * 0.036f * effectivePayload.scale).toInt().coerceAtLeast(12) - view.edgeInsetOffsetPx)
                .coerceAtLeast(0)
            val marginY = (screenHeight * 0.03f).toInt()
            val windowWidth = view.estimatedWidthPx
            val windowHeight = view.estimatedHeightPx
            val topY = (effectivePayload.anchorRawY.toInt() - windowHeight / 2)
                .coerceIn(marginY, (screenHeight - windowHeight - marginY).coerceAtLeast(marginY))

            val params = buildOverlayLayoutParams(
                windowType = TYPE_STATUS_BAR_SUB_PANEL,
                widthPx = windowWidth,
                heightPx = windowHeight,
                fromLeft = effectivePayload.fromLeft,
                marginX = marginX,
                topY = topY,
            )

            try {
                wm.addView(view, params)
            } catch (_: Throwable) {
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                wm.addView(view, params)
            }

            activeOverlayView = view
            view.animateEnter()
            if (RuntimeSettingsHolder.current.diagnosticTraceEnabled) {
                moduleRef?.log(
                    Log.DEBUG,
                    TAG,
                    "Overlay shown material=${view.appliedMaterialSummary} " +
                        "scale=${effectivePayload.scale} (payload=${payload.scale}, effective=${view.effectiveScale}) " +
                        "size=${windowWidth}x${windowHeight}",
                )
            }
            performHaptic(ctx, heavy = false)
        }.onFailure { err ->
            moduleRef?.log(Log.WARN, TAG, "Failed to show SystemUI overlay", err)
        }
    }

    private fun buildOverlayLayoutParams(
        windowType: Int,
        widthPx: Int,
        heightPx: Int,
        fromLeft: Boolean,
        marginX: Int,
        topY: Int,
    ): WindowManager.LayoutParams = WindowManager.LayoutParams(
        widthPx,
        heightPx,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = if (fromLeft) {
            Gravity.END or Gravity.TOP
        } else {
            Gravity.START or Gravity.TOP
        }
        x = marginX
        y = topY
        title = "HyperSwipeGestureCapsule"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    private fun updateSelectionOnMain(selectedIndex: Int, hitBoundary: Boolean) {
        val ctx = appContext ?: return
        activeOverlayView?.updateSelection(selectedIndex)
        performHaptic(ctx, heavy = hitBoundary)
    }

    private fun hideOverlayOnMain(cancelled: Boolean) {
        val view = activeOverlayView ?: return
        activeOverlayView = null
        val ctx = appContext ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        exitingOverlayView?.let { stale ->
            stale.animate().cancel()
            removeViewSafely(wm, stale, cancelled)
        }
        exitingOverlayView = view

        view.animateExit(cancelled = cancelled) {
            if (exitingOverlayView === view) {
                exitingOverlayView = null
            }
            removeViewSafely(wm, view, cancelled)
        }
    }

    private fun removeOverlayImmediately(wm: WindowManager) {
        activeOverlayView?.let { active ->
            activeOverlayView = null
            active.animate().cancel()
            removeViewSafely(wm, active, cancelled = false)
        }
        exitingOverlayView?.let { exiting ->
            exitingOverlayView = null
            exiting.animate().cancel()
            removeViewSafely(wm, exiting, cancelled = false)
        }
    }

    private fun removeViewSafely(
        wm: WindowManager,
        view: OppositeEdgeCapsuleView,
        cancelled: Boolean,
    ) {
        if (!view.isAttachedToWindow && view.parent == null) return
        runCatching {
            wm.removeViewImmediate(view)
        }.onFailure { err ->
            moduleRef?.log(Log.WARN, TAG, "Failed to remove SystemUI overlay (cancelled=$cancelled)", err)
        }
    }

    @Suppress("DEPRECATION")
    private fun performHaptic(context: Context, heavy: Boolean) {
        runCatching {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            val effectId = if (heavy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.EFFECT_HEAVY_CLICK
            } else {
                VibrationEffect.EFFECT_TICK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(effectId))
            }
        }
    }

    private class ShadeStateFields(
        private val controlCenterExpanded: Field?,
        private val qsExpanded: Field?,
        private val shadeOrQsExpanded: Field?,
        private val bouncerShowing: Field?,
        private val keyguardShowing: Field?,
        private val keyguardOccluded: Field?,
        private val dozing: Field?,
        private val dreaming: Field?,
    ) {
        fun isGestureBlocked(state: Any): Boolean {
            if (readBool(controlCenterExpanded, state)) return true
            if (readBool(qsExpanded, state)) return true
            if (readBool(shadeOrQsExpanded, state)) return true
            if (readBool(bouncerShowing, state)) return true
            if (readBool(dozing, state) || readBool(dreaming, state)) return true
            val kgShowing = readBool(keyguardShowing, state)
            val kgOccluded = readBool(keyguardOccluded, state)
            return kgShowing && !kgOccluded
        }

        private fun readBool(field: Field?, target: Any): Boolean = runCatching {
            field?.getBoolean(target) ?: false
        }.getOrDefault(false)

        companion object {
            fun resolve(stateClass: Class<*>): ShadeStateFields {
                fun field(name: String): Field? = runCatching {
                    stateClass.getDeclaredField(name).apply { isAccessible = true }
                }.getOrNull()

                return ShadeStateFields(
                    controlCenterExpanded = field("controlCenterExpanded"),
                    qsExpanded = field("qsExpanded"),
                    shadeOrQsExpanded = field("shadeOrQsExpanded"),
                    bouncerShowing = field("bouncerShowing"),
                    keyguardShowing = field("keyguardShowing"),
                    keyguardOccluded = field("keyguardOccluded"),
                    dozing = field("dozing"),
                    dreaming = field("dreaming"),
                )
            }
        }
    }
}
